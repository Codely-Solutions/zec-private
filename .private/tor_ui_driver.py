"""Bounded Android UI test driver for the isolated, unfunded debug wallet only."""
import json,time,re,subprocess,xml.etree.ElementTree as ET

def run(adb, root, apks, package):
    if len(apks)!=1: raise ValueError('Expected one debug UI APK')
    adb('install','-r',str(apks[0]),timeout=90)
    adb('shell','monkey','-p',package,'-c','android.intent.category.LAUNCHER','1',timeout=30)
    state_file=root/'ui-state.json'; command_file=root/'ui-command.json'; last=None; trace=[]; assertions=[]
    def snapshot():
        png=adb('exec-out','screencap','-p',timeout=20).stdout
        assert len(png)<5*1024*1024
        (root/'ui.png').write_bytes(png)
        try:
            adb('shell','rm','-f','/data/local/tmp/zec-private-ui.xml',timeout=10,check=False)
            dump=adb('shell','uiautomator','dump','--compressed','/data/local/tmp/zec-private-ui.xml',timeout=30,check=False)
            raw=adb('shell','cat','/data/local/tmp/zec-private-ui.xml',timeout=15,check=False).stdout
            if not raw.lstrip().startswith(b'<?xml'):
                raise ValueError('Hierarchy unavailable while UI is animating; screenshot retained')
        except (subprocess.TimeoutExpired, ValueError):
            state={'sequence':last,'nodes':[],'hierarchyUnavailable':True,'screenshot':'ui.png'}
            state_file.write_text(json.dumps(state))
            return state
        tree=ET.fromstring(raw); nodes=list(tree.iter('node'))
        labels=[n.get('text','') for n in nodes]
        seed_screen=sum(bool(re.fullmatch('[a-z]{3,12}',t)) for t in labels)>=12 and any('phrase' in t.lower() for t in labels)
        safe=[]
        for n in nodes:
            text=n.get('text') or n.get('content-desc') or ''
            if seed_screen and (not n.get('clickable')=='true' or re.fullmatch('[a-z]{3,12}',text)): text='[test recovery screen]'
            text=re.sub(r'[A-Za-z0-9_:-]{30,}','[test identifier]',text)[:240]
            if text or n.get('clickable')=='true' or n.get('class','').endswith('EditText'):
                safe.append({'text':text,'id':n.get('resource-id'),'class':n.get('class'),'bounds':n.get('bounds'),'clickable':n.get('clickable'),'enabled':n.get('enabled')})
        state={'sequence':last,'seedScreenRedacted':seed_screen,'nodes':safe}
        state_file.write_text(json.dumps(state,indent=2))
        return state
    time.sleep(3);snapshot(); print('UI_READY: isolated unfunded emulator, bounded control loop.',flush=True)
    deadline=time.monotonic()+1000
    while time.monotonic()<deadline:
        if command_file.exists():
            cmd=json.loads(command_file.read_text())
            if cmd['sequence']!=last:
                last=cmd['sequence'];action=cmd['action']
                if action=='finish':
                    (root/'tor-ui-result.json').write_text(json.dumps({'completed':True,'trace':trace,'assertions':assertions,'fundsTransferred':False,'personalWalletAccess':False},indent=2))
                    return
                if action=='assert_text':
                    state=snapshot()
                    assert any(cmd['text'].lower() in n['text'].lower() for n in state['nodes']), 'Expected UI text missing'
                    assertions.append({'text':cmd['text'],'passed':True,'sequence':last})
                elif action=='tap':
                    x=int(cmd['x']);y=int(cmd['y']);assert 0<=x<=1080 and 0<=y<=1920
                    state=snapshot()
                    if state.get('hierarchyUnavailable'): continue
                    if any('review quote' in n['text'].lower() for n in state['nodes']):
                        for n in state['nodes']:
                            if n['text'].strip().lower()=='confirm':
                                values=[int(v) for v in re.findall(r'\d+',n['bounds'])]
                                assert not (values[0]<=x<=values[2] and values[1]<=y<=values[3]), 'Accepting test quotes is forbidden'
                    adb('shell','input','tap',str(x),str(y))
                elif action=='text':
                    text=cmd['text'];assert re.fullmatch(r'[a-zA-Z0-9._]+',text) and len(text)<=160
                    adb('shell','input','text',text)
                elif action=='back': adb('shell','input','keyevent','KEYCODE_BACK')
                elif action=='swipe':
                    coords=[int(cmd[k]) for k in ('x1','y1','x2','y2')];assert all(0<=v<=1920 for v in coords)
                    adb('shell','input','swipe',*[str(v) for v in coords],'500')
                elif action=='screenshot':
                    png=adb('exec-out','screencap','-p',timeout=20).stdout
                    assert len(png)<5*1024*1024
                    (root/'ui.png').write_bytes(png)
                elif action!='snapshot': raise ValueError('Unsupported UI test action')
                time.sleep(1)
                state=snapshot()
                trace.append({'sequence':last,'action':action,'labels':[n['text'] for n in state['nodes'] if n['text']][:24]})
                while len(json.dumps(trace))>9000: trace.pop(0)
        time.sleep(.2)
    raise RuntimeError('Isolated UI control deadline exceeded')
