"""Web preview of the designed Unity UI (시안 E). Serves this folder, and the app's own fonts, icons and example
photos under /res/ straight from Assets/Beside/UI/Resources/BesideUI, so the preview never keeps copies.

    python3 unity/docs/ui-preview/serve.py [port]
"""
import http.server
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "Assets", "Beside", "UI", "Resources", "BesideUI"))


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map, ".ttf": "font/ttf", ".js": "text/javascript"}

    def translate_path(self, path):
        clean = path.split("?", 1)[0].split("#", 1)[0]
        if clean.startswith("/res/"):
            target = os.path.normpath(os.path.join(RES, clean[len("/res/"):]))
            # inside RES only: a bare string prefix let siblings such as ../BesideUI.meta through (review 2026-11-25)
            try:
                inside = os.path.commonpath([RES, target]) == RES
            except ValueError:   # another drive on Windows
                inside = False
            return target if inside else RES
        return super().translate_path(path)

    polls = 0

    def send_text(self, body, ctype):
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        clean = self.path.split("?", 1)[0]
        stamp = str(int(max(os.path.getmtime(os.path.join(HERE, f)) for f in os.listdir(HERE) if not f.startswith("."))))
        # the page polls this and reloads itself when a preview file changes
        if clean == "/live-version":
            Handler.polls += 1
            self.send_text(stamp, "text/plain")
            return
        # the page names its css and js with the current stamp, so no cache anywhere can hand back an old copy
        if clean in ("/", "/index.html"):
            with open(os.path.join(HERE, "index.html"), encoding="utf-8") as f:
                html = f.read()
            html = html.replace('href="style.css"', f'href="style.css?v={stamp}"').replace('src="app.js"', f'src="app.js?v={stamp}"')
            self.send_text(html, "text/html; charset=utf-8")
            return
        super().do_GET()

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        if self.path.startswith("/live-version") and Handler.polls % 60 != 1:
            return   # one line a minute is enough to see that an open page is checking for updates
        ua = self.headers.get("User-Agent", "") if getattr(self, "headers", None) else ""
        who = "headless" if "Headless" in ua else "curl" if ua.startswith("curl") else "browser"
        sys.stderr.write(f"[ui-preview] {fmt % args} [{who}]\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5173
    handler = lambda *a, **kw: Handler(*a, directory=HERE, **kw)
    with http.server.ThreadingHTTPServer(("0.0.0.0", port), handler) as server:
        print(f"[ui-preview] serving on 0.0.0.0:{port}", flush=True)
        server.serve_forever()
