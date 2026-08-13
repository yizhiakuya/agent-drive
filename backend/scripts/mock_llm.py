"""本地 Mock LLM：模拟 OpenAI 兼容协议 (POST /v1/chat/completions)。

用于端到端测试：识别"看看/查看"指令 → 返回 list_files 工具调用；
看到工具结果后 → 返回总结文本。
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length))
        messages = body.get("messages", [])
        tools = body.get("tools", [])

        last = messages[-1]["content"] if messages else ""
        # 检测是否已有工具结果
        has_tool_result = any(m.get("role") == "tool" for m in messages)

        if tools and not has_tool_result and any(k in last for k in ("看看", "查看", "list")):
            resp = {
                "choices": [{
                    "message": {
                        "role": "assistant",
                        "content": "好的，我看看网盘里有什么。",
                        "tool_calls": [{
                            "id": "call_mock_1",
                            "type": "function",
                            "function": {"name": "list_files", "arguments": "{\"path\": \"\"}"},
                        }],
                    },
                    "finish_reason": "tool_calls",
                }],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
            }
        else:
            files = []
            for m in messages:
                if m.get("role") == "tool":
                    try:
                        files = json.loads(m["content"])
                    except Exception:
                        pass
            summary = "、".join(f.get("name", "?") for f in files if isinstance(f, dict)) or "(空)"
            resp = {
                "choices": [{
                    "message": {"role": "assistant", "content": f"我查看了你的网盘，当前有 {len(files)} 个文件：{summary}。"},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
            }

        data = json.dumps(resp, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print("Mock LLM 运行在 http://localhost:9999/v1")
    HTTPServer(("0.0.0.0", 9999), Handler).serve_forever()
