#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ColorOS <-> Ubuntu X11 双向剪贴板同步守护进程
通过 Unix Domain Socket (/tmp/.clip_ipc.sock) 与 Android 宿主建立全双工实时同步
"""

import os
import socket
import struct
import subprocess
import threading
import time

SOCKET_PATH = "/tmp/.clip_ipc.sock"
POLL_INTERVAL = 0.35

class X11ClipboardDaemon:
    def __init__(self):
        self.last_clip = ""
        self.lock = threading.Lock()
        self.client_conn = None
        self.running = True

    def get_x11_clipboard(self) -> str:
        try:
            res = subprocess.run(
                ["xclip", "-selection", "clipboard", "-o"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=1.0
            )
            if res.returncode == 0:
                return res.stdout.decode("utf-8", errors="ignore")
        except Exception:
            pass
        return ""

    def set_x11_clipboard(self, text: str):
        try:
            with self.lock:
                self.last_clip = text
            proc = subprocess.Popen(
                ["xclip", "-selection", "clipboard", "-i"],
                stdin=subprocess.PIPE,
                stderr=subprocess.DEVNULL
            )
            proc.communicate(input=text.encode("utf-8"), timeout=1.5)
        except Exception:
            pass

    def x11_poll_loop(self):
        while self.running:
            current = self.get_x11_clipboard()
            with self.lock:
                if current and current != self.last_clip:
                    self.last_clip = current
                    self.send_to_android(current)
            time.sleep(POLL_INTERVAL)

    def send_to_android(self, text: str):
        conn = self.client_conn
        if not conn:
            return
        try:
            payload = text.encode("utf-8")
            header = struct.pack(">I", len(payload))
            conn.sendall(header + payload)
        except Exception:
            self.client_conn = None

    def recv_exact(self, conn: socket.socket, size: int) -> bytes:
        buf = bytearray()
        while len(buf) < size:
            chunk = conn.recv(size - len(buf))
            if not chunk:
                raise ConnectionError("Socket closed")
            buf.extend(chunk)
        return bytes(buf)

    def handle_client(self, conn: socket.socket):
        self.client_conn = conn
        try:
            while self.running:
                header = self.recv_exact(conn, 4)
                (length,) = struct.unpack(">I", header)
                if length <= 0 or length > 10 * 1024 * 1024:
                    break
                payload = self.recv_exact(conn, length)
                text = payload.decode("utf-8", errors="ignore")
                if text:
                    self.set_x11_clipboard(text)
        except Exception:
            pass
        finally:
            if self.client_conn == conn:
                self.client_conn = None
            try:
                conn.close()
            except Exception:
                pass

    def start(self):
        if os.path.exists(SOCKET_PATH):
            try:
                os.unlink(SOCKET_PATH)
            except OSError:
                pass

        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server.bind(SOCKET_PATH)
        os.chmod(SOCKET_PATH, 0o666)
        server.listen(1)

        poll_thread = threading.Thread(target=self.x11_poll_loop, daemon=True)
        poll_thread.start()

        while self.running:
            try:
                conn, _ = server.accept()
                client_thread = threading.Thread(target=self.handle_client, args=(conn,), daemon=True)
                client_thread.start()
            except Exception:
                time.sleep(1)

if __name__ == "__main__":
    os.environ.setdefault("DISPLAY", ":0")
    daemon = X11ClipboardDaemon()
    daemon.start()
