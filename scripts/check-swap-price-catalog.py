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
MARKET = re.compile(
    r'"([^"]+)" to\s*Market\(\s*"([^"]+)",\s*"([^"]+)",\s*(\d+),\s*(null|"[^"]+"),\s*"([^"]+)"'
)


def entries():
    allowed_text = (COMMON / 'provider/SimpleSwapAssetProvider.kt').read_text(encoding='utf-8')
    market_text = (COMMON / 'model/SwapPriceMarkets.kt').read_text(encoding='utf-8')
    allowed = {(token.casefold(), chain) for token, chain in re.findall(r'"([^"]+)" to "([^"]+)"', allowed_text)}
    rows = MARKET.findall(market_text)
    mapped = {asset: (token.casefold(), chain, int(decimals), None if contract == 'null' else contract.strip('"'))
              for asset, token, chain, decimals, contract, _ in rows}
    if len(mapped) != len(rows) or {(token, chain) for token, chain, _, _ in mapped.values()} != allowed:
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
