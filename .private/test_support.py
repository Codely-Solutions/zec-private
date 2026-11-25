"""Shared helpers for offline controller tests."""
import hashlib
import os
from contextlib import contextmanager
from unittest.mock import patch


if os.name == 'nt':
    from ctypes import WinDLL, WinError, get_last_error, wintypes

    _set_environment_variable = WinDLL('kernel32', use_last_error=True).SetEnvironmentVariableW
    _set_environment_variable.argtypes = (wintypes.LPCWSTR, wintypes.LPCWSTR)
    _set_environment_variable.restype = wintypes.BOOL


@contextmanager
def patch_environment(values=(), *, clear=False, **kwargs):
    """Restore the Python mapping and native empty Windows entries."""
    empty = [key for key, value in os.environ.items() if value == ''] if os.name == 'nt' else ()
    try:
        with patch.dict(os.environ, values, clear=clear, **kwargs) as environment:
            yield environment
    finally:
        # On Windows the CRT treats an empty assignment as deletion. Restore
        # the native empty entries that Python's mapping restored above.
        for key in empty:
            if not _set_environment_variable(key, ''):
                raise WinError(get_last_error())


def public_baseline_metadata(fixture, apk_sha256):
    from release_metadata import WALLET_APK, checksum_bytes

    tag = fixture['release']
    checksum = checksum_bytes(apk_sha256)
    release = dict(id=30, tag_name=tag, draft=False, prerelease=False,
                   body=f"Android versionCode: {fixture['version_code']}",
                   assets=[dict(name=WALLET_APK, state='uploaded', size=4096, digest='sha256:' + apk_sha256),
                           dict(name='SHA256SUMS', state='uploaded', size=len(checksum),
                                digest='sha256:' + hashlib.sha256(checksum).hexdigest())])
    ref = dict(ref='refs/tags/' + tag, object=dict(type='commit', sha=fixture['source_sha']))
    return {'/releases/latest': release, '/git/matching-refs/tags/' + tag: [ref]}
