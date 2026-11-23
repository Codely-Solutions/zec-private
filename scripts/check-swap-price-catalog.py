"""Check the app's exact independent market mappings against the public swap catalog."""
import argparse
import json
from decimal import Decimal
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / 'ui-lib/src/main/java/co/electriccoin/zcash/ui/common'
CATALOG = 'https://1click.chaindefuser.com/v0/tokens'
PLAIN_STRING = r'"[^"\\$\r\n]+"'
IDENTIFIER = r'[A-Za-z_][A-Za-z0-9_]*'
STRING_CONSTANT = re.compile(
    rf'^[ \t]*(?:(?:private|internal|public)[ \t]+)?const[ \t]+val[ \t]+'
    rf'({IDENTIFIER})(?:[ \t]*:[ \t]*String)?[ \t]*=[ \t]*({PLAIN_STRING})[ \t]*$',
    re.MULTILINE,
)
MARKET_MAP = re.compile(r'^[ \t]*private val markets = mapOf\([ \t]*\n(.*?)^[ \t]*\)[ \t]*$',
                        re.MULTILINE | re.DOTALL)
MARKET = re.compile(
    rf'\s*({PLAIN_STRING}|{IDENTIFIER})\s+to\s+Market\(\s*'
    rf'({PLAIN_STRING})\s*,\s*({PLAIN_STRING})\s*,\s*(\d+)\s*,\s*(null|{PLAIN_STRING})\s*,\s*{PLAIN_STRING}'
    r'\s*,?\s*\)\s*(?:,|(?=\Z))'
)


def parse_markets(text):
    constants = {}
    for name, value in STRING_CONSTANT.findall(text):
        if name in constants:
            raise ValueError(f'Duplicate Kotlin string constant: {name}')
        constants[name] = value[1:-1]

    match = MARKET_MAP.search(text)
    if match is None:
        raise ValueError('Could not find the Kotlin market map')
    body = match.group(1).strip()
    mapped = {}
    offset = 0
    while offset < len(body):
        row = MARKET.match(body, offset)
        if row is None:
            line = body[offset:].lstrip().splitlines()[0]
            raise ValueError(f'Unsupported Kotlin market mapping: {line}')
        key, token, chain, decimals, contract = row.groups()
        asset = key[1:-1] if key.startswith('"') else constants.get(key)
        if asset is None:
            raise ValueError(f'Unresolved Kotlin market key: {key}')
        if asset in mapped:
            raise ValueError(f'Duplicate market asset ID: {asset}')
        mapped[asset] = (token[1:-1].casefold(), chain[1:-1], int(decimals),
                         None if contract == 'null' else contract[1:-1])
        offset = row.end()
    return mapped


def entries():
    allowed_text = (COMMON / 'provider/SimpleSwapAssetProvider.kt').read_text(encoding='utf-8')
    market_text = (COMMON / 'model/SwapPriceMarkets.kt').read_text(encoding='utf-8')
    allowed = {(token.casefold(), chain) for token, chain in re.findall(r'"([^"]+)" to "([^"]+)"', allowed_text)}
    mapped = parse_markets(market_text)
    if len(mapped) != len(allowed) or {(token, chain) for token, chain, _, _ in mapped.values()} != allowed:
        raise ValueError('Market mappings do not cover the curated asset/network set')
    return allowed, mapped


def live_catalog(allowed, response):
    live = {}
    seen = set()
    for row in response:
        symbol, chain, decimals = (row[key] for key in ('symbol', 'blockchain', 'decimals'))
        key = symbol, chain, decimals
        if key in seen:
            continue
        seen.add(key)
        if (symbol.casefold(), chain) in allowed and Decimal(str(row.get('price') or '0')) > 0:
            live[row['assetId']] = (symbol.casefold(), chain, decimals, row.get('contractAddress'))
    return live


def check(response):
    allowed, mapped = entries()
    live = live_catalog(allowed, response)
    missing = live.keys() - mapped.keys()
    extra = mapped.keys() - live.keys()
    changed = {key for key in live.keys() & mapped.keys() if live[key] != mapped[key]}
    if missing or extra or changed:
        raise ValueError(f'Catalog drift: unmapped={sorted(missing)}, not listed={sorted(extra)}, '
                         f'changed identity={sorted(changed)}')
    return len(live)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true', help='Check curated mappings without contacting the catalog')
    args = parser.parse_args()
    if args.offline:
        _, mapped = entries()
        print(f'Curated asset/network mappings verified offline: {len(mapped)}')
        return
    response = subprocess.run(['curl', '-fsS', '--max-time', '15', '--max-filesize', '1048576', CATALOG],
                              capture_output=True, check=True, timeout=18)
    print(f'Live catalog matches all {check(json.loads(response.stdout))} exact market mappings.')


if __name__ == '__main__':
    try:
        main()
    except (subprocess.SubprocessError, ValueError) as error:
        raise SystemExit(str(error)) from None
