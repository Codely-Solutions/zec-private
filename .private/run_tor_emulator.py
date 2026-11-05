"""Task adapter: blank Android guest; all writable state inside the guard Job/root.
No authenticated AVD, wallet, seed, deposit or user app is loaded. QEMU and private
ADB inherit the kill-on-close Windows Job, so a killed controller ends the guest.
The measured 6 GiB userdata partition bounds guest writes; host storage is scanned
by shared admission. This adapter is specific to this credential-free probe.
"""
import os,subprocess,time,json,shutil,socket,urllib.request,zipfile,hashlib
from pathlib import Path
ROOT=Path(os.environ["CODEX_GUARD_OUTPUT"])
SDK=Path(r"C:\Users\User\AppData\Local\Android\Sdk")
PROJECT=Path(__file__).resolve().parents[1]
ADB=str(SDK/'platform-tools/adb.exe')
# Official stable Android35 AOSP x86_64 revision2; metadata verified 2026-11-05.
image_zip=ROOT/'android-image.zip'
with urllib.request.urlopen('https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip',timeout=30) as response, image_zip.open('wb') as out:
    total=0; digest=hashlib.sha1()
    while chunk:=response.read(1024*1024):
        total+=len(chunk)
        if total>782404023: raise ValueError('Image archive exceeds official size')
        digest.update(chunk);out.write(chunk)
if total!=782404023 or digest.hexdigest()!='2d857d170c0d1b827149565da34b3383e5306f7f': raise ValueError('Official image integrity mismatch')
image=ROOT/'image';image.mkdir()
with zipfile.ZipFile(image_zip) as archive:
    if sum(i.file_size for i in archive.infolist())>4*1024**3: raise ValueError('Image expanded size exceeds budget')
    for item in archive.infolist():
        target=(image/item.filename).resolve()
        if not target.is_relative_to(image.resolve()): raise ValueError('Image archive path escapes output')
    archive.extractall(image)
image_zip.unlink()
system=next(image.rglob('system.img')).parent
print('Verified disposable Android image extracted.',flush=True)
manifest=json.loads((PROJECT.parent/'tor-probe-artifact.json').read_text())
artifact=ROOT/'probe.zip'
with artifact.open('wb') as out:
    subprocess.run([r'C:\Program Files\GitHub CLI\gh.exe','api','repos/Sammy-Lastre/zec-private/actions/artifacts/'+str(manifest['id'])+'/zip'],stdout=out,stderr=subprocess.PIPE,check=True,timeout=120)
if artifact.stat().st_size>manifest['size_in_bytes']+1048576: raise ValueError('Probe archive exceeds declared size')
if 'digest' in manifest and manifest['digest'] and 'sha256:'+hashlib.sha256(artifact.read_bytes()).hexdigest()!=manifest['digest']: raise ValueError('Probe artifact digest mismatch')
apks=ROOT/'apks';apks.mkdir();apk_paths=[]
with zipfile.ZipFile(artifact) as archive:
    if sum(i.file_size for i in archive.infolist())>512*1024**2: raise ValueError('APK expansion exceeds budget')
    for item in archive.infolist():
        if item.filename.endswith('.apk'):
            target=apks/Path(item.filename).name
            target.write_bytes(archive.read(item));apk_paths.append(target)
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
from strip_emulator_apk import prepare
apk_preparation=prepare(apk_paths,ROOT,SDK)
print('Verified x86_64-only emulator APK copies ready; execution bytes unchanged.',flush=True)
PORT=5041
SERIAL='emulator-5580'
for port in (PORT,5580,5581,8877):
    with socket.socket() as sock: sock.bind(('127.0.0.1',port))
