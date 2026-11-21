"""Shared helpers for offline controller tests."""
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
