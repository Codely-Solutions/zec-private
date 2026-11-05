"""Inspect actual packaged native code at both build and signing boundaries."""
import zipfile


def check_apk(path, expected=('arm64-v8a',)):
    with zipfile.ZipFile(path) as archive:
        libraries = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        abis = sorted({n.split('/')[1] for n in libraries})
        if abis != sorted(expected):
            raise ValueError('Unexpected native architectures: ' + repr(abis))
        for abi in expected:
            if f'lib/{abi}/libzcashwalletsdk.so' not in libraries:
                raise ValueError('Missing wallet engine for ' + abi)
        return {'abis': abis, 'native_bytes': sum(archive.getinfo(n).compress_size for n in libraries)}
