"""Extract unsigned APK manifest and bind the artifact to the checked revision."""
import hashlib,json,os,re,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
apks=list((root/'app/build/outputs/apk/zcashmainnetFoss/release').glob('*unsigned.apk'))
if len(apks)!=1: raise SystemExit('Expected exactly one unsigned FOSS release APK')
apk=apks[0]
sdk=Path(os.environ['ANDROID_HOME'])
badging=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','badging',str(apk)],text=True)
package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
if not package or package[1]!='com.personal.zecprivate.foss' or 'application-debuggable' in badging:
    raise SystemExit('Invalid release manifest')
upstream=json.loads((root/'.private/candidate.json' if (root/'.private/candidate.json').exists() else root/'.private/upstream.json').read_text())
version=upstream['tag'].rsplit('-',1)[0]
sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
proof={'source_sha':sha,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'package':package[1],'versionCode':int(package[2]),'versionName':package[3],'debuggable':False,'upstream':upstream,'release_tag':f'v{version}-private.1','validation':'unit-lint-detekt-fork-v1'}
out=root/'artifact';out.mkdir(exist_ok=True)
shutil.copy2(apk,out/'zec-private-unsigned.apk')
(out/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
