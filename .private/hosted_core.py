"""Run the maintained funded lifecycle against a verified hosted APK pair."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

from acceptance_policy import input_fingerprint, validate_core_sources
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
                    int(os.environ['GITHUB_RUN_ID']), os.environ['ZEC_CORE_REHEARSAL'] == 'true')
    verify(value, os.environ['ZEC_CORE_RESERVATION'], Metadata())
    return value


def egress_diagnostics(report):
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
    version = int(os.environ['ZEC_CORE_VERSION'])
    require(version > selected['version_code'], 'Hosted testnet update must advance version')
    files = candidate_pair(candidate, source)
    inputs = files + [fixture / 'baseline-ui.apk', fixture / 'baseline-androidTest.apk']
    apks = [Path(shutil.copyfile(path, root / path.name)) for path in inputs]
    base, base_test, app, test = select_apk_roles(apks)
    require(sha(base) == selected['candidate_apk_sha256']
            and sha(base_test) == selected['instrumentation_apk_sha256'], 'Baseline APK bytes differ')
    manifest = dict(
        build_source_sha=source, baseline_source_sha=selected['source_sha'],
        baseline_version_code=selected['version_code'], candidate_version_code=version,
        baseline_apk_sha256=sha(base), candidate_apk_sha256=sha(app),
        baseline_instrumentation_apk_sha256=sha(base_test), instrumentation_apk_sha256=sha(test),
        upgradeBaselineInstrumentation=dict(source_sha=selected['source_sha'], apk_sha256=sha(base_test)),
        input_fingerprint=input_fingerprint(source),
        runtime_input_fingerprint=input_fingerprint(source, profile='core-runtime'),
        build_input_fingerprint=input_fingerprint(source, profile='core-build'))
    prepared = prepare(apks, root, SDK)
    try:
        lifecycle(session.adb, root, apks, manifest, transport_only=bool(upgrade_probe), upgrade_probe=upgrade_probe)
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
            sync = stage_error.get('sync', [])
            if sync:
                keys = ('phase', 'elapsed_ms', 'probe_only', 'exception_class', 'status', 'error_type', 'cause_class')
                data = [{key: item[key] for key in keys if key in item} for item in sync]
                print('Hosted synchronization markers ' + json.dumps(data), flush=True)
        raise
    report = json.loads((root / 'core-testnet-result.json').read_text())
    if upgrade_probe:
        require(report['passed'] and report['faucet_claims'] == 0, 'Unfunded upgrade probe failed')
        print(f'Hosted unfunded {upgrade_probe} after installed upgrade passed with the Tor firewall enabled.', flush=True)
        return
    report.update(apk_preparation=prepared, hosted_fixture=selected,
                  hosted_reservation=os.environ['ZEC_CORE_RESERVATION'],
                  rehearsal=os.environ['ZEC_CORE_REHEARSAL'] == 'true',
                  workflow_run_id=int(os.environ['GITHUB_RUN_ID']))
    if not report['rehearsal']:
        report['producer'] = json.loads((candidate / 'producer.json').read_text())
        report['production_artifact'] = json.loads((candidate / 'production.json').read_text())
    validate_core_sources(report, source, baseline=selected)
    require(report['funding_setup']['claim_requests'] == 1, 'Exactly one hosted faucet claim required')
    result.write_text(json.dumps(report, indent=2) + '\n')
    print('Hosted testnet upgrade, Tor recovery, send, restart and restore passed with one faucet claim.')
