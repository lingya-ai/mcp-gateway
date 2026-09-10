#!/bin/sh
# 在 Linux x64 或 macOS arm64 构建并打包，不上传产物。
set -eu
version=${1:-1.0.0}
case "$version" in *[!A-Za-z0-9.-]*|'') echo 'Invalid version' >&2; exit 2;; esac
case "$(uname -s)/$(uname -m)" in
    Linux/x86_64) platform=linux-x64;;
    Darwin/arm64) platform=macos-arm64;;
    *) echo 'Unsupported platform: use Linux x64 or macOS arm64' >&2; exit 2;;
esac
repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repository"
sh gradlew nativeCompile "-PgatewayVersion=$version" -Pkotlin.compiler.execution.strategy=in-process
module=.
name="mcp-gateway-$version-$platform"
release="$module/build/release/$name"
mkdir -p "$release"
cp "$module/build/native/nativeCompile/mcp-gateway" "$module/README.md" "$module/THIRD-PARTY-NOTICES.md" "$release/"
for library in "$module"/build/native/nativeCompile/*.so "$module"/build/native/nativeCompile/*.dylib; do
    [ ! -f "$library" ] || cp "$library" "$release/"
done
tar -C "$module/build/release" -czf "$module/build/release/$name.tar.gz" "$name"
(
    cd "$module/build/release"
    case "$platform" in
        macos-*) shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256";;
        *) sha256sum "$name.tar.gz" > "$name.tar.gz.sha256";;
    esac
)
printf '%s\n' "$module/build/release/$name.tar.gz"
