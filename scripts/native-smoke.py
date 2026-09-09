#!/usr/bin/env python3
"""离线验收实际 Native 程序；仅使用 Python 标准库，不依赖 JVM。"""

import argparse
import concurrent.futures
import contextlib
import http.client
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time


def resident_bytes(pid):
    """读取目标进程常驻内存，测量失败时返回空值而不是估算。"""
    if sys.platform == "linux":
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) * 1024
    elif sys.platform == "win32":
        import ctypes
        from ctypes import wintypes
        class MemoryCounters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("faults", wintypes.DWORD)] + [
                (name, ctypes.c_size_t) for name in
                ("peak", "working", "paged_peak", "paged", "nonpaged_peak", "nonpaged", "pagefile", "pagefile_peak")]
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        process = kernel.OpenProcess(0x410, False, pid)
        if process:
            try:
                counters = MemoryCounters()
                counters.cb = ctypes.sizeof(counters)
                query = ctypes.WinDLL("psapi").GetProcessMemoryInfo
                query.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
                if query(process, ctypes.byref(counters), counters.cb):
                    return counters.working
            finally:
                kernel.CloseHandle(process)
    return None


def fixture():
    """模拟保留进程状态的 MCP 服务，独立于网关代码。"""
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    initialized = False
    for line in sys.stdin:
        request = json.loads(line)
        if "id" not in request:
            continue
        method = request.get("method")
        if method == "initialize":
            if initialized:
                raise AssertionError("重复初始化子进程")
            initialized = True
            result = {
                "protocolVersion": request["params"]["protocolVersion"],
                "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
                "serverInfo": {"name": "offline-fixture", "version": "1"},
            }
        else:
            assert initialized, "业务请求必须在初始化后发送"
            if method == "tools/list":
                result = {"tools": [{"name": "echo", "inputSchema": {"type": "object"}}]}
            elif method == "resources/list":
                result = {"resources": []}
            elif method == "prompts/list":
                result = {"prompts": []}
            elif method == "empty":
                result = None
            elif method == "failure":
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"],
                                  "error": {"code": -123, "message": "fixture error", "data": {"detail": "preserved"}}}), flush=True)
                continue
            else:
                result = {"pid": os.getpid(), "echo": request.get("params")}
        print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}, ensure_ascii=False), flush=True)


def message(method, identity=0, params=None):
    value = {"jsonrpc": "2.0", "id": identity, "method": method}
    if params is not None:
        value["params"] = params
    return value


def initialize():
    return message("initialize", 0, {
        "protocolVersion": "2025-11-25", "capabilities": {},
        "clientInfo": {"name": "native-smoke", "version": "1"},
    })


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(port, value=None, session=None, method="POST"):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    try:
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if session:
            headers["Mcp-Session-Id"] = session
        body = None if value is None else json.dumps(value, ensure_ascii=False).encode("utf-8")
        connection.request(method, "/mcp", body, headers)
        response = connection.getresponse()
        text = response.read().decode("utf-8")
        records = [json.loads(line[6:]) for line in text.splitlines() if line.startswith("data: ")]
        return response.status, response.getheader("Mcp-Session-Id"), records
    finally:
        connection.close()


@contextlib.contextmanager
def gateway(command, *options):
    port = free_port()
    with tempfile.TemporaryFile() as log:
        process = subprocess.Popen(
            command + ["--from", "stdio", "--port", str(port), *options, "--", sys.executable, str(Path(__file__).resolve()), "--fixture"],
            stdout=subprocess.DEVNULL, stderr=log,
        )
        started = time.monotonic()
        try:
            while time.monotonic() - started < 30:
                if process.poll() is not None:
                    log.seek(0)
                    raise AssertionError(log.read().decode("utf-8", errors="replace"))
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                try:
                    connection.request("GET", "/healthz")
                    if connection.getresponse().status == 200:
                        break
                except OSError:
                    time.sleep(0.02)
                finally:
                    connection.close()
            else:
                raise AssertionError("网关启动超时")
            yield port, {"startup_ms": round((time.monotonic() - started) * 1000, 1),
                         "resident_bytes": resident_bytes(process.pid)}
        finally:
            # 有状态会话在调用方 DELETE，SSE 在连接断开时结束；避免 Windows 强制终止遗留 fixture。
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def validate_http(command, scope, mode):
    with gateway(command, "--process-scope", scope, "--http-mode", mode) as (port, startup):
        ids = []
        if mode == "stateful":
            for _ in range(2):
                status, session, responses = request(port, initialize())
                assert status == 200 and session and "result" in responses[-1], responses
                ids.append(session)
        else:
            ids = [None, None]
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                calls = [pool.submit(request, port, message("echo", "same:0", {"owner": owner, "text": "中文 参数"}), session)
                         for owner, session in enumerate(ids)]
                responses = [call.result(timeout=20) for call in calls]
            pids = []
            for owner, (status, _, records) in enumerate(responses):
                assert status == 200, records
                result = records[-1]
                assert result["id"] == "same:0" and result["result"]["echo"]["owner"] == owner, result
                assert result["result"]["echo"]["text"] == "中文 参数", result
                pids.append(result["result"]["pid"])
            assert (pids[0] == pids[1]) == (scope == "shared"), pids
            for method, field in (("tools/list", "tools"), ("resources/list", "resources"), ("prompts/list", "prompts")):
                status, _, records = request(port, message(method), ids[0])
                assert status == 200 and field in records[-1]["result"], records
            if mode == "stateless":
                assert request(port, method="GET")[0] == 405
                assert request(port, method="DELETE")[0] == 405
        finally:
            for session in ids:
                if session:
                    assert request(port, session=session, method="DELETE")[0] == 204
        return startup


