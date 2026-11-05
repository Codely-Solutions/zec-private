"""One resident UiAutomation instrumentation session; no repeated UI-dump JVMs."""
import socket,json,time,re,subprocess

def run(adb, root, apks, manifest):
    bridge=next(p for p in apks if p.name=='ui-bridge.apk')
    app=next(p for p in apks if p!=bridge)
    for path in (app,bridge):adb('install','-t','-r',str(path),timeout=90)
    info=adb('shell','dumpsys','package',manifest['package'],timeout=20).stdout.decode(errors='replace')
    version=int(re.search(r'versionCode=(\d+)',info)[1]);assert version==manifest['expectedVersionCode']
    adb('forward','tcp:8877','tcp:8777',timeout=15)
    watcher_log=(root/'ui-instrumentation.log').open('wb')
    watcher=subprocess.Popen(adb.command_base+['shell','am','instrument','-w','-r','zec.uibridge/zec.uibridge.Bridge'],env=adb.environment,stdout=watcher_log,stderr=watcher_log,creationflags=0x08000000)
    def request(command):
        with socket.create_connection(('127.0.0.1',8877),timeout=10) as conn:
            conn.settimeout(20);conn.sendall((json.dumps(command)+'\n').encode());data=b''
            while b'\n' not in data:
                chunk=conn.recv(65536)
                if not chunk:raise ConnectionError('UI bridge disconnected')
                data+=chunk
                if len(data)>512*1024:raise ValueError('UI bridge response exceeds budget')
        return json.loads(data.split(b'\n',1)[0])
    last=None;trace=[];assertions=[];tor_opted_in=False;quote_requests=0;dismissals=0
    def capture(command):
        response=request(command)
        safe=[];labels=[str(n.get('text','')) for n in response.get('nodes',[])]
        seed_screen=sum(bool(re.fullmatch('[a-z]{3,12}',t)) for t in labels)>=12 and any('phrase' in t.lower() for t in labels)
        for node in response.get('nodes',[]):
            text=str(node.get('text',''))
            if seed_screen:text='[test recovery screen]'
            text=re.sub(r'[A-Za-z0-9_:/+.-]{30,}','[test identifier]',text)[:240]
            node=dict(node,text=text,id=node.get('id',''));safe.append(node)
        state={'sequence':last,'nodes':safe,'error':response.get('error'),'residentInstrumentation':True,'versionCode':version}
        (root/'ui-state.json').write_text(json.dumps(state,indent=2));return state
    try:
        ready=time.monotonic()+90
        while True:
            try:capture({'action':'snapshot'});break
            except (OSError,ValueError):
                if time.monotonic()>ready:raise RuntimeError('Resident UI bridge did not become ready')
                time.sleep(1)
        print('UI_READY: resident instrumentation; fresh unfunded test wallet only.',flush=True)
        deadline=time.monotonic()+1000
        while time.monotonic()<deadline:
            if adb.emulator.poll() is not None:raise RuntimeError('Emulator exited during UI test: '+str(adb.emulator.returncode))
            if watcher.poll() is not None:raise RuntimeError('UI instrumentation exited: '+str(watcher.returncode))
            path=root/'ui-command.json'
            if path.exists():
                command=json.loads(path.read_text())
                if command['sequence']!=last:
                    last=command['sequence'];action=command['action']
                    if action=='finish':
                        request({'action':'finish'})
                        receipt={'completed':True,'versionCode':version,'assertions':assertions,'trace':trace,'fundsTransferred':False,'personalWalletAccess':False,'control':'same-certificate instrumentation, loopback ADB forward, strict target package'}
                        receipt['quoteReviewsVerified']=sum(a['passed'] and a['text'].lower()=='review quote' for a in assertions)
                        receipt['torOptInVerified']=tor_opted_in
                        receipt['quoteRequests']=quote_requests;receipt['dismissals']=dismissals
                        receipt['passed']=receipt['quoteReviewsVerified']>=3 and quote_requests>=3 and dismissals>=2 and receipt['torOptInVerified'] and all(a['passed'] for a in assertions)
                        (root/'tor-ui-result.json').write_text(json.dumps(receipt,separators=(',',':')))
                        if not receipt['passed']:raise RuntimeError('Required Tor and quote UI assertions were not completed')
                        return
                    if action=='assert_text':
                        state=capture({'action':'snapshot'})
                        passed=any(command['text'].lower() in n['text'].lower() for n in state['nodes'])
                        assertions.append({'text':command['text'],'passed':passed,'sequence':last})
                        if not passed:state['error']='Expected UI text not present';(root/'ui-state.json').write_text(json.dumps(state))
                    else:
                        try:state=capture({k:v for k,v in command.items() if k!='sequence'})
                        except (OSError,ValueError) as error:
                            state={'sequence':last,'nodes':[],'error':type(error).__name__};(root/'ui-state.json').write_text(json.dumps(state))
                    if not state.get('error'):
                        if action=='click_text' and command.get('text')=='Opt in':tor_opted_in=True
                        if action=='click_text' and command.get('text','').lower() in ('get quote','get a quote'):quote_requests+=1
                        if action=='swipe':dismissals+=1
                    trace.append({'sequence':last,'action':action,'requestedText':command.get('text'),'labels':[n['text'] for n in state.get('nodes',[]) if n['text']][:24],'error':state.get('error')})
                    while len(json.dumps(trace))>6500:trace.pop(0)
            time.sleep(.15)
        raise RuntimeError('Resident UI test deadline exceeded')
    finally:
        try:request({'action':'finish'})
        except (OSError,ValueError):pass
        try:watcher.wait(timeout=15)
        except subprocess.TimeoutExpired:
            watcher.terminate()
            try:watcher.wait(timeout=10)
            except subprocess.TimeoutExpired:pass
        watcher_log.close()
        try:adb('forward','--remove','tcp:8877',timeout=10,check=False)
        except (OSError,subprocess.TimeoutExpired):pass
