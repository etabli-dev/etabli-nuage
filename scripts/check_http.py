#!/usr/bin/env python3
"""Run Swift HTTP checks against a loopback server; no account or internet needed."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import subprocess
import sys
import threading
import time

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *args):
        pass
    def do_GET(self):
        print("fixture request:", self.path, flush=True)
        if self.path == '/slow-headers':
            time.sleep(3)
        self.send_response(302 if self.path == '/redirect' else 404 if self.path == '/missing' else 200)
        if self.path == '/redirect':
            self.send_header('Location', '/missing')
        body = b'x' * (65546 if self.path == '/slow-body' else 200 if self.path == '/large' else 10)
        if self.path != '/sniff-delayed':
            self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.flush()
        if self.path == '/slow-body':
            self.wfile.write(body[:65536])
            self.wfile.flush()
            body = body[65536:]
            time.sleep(3)
        if self.path == '/sniff-delayed':
            time.sleep(3)
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    code = subprocess.call([sys.argv[1], f'http://127.0.0.1:{server.server_port}'])
finally:
    server.shutdown()
    server.server_close()
sys.exit(code)