home=ROOT/'android';avds=home/'avd';avd=avds/'CodexTorProbe.avd';avd.mkdir(parents=True)
config={'abi.type':'x86_64','hw.cpu.arch':'x86_64','image.sysdir.1':str(system),'tag.id':'default','hw.lcd.width':'1080','hw.lcd.height':'1920','hw.lcd.density':'420','hw.gpu.enabled':'yes','hw.gpu.mode':'host','disk.cachePartition':'yes'}
config.update({'avd.id':'CodexTorProbe','avd.name':'CodexTorProbe','hw.ramSize':'1536','hw.cpu.ncore':'2','hw.sdCard':'no','hw.camera.back':'none','hw.camera.front':'none','hw.keyboard':'yes','fastboot.forceColdBoot':'yes','fastboot.forceFastBoot':'no','firstboot.saveToLocalSnapshot':'no','disk.dataPartition.size':'2G','disk.dataPartition.path':str(ROOT/'userdata.img'),'disk.cachePartition.path':str(ROOT/'cache.img')})
(avd/'config.ini').write_text(''.join(k+'='+v+'\n' for k,v in config.items()))
(avds/'CodexTorProbe.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-35\n')
env=dict(os.environ,ANDROID_HOME=str(SDK),ANDROID_SDK_ROOT=str(SDK),ANDROID_USER_HOME=str(home),ANDROID_EMULATOR_HOME=str(home),ANDROID_AVD_HOME=str(avds),ANDROID_ADB_SERVER_PORT=str(PORT),ADB_SERVER_SOCKET='tcp:localhost:'+str(PORT),ANDROID_EMU_ENABLE_CRASH_REPORTING='0')
log=(ROOT/'emulator.log').open('wb')
def adb(*args,timeout=30,check=True):
    return subprocess.run([ADB,'-P',str(PORT),'-s',SERIAL,*args],env=env,capture_output=True,timeout=timeout,check=check)
adb.command_base=[ADB,'-P',str(PORT),'-s',SERIAL]
adb.environment=env
receipt={'apkPreparation':apk_preparation,'probeArtifact':manifest,'apkHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in apk_paths},'fundsTransferred':False,'walletAccess':False,'route':'native SDK Tor only','guest':'fresh Android API35 x86_64','writableState':'guard-owned root','userdataRequestedBytes':2147483648}
proc=None
try:
    subprocess.run([ADB,'-P',str(PORT),'start-server'],env=env,capture_output=True,check=True,timeout=15)
    proc=subprocess.Popen([str(SDK/'emulator/emulator.exe'),'-avd','CodexTorProbe','-port','5580','-no-window','-partition-size','2047','-feature','-QuickbootFileBacked,-Vulkan','-no-snapshot','-no-boot-anim','-no-audio','-memory','1536','-cores','2','-gpu','host','-data',str(ROOT/'userdata.img'),'-cache',str(ROOT/'cache.img')],env=env,stdout=log,stderr=log,creationflags=0x08000000)
    adb.emulator=proc
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        if proc.poll() is not None: raise RuntimeError('Emulator exited: '+(ROOT/'emulator.log').read_text(errors='replace')[-2500:])
        r=adb('shell','getprop','sys.boot_completed',check=False,timeout=10)
        if r.stdout.strip()==b'1': break
        time.sleep(2)
    else: raise RuntimeError('Emulator boot deadline exceeded')
    receipt['guestDataBefore']=adb('shell','df','-k','/data').stdout.decode().strip()
    data_kib=int(receipt['guestDataBefore'].splitlines()[-1].split()[1])
    if data_kib>6500000: raise ValueError('Guest data partition exceeds reviewed 6GiB profile')
    receipt['guestDataPartitionActualKiB']=data_kib
    if manifest.get('probeKind')=='core-testnet':
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
finally:
    try:
        if proc and proc.poll() is None:
            try: adb('emu','kill',check=False,timeout=10)
            except (subprocess.TimeoutExpired, OSError): pass
            try: proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.terminate()
                try: proc.wait(timeout=10)
                except subprocess.TimeoutExpired: pass  # enclosing Job kills and verifies descendants
    finally:
        try: subprocess.run([ADB,'-P',str(PORT),'kill-server'],env=env,capture_output=True,timeout=10)
        except (subprocess.TimeoutExpired, OSError): pass
        finally: log.close()
