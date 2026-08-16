"""QA mock：OpenAI 兼容 /models + /chat/completions（供 settings 浏览器 QA 使用）。

chat/completions 支持 stream=true（SSE 分片 + [DONE]），回复固定为
「你好，我是QAMock助手。」——qa-settings.mjs 的 chat-stream-reply 检查依赖此文案。
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

REPLY = "你好，我是QAMock助手。"


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
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
            if body.get("stream"):
                self._sse(body.get("model", "qa-model-alpha"))
            else:
                self._json(200, {
                    "id": "qa-chat", "object": "chat.completion", "created": 1,
                    "model": body.get("model", "qa-model-alpha"),
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": REPLY},
                                 "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                })
        else:
            self.send_response(404)
            self.end_headers()

    def _sse(self, model: str) -> None:
        """SSE 流式回复：分两片发，验证前端跨 chunk 拼接。"""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        chunks = [REPLY[:6], REPLY[6:]]
        for c in chunks:
            payload = json.dumps({
                "id": "qa-chat", "object": "chat.completion.chunk", "created": 1,
                "model": model,
                "choices": [{"index": 0, "delta": {"content": c}, "finish_reason": None}],
            }, ensure_ascii=False)
            self.wfile.write(("data: " + payload + "\n\n").encode())
            self.wfile.flush()
        done = json.dumps({
            "id": "qa-chat", "object": "chat.completion.chunk", "created": 1,
            "model": model,
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        }, ensure_ascii=False)
        self.wfile.write(("data: " + done + "\n\n").encode())
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _json(self, code, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 9001), H).serve_forever()
