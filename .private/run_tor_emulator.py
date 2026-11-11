"""Task adapter: blank Android guest; all writable state inside the guard Job/root.
No authenticated AVD, wallet, seed, deposit or user app is loaded. QEMU and private
ADB inherit the kill-on-close Windows Job, so a killed controller ends the guest.
The measured 6 GiB userdata partition bounds guest writes; host storage is scanned
by shared admission. This adapter is specific to this credential-free probe.
"""
import os,subprocess,json,zipfile,hashlib
from pathlib import Path
from emulator_session import EmulatorSession, SDK, load_manifest
from release_metadata import GH
ROOT=Path(os.environ["CODEX_GUARD_OUTPUT"])
PROJECT=Path(__file__).resolve().parents[1]
manifest=load_manifest(PROJECT)
from ui_capture import validate_request
validate_request(manifest)
guest_memory=3072 if manifest.get('probeKind')=='core-testnet' else 1536
from prepared_apks import binding, reuse, write_receipt, export_prepared
local_receipt=os.environ.get('CODEX_EMULATOR_PREPARED_RECEIPT')
manifest_binding=binding(manifest) if local_receipt else json.loads(json.dumps(manifest))
if local_receipt:
    apk_paths,apk_preparation,manifest=reuse(local_receipt,manifest,ROOT,SDK,os.environ.get('CODEX_EMULATOR_PREPARED_SHA256'))
else:
    artifact=ROOT/'probe.zip'
    with artifact.open('wb') as out:
        subprocess.run([GH,'api','repos/Codely-Solutions/zec-private/actions/artifacts/'+str(manifest['id'])+'/zip'],stdout=out,stderr=subprocess.PIPE,check=True,timeout=120)
    if artifact.stat().st_size>manifest['size_in_bytes']+1048576: raise ValueError('Probe archive exceeds declared size')
    if 'digest' in manifest and manifest['digest'] and 'sha256:'+hashlib.sha256(artifact.read_bytes()).hexdigest()!=manifest['digest']: raise ValueError('Probe artifact digest mismatch')
    apks=ROOT/'apks';apks.mkdir();apk_paths=[]
    with zipfile.ZipFile(artifact) as archive:
        if sum(i.file_size for i in archive.infolist())>512*1024**2: raise ValueError('APK expansion exceeds budget')
        for item in archive.infolist():
            if item.filename.endswith('.apk'):
                target=apks/Path(item.filename).name
                target.write_bytes(archive.read(item));apk_paths.append(target)
        if manifest.get('probeKind')=='core-testnet':
            source=next(n for n in archive.namelist() if n.endswith('source-sha.txt'))
            if archive.read(source).decode().strip()!=manifest['build_source_sha']:
                raise ValueError('Core artifact source metadata mismatch')
            checksums=next(n for n in archive.namelist() if n.endswith('SHA256SUMS'))
            expected={Path(line.split()[1]).name:line.split()[0] for line in archive.read(checksums).decode().splitlines()}
            if len(set(apk_paths))!=2:raise ValueError('Core artifact names must be unique')
            for path in apk_paths:
                digest=hashlib.sha256(path.read_bytes()).hexdigest()
                if expected.get(path.name)!=digest:raise ValueError('Core APK checksum mismatch')
                field='instrumentation_apk_sha256' if 'androidTest' in path.name else 'candidate_apk_sha256'
                manifest[field]=digest
    artifact.unlink()
    expected_apks=1 if manifest.get('probeKind')=='ui' else 2
    if len(apk_paths)!=expected_apks: raise ValueError('Unexpected probe APK count')
    if manifest.get('probeKind')=='differential':
        from tor_differential_driver import fetch_baseline
        apk_paths.append(fetch_baseline(ROOT,manifest['baselineArtifact']))
    if manifest.get('residentUiBridge'):
        from tor_differential_driver import fetch_baseline
        apk_paths.append(fetch_baseline(ROOT,manifest['residentUiBridge'],'ui-bridge.apk'))
    if manifest.get('upgradeBaseline'):
        from tor_differential_driver import fetch_baseline
        apk_paths.append(fetch_baseline(ROOT,manifest['upgradeBaseline'],'baseline-ui.apk'))
        if manifest.get('probeKind')=='core-testnet':
            manifest['baseline_apk_sha256']=hashlib.sha256(apk_paths[-1].read_bytes()).hexdigest()
            baseline_instrumentation=manifest.get('upgradeBaselineInstrumentation')
            if not baseline_instrumentation or baseline_instrumentation.get('source_sha')!=manifest['baseline_source_sha']:
                raise ValueError('Version-matched baseline instrumentation binding required')
            baseline_test=fetch_baseline(ROOT,baseline_instrumentation,'baseline-androidTest.apk',
                instrumentation=True,expected_apk_sha256=baseline_instrumentation.get('apk_sha256'))
            apk_paths.append(baseline_test)
            manifest['baseline_instrumentation_apk_sha256']=hashlib.sha256(baseline_test.read_bytes()).hexdigest()
    from strip_emulator_apk import prepare
    apk_preparation=prepare(apk_paths,ROOT,SDK)

