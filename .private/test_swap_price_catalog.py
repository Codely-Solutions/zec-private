"""Offline regressions for the exact asset-identity checks used in hosted preflight."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / 'scripts/check-swap-price-catalog.py'
SPEC = importlib.util.spec_from_file_location('check_swap_price_catalog', CHECKER_PATH)
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)

NATIVE_ID = 'nep141:zec.omft.near'
NATIVE_ROW = 'Market("ZEC", "zec", 8, null, "ZECUSD")'


def catalog_source(*rows, constants=''):
    return f'{constants}\nprivate val markets = mapOf(\n' + ',\n'.join(rows) + '\n)\n'


class SwapPriceCatalogTest(unittest.TestCase):
    def test_real_curated_mappings_match_all_33_frozen_identities(self):
        rows = self.catalog()
        allowed, mapped = checker.entries()
        expected = {row['assetId']: (row['symbol'].casefold(), row['blockchain'], row['decimals'],
                                     row.get('contractAddress')) for row in rows}

        self.assertEqual(len(rows), 33)
        self.assertEqual(len(allowed), 33)
        self.assertEqual(mapped, expected)
        self.assertEqual(mapped[NATIVE_ID], ('zec', 'zec', 8, None))
        self.assertEqual(checker.check(rows), 33)

    def test_plain_named_constant_key_resolves_to_its_literal_value(self):
        source = catalog_source(
            f'NATIVE_ZEC_ID to {NATIVE_ROW}',
            constants=f'private const val NATIVE_ZEC_ID: String = "{NATIVE_ID}"',
        )
        self.assertEqual(checker.parse_markets(source), {NATIVE_ID: ('zec', 'zec', 8, None)})

    def test_undefined_or_nonliteral_key_is_rejected(self):
        for constants in ('', 'private const val NATIVE_ZEC_ID = OTHER_ID',
                          'private const val NATIVE_ZEC_ID = "nep141:${asset}"'):
            with self.subTest(constants=constants):
                source = catalog_source(f'NATIVE_ZEC_ID to {NATIVE_ROW}', constants=constants)
                with self.assertRaisesRegex(ValueError, 'Unresolved Kotlin market key: NATIVE_ZEC_ID'):
                    checker.parse_markets(source)

    def test_literal_and_constant_with_same_identity_are_rejected(self):
        source = catalog_source(
            f'"{NATIVE_ID}" to {NATIVE_ROW}',
            f'NATIVE_ZEC_ID to {NATIVE_ROW}',
            constants=f'private const val NATIVE_ZEC_ID = "{NATIVE_ID}"',
        )
        with self.assertRaisesRegex(ValueError, 'Duplicate market asset ID'):
            checker.parse_markets(source)

    def test_unparsed_map_rows_are_rejected_even_after_a_valid_row(self):
        source = catalog_source(f'"{NATIVE_ID}" to {NATIVE_ROW}', 'computedId() to defaultMarket()')
        with self.assertRaisesRegex(ValueError, 'Unsupported Kotlin market mapping'):
            checker.parse_markets(source)

    def test_live_check_rejects_changed_native_identity(self):
        for field, value in (('assetId', 'unrecognized:zec'), ('decimals', 9), ('contractAddress', 'unexpected')):
            with self.subTest(field=field):
                rows = self.catalog()
                native = next(row for row in rows if row['assetId'] == NATIVE_ID)
                native[field] = value
                message = 'unmapped=.*unrecognized:zec' if field == 'assetId' else 'changed identity=.*zec.omft.near'
                with self.assertRaisesRegex(ValueError, message):
                    checker.check(rows)

    @staticmethod
    def catalog():
        source = ROOT / 'ui-lib/src/test/resources/swap-catalog.json'
        return [dict(row, price='1') for row in json.loads(source.read_text(encoding='utf-8'))]


if __name__ == '__main__':
    unittest.main()
