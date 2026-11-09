"""Capture synthetic review scenes in an existing isolated EmulatorSession.

The caller owns admission, verified APK installation, guest lifetime and evidence
retention. Reuse the guest across captures and compatible subsequent checks.
"""
import hashlib
import json
from pathlib import Path
import re
import time

PACKAGE = 'com.personal.zecprivate.foss.debug'
ACTIVITY = 'co.electriccoin.zcash.ui.screen.donation.StyleComparisonActivity'


def validate_request(manifest):
    scenes = manifest.get('captureScenes')
    if scenes is not None and (not isinstance(scenes, list) or not 1 <= len(scenes) <= 100):
        raise ValueError('Bounded nonempty scene list required')
    if scenes and manifest.get('probeKind') != 'ui':
        raise ValueError('Synthetic scenes belong to UI review sessions')
    if manifest.get('captureOnly') and (not scenes or manifest.get('probeKind') != 'ui' or manifest.get('residentUiBridge')):
        raise ValueError('Capture-only cannot satisfy financial acceptance')


def capture_scenes(adb, scenes, destination, *, package=PACKAGE, activity=ACTIVITY, settle=time.sleep):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    records = []
    for scene in scenes:
        name = scene.get('name', scene['screen']+'-'+scene['theme'])
        if not re.fullmatch(r'[a-zA-Z0-9_-]+', name) or scene['theme'] not in ('light', 'dark', 'oled'):
            raise ValueError('Invalid capture name or theme')
        if (destination/(name+'.png')).exists() or (destination/(name+'.xml')).exists():
            raise ValueError('Capture evidence already exists')
        font = float(scene.get('font', 1))
        if not .5 <= font <= 3:
            raise ValueError('Invalid font scale')
        adb('shell', 'settings', 'put', 'system', 'font_scale', str(font), timeout=15)
        adb('shell', 'am', 'force-stop', package, timeout=15)
        launch = adb('shell', 'am', 'start', '-W', '-n', package+'/'+activity,
                     '--es', 'screen', scene['screen'], '--es', 'theme', scene['theme'], timeout=35, check=False)
        output = launch.stdout.decode(errors='replace')
        if launch.returncode or 'Error' in output or 'Exception' in output:
            # Synthetic host launch metadata only; avoid raw device/network logs.
            (destination/(name+'-launch.txt')).write_text((output+launch.stderr.decode(errors='replace'))[-12000:])
            raise RuntimeError('Synthetic scene launch failed: '+name)
        settle(1)
        for label in (['More'] if scene.get('expand_more') else []) + ([scene['tap_text']] if scene.get('tap_text') else []):
            import xml.etree.ElementTree as ET
            adb('shell', 'uiautomator', 'dump', '/data/local/tmp/review-tap.xml', timeout=20)
            tree = ET.fromstring(adb('shell', 'cat', '/data/local/tmp/review-tap.xml', timeout=10).stdout)
            node = next((n for n in tree.iter('node') if label in (n.get('text','')+' '+n.get('content-desc',''))), None)
            values = re.findall(r'\d+', node.get('bounds','')) if node is not None else []
            if len(values) != 4:
                raise RuntimeError('Synthetic tap target missing: '+label)
            x1, y1, x2, y2 = map(int, values)
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2), timeout=10)
            settle(.5)
        if scene.get('swipe'):
            adb('shell', 'input', 'swipe', '550', '1550', '550', '650', '500', timeout=10)
            settle(.5)
        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/review.xml', timeout=20)
        xml = adb('shell', 'cat', '/data/local/tmp/review.xml', timeout=10).stdout
        import xml.etree.ElementTree as ET
        nodes = list(ET.fromstring(xml).iter('node'))
        if not any(n.get('package') == package for n in nodes) or not any(
                scene.get('expected', '').lower() in (n.get('text','')+' '+n.get('content-desc','')).lower()
                for n in nodes):
            raise RuntimeError('Synthetic scene hierarchy mismatch: '+name)
        png = adb('exec-out', 'screencap', '-p', timeout=15).stdout
        if not png.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError('Screenshot is not PNG')
        (destination/(name+'.xml')).write_bytes(xml)
        (destination/(name+'.png')).write_bytes(png)
        records.append(dict(scene, name=name, pngSha256=hashlib.sha256(png).hexdigest(),
                            hierarchySha256=hashlib.sha256(xml).hexdigest(), synthetic=True))
        # Append after every successful frame; partial failure keeps completed evidence.
        with (destination/'captures.jsonl').open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(records[-1])+'\n')
    return records
