import http.server
import os
import urllib.parse

APK_PATH = "release/laya-v1.0.2.apk"
APK_NAME = "laya-v1.0.2.apk"

HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Laya Music — Release APK</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #0f0f13;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    color: #e8e8f0;
  }
  .card {
    background: #1a1a24;
    border: 1px solid #2e2e40;
    border-radius: 20px;
    padding: 48px 40px;
    max-width: 440px;
    width: 90%;
    text-align: center;
    box-shadow: 0 24px 64px rgba(0,0,0,.6);
  }
  .icon {
    width: 72px; height: 72px;
    background: linear-gradient(135deg, #6c63ff, #a78bfa);
    border-radius: 20px;
    display: flex; align-items: center; justify-content: center;
    margin: 0 auto 24px;
    font-size: 36px;
  }
  h1 { font-size: 1.5rem; font-weight: 700; letter-spacing: -.02em; }
  .version {
    display: inline-block;
    margin: 8px 0 24px;
    padding: 3px 12px;
    background: #2e2e40;
    border-radius: 99px;
    font-size: .8rem;
    color: #a78bfa;
    letter-spacing: .04em;
  }
  .meta {
    display: flex; gap: 12px; justify-content: center;
    margin-bottom: 32px;
    flex-wrap: wrap;
  }
  .badge {
    padding: 5px 14px;
    background: #12121a;
    border: 1px solid #2e2e40;
    border-radius: 99px;
    font-size: .78rem;
    color: #888;
  }
  .badge span { color: #c4c4d8; }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    padding: 14px 32px;
    background: linear-gradient(135deg, #6c63ff, #a78bfa);
    color: #fff;
    text-decoration: none;
    border-radius: 12px;
    font-weight: 600;
    font-size: 1rem;
    letter-spacing: -.01em;
    transition: opacity .15s, transform .15s;
    box-shadow: 0 8px 24px rgba(108,99,255,.4);
  }
  .btn:hover { opacity: .88; transform: translateY(-1px); }
  .btn svg { flex-shrink: 0; }
  .note {
    margin-top: 20px;
    font-size: .75rem;
    color: #555;
    line-height: 1.6;
  }
</style>
</head>
<body>
<div class="card">
  <div class="icon">🎵</div>
  <h1>Laya Music</h1>
  <div class="version">v1.0.2 · Release Build</div>
  <div class="meta">
    <div class="badge">Size <span>5.9 MB</span></div>
    <div class="badge">minSDK <span>24</span></div>
    <div class="badge">targetSDK <span>35</span></div>
    <div class="badge">Signed <span>v2 ✓</span></div>
  </div>
  <a class="btn" href="/download">
    <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.2">
      <path stroke-linecap="round" stroke-linejoin="round"
        d="M4 16v2a2 2 0 002 2h12a2 2 0 002-2v-2M7 10l5 5 5-5M12 15V3"/>
    </svg>
    Download APK
  </a>
  <p class="note">
    Enable <strong>Install from unknown sources</strong> on your device<br>
    before installing a sideloaded APK.
  </p>
</div>
</body>
</html>
"""

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # suppress request logs

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/download":
            size = os.path.getsize(APK_PATH)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f'attachment; filename="{APK_NAME}"')
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(APK_PATH, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        else:
            body = HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", 5000), Handler)
    print("Serving on :5000")
    server.serve_forever()
