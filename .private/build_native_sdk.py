"""Rebuild only the pinned JNI backend, with strict Tor bulk transport.

Runs on a disposable GitHub runner, not inside the Windows resource guard.
No signing custody or wallet data is available to this job.
"""
import argparse
import hashlib
import io
import json
import os
import signal
import struct
import subprocess
import tarfile
import time
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
POLICY=ROOT/'.private/native-sdk.json'
config=json.loads(POLICY.read_text())
parser=argparse.ArgumentParser()
parser.add_argument('--sdk',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--abi',choices=config['targets'],required=True)
args=parser.parse_args()
sdk=args.sdk.resolve();output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=sdk,text=True).strip()==config['sdk_source']
target=config['targets'][args.abi]
url=f"https://static.crates.io/crates/zodl-slipstream/zodl-slipstream-{config['crate_version']}.crate"
archive=urllib.request.urlopen(url,timeout=30).read(3_000_001)
assert len(archive)<=3_000_000 and hashlib.sha256(archive).hexdigest()==config['crate_sha256']
vendor=output/'vendor';vendor.mkdir()
expanded=0
with tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz') as bundle:
    members=bundle.getmembers();assert len(members)<2000
    for member in members:
        if member.isdir():continue
        assert member.isfile() and not member.issym() and not member.islnk()
        relative=Path(*Path(member.name).parts[1:]);destination=(vendor/relative).resolve()
        assert destination.is_relative_to(vendor)
        expanded+=member.size;assert expanded<20_000_000
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_bytes(bundle.extractfile(member).read())
connector=vendor/'src/connector.rs'
original=connector.read_bytes()
before=b'ConnPurpose::BulkFetch => None, // always direct'
after=b'ConnPurpose::BulkFetch => Some(TorMode::Default), // strict Tor when enabled'
assert original.count(before)==1
patched=original.replace(before,after)
# Keep the inherited policy test consistent with the intentional change.
patched=patched.replace(b'assert_eq!(tor_mode(ConnPurpose::BulkFetch), None);',
                        b'assert_eq!(tor_mode(ConnPurpose::BulkFetch), Some(TorMode::Default));')
connector.write_bytes(patched)
backend=sdk/'backend-lib';lock=backend/'Cargo.lock'
old_lock=lock.read_bytes();blocks=old_lock.decode().split('[[package]]');matches=0
for index,block in enumerate(blocks):
    if '\nname = "zodl-slipstream"\n' in block:
        assert '\nversion = "0.4.1"\n' in block and config['crate_sha256'] in block
        blocks[index]='\n'.join(line for line in block.split('\n') if not line.startswith(('source =','checksum =')))
        matches+=1
assert matches==1
lock.write_text('[[package]]'.join(blocks))
lock_hash=hashlib.sha256(lock.read_bytes()).hexdigest()
ndk=Path(os.environ['ANDROID_HOME'])/'ndk'/config['ndk']/'toolchains/llvm/prebuilt/linux-x86_64/bin'
clang=ndk/(target+str(config['android_api'])+'-clang')
assert clang.is_file()
toolchain=Path.home()/'.rustup/toolchains'/(config['rust']+'-x86_64-unknown-linux-gnu')/'bin'
env=dict(os.environ,CARGO_HOME=str(output/'cargo-home'),CARGO_TARGET_DIR=str(output/'target'),
         CARGO_BUILD_JOBS='2',RUSTC=str(toolchain/'rustc'),RUSTDOC=str(toolchain/'rustdoc'),
         RUSTFLAGS='--cfg zcash_voting -C link-arg=-Wl,-z,max-page-size=16384',
         PATH=str(ndk)+os.pathsep+os.environ['PATH'])
