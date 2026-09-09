# MCP Gateway

独立 Kotlin 命令行 MCP 传输网关。使用 GraalVM 25 编译为 Windows/Linux x64 原生程序；运行网关无需 JVM。
运行 stdio 子服务仍需要该服务自己的依赖，例如 Node.js、Python 或 uv。

## 使用

```sh
# 默认：有状态 Streamable HTTP，每个 MCP 会话独占一个子进程。
mcp-gateway --from stdio -- uvx mcp-server-fetch

# 所有客户端共享一个子进程。
mcp-gateway --from stdio --process-scope shared -- node server.js

# 旧版 SSE；Java SDK 1.1.2 的 SSE 客户端仅声明支持 2024-11-05。
mcp-gateway --from stdio --to sse --process-scope shared --protocol-version 2024-11-05 -- node server.js

mcp-gateway --from stdio --to ws -- node server.js
mcp-gateway --from stdio --http-mode stateless -- node server.js

# 远端服务转本地 stdio。
mcp-gateway --from streamable-http --url https://example.com/mcp
mcp-gateway --from sse --url https://example.com/sse
```

`--` 之后的参数直接交给 ProcessBuilder，不再拆分字符串。Windows `.cmd` 或 shell 语法使用
`--shell-command 'npx.cmd -y your-server'`；该参数与 `--` 后的命令互斥。生产优先使用明确的可执行文件与参数列表。

主要参数如下（超时均以秒为单位）：

| 参数                                         | 默认值 / 说明                                                           |
|----------------------------------------------|-------------------------------------------------------------------------|
| `--from`                                     | 必填：stdio、sse、streamable-http                                       |
| `--to`                                       | stdio 输入默认 streamable-http；远端输入固定 stdio；另支持 sse、ws 输出 |
| `--process-scope`                            | isolated / shared，默认 isolated                                        |
| `--http-mode`                                | stateful / stateless，默认 stateful                                     |
| `--host` / `--port`                          | 127.0.0.1 / 8000；容器通过 MCP_GATEWAY_HOST 默认监听 0.0.0.0            |
| `--http-path` / `--sse-path`                 | /mcp / /sse                                                             |
| `--message-path` / `--ws-path`               | /message / /message                                                     |
| `--public-base-url`                          | SSE 对外消息地址前缀，可包含反向代理路径                                |
| `--upstream-header` / `--response-header`    | 可重复 `Name: value`；不能覆盖协议保留头                                |
| `--upstream-bearer-env`                      | 保存上游 Token 的环境变量名称                                           |
| `--listen-bearer-env`                        | 保存网关访问 Token 的环境变量名称                                       |
| `--allow-origin`                             | 可重复精确 Origin 或 `*`；默认只接受无 Origin 或 loopback Origin        |
| `--connect-timeout` / `--initialize-timeout` | 30 / 30                                                                 |
| `--request-timeout` / `--session-timeout`    | 180 / 1800；活动请求和监听流不按空闲回收                                |
| `--shutdown-timeout`                         | 5                                                                       |
| `--protocol-version`                         | 主动握手使用 2025-11-25，可指定 2024-11-05、2025-03-26、2025-06-18      |
| `--max-message-bytes`                        | 4194304                                                                 |
| `--max-pending` / `--max-sessions`           | 每上游并发请求 256 / 网关客户端会话 64                                  |
| `--queue-capacity`                           | 每输出流或进程发送队列 256 条                                           |
| `--log-level`                                | info / debug / none；日志与子进程 stderr 均写 stderr                    |

网关不监听 HTTPS；需要 TLS 的部署在网关前配置反向代理。远端输入支持 HTTPS，使用 GraalVM 的默认信任库。
`GET /healthz` 返回 `ok`，不要求访问 Token。退出码：0 正常退出，1 运行失败，2 参数无效。

## 会话行为

| 输出        | isolated               | shared                         |
|-------------|------------------------|--------------------------------|
| SSE / WS    | 每连接一进程           | 所有连接一进程                 |
| 有状态 HTTP | 每 MCP Session 一进程  | 多个 MCP Session 共用一进程    |
| 无状态 HTTP | 每请求一进程，自动握手 | 所有请求共用一进程，启动时握手 |