if manifest_binding.get('build_source_sha') or manifest_binding.get('source_sha'):
    write_receipt(ROOT/'prepared-apks.json',manifest_binding,manifest,apk_paths,apk_preparation)
    if os.environ.get('CODEX_EMULATOR_PREPARED_EXPORT'):
        export_prepared(Path(os.environ['CODEX_EMULATOR_PREPARED_EXPORT']),manifest_binding,manifest,apk_paths,apk_preparation,SDK)
print('Verified x86_64-only emulator APK copies ready; execution bytes unchanged.',flush=True)
receipt={'apkPreparation':apk_preparation,'probeArtifact':manifest,'apkHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in apk_paths},'fundsTransferred':False,'walletAccess':False,'route':'native SDK Tor only','guest':'fresh Android API35 x86_64','guestMemoryMiB':guest_memory,'writableState':'guard-owned root','userdataRequestedBytes':2147483648}
with EmulatorSession(ROOT, guest_memory=guest_memory) as session:
    adb=session.adb
    receipt.update(emulatorSlot=session.slot,privateAdbPort=session.ports.adb,
                   consolePort=session.ports.console,grpcPort=session.ports.grpc)
    receipt['guestDataBefore']=session.guest_data_before
    receipt['guestDataPartitionActualKiB']=session.guest_data_partition_kib
    scenes = manifest.get('captureScenes')
    capture_only = manifest.get('captureOnly', False)
    if capture_only and (not scenes or manifest.get('probeKind') != 'ui' or manifest.get('residentUiBridge')):
        raise ValueError('Capture-only requires synthetic UI scenes without financial acceptance')
    if scenes:
        if manifest.get('probeKind') != 'ui':
            raise ValueError('Synthetic scenes belong to UI review sessions')
        from ui_capture import capture_scenes
        adb('install', '-r', str(apk_paths[0]), timeout=90)
        receipt['syntheticCaptures'] = capture_scenes(adb, scenes, ROOT/'captures')
    if capture_only:
        receipt.update(passed=True, captureOnly=True, evidenceScope='Synthetic rendering only')
    elif manifest.get('probeKind')=='core-testnet':
        from core_testnet_driver import run
        run(adb,ROOT,apk_paths,manifest)
        receipt.update(passed=True,fundsTransferred=True,realFundsUsed=False,walletAccess='isolated-testnet-only')
    elif manifest.get('probeKind')=='differential':
        from tor_differential_driver import run
        run(adb,ROOT,apk_paths)
        receipt['passed']=True
    elif manifest.get('probeKind')=='ui':
        if manifest.get('residentUiBridge'):
            from tor_resident_ui_driver import run
            run(adb,ROOT,apk_paths,manifest)
        else:
            from tor_ui_driver import run
            run(adb,ROOT,apk_paths,manifest['package'])
        receipt['passed']=True
    else:
        for path in sorted(apk_paths, key=lambda p: 'androidTest' in p.name):
            adb('install','-r',str(path),timeout=60)
        result=adb('shell','am','instrument','-w','-r','zec.privateprobe.test/androidx.test.runner.AndroidJUnitRunner',timeout=600,check=False)
        text=result.stdout.decode(errors='replace')
        # Instrumentation prints assertion summaries, never HTTP bodies or addresses.
        safe_lines=[line for line in text.splitlines() if line.startswith('TOR_QUOTE ') or line.startswith('OK (') or line.startswith('FAILURES') or line.startswith('Tests run:')]
        print('\n'.join(safe_lines),flush=True)
        if 'OK (1 test)' not in text:
            # A bounded private file retains only public-fixture test diagnostics for investigation.
            (ROOT/'tor-probe-diagnostic.txt').write_text(text[-12000:])
        receipt['passed']='OK (1 test)' in text
    receipt['guestDataAfter']=adb('shell','df','-k','/data').stdout.decode().strip()
    (ROOT/'tor-probe-result.json').write_text(json.dumps(receipt,indent=2)+'\n')
    if not receipt['passed']: raise RuntimeError('Native Tor probe failed; safe instrumentation output above')
