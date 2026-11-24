import contextlib, importlib.util, json, os, tempfile, unittest
from copy import deepcopy
from io import StringIO
from unittest.mock import patch
from pathlib import Path
from textwrap import dedent
import shutil
import re
import subprocess
import sys
from urllib.parse import parse_qs, unquote, urlsplit

spec=importlib.util.spec_from_file_location('forkcheck',Path(__file__).with_name('check.py'))
checks=importlib.util.module_from_spec(spec);spec.loader.exec_module(checks)

@contextlib.contextmanager
def fixture_directory():
    # The native guard scans its output while the child runs. Leave task-owned
    # fixture deletion to its final cleanup, avoiding a scan/delete race.
    guarded=os.environ.get('CODEX_GUARD_OUTPUT')
    if guarded:
        yield tempfile.mkdtemp(prefix='controls-',dir=guarded)
    else:
        with tempfile.TemporaryDirectory() as temp:
            yield temp

class ControlsTest(unittest.TestCase):
    def test_environment_patch_restores_empty_entries_for_child_processes(self):
        code = """
import json
import os
import subprocess
import sys
from test_support import patch_environment

keys = ('ZEC_TEST_EMPTY', 'ZEC_TEST_ORIGINAL', 'ZEC_TEST_UNRELATED', 'ZEC_TEST_ADDED')
read_child = 'import json, os, sys; print(json.dumps([os.getenv(key) for key in sys.argv[1:]]))'

def inherited():
    return json.loads(subprocess.check_output([sys.executable, '-B', '-c', read_child, *keys], timeout=10))

original = ['', 'original', 'unrelated', None]
assert inherited() == original
for clear in (False, True):
    try:
        with patch_environment({keys[1]: 'updated'}, clear=clear, ZEC_TEST_ADDED='new') as environment:
            assert environment is os.environ
            assert environment[keys[1]] == 'updated'
            assert environment[keys[3]] == 'new'
            assert environment.get(keys[2]) == (None if clear else 'unrelated')
            if clear:
                raise RuntimeError('test cleanup after failure')
    except RuntimeError as error:
        assert str(error) == 'test cleanup after failure'
    assert [os.getenv(key) for key in keys] == original
    assert inherited() == original
"""
        environment = dict(os.environ, ZEC_TEST_EMPTY='', ZEC_TEST_ORIGINAL='original',
                           ZEC_TEST_UNRELATED='unrelated')
        environment.pop('ZEC_TEST_ADDED', None)
        subprocess.run([sys.executable, '-B', '-c', code], cwd=Path(__file__).resolve().parent,
                       env=environment, timeout=30, check=True)

    def test_active_repository_references_follow_transfer(self):
        legacy='Sammy'+'-Lastre/zec-private'
        root=checks.ROOT
        paths=[root/'README.md',root/'.private/release-notes.json']
        for directory in ('.github','ui-lib/src','app/src'):
            paths.extend(p for p in (root/directory).rglob('*')
                         if p.is_file() and p.suffix in ('.kt','.md','.yml','.yaml'))
        paths.extend((root/'docs'/name) for name in ('obtainium.json','WALLET-GUIDE.md'))
        paths.extend((root/'.private').glob('*.py'))
        for path in paths:
            with self.subTest(path=path.relative_to(root).as_posix()):
                self.assertNotIn(legacy,path.read_text())

    def test_obtainium_import_is_valid_and_selects_only_production_apk(self):
        config=json.loads((checks.ROOT/'docs/obtainium.json').read_text())
        settings=json.loads(config['additionalSettings'])
        self.assertEqual('com.personal.zecprivate.foss',config['id'])
        self.assertEqual('https://github.com/Codely-Solutions/zec-private',config['url'])
        for key in ('includePrereleases','includeDrafts','fallbackToOlderReleases','trackOnly'):
            self.assertIs(settings[key],False)
        pattern=re.compile(settings['apkFilterRegEx'])
        self.assertIsNotNone(pattern.fullmatch('zec-private-latest.apk'))
        for name in ('TESTNET_UPGRADE_FIXTURE.zip','app-x86_64.apk','latest.apk',
                     'zec-private-latestXapk','zec-private-latest.apk.bak'):
            self.assertIsNone(pattern.fullmatch(name))
        for document in ('README.md','docs/WALLET-GUIDE.md'):
            with self.subTest(document=document):
                guide=(checks.ROOT/document).read_text()
                link=re.search(r'\[Add ZEC Private to Obtainium\]\((https://[^)]+)\)',guide).group(1)
                uri=parse_qs(urlsplit(link).query)['r'][0]
                prefix='obtainium://app/'
                self.assertTrue(uri.startswith(prefix))
                self.assertEqual(config,json.loads(unquote(uri[len(prefix):])))

    def test_automation_test_classifies_only_the_exact_main_ref(self):
        workflow = (checks.ROOT / '.github/workflows/automation-test.yml').read_text()
        step = re.search(r'(?ms)^        id: dispatch_scope\n.*?^        run: \|\n(.*?)^      - name:', workflow)
        self.assertIsNotNone(step)
        code = dedent(step[1])
        self.assertEqual(workflow.count("if: steps.dispatch_scope.outputs.main == 'true'"), 4)
        self.assertEqual(workflow.count("if: steps.dispatch_scope.outputs.main == 'false'"), 2)
        with fixture_directory() as temp:
            output = Path(temp) / 'scope.txt'
            for ref, exact_main in (
                ('refs/heads/main', True), ('refs/heads/Main', False), ('refs/heads/review/fixture', False),
            ):
                with self.subTest(ref=ref):
                    output.write_text('')
                    env = dict(os.environ, GITHUB_REF=ref, GITHUB_OUTPUT=str(output))
                    subprocess.run([sys.executable, '-B', '-c', code], env=env, check=True)
                    self.assertEqual(output.read_text(), f'main={str(exact_main).lower()}\n')

    def test_unfunded_near_probe_rejects_non_atomic_output(self):
        from live_near import read_positive_atomic

        self.assertEqual(read_positive_atomic("100"), read_positive_atomic(100))
        for malformed in (0, "0", True, False, 100.9, "100.1", "-7", "9" * 129, None):
            with self.subTest(malformed=malformed), self.assertRaisesRegex(RuntimeError, 'base units|positive'):
                read_positive_atomic(malformed)

    def test_unfunded_probe_checks_the_original_decimal_token(self):
        from live_near import WireDecimal, read_decimal, request

        wire = '0.' + '0' * 510 + '1e512'
        result = subprocess.CompletedProcess([], 0, stdout=('{"amount":' + wire + '}\n200').encode())
        with patch.dict(os.environ, {'PRIVATE_TOR_SOCKS': '127.0.0.1:9050'}):
            with patch('live_near.subprocess.run', return_value=result):
                status, body = request('/v0/status')
        self.assertEqual(status, 200)
        self.assertEqual(body['amount'], WireDecimal(wire))
        with self.assertRaisesRegex(RuntimeError, 'response decimal'):
            read_decimal(body['amount'])
        self.assertEqual(read_decimal(WireDecimal('1.25e-2')), read_decimal('0.0125'))

    def test_unfunded_near_probe_compares_deadlines_at_nanosecond_precision(self):
        from live_near import read_timestamp

        deadline = read_timestamp('2099-01-01T00:00:00.000000001Z')
        self.assertEqual(deadline, read_timestamp('2099-01-01T01:00:00.000000001+01:00'))
        self.assertNotEqual(deadline, read_timestamp('2099-01-01T00:00:00.000000002Z'))
        self.assertEqual(deadline, read_timestamp('2099-01-01T18:00:00.000000001+18:00'))
        with self.assertRaisesRegex(RuntimeError, 'response timestamp offset'):
            read_timestamp('2099-01-01T04:00:00+03:60')

    def test_unfunded_near_probe_binds_every_reviewed_term(self):
        import live_near

        fixture_address = live_near.FIXTURE['unified']
        report_time = '2026-11-24T00:00:00Z'

        def run_probe(root, change=None, issued_deadline='2099-01-01T00:00:00Z', omit_dry_quote=False,
                      dry_funding_field=None):
            issued = None

            def request(path, body=None):
                nonlocal issued
                if path == '/v0/quote':
                    if body['dry']:
                        dry_quote = {'timestamp': report_time, 'quoteRequest': body}
                        if not omit_dry_quote:
                            amount_in = int(body['amount']) if body['swapType'] != 'EXACT_OUTPUT' else 900_000
                            amount_out = int(body['amount']) if body['swapType'] == 'EXACT_OUTPUT' else 900_000
                            dry_quote['quote'] = {
                                'amountIn': str(amount_in), 'amountInFormatted': f'{amount_in / 100_000_000:.8f}',
                                'minAmountIn': str(amount_in), 'amountInUsd': '35',
                                'amountOut': str(amount_out), 'amountOutFormatted': f'{amount_out / 100_000_000:.8f}',
                                'minAmountOut': str(amount_out), 'amountOutUsd': '34.64',
                                'deadline': body['deadline'],
                            }
                        if dry_funding_field is not None:
                            field, value = dry_funding_field
                            dry_quote['quote'][field] = value
                        return 200, dry_quote
                    issued = {
                        'timestamp': report_time,
                        'quoteRequest': body,
                        'quote': {'depositAddress': fixture_address, 'amountIn': '100000000', 'amountInFormatted': '1',
                                  'amountInUsd': '35', 'minAmountIn': '99000000', 'amountOut': '900000',
                                  'amountOutFormatted': '0.009', 'amountOutUsd': '34.64', 'minAmountOut': '892000',
                                  'deadline': issued_deadline},
                    }
                    return 200, issued
                if path == '/v0/status?depositAddress=' + fixture_address:
                    observed = deepcopy(issued)
                    status = {'status': 'PENDING_DEPOSIT', 'updatedAt': report_time, 'quoteResponse': observed}
                    if change:
                        section, field, value = change
                        target = {'status': status, 'envelope': observed}.get(section)
                        if target is None:
                            target = observed[section]
                        target[field] = value
                    else:
                        observed['quote']['amountOutFormatted'] = '0.0090'
                        if issued_deadline == '2099-01-01T00:00:00Z':
                            observed['quote']['deadline'] = '2099-01-01T00:00:00.000Z'
                    return 200, status
                self.assertEqual((path, body), ('/v0/deposit/submit', {}))
                return 400, {}

            with (patch.object(live_near, 'request', side_effect=request) as remote,
                  patch.object(live_near, 'ROOT', root),
                  patch.object(live_near.subprocess, 'check_output', return_value='a' * 40),
                  contextlib.redirect_stdout(StringIO())):
                if dry_funding_field is not None:
                    with self.assertRaisesRegex(RuntimeError, 'dry quote issued a deposit address'):
                        live_near.main()
                    self.assertEqual(remote.call_count, 1)
                    self.assertFalse((root / 'artifact' / 'near-contract.json').exists())
                elif omit_dry_quote:
                    with self.assertRaisesRegex(KeyError, 'quote'):
                        live_near.main()
                    self.assertEqual(remote.call_count, 1)
                elif issued_deadline < '2000':
                    with self.assertRaisesRegex(RuntimeError, 'deadline is not in the future'):
                        live_near.main()
                    self.assertEqual(remote.call_count, 4)
                elif change:
                    with self.assertRaises((RuntimeError, ValueError)):
                        live_near.main()
                    self.assertEqual(remote.call_count, 5)
                else:
                    live_near.main()
                    self.assertEqual(remote.call_count, 6)

        mutations = (
            ('quote', 'amountIn', '100000001'), ('quote', 'amountIn', '100000000.1'),
            ('quote', 'amountOut', '900001'), ('quote', 'amountInFormatted', '0.99'),
            ('quote', 'amountOutFormatted', '0.0089'), ('quote', 'minAmountIn', '99500000'),
            ('quote', 'minAmountOut', '891000'), ('quote', 'deadline', '2099-01-01T00:01:00Z'),
            ('quote', 'deadline', '2099-01-01T00:00:00.000000001Z'),
            ('quote', 'depositAddress', 't1different'), ('quote', 'depositMemo', 'unexpected'),
            ('quoteRequest', 'swapType', 'FLEX_INPUT'), ('quoteRequest', 'originAsset', 'different'),
            ('quoteRequest', 'destinationAsset', 'different'), ('quoteRequest', 'amount', '120000000'),
            ('quoteRequest', 'slippageTolerance', 101), ('quoteRequest', 'recipientType', 'INTENTS'),
            ('quoteRequest', 'appFees', [{'recipient': 'fixture', 'fee': 1}]),
            ('quote', 'amountInUsd', 'undefined'), ('envelope', 'timestamp', None),
            ('status', 'updatedAt', 'invalid'),
            ('envelope', 'timestamp', '2026-11-24 00:00:00Z'),
            ('quoteRequest', 'deadline', '2099-01-01 00:00:00Z'),
            ('quoteRequest', 'deadline', '2099-01-01T00:00:00+19:00'),
            ('status', 'updatedAt', '2026-11-24T00:00:00-18:01'),
            ('status', 'updatedAt', '2026-11-24 00:00:00Z'),
            ('quoteRequest', 'appFees', [{'fee': 0}]),
            ('status', 'swapDetails', {'depositedAmount': '0.5'}),
            ('status', 'swapDetails', {'amountOut': '500000', 'amountOutFormatted': '1'}),
            ('status', 'swapDetails', {'amountIn': '0', 'amountInFormatted': '0'}),
            ('status', 'swapDetails', {'slippage': 2 ** 31}),
            ('status', 'swapDetails', {'depositedAmountUsd': '9' * 128 + '.1'}),
            ('status', 'swapDetails', {'amountOutFormatted': '0.000000001'}),
            ('quote', 'chainDepositAddresses', {}),
            ('quoteRequest', 'quoteWaitingTimeMs', {}),
            ('quoteRequest', 'referral', {}),
        )
        with fixture_directory() as temp:
            root = Path(temp)
            run_probe(root)
            receipt = root / 'artifact' / 'near-contract.json'
            self.assertTrue(json.loads(receipt.read_text())['unused_quote_status_passed'])
        with fixture_directory() as temp:
            root = Path(temp)
            run_probe(root, dry_funding_field=('depositAddress', fixture_address))
            run_probe(root, dry_funding_field=('chainDepositAddresses', [{'address': fixture_address}]))
            run_probe(root, omit_dry_quote=True)
            run_probe(root, issued_deadline='1999-01-01T00:00:00Z')
            self.assertFalse((root / 'artifact' / 'near-contract.json').exists())
            for change in mutations:
                with self.subTest(change=change):
                    run_probe(root, change)
                    self.assertFalse((root / 'artifact' / 'near-contract.json').exists())

    def test_current_source(self):
        self.assertEqual([],checks.check())

    def test_quote_policy_regressions_block(self):
        # Copy only static-check inputs; never touch real release state or keys.
        with fixture_directory() as temp:
            root=Path(temp)
            for relative in ['ui-lib/src/main','app/src','gradle.properties','LICENSE',
                             'settings.gradle.kts','app/build.gradle.kts','ui-lib/build.gradle.kts',
                             '.private/excluded-features.json', '.private/branding-policy.json']:
                source=checks.ROOT/relative
                target=root/relative
                target.parent.mkdir(parents=True,exist_ok=True)
                if source.is_dir(): shutil.copytree(source,target)
                else: shutil.copy2(source,target)
            p=root/checks.COMMON/'usecase/RequestSwapQuoteUseCase.kt'
            original=p.read_text()
            for before, after, expected in (
                ('requestFreshAddress(canNavigateToSwapQuote, owner.account) ?: return@runRequest',
                 'requestFreshAddress(canNavigateToSwapQuote, owner.account)!!',
                 'Fresh shielded recipient/refund derivation changed'),
                ('requestFreshAddress(canNavigateToSwapQuote, owner.account) ?: return@runRequest',
                 'requestFreshAddress(canNavigateToSwapQuote) ?: return@runRequest',
                 'Fresh shielded recipient/refund derivation changed'),
                ('requestNextShieldedAddress(requireFresh = true, account = account)',
                 'requestNextShieldedAddress(requireFresh = false, account = account)',
                 'Swap address requests may fall back to an existing address'),
            ):
                with self.subTest(expected=expected):
                    p.write_text(original.replace(before,after,1))
                    self.assertIn(expected,checks.check(root))
            p.write_text(original)
            p=root/checks.COMMON/'datasource/NearSwapDataSource.kt'
            original=p.read_text()
            p.write_text(original.replace('appFees = emptyList()','appFees = listOf(AppFee("regression", 67))'))
            self.assertTrue(checks.check(root))
            p.write_text(original)
            p=root/checks.COMMON/'provider/NearApiProvider.kt'
            p.write_text(p.read_text()+'\n// Authorization: Bearer regression\n')
            self.assertTrue(checks.check(root))
            p.write_text(p.read_text().replace('// Authorization: Bearer regression',''))
            p=root/'ui-lib/src/main/res/ui/non_translatable/values/private_strings.xml'
            p.write_text(p.read_text().replace('0.25%% included in quote','0.25% included in quote'))
            self.assertTrue(checks.check(root))

    def test_excluded_integrations_cannot_return(self):
        from check_exclusions import check
        with fixture_directory() as temp:
            root=Path(temp)
            for relative in ['app/build.gradle.kts','ui-lib/build.gradle.kts','settings.gradle.kts',
                             '.private/excluded-features.json', '.private/branding-policy.json']:
                target=root/relative
                target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(checks.ROOT/relative,target)
            self.assertEqual([],check(root))
            settings=root/'settings.gradle.kts'
            original=settings.read_text()
            settings.write_text(original+'\ninclude("feature-voting")\n')
            self.assertTrue(check(root))
            settings.write_text(original)
            source=root/'app/src/main/Reintroduced.kt'
            source.parent.mkdir(parents=True)
            source.write_text('import com.flexa.core.Flexa\n')
            self.assertTrue(check(root))

if __name__=='__main__': unittest.main()
