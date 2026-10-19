"""Turn instrumentation-test failures and the EngineTest log into GitHub annotations,
so they can be read from the checks API without downloading the raw job log."""
import glob, os
import xml.etree.ElementTree as ET

def esc(s):
    return s.replace('%', '%25').replace('\r', '').replace('\n', '%0A')

found = 0
for path in glob.glob('app/build/outputs/androidTest-results/connected/**/*.xml', recursive=True):
    try:
        root = ET.parse(path).getroot()
    except Exception as e:
        print(f'::warning title=unreadable test report::{esc(path + ": " + str(e))}')
        continue
    for case in root.iter('testcase'):
        for tag in ('failure', 'error'):
            node = case.find(tag)
            if node is not None:
                found += 1
                text = (node.get('message') or '') + '\n' + (node.text or '')
                print(f"::error title={case.get('name')}::{esc(text[:3500])}")

if os.path.exists('device.log'):
    lines = [l.strip() for l in open('device.log', errors='replace')
             if 'EngineTest' in l or 'AndroidRuntime' in l or ('VideoDl' in l and ' E ' in l)]
    real = [l for l in lines if 'REAL ' in l]
    if real:
        print(f"::notice title=real sites::{esc(chr(10).join(real)[-3500:])}")
    if lines:
        print(f"::notice title=EngineTest log::{esc(chr(10).join(lines)[-3500:])}")

if os.path.exists('shots/result.txt'):
    print(f"::notice title=app run on emulator::{esc(open('shots/result.txt', errors='replace').read()[-3500:])}")

if not found:
    print('::notice title=device tests::no failures parsed')
