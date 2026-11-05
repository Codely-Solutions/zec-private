"""Fresh-wallet unfunded size/workflow quote acceptance inside the guarded emulator Job."""
import json
import time


def run(root, stop):
    sequence=0

    def read_state():
        for _ in range(10):
            try:return json.loads((root/'ui-state.json').read_text())
            except (PermissionError,FileNotFoundError,json.JSONDecodeError):
                if stop.wait(.05):raise InterruptedError('UI driver stopped')
        raise RuntimeError('UI state remained unavailable')

    def action(kind,**fields):
        nonlocal sequence
        if stop.is_set():raise InterruptedError('UI driver stopped')
        sequence+=1
        command=dict(action=kind,sequence=sequence,**fields)
        pending=root/'scenario-command.next'
        pending.write_text(json.dumps(command))
        for attempt in range(10):
            try:
                pending.replace(root/'ui-command.json')
                break
            except PermissionError:
                if attempt==9:raise
                if stop.wait(.05):raise InterruptedError('UI driver stopped')
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            if stop.wait(.15):raise InterruptedError('UI driver stopped')
            state=read_state()
            if state.get('sequence')==sequence:
                if state.get('error'):raise RuntimeError('UI command failed: '+kind+' '+str(state['error']))
                return state
        raise RuntimeError('UI command response timed out: '+kind)

    def labels(state):
        return {n['text'].casefold() for n in state.get('nodes',[]) if n.get('text')}

    def wait(text,timeout=240):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            state=action('snapshot')
            if text.casefold() in labels(state):return state
            if stop.wait(.5):raise InterruptedError('UI driver stopped')
        raise RuntimeError('UI label timed out: '+text)

    def click(text,next_text=None):
        wait(text)
        state=action('click_text',text=text)
        return wait(next_text) if next_text else state

    def back(next_text):
        action('back')
        return wait(next_text)

    try:
        wait('Create New Wallet')
        click('Restore Existing Wallet','Seed Recovery Phrase')
        action('assert_text',text='Seed Recovery Phrase')
        back('Create New Wallet')
        click('Create New Wallet','More')
        click('Review','Opt in')
        click('Opt in','More')
        click('More','More...')
        click('More...','Address Book')
        click('Address Book','Add New Contact')
        click('Add New Contact','Manual entry')
        click('Manual entry','Select…')
        action('set_text',index=0,value='1BoatSLRHtKNngkdXEeobR76b53LETtpyT')
        action('set_text',index=1,value='Phase1Fixture')
        click('Select…','Bitcoin')
        click('Bitcoin','Save')
        click('Save','Phase1Fixture')
        action('assert_text',text='Phase1Fixture')
        back('SETTINGS')
        back('More')
        click('Swap','From')
        action('set_text',index=0,value='0.001')
        click('Open address book','Phase1Fixture')
        click('Phase1Fixture','Get a quote')
        provider_errors=[]
        for index in range(3):
            for attempt in range(3):
                state=click('Get a quote')
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    state=action('snapshot')
                    current=labels(state)
                    if 'no refunds under $300' in current:
                        click('Continue')
                    elif 'review quote' in current:
                        break
                    elif 'quote unavailable' in current:
                        messages=[n['text'] for n in state['nodes'] if 'timeout' in n['text'].lower()]
                        # Retry this observed upstream timeout only. Other errors require diagnosis.
                        if not messages or not any('25000ms' in m for m in messages):
                            raise RuntimeError('Unexpected provider quote failure')
                        provider_errors.append({'cycle':index+1,'attempt':attempt+1,'messages':messages})
                        click('Edit swap','Get a quote')
                        if stop.wait(15):raise InterruptedError('UI driver stopped')
                        break
                    if stop.wait(.5):raise InterruptedError('UI driver stopped')
                if 'review quote' in labels(state):break
            else:raise RuntimeError('Provider timeout retry budget exhausted')
            if 'review quote' not in labels(state):raise RuntimeError('Quote response deadline exceeded')
            action('assert_text',text='Review Quote')
            title=next(n for n in state['nodes'] if n['text'].casefold()=='review quote')
            title_top=int(title['bounds'].split()[1])
            accept=next(n for n in state['nodes'] if n['text'].casefold()=='confirm')
            accept_top=int(accept['bounds'].split()[1])
            y1=title_top-53
            y2=min(y1+905,accept_top-80)
            action('swipe',x=540,y1=y1,y2=y2)
            wait('Get a quote')
        back('More')
        click('More','More...')
        click('More...','Address Book')
        state=wait('Advanced Settings')
        current=labels(state)
        forbidden={'beta: coinholder polling','send us feedback','pay with flexa'}
        if current & forbidden:raise RuntimeError('Excluded menu entries remain')
        action('assert_text',text='Advanced Settings')
        action('assert_text',text='About')
        action('assert_text',text="What's New")
        result={'passed':True,'freshIsolatedWallet':True,'quotesReviewedAndDismissed':3,
                'providerTimeouts':provider_errors,'excludedSettingsAbsent':True,'settingsLabels':sorted(current),'fundsTransferred':False}
        (root/'phase1-scenario-result.json').write_text(json.dumps(result,indent=2)+'\n')
        action('finish')
    except Exception as error:
        if stop.is_set():return
        (root/'phase1-scenario-error.json').write_text(json.dumps({'error':str(error)},indent=2)+'\n')
        print('PHASE1_SCENARIO_FAILED: '+str(error),flush=True)
        try:action('finish')
        except Exception:pass
