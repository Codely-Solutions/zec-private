"""Drop unused CPU libraries from emulator-only copies; preserve executable bytes."""
import zipfile,hashlib,subprocess,json,re,os
from pathlib import Path
from emulator_session import sdk_binary

JDK = Path(os.environ.get('ZEC_PRIVATE_JDK') or
           (str(Path.home()/'.codex/cache/jdks/temurin-25.0.5+8') if os.name == 'nt' else os.environ.get('JAVA_HOME', '/usr')))
def prepare(paths, root, sdk):
    key=root/'ephemeral-test.p12'
    subprocess.run([str(sdk_binary(JDK,'bin','keytool')),'-genkeypair','-keystore',str(key),'-storepass','android','-keypass','android','-alias','test','-dname','CN=Isolated Unfunded Emulator Test','-keyalg','RSA','-keysize','2048','-validity','2'],capture_output=True,check=True,timeout=30)
    receipts={}
    for original in paths:
        original_hash=hashlib.sha256(original.read_bytes()).hexdigest()
        stripped=root/(original.stem+'-x86-unsigned.apk');aligned=root/(original.stem+'-x86-aligned.apk')
        executable={}
        with zipfile.ZipFile(original) as source,zipfile.ZipFile(stripped,'w') as target:
            for info in source.infolist():
                name=info.filename
                if name.startswith('lib/') and not name.startswith('lib/x86_64/'): continue
                if re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF|MF)',name,re.I): continue
                data=source.read(info)
                if name.startswith('lib/x86_64/') or name.endswith('.dex') or name in ('AndroidManifest.xml','resources.arsc'):
                    executable[name]=hashlib.sha256(data).hexdigest()
                target.writestr(info,data)
        subprocess.run([str(sdk_binary(sdk,'build-tools/36.0.0','zipalign')),'-P','16','-f','4',str(stripped),str(aligned)],capture_output=True,check=True,timeout=60)
        # This task-owned test key never signs a release and is destroyed with the guest.
        subprocess.run([str(sdk_binary(JDK,'bin','java')),'-jar',str(sdk/'build-tools/36.0.0/lib/apksigner.jar'),'sign','--ks',str(key),'--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--out',str(original),str(aligned)],capture_output=True,check=True,timeout=90)
        subprocess.run([str(sdk_binary(JDK,'bin','java')),'-jar',str(sdk/'build-tools/36.0.0/lib/apksigner.jar'),'verify',str(original)],capture_output=True,check=True,timeout=30)
        with zipfile.ZipFile(original) as signed:
            assert all(hashlib.sha256(signed.read(n)).hexdigest()==h for n,h in executable.items())
            assert not any(n.startswith('lib/') and not n.startswith('lib/x86_64/') for n in signed.namelist())
        stripped.unlink();aligned.unlink()
        receipts[original.name]={'originalSha256':original_hash,'testedSha256':hashlib.sha256(original.read_bytes()).hexdigest(),'preservedExecutionEntries':len(executable),'executionEntriesDigest':hashlib.sha256(json.dumps(executable,sort_keys=True).encode()).hexdigest(),'executionEntriesUnchanged':True,'abi':'x86_64','signing':'ephemeral test key; not release key','testSignerSha256':signing_identity(original,sdk)}
    return receipts


def signing_identity(path, sdk):
    result = subprocess.run([str(sdk_binary(JDK,'bin','java')), '-jar',
        str(sdk/'build-tools/36.0.0/lib/apksigner.jar'), 'verify', '--print-certs', str(path)],
        capture_output=True, check=True, timeout=30)
    output = result.stdout.decode(errors='replace').replace('\r\n', '\n')
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.M)
    if len(digests) != 1 or 'Signer #1 certificate DN: CN=Isolated Unfunded Emulator Test' not in output:
        raise ValueError('Exactly one verified test signer required')
    return digests[0].lower()


def verify_prepared(path, receipt, sdk):
    if (receipt.get('abi') != 'x86_64' or receipt.get('signing') != 'ephemeral test key; not release key'
            or receipt.get('executionEntriesUnchanged') is not True
            or not re.fullmatch('[0-9a-f]{64}', receipt.get('originalSha256', ''))
            or not re.fullmatch('[0-9a-f]{64}', receipt.get('testSignerSha256', ''))):
        raise ValueError('Prepared APK ABI/signing/preparation identity mismatch')
    if hashlib.sha256(path.read_bytes()).hexdigest() != receipt.get('testedSha256'):
        raise ValueError('Prepared APK digest mismatch')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(n.startswith('lib/') and not n.startswith('lib/x86_64/') for n in names):
            raise ValueError('Prepared APK contains incompatible or duplicate entries')
        executable = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in names
            if n.startswith('lib/x86_64/') or n.endswith('.dex') or n in ('AndroidManifest.xml', 'resources.arsc')}
    if (not executable or len(executable) != receipt.get('preservedExecutionEntries') or
            hashlib.sha256(json.dumps(executable, sort_keys=True).encode()).hexdigest() != receipt.get('executionEntriesDigest')):
        raise ValueError('Prepared APK execution-entry receipt mismatch')
    if signing_identity(path, sdk) != receipt['testSignerSha256']:
        raise ValueError('Prepared APK signer mismatch')
