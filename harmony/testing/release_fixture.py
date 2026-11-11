#!/usr/bin/env python3
# coding: utf-8
"""只读公开版本元数据夹具：不提供任何安装包或下载接口。"""
import json
import os
from pathlib import Path
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
ROOT = 'https://github.com/Clarklevis1995/dsh-mobile'
STATE = Path(os.environ['HARMONY_RELEASE_FIXTURE_STATE'])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path != '/latest': self.send_error(404); return
        mode = STATE.read_text().strip()
        code = 429 if mode == 'quota' else 404 if mode == 'empty' else 200
        version = 'v9.8.1' if mode == 'apk' else 'v0.0.1' if mode == 'current' else 'v9.9.9' if mode == 'delayed' else 'v0.2.0'
        name = 'android.apk' if mode == 'apk' else 'harmony.hap'
        value = dict(tag_name=version, html_url=ROOT+'/releases/tag/'+version, draft=False, prerelease=False,
                     assets=[dict(name=name, state='uploaded', size=123, browser_download_url=ROOT+'/releases/download/'+version+'/'+name)])
        data = ('invalid json' if mode == 'invalid' else json.dumps(value)).encode()
        print('request', mode, code, flush=True)
        if mode == 'delayed': time.sleep(14)
        try:
            self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError): pass
        print('finished', mode, flush=True)


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1',18787),Handler).serve_forever()
