"""Tiny UI driver over adb + uiautomator.
  ui.py tap TEXT [--exact]     tap the first element whose text/description contains (or equals) TEXT
  ui.py wait TEXT [SECONDS] [--exact]  wait until TEXT is on screen ("A||B" waits for either)
  ui.py gone TEXT [SECONDS]    wait until TEXT is no longer on screen
  ui.py tapclass CLASS         tap the first element of that class (e.g. android.widget.EditText)
"""
import re, subprocess, sys, time

def dump():
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
    return subprocess.run(['adb', 'shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout

def find(text, exact):
    xml = dump()
    for want in text.split('||'):
        for m in re.finditer(r'<node\b[^>]*>', xml):
            node = m.group(0)
            labels = [x.group(1) for x in re.finditer(r'(?:text|content-desc)="([^"]*)"', node) if x.group(1)]
            hit = any((l.strip().lower() == want.lower()) if exact else (want.lower() in l.lower()) for l in labels)
            if hit:
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
                return ' / '.join(labels), (int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2
    return None

cmd, text = sys.argv[1], sys.argv[2]
if cmd == 'tapclass':
    for m in re.finditer(r'<node\b[^>]*>', dump()):
        node = m.group(0)
        if f'class="{text}"' in node:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            subprocess.run(['adb', 'shell', 'input', 'tap', str((int(b.group(1)) + int(b.group(3))) // 2), str((int(b.group(2)) + int(b.group(4))) // 2)])
            print('tapped class:', text)
            sys.exit(0)
    print('no element of class', text)
    sys.exit(1)
elif cmd == 'tap':
    exact = '--exact' in sys.argv
    for _ in range(10):
        f = find(text, exact)
        if f:
            subprocess.run(['adb', 'shell', 'input', 'tap', str(f[1]), str(f[2])])
            print('tapped:', f[0])
            sys.exit(0)
        time.sleep(1)
    print('not found:', text)
    sys.exit(1)
elif cmd in ('wait', 'gone'):
    exact = '--exact' in sys.argv
    nums = [a for a in sys.argv[3:] if not a.startswith('--')]
    limit = float(nums[0]) if nums else 60
    end = time.time() + limit
    while True:
        f = find(text, exact)
        if cmd == 'wait' and f:
            print('seen:', f[0])
            sys.exit(0)
        if cmd == 'gone' and not f:
            print('gone:', text)
            sys.exit(0)
        if time.time() >= end:
            break
        time.sleep(2)
    print('timed out:', cmd, text)
    sys.exit(1)
