#!/usr/bin/env python3
"""本地 mock LLM 服务，用于在文石 Leaf3 上端到端测试流式问答。

用法：
  python3 tools/mock_llm_server.py 8765
  adb reverse tcp:8765 tcp:8765
  App 设置里 Base URL 填 http://127.0.0.1:8765/v1，API Key 随便填，模型填 mock

支持：
  POST /v1/chat/completions  OpenAI 兼容 SSE
  POST /v1/messages          Anthropic Messages SSE
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_ANSWER = (
    "这段话的核心是在讲折旧率如何成为行业惯例。"
    "简单说，公司会按资产的预计使用年限，把成本分摊到很多年里；"
    "不同资产的折旧年限不同，而且计算时没有考虑残值。"
    "它反映的是会计上的惯例，不一定等于资产真实的价值变化。"
)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] " + fmt % args + "\n")

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            body = json.dumps({
                "object": "list",
                "data": [{"id": "mock", "object": "model"}],
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            payload = {}
        print("[mock] request", self.path, json.dumps(payload, ensure_ascii=False)[:1200],
              file=sys.stderr)
        for message in payload.get("messages", []):
            content = message.get("content")
            if isinstance(content, list):
                parts = []
                for part in content:
                    kind = part.get("type")
                    if kind == "image_url":
                        url = (part.get("image_url") or {}).get("url", "")
                        parts.append(f"image_url(len={len(url)})")
                    elif kind == "image":
                        data = (part.get("source") or {}).get("data", "")
                        parts.append(f"image(len={len(data)})")
                    else:
                        parts.append(str(kind))
                print("[mock] parts", message.get("role"), parts, file=sys.stderr)

        if "messages" in self.path:
            self.anthropic_stream(payload)
        else:
            self.openai_stream(payload)

    def answer_for(self, payload):
        user_text = ""
        for msg in payload.get("messages", []):
            if msg.get("role") == "user":
                user_text = str(msg.get("content", ""))
        if "连接测试" in user_text or user_text.strip() in ("ping", "Ping", "PING"):
            return "OK"
        return DEFAULT_ANSWER

    def openai_stream(self, payload):
        answer = self.answer_for(payload)
        self.close_connection = True
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        model = payload.get("model", "mock")
        chunks = [answer[i:i + 3] for i in range(0, len(answer), 3)]
        for chunk in chunks:
            data = {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": model,
                "choices": [{"index": 0, "delta": {"content": chunk}, "finish_reason": None}],
            }
            self.wfile.write(("data: " + json.dumps(data, ensure_ascii=False) + "\n\n").encode())
            self.wfile.flush()
            time.sleep(0.05)
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def anthropic_stream(self, payload):
        answer = self.answer_for(payload)
        self.close_connection = True
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def event(name, obj):
            self.wfile.write(("event: " + name + "\n").encode())
            self.wfile.write(("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode())
            self.wfile.flush()

        event("message_start", {
            "type": "message_start",
            "message": {
                "id": "msg_mock",
                "type": "message",
                "role": "assistant",
                "model": payload.get("model", "mock"),
                "content": [],
                "usage": {"input_tokens": 10, "output_tokens": 0},
            },
        })
        event("content_block_start", {
            "type": "content_block_start",
            "index": 0,
            "content_block": {"type": "text", "text": ""},
        })
        chunks = [answer[i:i + 3] for i in range(0, len(answer), 3)]
        for chunk in chunks:
            event("content_block_delta", {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "text_delta", "text": chunk},
            })
            time.sleep(0.05)
        event("content_block_stop", {"type": "content_block_stop", "index": 0})
        event("message_delta", {
            "type": "message_delta",
            "delta": {"stop_reason": "end_turn"},
            "usage": {"output_tokens": len(answer)},
        })
        event("message_stop", {"type": "message_stop"})


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"[mock] listening on http://0.0.0.0:{port}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