共享模式的进程只初始化一次，以空客户端能力与上游握手，各客户端获得同一协议版本及服务端能力。
客户端必须接受这个协商结果；网关不转换协议版本。共享资源、订阅、日志级别及工具内部状态对所有客户端可见。
请求、取消及进度通知按客户端映射标识，响应不会广播；一般状态通知广播给已初始化客户端。
共享模式不声明 sampling、roots、elicitation，收到相应反向请求时返回 -32601。

隔离的有状态传输支持服务端反向请求及其响应。HTTP POST 用 SSE 返回关联响应；GET 建立会话后台流，DELETE 结束会话。
无状态模式没有后台流，不保留订阅、不提供反向调用能力；GET/DELETE 返回 405。

不支持批量 JSON-RPC、WS 输入、多上游聚合、自动 OAuth 登录、跨实例会话共享或断线消息重放。
业务请求不会自动重试。共享进程崩溃会结束网关；隔离进程失败只影响所属会话。
正常退出回收已跟踪的子进程及后代；主动脱离父进程树的守护进程不属于受支持的 MCP stdio 子服务。

## 构建与发布

仓库根目录执行，JAVA_HOME 指向 GraalVM 25：

```sh
./gradlew installDist
./gradlew nativeCompile
```

原生产物位于 `build/native/nativeCompile/`。发布时保留相邻的辅助动态库（如 Windows 的
management_ext.dll）。
Windows 需要 Visual Studio 2022 的 C++ 工具和 Windows SDK，
Linux 需要 gcc、glibc 开发文件、zlib 开发文件。两平台分别构建，不使用跨平台 exe。Native 禁用 fallback、使用 NIO/JDK TLS。

Windows 打包：`./scripts/release.ps1 -Version 1.0.0`。
Linux 打包：`sh scripts/release.sh 1.0.0`。
产物和 SHA-256 校验文件写入模块 `build/release/`，脚本不上传到外部服务。

Docker 构建上下文为仓库根目录：

```sh
docker buildx bake -f docker-bake.hcl --load
docker run --rm -p 8000:8000 mcp-gateway:1.0.0-node --from stdio -- node /workspace/server.js
docker run --rm -p 8000:8000 mcp-gateway:1.0.0-uv --from stdio -- uvx mcp-server-fetch
```

镜像包括 base、node（Node.js 24）、uv（Python 3.12 + uv/uvx）三个目标，均以非 root 身份运行。
环境变量 VERSION 与 IMAGE_REPOSITORY 控制 bake 的版本和目标仓库；需要推送时显式使用 `--push`。
依赖缓存可挂载到 `/home/gateway/.cache`；脚本/数据挂载到 `/workspace`，挂载目录须允许 UID 10001 访问。

## GitHub Actions 发布

工作流 `.github/workflows/docker-publish.yml` 构建 Linux x64 Native 镜像，并推送到
`moailaozi/mcp-gateway`。运行前在 GitHub 仓库的 Settings → Secrets and variables → Actions 配置：

- `DOCKER_HUB_USERNAME`：具有该仓库推送权限的 Docker Hub 用户名。
- `DOCKER_HUB_TOKEN`：Docker Hub Access Token，授予该镜像仓库的写入权限。

PR 只执行定向测试、镜像构建和 Native 互转验证，不登录或推送 Docker Hub。
推送到 `main` 时发布 `sha-<12位提交号>` 和 `edge`；推送 `v1.2.3` 标签时发布 `1.2.3` 和 `latest`。
预发布标签（例如 `v1.2.3-rc.1`）不更新 `latest`。手动运行按所选分支或标签采用相同规则。
每个版本提供默认/base、node、uv 镜像，例如 `1.2.3`、`1.2.3-base`、`1.2.3-node`、`1.2.3-uv`。
所有发布都在定向测试和实际容器验证成功后执行。GitHub Actions 中使用固定提交版本的第三方 Action。

## 验证

只运行本模块直接相关的测试类：

```sh
./gradlew test --tests '*GatewayCliTest' --tests '*GatewayBridgeTest' --tests '*SharedProcessRoutingTest' --tests '*GatewaySessionTest' --tests '*GatewayProcessTest' --tests '*GatewayHttpContractTest'
```

`python scripts/native-smoke.py <原生程序路径>` 对真实可执行文件执行离线黑盒验证，
Python 仅作为测试驱动和 MCP 子服务 fixture，不是网关的运行依赖。实际平台验收结果应以执行记录为准。
