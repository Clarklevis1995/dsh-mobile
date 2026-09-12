#!/usr/bin/env python3
"""仅本机自动化/人工冒烟夹具：两台模拟网关复用资源 ID，凭证互不通用。
不连接真实 DSH，不执行消息/文件/审批写操作。Ctrl-C 停止。
"""
import base64
import hashlib
import json
import socketserver
import struct
import threading
import time


class Gateway(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(90)
        self.rfile.readline()
        headers = {}
        while True:
            line = self.rfile.readline().decode().strip()
            if not line:
                break
            key, value = line.split(":", 1)
            headers[key.lower()] = value.strip()
        host = self.server.host_name
        token = "smoke-token-" + host
        paired = "dsh-pair.smoke-" + host in headers.get("sec-websocket-protocol", "")
        if not paired and headers.get("authorization") != "Bearer " + token:
            self.wfile.write(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
            return
        accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.wfile.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Protocol: dsh-mobile-v1\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").encode())
        identity = {"gatewayId": self.server.gateway_id, "gatewayName": "Smoke " + host}
        if paired:
            self.send({"kind": "paired", "token": token, "device": {"id": "device-" + host, "name": "Smoke device"}, **identity})
        self.send({"kind": "hello", "protocol": 3, "authenticated": True, "capabilities": ["split-channels"], **identity})
        try:
            while True:
                head = self.rfile.read(2)
                if len(head) < 2 or head[0] & 15 == 8:
                    return
                length = head[1] & 127
                if length == 126:
                    length = struct.unpack("!H", self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack("!Q", self.rfile.read(8))[0]
                if length > 1_000_000:
                    return
                mask = self.rfile.read(4) if head[1] & 128 else b""
                data = self.rfile.read(length)
                if mask:
                    data = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
                if head[0] & 15 == 9:
                    self.send_bytes(data, opcode=10)
                    continue
                if head[0] & 15 != 1:
                    continue
                request = json.loads(data)
                kind = request.get("type")
                if kind == "sessions":
                    self.send({"kind": kind, "items": [{"sessionId": "same-session", "projections": {"values": {"title": host + " only session"}}, "updatedAt": time.time() * 1000, "running": False, "blank": False}]})
                elif kind == "workspaces":
                    self.send({"kind": kind, "items": [{"workspaceId": "same-workspace", "path": "/smoke/" + host, "title": host + " workspace", "sessionIds": ["same-session"], "createdAt": "2026-09-10T00:00:00Z", "updatedAt": "2026-09-10T00:00:00Z"}], "archivedSessionIds": []})
                elif kind == "history":
                    self.send({"kind": kind, "sessionId": "same-session", "events": [], "hasMore": False})
                elif kind == "host":
                    self.send({"kind": kind, "host": {"name": "Smoke " + host}})
                elif kind in {"ping", "subscribe", "unsubscribe"}:
                    self.send({"kind": {"ping": "pong", "subscribe": "subscribed", "unsubscribe": "unsubscribed"}[kind], "sessionId": request.get("sessionId")})
                elif kind in {"agent-presets", "defaults", "default-model", "models", "permission-options", "commands"}:
                    self.send({"kind": kind, "items": [], "presets": [], "models": [], "options": []})
                else:
                    self.send({"kind": "error", "requestType": kind, "message": "本夹具不实现此操作", "code": "smoke-unsupported"})
        except (OSError, ValueError, struct.error):
            return

    def send(self, value):
        self.send_bytes(json.dumps(value, ensure_ascii=False).encode())

    def send_bytes(self, data, opcode=1):
        header = bytes([128 | opcode])
        header += bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack("!H", len(data))
        self.wfile.write(header + data)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    for host, port, identity in [("A", 18781, "d56a1098-8519-43a1-9dce-fb99863bf5bb"), ("B", 18782, "a56a1098-8519-43a1-9dce-fb99863bf5bb")]:
        server = Server(("127.0.0.1", port), Gateway)
        server.host_name, server.gateway_id = host, identity
        threading.Thread(target=server.serve_forever, daemon=True).start()
        for platform, address in [("iOS", "127.0.0.1"), ("Android", "10.0.2.2")]:
            payload = {"version": 2, "publicUrl": f"ws://{address}:{port}/ws/mobile", "pairingCode": "smoke-" + host, "expiresAt": int((time.time() + 3600) * 1000), "gatewayId": identity, "gatewayName": "Smoke " + host}
            print(platform, host, base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("="), flush=True)
    threading.Event().wait()
