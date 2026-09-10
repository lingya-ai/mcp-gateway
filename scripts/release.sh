#!/bin/sh
# 在 Linux x64 构建并打包，不上传产物。
set -eu
version=${1:-0.0.2}
case "$version" in *[!A-Za-z0-9.-]*|'') echo 'Invalid version' >&2; exit 2;; esac
test "$(uname -s)" = Linux && test "$(uname -m)" = x86_64
repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repository"
sh gradlew nativeCompile "-PgatewayVersion=$version" -Pkotlin.compiler.execution.strategy=in-process
module=.
name="mcp-gateway-$version-linux-x64"
release="$module/build/release/$name"
mkdir -p "$release"
cp "$module/build/native/nativeCompile/mcp-gateway" "$module/README.md" "$module/THIRD-PARTY-NOTICES.md" "$release/"
for library in "$module"/build/native/nativeCompile/*.so; do
    [ ! -f "$library" ] || cp "$library" "$release/"
done
tar -C "$module/build/release" -czf "$module/build/release/$name.tar.gz" "$name"
(cd "$module/build/release" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256")
printf '%s\n' "$module/build/release/$name.tar.gz"
