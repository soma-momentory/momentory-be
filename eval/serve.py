#!/usr/bin/env python3
"""
quick-labeler.html 을 다른 노트북에서도 열 수 있게 띄운다.

    python3 serve.py --allow 172.16.202.57          # 그 노트북만 접속 허용
    python3 serve.py --allow 172.16.202.57 --port 8766

`python3 -m http.server` 를 같은 망에 열면 eval/ **전체**가 디렉터리 목록과 함께 나간다 —
AI Hub 변환본 전체(data/aihub-v2.jsonl)와 골드까지. 같은 망의 누구나 받을 수 있어 AI Hub 이용
조건의 제3자 제공 금지에 걸린다(계획 §6.1.1). 그래서 **라벨링 화면과 표본만**, **허락한 기기에만**
내준다. 이 노트북(localhost)은 늘 허용한다.

판정은 여전히 각 노트북 브라우저의 localStorage 에 저장된다. 노트북을 옮겨 이어 하려면 내보낸
골드 파일을 새 쪽 화면에 끌어다 놓는다.
"""

import argparse
import http.server
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
ALLOWED_PATHS = [
    re.compile(r"^/quick-labeler\.html$"),
    re.compile(r"^/data/sample-r\d+/(pilot|eval|safety|train)\.jsonl$"),
]
LOCAL = {"127.0.0.1", "::1"}


def make_handler(allow):
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=ROOT, **kw)

        def _gate(self):
            ip = self.client_address[0].removeprefix("::ffff:")
            if ip not in LOCAL and ip not in allow:
                self.log_message("거부(허용 안 된 기기) %s", ip)
                self.send_error(403)
                return False
            path = self.path.split("?", 1)[0]
            if path == "/":
                self.send_response(302)
                self.send_header("Location", "/quick-labeler.html")
                self.end_headers()
                return False
            if not any(p.match(path) for p in ALLOWED_PATHS):
                self.send_error(404)
                return False
            return True

        def do_GET(self):
            if self._gate():
                super().do_GET()

        def do_HEAD(self):
            if self._gate():
                super().do_HEAD()

    return Handler


def main():
    ap = argparse.ArgumentParser(description="빠른 라벨링 화면을 같은 망의 다른 노트북에 연다")
    ap.add_argument("--allow", action="append", default=[], metavar="IP",
                    help="접속을 허용할 다른 노트북의 IP. 여러 번 줄 수 있다")
    ap.add_argument("--port", type=int, default=8766)
    args = ap.parse_args()

    allow = set(args.allow)
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(allow))
    print("http://<이 노트북 IP>:{}/quick-labeler.html  · 허용: localhost{}".format(
        args.port, "".join(", " + ip for ip in sorted(allow))), file=sys.stderr)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