env['CARGO_TARGET_'+target.upper().replace('-','_')+'_LINKER']=str(clang)
env['CC_'+target.replace('-','_')]=str(clang)
env['AR_'+target.replace('-','_')]=str(ndk/'llvm-ar')
patch_config='patch.crates-io.zodl-slipstream.path='+json.dumps(str(vendor))
command=[str(toolchain/'cargo'),'--config',patch_config,'build','--release','--locked','--features','slipstream','--target',target]
started=time.monotonic();peak_bytes=0;reason=None
log=output/'cargo.log'
with log.open('wb') as stream:
    process=subprocess.Popen(command,cwd=backend,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
    try:
        while process.poll() is None:
            size=0;entries=0
            for folder,_,files in os.walk(output):
                for name in files:
                    entries+=1
                    if entries>150000:raise RuntimeError('Native output entry budget exceeded')
                    try:size+=(Path(folder)/name).stat().st_size
                    except FileNotFoundError:pass
            peak_bytes=max(peak_bytes,size)
            if size>16*1024**3:raise RuntimeError('Native output exceeded 16 GiB budget')
            if log.stat().st_size>16*1024**2:raise RuntimeError('Native log exceeded 16 MiB budget')
            if time.monotonic()-started>75*60:raise RuntimeError('Native build exceeded 75 minute deadline')
            if os.statvfs(output).f_bavail*os.statvfs(output).f_frsize<8*1024**3:
                raise RuntimeError('Native runner has less than 8 GiB free headroom')
            time.sleep(10)
    except BaseException:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=10)
        raise
    finally:
        print('\n'.join(log.read_text(errors='replace').splitlines()[-45:]))
assert process.returncode==0,'Native Cargo build failed'
assert hashlib.sha256(lock.read_bytes()).hexdigest()==lock_hash,'Cargo altered the controlled lockfile'
library=output/'target'/target/'release/libzcashwalletsdk.so'
assert library.stat().st_size<200*1024**2
subprocess.run([str(ndk/'llvm-strip'),'--strip-unneeded',str(library)],check=True,timeout=60)
elf=library.read_bytes()
assert elf[:6]==b'\x7fELF\x02\x01'
machine=struct.unpack_from('<H',elf,18)[0]
assert machine=={'arm64-v8a':183,'x86_64':62}[args.abi]
phoff=struct.unpack_from('<Q',elf,32)[0];phsize,phnum=struct.unpack_from('<HH',elf,54)
alignments=[]
for index in range(phnum):
    values=struct.unpack_from('<IIQQQQQQ',elf,phoff+index*phsize)
    if values[0]==1:
        assert values[-1]>=16384 and values[2]%16384==values[3]%16384
        alignments.append(values[-1])
assert alignments
deliver=output/'deliver';deliver.mkdir();(deliver/library.name).write_bytes(library.read_bytes())
symbols=subprocess.check_output([str(ndk/'llvm-readelf'),'--dyn-syms','--wide',str(library)],text=True,timeout=30)
exports=sorted({line.split()[-1] for line in symbols.splitlines() if ' Java_' in line and ' UND ' not in line})
assert any('Java_com_zodl_slipstream_SlipstreamNative_start' in item for item in exports)
receipt=dict(schema=1,abi=args.abi,target=target,policy_sha256=hashlib.sha256(POLICY.read_bytes()).hexdigest(),
             controller_source=os.environ['GITHUB_SHA'],workflow_run=os.environ['GITHUB_RUN_ID'],
             builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),sdk_source=config['sdk_source'],
             crate_sha256=config['crate_sha256'],original_connector_sha256=hashlib.sha256(original).hexdigest(),
             patched_connector_sha256=hashlib.sha256(patched).hexdigest(),lock_sha256=lock_hash,
             rust_version=subprocess.check_output([str(toolchain/'rustc'),'--version'],text=True).strip(),
             ndk=config['ndk'],android_api=config['android_api'],features=['slipstream'],test_fixtures=False,
             compiler_version=subprocess.check_output([str(clang),'--version'],text=True).splitlines()[0],
             elf_machine=machine,load_segment_alignments=alignments,
             native_sha256=hashlib.sha256(library.read_bytes()).hexdigest(),native_bytes=library.stat().st_size,
             jni_exports=exports,seconds=time.monotonic()-started,peak_logical_output_bytes=peak_bytes)
(deliver/'native-proof.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({key:receipt[key] for key in ('abi','native_sha256','native_bytes','seconds','peak_logical_output_bytes')}))
