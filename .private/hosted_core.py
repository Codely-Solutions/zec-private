"""Run the maintained funded lifecycle against a verified hosted APK pair."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

from acceptance_policy import direct_core_build_fingerprint, input_fingerprint, needs_live_tor_probe, validate_core_sources
from core_testnet_driver import run as lifecycle, select_apk_roles
from emulator_session import SDK
from hosted_claim import binding, verify
from release_metadata import Metadata, require
from strip_emulator_apk import prepare


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def candidate_pair(directory, source):
    require((directory / 'source-sha.txt').read_text().strip() == source, 'Testnet producer source mismatch')
    files = sorted(directory.glob('*.apk'))
    require(len(files) == 2 and sum('androidTest' in p.name for p in files) == 1,
            'Expected one testnet app and its instrumentation')
    rows = (directory / 'SHA256SUMS').read_text().splitlines()
    expected = [re.fullmatch(r'([a-f0-9]{64})  (?:\./|core-artifact/)([A-Za-z0-9_.-]+\.apk)', row)
                for row in rows]
    require(all(expected) and sorted(match.groups() for match in expected) ==
            sorted((sha(path), path.name) for path in files),
            'Testnet APK checksums differ from producer')
    return files


def verify_reservation():
    require(os.environ['GITHUB_RUN_ATTEMPT'] == '1', 'Hosted funded reruns are forbidden')
    value = binding(os.environ['ZEC_CORE_SOURCE'], os.environ['GITHUB_SHA'], int(os.environ['ZEC_CORE_VERSION']),
                    int(os.environ['GITHUB_RUN_ID']), os.environ['ZEC_CORE_REHEARSAL'] == 'true',
                    os.environ.get('ZEC_CORE_PREVIOUS_RESERVATION'))
    verify(value, os.environ['ZEC_CORE_RESERVATION'], Metadata())
    return value


def network_startup_diagnostics(diagnostic):
    lines = diagnostic.get('pre_wallet_instrumentation', '') + '\n' + diagnostic.get('pre_wallet_crash', '')
    classes = sorted(set(re.findall(r'\b(?:[a-z][\w]*\.){2,}[A-Z]\w*(?:Exception|Error)\b', lines)))
    frames = list(dict.fromkeys(re.findall(
        r'^\s*at ((?:android\.|cash\.z\.ecc\.android\.|co\.electriccoin\.|io\.ktor\.)'
        r'[\w.$<>]+\((?:[\w.]+:\d+|Native Method|Unknown Source)\))', lines, re.MULTILINE)))
    native_fields = ('probe_stage', 'exception_class', 'height_response_class',
                     'submit_response_class', 'sdk_code', 'native_rejection_code')
    known_causes = {
        'certificate': r'UnknownIssuer|CertExpired|certificate|invalid peer',
        'tls': r'handshake|rustls|tls error|tls connect',
        'stream': r'stream failed|stream failure|stream closed|stream error',
        'timeout': r'timed? ?out|timeout|deadline (?:has )?(?:elapsed|exceeded)',
        'refused': r'connection refused|connection reset|no route to host',
        'tor_circuit': r'circuit|guard selection|bootstrap',
        'dns': r'dns|name resolution|lookup address',
    }
    return dict(exception_classes=classes[:12], frames=frames[:12],
                resolved_targets=bool(re.search(r'^CORE_TARGETS ', lines, re.MULTILINE)),
                causes=[name for name, pattern in known_causes.items() if re.search(pattern, lines, re.IGNORECASE)],
                native=[{key: entry[key] for key in native_fields if key in entry}
                        for entry in diagnostic.get('native_errors', [])][-3:])


def egress_diagnostics(report):
    if report.get('last_stage') == 'networkTargets':
        path = Path(os.environ['CODEX_GUARD_OUTPUT']) / 'core-testnet-diagnostic.json'
        if path.is_file():
            debug = json.loads(path.read_text())
            print('Hosted network startup evidence ' + json.dumps(network_startup_diagnostics(debug)), flush=True)
    if not report.get('egress_denial_stage'):
        return
    data = {key: report.get(key) for key in ('egress_denial_stage', 'unclassified_connections',
            'connection_trace_error')}
    for family in ('iptables', 'ip6tables'):
        data[family] = [line for line in report.get(family+'_denials', []) if int(line.split()[0])][:12]
    print('Hosted egress evidence ' + json.dumps(data), flush=True)


def run(session, root, fixture, candidate, result):
    require(os.environ['GITHUB_RUN_ATTEMPT'] == '1', 'Hosted funded reruns are forbidden')
    require(not os.environ.get('GH_TOKEN') and not os.environ.get('GITHUB_TOKEN'),
            'Wallet runner must not receive a repository token')
    upgrade_probe = os.environ.get('ZEC_CORE_UPGRADE_PROBE')
    require(not upgrade_probe or (upgrade_probe in ('restore', 'transport') and os.environ['ZEC_CORE_REHEARSAL'] == 'true'
            and not os.environ['ZEC_CORE_RESERVATION'] and os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch'),
            'An unfunded upgrade probe must use the dedicated rehearsal workflow without a reservation')
    selected = json.loads(Path('.private/hosted-fixture.json').read_text())
    require(json.loads((fixture / 'fixture-binding.json').read_text()) == selected,
            'Downloaded baseline differs from protected selection')
    source = os.environ['ZEC_CORE_SOURCE']
    use_tor = os.environ.get('ZEC_CORE_USE_TOR', 'true') == 'true'
    if os.environ['ZEC_CORE_REHEARSAL'] != 'true':
        require(not use_tor, 'Release wallet acceptance must use direct app networking')
    version = int(os.environ['ZEC_CORE_VERSION'])
    require(version > selected['version_code'], 'Hosted testnet update must advance version')
    files = candidate_pair(candidate, source)
    prepared = None
    baseline_test = fixture / 'baseline-androidTest.apk'
    require(sha(baseline_test) == selected['instrumentation_apk_sha256'], 'Baseline instrumentation bytes differ')
    if not use_tor:
        prepared = json.loads((candidate / 'direct-preparation.json').read_bytes())
        compiled_base = candidate / 'baseline-instrumentation.bin'
        app, test = sorted(files, key=lambda path: 'androidTest' in path.name)
        require(prepared['source'] == source and prepared['harness_controller'] == os.environ['GITHUB_SHA']
                and prepared['baseline_source'] == selected['source_sha']
                and prepared['baseline_apk_sha256'] == selected['candidate_apk_sha256']
                and prepared['candidate_apk_sha256'] == sha(app)
                and prepared['candidate_instrumentation_sha256'] == sha(test)
                and prepared['baseline_instrumentation_sha256'] == sha(compiled_base),
                'Prepared baseline or candidate differs from exact instrumentation binding')
        baseline_test = Path(shutil.copyfile(compiled_base, root / 'baseline-androidTest.apk'))
    inputs = files + [fixture / 'baseline-ui.apk']
    apks = [Path(shutil.copyfile(path, root / path.name)) for path in inputs]
    apks.append(Path(shutil.copyfile(baseline_test, root / baseline_test.name)) if use_tor else baseline_test)
    base, base_test, app, test = select_apk_roles(apks)
    require(sha(base) == selected['candidate_apk_sha256'], 'Baseline app APK bytes differ')
    manifest = dict(
        build_source_sha=source, baseline_source_sha=selected['source_sha'],
        baseline_version_code=selected['version_code'], candidate_version_code=version,
        baseline_apk_sha256=sha(base), candidate_apk_sha256=sha(app),
        baseline_instrumentation_apk_sha256=sha(base_test), instrumentation_apk_sha256=sha(test),
        upgradeBaselineInstrumentation=dict(source_sha=selected['source_sha'], apk_sha256=sha(base_test)),
        input_fingerprint=input_fingerprint(source),
        runtime_input_fingerprint=input_fingerprint(source, profile='core-runtime'),
        build_input_fingerprint=(input_fingerprint(source, profile='core-build') if use_tor else
                                 direct_core_build_fingerprint(source, prepared['harness_controller'])))
    apk_preparation = prepare(apks, root, SDK)
    try:
        lifecycle(session.adb, root, apks, manifest, transport_only=bool(upgrade_probe), upgrade_probe=upgrade_probe,
                  preclaim_check=not upgrade_probe, use_tor=use_tor,
                  live_tor_probe=not use_tor and needs_live_tor_probe(source))
    except Exception:
        report = root / 'core-testnet-result.json'
        if report.exists():
            value = json.loads(report.read_text())
            ledger = root / 'funding-claim-ledger.json'
            state = json.loads(ledger.read_text())['status'] if ledger.exists() else 'no-POST'
            print(f'Hosted lifecycle failed at {value["last_stage"]}, claim state {state}', flush=True)
            egress_diagnostics(value)
            if 'transport_failure' in value:
                print('Hosted transport failure ' + json.dumps(value['transport_failure']), flush=True)
        diagnostic = root / 'core-testnet-diagnostic.json'
        if diagnostic.is_file():
            stage_error = json.loads(diagnostic.read_text())
            markers = sorted(set(re.findall(r'CoreWalletAcceptance\.kt:\d+|^(?:java\.lang|kotlinx\.coroutines)\.\w+',
                                             '\n'.join(stage_error.get('lines', [])), re.MULTILINE)))
            print('Hosted instrumentation markers ' + json.dumps(markers[:16]), flush=True)
            print('Hosted result channel ' + json.dumps(stage_error.get('result_channel')), flush=True)
            sync = stage_error.get('sync', [])
            if sync:
                keys = ('phase', 'elapsed_ms', 'probe_only', 'exception_class', 'status', 'error_type', 'cause_class',
                        'http_status')
                data = [{key: item[key] for key in keys if key in item} for item in sync]
                print('Hosted synchronization markers ' + json.dumps(data), flush=True)
            submission = stage_error.get('submission', [])
            if submission:
                item = submission[-1]
                keys = ('type', 'code', 'reason', 'cause_class', 'manual_resubmissions')
                data = {key: item[key] for key in keys if key in item}
                data['tx_id_count'] = len(item.get('tx_ids', []))
                rpc = item.get('rpc_failure', {})
                safe_rpc = ('native_rejection_code', 'height_preflight_failed', 'sdk_submit_failure_logged',
                            'raw_submit_results_logged', 'capture_failure_class')
                data['rpc'] = {key: rpc[key] for key in safe_rpc if key in rpc}
                print('Hosted submission evidence ' + json.dumps(data), flush=True)
        readiness = root / 'faucet-readiness.json'
        if readiness.is_file():
            status = json.loads(readiness.read_text())
            if status.get('passed') is False:
                keys = ('stage', 'exception_class', 'http_status', 'server_code', 'bootstrap_percent', 'claim_requests')
                print('Hosted faucet readiness ' + json.dumps({key: status[key] for key in keys if key in status}),
                      flush=True)
        raise
    report = json.loads((root / 'core-testnet-result.json').read_text())
    if upgrade_probe:
        require(report['passed'] and report['faucet_claims'] == 0, 'Unfunded upgrade probe failed')
        print(f'Hosted unfunded {upgrade_probe} after installed upgrade passed with the Tor firewall enabled.', flush=True)
        return
    report.update(apk_preparation=apk_preparation, hosted_fixture=selected,
                  hosted_reservation=os.environ['ZEC_CORE_RESERVATION'],
                  rehearsal=os.environ['ZEC_CORE_REHEARSAL'] == 'true',
                  workflow_run_id=int(os.environ['GITHUB_RUN_ID']))
    if not report['rehearsal']:
        report['producer'] = json.loads((candidate / 'producer.json').read_text())
        report['production_artifact'] = json.loads((candidate / 'production.json').read_text())
        report['artifact_run_id'] = int((os.environ.get('ZEC_CORE_ARTIFACT_RUN') if use_tor else None)
                                        or os.environ['GITHUB_RUN_ID'])
    if prepared is not None:
        report['direct_preparation'] = prepared
    validate_core_sources(report, source, baseline=selected, use_tor=use_tor, prepared=prepared)
    require(report['funding_setup']['claim_requests'] == 1, 'Exactly one hosted faucet claim required')
    result.write_text(json.dumps(report, indent=2) + '\n')
    route = 'Tor (including interruption and recovery)' if use_tor else 'direct app networking'
    print(f'Hosted testnet upgrade, send, restart and restore passed with {route} and one faucet claim.')