def remote_roundtrip(command, transport):
    with gateway(command, "--to", transport) as (port, _):
        endpoint = "/sse" if transport == "sse" else "/mcp"
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen(command + ["--from", transport, "--url", f"http://127.0.0.1:{port}{endpoint}"],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log)
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                    for value in (initialize(), message("tools/list", "中文:0"), message("empty", "null:0"), message("failure", "error:0")):
                        process.stdin.write((json.dumps(value) + "\n").encode("utf-8"))
                        process.stdin.flush()
                        try:
                            line = pool.submit(process.stdout.readline).result(timeout=20)
                        except concurrent.futures.TimeoutError:
                            process.kill()
                            raise
                        assert line, "远端桥接提前退出"
                        result = json.loads(line)
                        assert result["id"] == value["id"], result
                        if value["method"] == "failure":
                            assert result["error"]["code"] == -123 and result["error"]["data"]["detail"] == "preserved", result
                        else:
                            assert "result" in result, result
                            if value["method"] == "empty":
                                assert result["result"] is None, result
                process.stdin.close()
                assert process.wait(timeout=10) == 0
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)


def websocket_roundtrip(command):
    with gateway(command, "--to", "ws") as (port, _):
        with socket.create_connection(("127.0.0.1", port), timeout=15) as connection:
            connection.sendall((f"GET /message HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                                "Sec-WebSocket-Version: 13\r\n\r\n").encode("ascii"))
            reader = connection.makefile("rb")
            assert b"101" in reader.readline()
            while reader.readline() != b"\r\n":
                pass
            payload = json.dumps(initialize()).encode("utf-8")
            mask = b"test"
            header = bytes([0x81, 0x80 | 126]) + struct.pack("!H", len(payload))
            connection.sendall(header + mask + bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))
            first, length = reader.read(2)
            assert first == 0x81
            if length == 126:
                length = struct.unpack("!H", reader.read(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", reader.read(8))[0]
            assert json.loads(reader.read(length))["result"]["serverInfo"]["name"] == "offline-fixture"
            reader.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", nargs="?")
    parser.add_argument("--fixture", action="store_true")
    parser.add_argument("--jvm-classpath", help="仅用于对照测量，binary 此时指定 java 可执行文件")
    args = parser.parse_args()
    if args.fixture:
        fixture()
        return
    binary = Path(args.binary).resolve(strict=True)
    command = [str(binary)]
    if args.jvm_classpath:
        command += ["-cp", args.jvm_classpath, "cc.lingya.xiaolingtong.mcp.gateway.GatewayMain"]
    assert subprocess.run(command + ["--help"], capture_output=True, timeout=10).returncode == 0
    assert subprocess.run(command + ["--from", "invalid"], capture_output=True, timeout=10).returncode == 2
    startups = []
    for scope in ("isolated", "shared"):
        for mode in ("stateful", "stateless"):
            startups.append(validate_http(command, scope, mode))
            print(f"PASS HTTP {scope} {mode}", flush=True)
    for transport in ("sse", "streamable-http"):
        remote_roundtrip(command, transport)
        print(f"PASS {transport} -> stdio", flush=True)
    websocket_roundtrip(command)
    print("PASS stdio -> WebSocket", flush=True)
    print(json.dumps({"runtime": "jvm" if args.jvm_classpath else "native",
                      "binary_bytes": binary.stat().st_size, "observations": startups}, ensure_ascii=False))


if __name__ == "__main__":
    main()
