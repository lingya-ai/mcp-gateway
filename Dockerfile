# 构建上下文为仓库根目录；原生编译与运行镜像均使用 glibc。
FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /workspace
COPY . .
ARG VERSION=0.0.3
RUN --mount=type=cache,target=/root/.gradle \
    sh gradlew nativeCompile -PgatewayVersion=${VERSION} -Pkotlin.compiler.execution.strategy=in-process --no-daemon --configure-on-demand

FROM node:24-bookworm-slim AS node-runtime
FROM python:3.12-slim-bookworm AS python-runtime
FROM ghcr.io/astral-sh/uv:0.10.12 AS uv-runtime

FROM debian:bookworm-slim AS base
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 zlib1g tini \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 gateway \
    && mkdir -p /workspace /home/gateway/.cache \
    && chown -R gateway:gateway /workspace /home/gateway
COPY --from=build /workspace/build/native/nativeCompile/ /opt/mcp-gateway/
ENV MCP_GATEWAY_HOST=0.0.0.0
ENV UV_CACHE_DIR=/home/gateway/.cache/uv
WORKDIR /workspace
USER gateway
EXPOSE 8000
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/opt/mcp-gateway/mcp-gateway"]
CMD ["--help"]

FROM base AS node
COPY --from=node-runtime /usr/local /usr/local
RUN node --version && npm --version

FROM base AS uv
COPY --from=python-runtime /usr/local /usr/local
COPY --from=uv-runtime /uv /uvx /usr/local/bin/
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    libbz2-1.0 libffi8 libgdbm6 libgdbm-compat4 liblzma5 libncursesw6 libreadline8 libsqlite3-0 libuuid1 libexpat1 \
    && rm -rf /var/lib/apt/lists/* && ldconfig \
    && python -c "import ssl, sqlite3, bz2, lzma, ctypes, readline" && uv --version
USER gateway
ENV UV_PYTHON_DOWNLOADS=never
