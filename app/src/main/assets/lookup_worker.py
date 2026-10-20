"""Keeps yt-dlp loaded so looking a link up doesn't pay for starting Python and importing yt-dlp each time.

  python lookup_worker.py PATH_TO_YT_DLP_ZIP

Reads one JSON request per line on stdin:  {"id": 1, "url": "...", "args": ["--flat-playlist", ...]}
and answers one JSON line per request on stdout (in any order, lookups run side by side):
  {"id": 1, "ok": true, "out": "<what yt-dlp -J would print>"}   or   {"id": 1, "ok": false, "err": "ERROR: ..."}
The first line written is {"id": 0, "ok": true, "out": "ready <version>"}.
"""
import json
import sys
import threading

sys.path.insert(0, sys.argv[1])
import yt_dlp  # noqa: E402
from yt_dlp import YoutubeDL  # noqa: E402

_out_lock = threading.Lock()


def reply(obj):
    line = json.dumps(obj, ensure_ascii=False)
    with _out_lock:
        sys.stdout.write(line + '\n')
        sys.stdout.flush()


class Log:
    """Collects what yt-dlp would have printed as warnings/errors, for the error message."""

    def __init__(self):
        self.lines = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        self.lines.append('WARNING: ' + str(msg))

    def error(self, msg):
        self.lines.append(str(msg))


def lookup(req):
    log = Log()
    try:
        opts = yt_dlp.parse_options(list(req['args'])).ydl_opts
        # Errors must come back as errors (yt-dlp's default quietly gives nothing for some of them).
        opts.update(logger=log, quiet=True, no_warnings=False, noprogress=True, simulate=True, ignoreerrors=False)
        with YoutubeDL(opts) as ydl:
            info = ydl.extract_info(req['url'], download=False)
            out = json.dumps(ydl.sanitize_info(info), ensure_ascii=False)
        reply({'id': req['id'], 'ok': True, 'out': out})
    except BaseException as e:  # noqa: BLE001 - every failure goes back as text
        msg = str(e)
        lines = [l for l in log.lines if l.strip()]
        if msg and msg not in lines:
            lines.append(msg)
        reply({'id': req['id'], 'ok': False, 'err': '\n'.join(lines[-20:]) or repr(e)})


def warm_up():
    # The first lookup of an ordinary web page checks every site yt-dlp knows; load them all now.
    try:
        import yt_dlp.extractor._extractors  # noqa: F401
    except Exception:
        pass


def main():
    threading.Thread(target=warm_up, daemon=True).start()
    reply({'id': 0, 'ok': True, 'out': 'ready ' + yt_dlp.version.__version__})
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        threading.Thread(target=lookup, args=(req,), daemon=True).start()


if __name__ == '__main__':
    main()
