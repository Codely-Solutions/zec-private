import subprocess,sys
from pathlib import Path
r=Path(__file__).resolve().parents[1]
for name in ('test_packaging.py','local_controls.py','test_sync.py'):
    subprocess.run([sys.executable,'-B',str(r/'.private'/name)],cwd=r,check=True,timeout=150)
