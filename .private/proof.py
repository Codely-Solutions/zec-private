"""Extract unsigned APK manifest and bind the artifact to the checked revision."""
import hashlib,json,os,re,shutil,subprocess,zipfile
from pathlib import Path
from apk_architecture import check_apk
from acceptance_policy import input_fingerprint, validate_core, validate_quotes, validate_core_sources, CORE_BINDING
root=Path(__file__).resolve().parents[1]
apks=list((root/'app/build/outputs/apk/zcashmainnetFoss/release').glob('*unsigned.apk'))
if len(apks)!=1: raise SystemExit('Expected exactly one unsigned FOSS release APK')
apk=apks[0]
architecture=check_apk(apk)
sdk=Path(os.environ['ANDROID_HOME'])
badging=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','badging',str(apk)],text=True)
package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
if not package or package[1]!='com.personal.zecprivate.foss' or 'application-debuggable' in badging:
    raise SystemExit('Invalid release manifest')
manifest=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','xmltree',str(apk),'AndroidManifest.xml'],text=True)
if 'com.personal.zecprivate.foss.provider' not in manifest or '"co.electriccoin.zcash.foss.provider"' in manifest:
    raise SystemExit('Provider authority conflicts with official Zodl FOSS')
with zipfile.ZipFile(apk) as archive:
    info=archive.getinfo('assets/UPSTREAM-LICENSE.txt')
    if info.file_size>8192 or archive.read(info)!=(root/'LICENSE').read_bytes():
        raise SystemExit('Missing or altered upstream license notice')
upstream=json.loads((root/'.private/candidate.json' if (root/'.private/candidate.json').exists() else root/'.private/upstream.json').read_text())
version=upstream['tag'].rsplit('-',1)[0]
revision=int(json.loads((root/'.private/release-revision.json').read_text())['private_revision'])
if revision<1 or revision>999: raise SystemExit('Invalid private revision')
upstream['versionCode']=int(package[2])
sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
contract_path=root/'artifact/near-contract.json'
contract=json.loads(contract_path.read_text())
if not contract.get('success') or contract.get('source_sha')!=sha:
    raise SystemExit('Missing live API contract evidence for this source revision')
if contract.get('transport')!='Tor SOCKS5 with remote DNS; no direct fallback':
    raise SystemExit('Live API checks did not establish the required Tor transport')
runtime_path=root/'artifact/runtime.json'
if json.loads(runtime_path.read_text()).get('source_sha')!=sha:
    raise SystemExit('Release dependency inventory is not bound to this source')
subprocess.run([os.sys.executable,str(root/'.private/check_exclusions.py'),str(runtime_path)],check=True)
proof={'source_sha':sha,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'package':package[1],'versionCode':int(package[2]),'versionName':package[3],'debuggable':False,'upstream':upstream,'release_tag':f'v{version}-private.{revision}','validation':'unit-lint-detekt-live-ui-fork-v3','live_contract_sha256':hashlib.sha256(contract_path.read_bytes()).hexdigest()}
out=root/'artifact';out.mkdir(exist_ok=True)
proof['architecture']=architecture
proof['workflow_run_id']=os.environ.get('GITHUB_RUN_ID')
core_inputs=input_fingerprint(sha)
core_path=root/'.private/core-acceptance.json'
proof['core_acceptance']={'input_fingerprint':input_fingerprint(sha,profile='core-runtime'),'binding':CORE_BINDING,'status':'pending'}
if core_path.exists():
    core=json.loads(core_path.read_text())
    if core.get('input_fingerprint')==core_inputs:
        validate_core_sources(core,sha)
        shutil.copy2(core_path,out/'core-acceptance.json')
        proof['core_acceptance'].update(status='passed',receipt_sha256=hashlib.sha256(core_path.read_bytes()).hexdigest())
quote_inputs=input_fingerprint(sha,profile='production-quotes')
quote_path=root/'.private/quote-acceptance.json'
proof['quote_acceptance']={'input_fingerprint':quote_inputs,'status':'pending'}
if quote_path.exists():
    quote=json.loads(quote_path.read_text())
    if quote.get('input_fingerprint')==quote_inputs:
        validate_quotes(quote,quote_inputs)
        shutil.copy2(quote_path,out/'quote-acceptance.json')
        proof['quote_acceptance'].update(status='passed',receipt_sha256=hashlib.sha256(quote_path.read_bytes()).hexdigest())
if os.environ.get('BUILD_EMULATOR')=='true':
    debug=list((root/'app/build/outputs/apk/zcashmainnetFoss/debug').glob('*.apk'))
    if len(debug)!=1: raise SystemExit('Expected one emulator candidate APK')
    proof['emulator']={'apk_sha256':hashlib.sha256(debug[0].read_bytes()).hexdigest(),
                       'architecture':check_apk(debug[0],('x86_64',))}
proof['runtime_graph_sha256']=hashlib.sha256(runtime_path.read_bytes()).hexdigest()
from foundation_architecture import check_graph
foundation_path=root/'artifact/foundation-runtime.json'
foundation=json.loads(foundation_path.read_text())
check_graph(foundation)
if foundation['source_sha']!=sha:raise SystemExit('Foundation graph source mismatch')
proof['foundation_graph_sha256']=hashlib.sha256(foundation_path.read_bytes()).hexdigest()
shutil.copy2(apk,out/'zec-private-unsigned.apk')
(out/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
