"""Dependency direction and selected-product assembly checks; no wallet data."""
from pathlib import Path
import re
import json
import sys
import hashlib

ROOT = Path(__file__).resolve().parents[1]


def check_graph(evidence):
    if evidence.get('configuration') != 'runtimeClasspath' or not evidence.get('components'):
        raise ValueError('Missing resolved foundation runtime graph')
    for component in evidence['components']:
        if component != 'project :wallet-foundation-lib' and not component.startswith(
                ('org.jetbrains.kotlin:kotlin-stdlib:', 'org.jetbrains:annotations:')):
            raise ValueError('Concrete or unexpected foundation runtime dependency: ' + component)


def validate_graph_binding(raw, proof, expected):
    evidence=json.loads(raw)
    if (hashlib.sha256(raw).hexdigest()!=proof.get('foundation_graph_sha256') or
            evidence.get('source_sha')!=expected):
        raise ValueError('Foundation graph source/digest mismatch')
    check_graph(evidence)


def check(root=ROOT):
    errors = []
    shared = root / 'wallet-foundation-lib'
    build = (shared / 'build.gradle.kts').read_text()
    if re.search(r'projects\.|com\.android|zcash\.sdk|ktor|keystone|koin', build):
        errors.append('Foundation depends on a concrete engine, Android, provider or product')
    for path in (shared / 'src/main').rglob('*.kt'):
        for name in re.findall(r'^import\s+([^\s]+)', path.read_text(), re.M):
            if not name.startswith(('java.math.', 'java.time.', 'kotlin.',
                                    'co.electriccoin.zcash.wallet.foundation.')):
                errors.append('Concrete foundation import: ' + name)
    common = root / 'ui-lib/src/main/java/co/electriccoin/zcash/ui/common'
    quote = (common / 'model/SwapQuote.kt').read_text()
    if re.search(r'cash\.z\.ecc|Proposal|Zatoshi|\.near\.|io\.ktor', quote):
        errors.append('Provider quote leaks an engine or HTTP implementation')
    selection = root / 'app/src/main/java/co/electriccoin/zcash/app/ZecPrivateProductModule.kt'
    if 'singleOf(::NearSwapDataSource) bind SwapDataSource::class' not in selection.read_text():
        errors.append('Shipping provider selection is not explicit')
    ui_di = root / 'ui-lib/src/main/java/co/electriccoin/zcash/di'
    if any('::NearSwapDataSource' in p.read_text() for p in ui_di.glob('*.kt')):
        errors.append('Shared UI selects its own concrete provider')
    if 'requireNearRecovery()' not in (common / 'repository/SwapRepository.kt').read_text():
        errors.append('Persisted provider identity is ignored for recovery')
    if 'ZecSwapComposition.validate(result.quote)' not in (common / 'usecase/RequestSwapQuoteUseCase.kt').read_text():
        errors.append('Product flow bypasses capability/deposit validation')
    return errors


if __name__ == '__main__':
    failures = check()
    if failures:
        raise SystemExit('\n'.join(failures))
    if len(sys.argv) == 2:
        check_graph(json.loads(Path(sys.argv[1]).read_text()))
    print('Foundation dependency direction and selected-product assembly passed')
