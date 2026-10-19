"""Static file server for the emulator tests that, like real video sites, supports byte ranges
(so aria2c can really split a download over many connections) and many connections at once.
Anything under /slow/ is the same file sent slowly (to have time to pause a download).
  python3 tools/serve.py DIR PORT
"""
import os, re, sys, time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class RangeHandler(SimpleHTTPRequestHandler):
    SLOW_BYTES_PER_SEC = 96 * 1024  # per connection

    def translate_path(self, path):
        self._slow = path.startswith('/slow/')
        return super().translate_path(path[5:] if self._slow else path)

    def send_head(self):
        rng = self.headers.get('Range')
        path = self.translate_path(self.path)
        if not rng or os.path.isdir(path) or not os.path.isfile(path):
            return super().send_head()
        m = re.match(r'bytes=(\d*)-(\d*)$', rng.strip())
        size = os.path.getsize(path)
        if not m:
            return super().send_head()
        start = int(m.group(1)) if m.group(1) else max(0, size - int(m.group(2) or 0))
        end = int(m.group(2)) if m.group(1) and m.group(2) else size - 1
        end = min(end, size - 1)
        if start > end:
            self.send_error(416)
            return None
        f = open(path, 'rb')
        f.seek(start)
        self.send_response(206)
        self.send_header('Content-Type', self.guess_type(path))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.send_header('Content-Length', str(end - start + 1))
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        left = getattr(self, '_remaining', None)
        slow = getattr(self, '_slow', False)
        if left is None and not slow:
            return super().copyfile(source, outputfile)
        step = 16384 if slow else 65536
        while left is None or left > 0:
            chunk = source.read(step if left is None else min(step, left))
            if not chunk:
                break
            outputfile.write(chunk)
            if left is not None:
                left -= len(chunk)
            if slow:
                time.sleep(len(chunk) / self.SLOW_BYTES_PER_SEC)
        self._remaining = None

    def end_headers(self):
        if not self.headers.get('Range'):
            self.send_header('Accept-Ranges', 'bytes')
        super().end_headers()


if __name__ == '__main__':
    directory, port = sys.argv[1], int(sys.argv[2])
    ThreadingHTTPServer(('0.0.0.0', port), partial(RangeHandler, directory=directory)).serve_forever()
