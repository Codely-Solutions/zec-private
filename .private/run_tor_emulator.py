"""Task adapter: blank Android guest; all writable state inside the guard Job/root.
No authenticated AVD, wallet, seed, deposit or user app is loaded. QEMU and private
ADB inherit the kill-on-close Windows Job, so a killed controller ends the guest.
The finite 2 GiB userdata partition bounds guest writes; host storage is scanned
by shared admission. This adapter is specific to this credential-free probe.
"""
import os,subprocess,time,json,shutil,socket
from pathlib import Path
ROOT=Path(os.environ["CODEX_GUARD_OUTPUT"])
SDK=Path(r"C:\Users\User\AppData\Local\Android\Sdk")
BASE=Path(r"C:\Users\User\.android\avd\Android_Emulator_35.avd\config.ini")
PROJECT=Path(__file__).resolve().parents[1]
ADB=str(SDK/'platform-tools/adb.exe')
PORT=5041
SERIAL='emulator-5580'
for port in (PORT,5580,5581):
    with socket.socket() as sock: sock.bind(('127.0.0.1',port))
home=ROOT/'android';avds=home/'avd';avd=avds/'CodexTorProbe.avd';avd.mkdir(parents=True)
config={'abi.type':'x86_64','hw.cpu.arch':'x86_64','image.sysdir.1':str(SDK/'system-images/android-35/google_apis/x86_64'),'tag.id':'google_apis','hw.lcd.width':'1080','hw.lcd.height':'1920','hw.lcd.density':'420','hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader_indirect','disk.cachePartition':'yes'}
config.update({'avd.id':'CodexTorProbe','avd.name':'CodexTorProbe','hw.ramSize':'1536','hw.cpu.ncore':'2','hw.sdCard':'no','hw.camera.back':'none','hw.camera.front':'none','hw.keyboard':'yes','fastboot.forceColdBoot':'yes','fastboot.forceFastBoot':'no','firstboot.saveToLocalSnapshot':'no','disk.dataPartition.size':'2G','disk.dataPartition.path':str(ROOT/'userdata.img'),'disk.cachePartition.path':str(ROOT/'cache.img')})
(avd/'config.ini').write_text(''.join(k+'='+v+'\n' for k,v in config.items()))
(avds/'CodexTorProbe.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-35\n')
env=dict(os.environ,ANDROID_HOME=str(SDK),ANDROID_SDK_ROOT=str(SDK),ANDROID_USER_HOME=str(home),ANDROID_EMULATOR_HOME=str(home),ANDROID_AVD_HOME=str(avds),ANDROID_ADB_SERVER_PORT=str(PORT),ADB_SERVER_SOCKET='tcp:localhost:'+str(PORT))
log=(ROOT/'emulator.log').open('wb')
def adb(*args,timeout=30,check=True):
    return subprocess.run([ADB,'-P',str(PORT),'-s',SERIAL,*args],env=env,capture_output=True,timeout=timeout,check=check)
receipt={'fundsTransferred':False,'walletAccess':False,'route':'native SDK Tor only','guest':'fresh Android API35 x86_64','writableState':'guard-owned root','userdataLimitBytes':2147483648}
proc=None
try:
    subprocess.run([ADB,'-P',str(PORT),'start-server'],env=env,capture_output=True,check=True,timeout=15)
    proc=subprocess.Popen([str(SDK/'emulator/emulator.exe'),'-avd','CodexTorProbe','-port','5580','-no-window','-no-snapshot','-no-boot-anim','-no-audio','-memory','1536','-cores','2','-gpu','swiftshader_indirect','-data',str(ROOT/'userdata.img'),'-initdata',str(SDK/'system-images/android-35/google_apis/x86_64/userdata.img'),'-cache',str(ROOT/'cache.img')],env=env,stdout=log,stderr=log,creationflags=0x08000000)
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        if proc.poll() is not None: raise RuntimeError('Emulator exited: '+(ROOT/'emulator.log').read_text(errors='replace')[-2500:])
        r=adb('shell','getprop','sys.boot_completed',check=False,timeout=10)
        if r.stdout.strip()==b'1': break
        time.sleep(2)
    else: raise RuntimeError('Emulator boot deadline exceeded')
    receipt['guestDataBefore']=adb('shell','df','-k','/data').stdout.decode().strip()
    for name in ('tor-probe-debug.apk','tor-probe-debug-androidTest.apk'):
        path=PROJECT/'dist/tor-probe'/name
        if not path.is_file(): raise RuntimeError('Missing probe APK '+name)
        adb('install','-r',str(path),timeout=60)
    result=adb('shell','am','instrument','-w','-r','zec.privateprobe.test/androidx.test.runner.AndroidJUnitRunner',timeout=600,check=False)
    text=result.stdout.decode(errors='replace')
    # Instrumentation prints assertion summaries, never HTTP bodies or addresses.
    safe_lines=[line for line in text.splitlines() if line.startswith('TOR_QUOTE ') or line.startswith('OK (') or line.startswith('FAILURES') or line.startswith('Tests run:')]
    print('\n'.join(safe_lines),flush=True)
    if 'OK (1 test)' not in text:
        # A bounded private file retains only public-fixture test diagnostics for investigation.
        (PROJECT.parent/'tor-probe-diagnostic.txt').write_text(text[-12000:])
    receipt['passed']='OK (1 test)' in text
    receipt['guestDataAfter']=adb('shell','df','-k','/data').stdout.decode().strip()
    (PROJECT/'.private/tor-probe-result.json').write_text(json.dumps(receipt,indent=2)+'\n')
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
