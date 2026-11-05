"""Repeatable, unfunded Phase 1 upgrade/quote sequence inside the guarded emulator Job."""
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
        action('assert_text',text='Beta: Coinholder Polling')
        action('assert_text',text='Send Us Feedback')
        click('Address Book','Add New Contact')
        click('Add New Contact','Manual entry')
        click('Manual entry','Select…')
        action('set_text',index=0,value='1BoatSLRHtKNngkdXEeobR76b53LETtpyT')
        action('set_text',index=1,value='Phase1Fixture')
        click('Select…','Bitcoin')
        click('Bitcoin','Save')
        click('Save','Phase1Fixture')
        action('assert_text',text='Phase1Fixture')
        # Model an installed wallet that has used swaps: populate its public
        # asset catalog over selected Tor before the upgrade, without quoting.
        back('SETTINGS')
        back('More')
        click('Swap','From')
        action('set_text',index=0,value='0.001')
        click('Open address book','Phase1Fixture')
        click('Phase1Fixture','Get a quote')
        back('More')
        action('upgrade_candidate')
        wait('More',120)
        click('Swap','From')
        action('set_text',index=0,value='0.001')
        click('Open address book','Phase1Fixture')
        action('assert_text',text='Phase1Fixture')
        click('Phase1Fixture','Get a quote')
        for index in range(3):
            state=click('Get a quote')
            # The safety warning remains enabled; its Continue only requests a quote.
            for _ in range(6):
                state=action('snapshot')
                if 'no refunds under $300' in labels(state):
                    click('Continue')
                    break
                if 'review quote' in labels(state):break
                if stop.wait(.5):raise InterruptedError('UI driver stopped')
            state=wait('Review Quote',120)
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
        result={'passed':True,'upgradeContactPreserved':True,'quotesReviewedAndDismissed':3,
                'excludedSettingsAbsent':True,'settingsLabels':sorted(current),'fundsTransferred':False}
        (root/'phase1-scenario-result.json').write_text(json.dumps(result,indent=2)+'\n')
        print('PHASE1_SCENARIO_READY: upgrade, contact persistence, three Tor quotes and removal menus passed; awaiting retained-screen checks.',flush=True)
    except Exception as error:
        if stop.is_set():return
        (root/'phase1-scenario-error.json').write_text(json.dumps({'error':str(error)},indent=2)+'\n')
        print('PHASE1_SCENARIO_FAILED: '+str(error),flush=True)
        try:action('finish')
        except Exception:pass
