"""Refresh the reviewed working tree's exact upstream delta and source inventory."""
import json,subprocess
from pathlib import Path
import sync

ROOT=Path(__file__).resolve().parents[1]

def main():
    upstream=json.loads((ROOT/'.private/upstream.json').read_text())['sha']
    paths=['.',':(exclude).private',':(exclude).github',':(exclude)README.md',
           ':(exclude)ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/NearApiProvider.kt',
           ':(exclude)docs/UPSTREAM-README.md',':(exclude)docs/UPSTREAM-dependabot.yml']
    paths += [':(exclude)'+project for project in sync.excluded_projects()]
    patch=subprocess.check_output(['git','diff','--binary','--unified=0',upstream,'--',*paths],cwd=ROOT,timeout=30)
    (ROOT/'.private/fork.patch').write_bytes(patch)
    (ROOT/'.private/source-state.json').write_text(json.dumps(sync.source_state(ROOT),indent=2)+'\n')
    print('Refreshed reviewed fork delta and normalized source inventory. Build and acceptance remain required.')

if __name__=='__main__':main()
