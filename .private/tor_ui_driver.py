"""Bounded Android UI test driver for the isolated, unfunded debug wallet only."""
import json,time,re,xml.etree.ElementTree as ET

def run(adb, root, apks, package):
    if len(apks)!=1: raise ValueError('Expected one debug UI APK')
    adb('install','-r',str(apks[0]),timeout=90)
    adb('shell','monkey','-p',package,'-c','android.intent.category.LAUNCHER','1',timeout=30)
    state_file=root/'ui-state.json'; command_file=root/'ui-command.json'; last=None; trace=[]
    def snapshot():
        adb('shell','uiautomator','dump','/sdcard/zec-private-ui.xml',timeout=30)
        raw=adb('shell','cat','/sdcard/zec-private-ui.xml',timeout=15).stdout
        if len(raw)>1024*1024: raise ValueError('UI hierarchy exceeds limit')
        tree=ET.fromstring(raw); nodes=list(tree.iter('node'))
        labels=[n.get('text','') for n in nodes]
        seed_screen=sum(bool(re.fullmatch('[a-z]{3,12}',t)) for t in labels)>=12 and any('phrase' in t.lower() for t in labels)
        safe=[]
        for n in nodes:
            text=n.get('text') or n.get('content-desc') or ''
            if seed_screen and not n.get('clickable')=='true': text='[test recovery screen]'
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
                    (root/'tor-ui-result.json').write_text(json.dumps({'completed':True,'trace':trace,'fundsTransferred':False,'personalWalletAccess':False},indent=2))
                    return
                if action=='tap':
                    x=int(cmd['x']);y=int(cmd['y']);assert 0<=x<=1080 and 0<=y<=1920
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
                if len(trace)>45: trace=trace[-45:]
        time.sleep(.2)
    raise RuntimeError('Isolated UI control deadline exceeded')
