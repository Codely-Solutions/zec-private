"""Refresh the staged source's canonical upstream delta and working source inventory."""
import json,subprocess
from pathlib import Path
import sync

ROOT=Path(__file__).resolve().parents[1]

def generate_patch(root,upstream,paths):
    # Diff canonical index blobs. Working-tree diff conversion depends on the
    # host's autocrlf setting and can produce a patch that fails on another OS.
    unstaged=subprocess.run(['git','diff','--quiet','--no-ext-diff','--',*paths],
        cwd=root,timeout=30)
    if unstaged.returncode==1:
        raise RuntimeError('Stage reviewed source changes before refreshing the canonical fork patch')
    unstaged.check_returncode()
    return subprocess.check_output(['git','diff','--cached','--no-ext-diff','--no-textconv',
        '--binary','--unified=0',upstream,'--',*paths],cwd=root,timeout=30)


def main():
    upstream=json.loads((ROOT/'.private/upstream.json').read_text())['sha']
    paths=['.',':(exclude).private',':(exclude).github',':(exclude)README.md',
           ':(exclude)ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/NearApiProvider.kt',
           ':(exclude)docs/UPSTREAM-README.md',':(exclude)docs/UPSTREAM-dependabot.yml']
    paths += [':(exclude)'+project for project in sync.excluded_projects()]
    patch=generate_patch(ROOT,upstream,paths)
    (ROOT/'.private/fork.patch').write_bytes(patch)
    (ROOT/'.private/source-state.json').write_text(json.dumps(sync.source_state(ROOT),indent=2)+'\n')
    print('Refreshed reviewed fork delta and normalized source inventory. Build and acceptance remain required.')

if __name__=='__main__':main()
