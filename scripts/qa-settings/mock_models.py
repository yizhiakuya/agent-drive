"""QA mock：OpenAI 兼容 /models + /chat/completions（供 settings 浏览器 QA 使用）。"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            body = {"object": "list", "data": [
                {"id": "qa-model-alpha", "object": "model", "created": 1},
                {"id": "qa-model-beta", "object": "model", "created": 2},
                {"id": "qa-model-gamma", "object": "model", "created": 3},
            ]}
            self._json(200, body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path.rstrip("/").endswith("/chat/completions"):
            self._json(200, {
                "id": "qa-chat", "object": "chat.completion", "created": 1,
                "model": "qa-model-alpha",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "pong"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            })
        else:
            self.send_response(404)
            self.end_headers()

    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 9001), H).serve_forever()
