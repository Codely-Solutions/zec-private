"""Run one real-native cancellation test against old and fixed app DEX."""
import json,hashlib,subprocess,zipfile,re
from pathlib import Path

def fetch_baseline(root, manifest, target_name="baseline-app.apk", instrumentation=False,
                   expected_apk_sha256=None):
    if Path(target_name).name!=target_name or not target_name.endswith('.apk'):
        raise ValueError('Baseline target must be a local APK basename')
    expected_apk_sha256=expected_apk_sha256 or manifest.get('apk_sha256')
    if instrumentation and (not re.fullmatch(r'[0-9a-f]{40}',str(manifest.get('source_sha','')))
            or not re.fullmatch(r'[0-9a-f]{64}',str(expected_apk_sha256 or ''))):
        raise ValueError('Baseline instrumentation requires exact source and APK digest')
    archive=root/'baseline.zip'
    if manifest.get('release_asset_id'):
        endpoint='repos/Sammy-Lastre/zec-private/releases/assets/'+str(int(manifest['release_asset_id']))
        command=[r'C:\Program Files\GitHub CLI\gh.exe','api','-H','Accept: application/octet-stream',endpoint]
    else:
        command=[r'C:\Program Files\GitHub CLI\gh.exe','api','repos/Sammy-Lastre/zec-private/actions/artifacts/'+str(manifest['id'])+'/zip']
    try:
        with archive.open('wb') as out:
            subprocess.run(command,stdout=out,stderr=subprocess.PIPE,check=True,timeout=120)
        assert archive.stat().st_size<=manifest['size_in_bytes']+1048576
        assert 'sha256:'+hashlib.sha256(archive.read_bytes()).hexdigest()==manifest['digest']
        with zipfile.ZipFile(archive) as z:
            assert sum(item.file_size for item in z.infolist())<=512*1024**2, 'Baseline expansion exceeds budget'
            if manifest.get('source_sha'):
                sources=[n for n in z.namelist() if n.endswith('source-sha.txt')]
                assert len(sources)==1, 'Exactly one baseline source identity required'
                assert z.read(sources[0]).decode().strip()==manifest['source_sha'], 'Baseline source identity mismatch'
            names=[n for n in z.namelist() if n.endswith('.apk') and ('androidTest' in n)==instrumentation]
            assert len(names)==1, 'Exactly one selected baseline APK required'
            data=z.read(names[0])
            if expected_apk_sha256:
                assert hashlib.sha256(data).hexdigest()==expected_apk_sha256, 'Baseline APK digest mismatch'
            target=root/'apks'/target_name
            target.write_bytes(data)
        return target
    finally:
        archive.unlink(missing_ok=True)


def run(adb, root, paths):
    baseline=next(p for p in paths if p.name=='baseline-app.apk')
    test=next(p for p in paths if 'androidTest' in p.name)
    candidate=next(p for p in paths if p not in (baseline,test))
    adb('install','-r',str(baseline),timeout=90)
    adb('install','-r',str(test),timeout=60)
    results=[]
    for name,apk,expect_failure in [('old',baseline,True),('fixed',candidate,False)]:
        if name=='fixed':
            adb('shell','am','force-stop','zec.privateprobe')
            adb('shell','pm','clear','zec.privateprobe',timeout=30)
            adb('install','-r',str(apk),timeout=90)
        result=adb('shell','am','instrument','-w','-r','zec.privateprobe.test/androidx.test.runner.AndroidJUnitRunner',timeout=600,check=False)
        text=result.stdout.decode(errors='replace')
        passed='OK (1 test)' in text
        reproduced='LATE_CANCELLED_ERROR_OVERWROTE_CURRENT_QUOTE oldHTTP=' in text
        (root/(name+'-native-diagnostic.txt')).write_text(text[-12000:])
        print('NATIVE_DIFFERENTIAL '+name+' passed='+str(passed)+' expectedLateErrorReproduced='+str(reproduced),flush=True)
        results.append({'version':name,'passed':passed,'lateErrorReproduced':reproduced,'realTor':True})
        if expect_failure: assert reproduced and not passed, 'Old code did not reproduce the intended native-Tor cancellation failure'
        else: assert passed and not reproduced, 'Fixed code failed native-Tor cancellation regression'
    receipt={'differentialPassed':True,'sameTestApk':True,'results':results,'fundsTransferred':False,'walletAccess':False,'testTiming':'Only delivery of a real invalid-refund HTTP 400/422 is delayed until a replacement live quote succeeds.'}
    (root/'tor-differential-result.json').write_text(json.dumps(receipt,indent=2))
