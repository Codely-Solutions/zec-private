"""Unfunded API contract checks using public SDK addresses, never a real wallet."""
import datetime,json,os,re,subprocess,time,urllib.parse
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE='https://1click.chaindefuser.com'
FIXTURE=json.loads((ROOT/'.private/public-api-fixture.json').read_text())

@dataclass(frozen=True)
class WireDecimal:
    text: str


def request(path,body=None):
    proxy = os.environ.get('PRIVATE_TOR_SOCKS', '')
    if not re.fullmatch(r'127\.0\.0\.1:[0-9]{2,5}', proxy):
        raise RuntimeError('A loopback Tor SOCKS endpoint is required; direct requests are forbidden')
    data=None if body is None else json.dumps(body).encode()
    for attempt in range(3):
        # curl is a documented, supported API client. The edge service rejects
        # urllib's client signature (1010) on hosted runners; no identity is spoofed.
        args=['curl','--socks5-hostname',proxy,'--noproxy','','--silent','--show-error','--max-time','45','--max-filesize','4194304',
              '--write-out','\n%{http_code}',BASE+path]
        if data is not None: args+=['--header','Content-Type: application/json','--data-binary','@-']
        try:
            result=subprocess.run(args,input=data,capture_output=True,timeout=50)
        except subprocess.TimeoutExpired:
            result=None
        if result is None or result.returncode!=0:
            if attempt<2:
                time.sleep(attempt+1);continue
            raise RuntimeError('NEAR API transport unavailable; publication stopped')
        raw,status_bytes=result.stdout.rsplit(b'\n',1)
        status=int(status_bytes)
        if len(raw)>4*1024*1024: raise RuntimeError('Oversized API response')
        if status==429 or status>=500:
            if attempt<2:
                time.sleep(attempt+1);continue
        if status>=400:
            return status, {}
        return status,json.loads(raw, parse_float=WireDecimal)

def quote(mode,incoming,dry=True):
    body=dict(dry=dry,swapType=mode,slippageTolerance=100,
              originAsset='nep141:btc.omft.near' if incoming else 'nep141:zec.omft.near',
              destinationAsset='nep141:zec.omft.near' if incoming else 'nep141:btc.omft.near',
              amount='100000' if mode=='EXACT_OUTPUT' else ('1000000' if incoming else '100000000'),
              refundTo=FIXTURE['bitcoin'] if incoming else FIXTURE['unified'],
              recipient=FIXTURE['unified'] if incoming else FIXTURE['bitcoin'],
              depositType='ORIGIN_CHAIN',refundType='ORIGIN_CHAIN',recipientType='DESTINATION_CHAIN',depositMode='SIMPLE',
              deadline=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat(),
              appFees=[])
    status,result=request('/v0/quote',body)
    if status not in (200,201) or not result:
        raise RuntimeError('Unauthenticated '+mode+' quote failed: HTTP '+str(status))
    read_timestamp(result['timestamp'])
    echo=result['quoteRequest']
    if reviewed_request(echo) != reviewed_request(body):
        raise RuntimeError('NEAR quote echo is incompatible; publication stopped')
    if dry:
        reviewed_quote(result, require_deposit=False)
    return result

def read_positive_atomic(value):
    if type(value) not in (int, str) or not re.fullmatch(r'[0-9]{1,128}', str(value)):
        raise RuntimeError('NEAR quote or status amount is not a bounded whole number of base units')
    amount = int(value)
    if amount <= 0:
        raise RuntimeError('NEAR quote or status amount must be positive')
    return amount


def read_timestamp(value):
    try:
        timestamp = datetime.datetime.fromisoformat(value)
    except (TypeError, ValueError):
        raise RuntimeError('Invalid NEAR response timestamp') from None
    if timestamp.tzinfo is None:
        raise RuntimeError('NEAR response timestamp is missing a timezone')
    return timestamp


def deadline_identity(value):
    timestamp = read_timestamp(value).astimezone(datetime.timezone.utc)
    fractional = re.fullmatch(r'[^.]+(?:\.([0-9]{1,9}))?(?:Z|[+-][0-9]{2}:[0-9]{2})', value)
    if fractional is None:
        raise RuntimeError('Invalid NEAR quote deadline precision')
    return timestamp.replace(microsecond=0), int((fractional[1] or '').ljust(9, '0'))


def read_decimal(value):
    wire = value.text if isinstance(value, WireDecimal) else str(value)
    if type(value) not in (Decimal, int, str, WireDecimal) or len(wire) > 512 or not re.fullmatch(
        r'[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?', wire
    ):
        raise RuntimeError('Invalid NEAR response decimal')
    raw_exponent = int(re.split(r'[eE]', wire)[-1]) if 'e' in wire.lower() else 0
    parsed = Decimal(wire)
    coefficient = parsed.as_tuple()
    digits = len(coefficient.digits)
    scale = -coefficient.exponent
    if abs(raw_exponent) > 512 or not -127 <= scale <= 320 or digits > 128 or digits - scale > 128:
        raise RuntimeError('NEAR response decimal is outside the wallet precision budget')
    return parsed


def reviewed_request(request):
    route_fields = ('swapType', 'originAsset', 'destinationAsset', 'depositType', 'refundType',
                    'recipientType', 'refundTo', 'recipient')
    if (type(request['dry']) is not bool or type(request['slippageTolerance']) is not int
            or request.get('depositMode', 'SIMPLE') != 'SIMPLE'):
        raise RuntimeError('Unsupported NEAR quote request terms')
    waiting = request.get('quoteWaitingTimeMs')
    if waiting is not None and (type(waiting) is not int or not -(2 ** 31) <= waiting < 2 ** 31):
        raise RuntimeError('Invalid NEAR quote waiting time')
    if request.get('referral') is not None and type(request['referral']) is not str:
        raise RuntimeError('Invalid NEAR quote referral field')
    fees = request['appFees']
    if not isinstance(fees, list) or any(
        type(entry.get('recipient')) is not str or type(entry.get('fee')) is not int or entry['fee'] != 0
        for entry in fees
    ):
        raise RuntimeError('NEAR quote echoed an app fee')
    return tuple(request[field] for field in route_fields) + (
        request['dry'], request['slippageTolerance'], read_positive_atomic(request['amount']),
    )


def reviewed_quote(result, require_deposit=True):
    read_timestamp(result['timestamp'])
    request = result['quoteRequest']
    quote = result['quote']
    read_timestamp(request['deadline'])
    address = quote['depositAddress'] if require_deposit else quote.get('depositAddress')
    if not require_deposit and address not in (None, ''):
        raise RuntimeError('NEAR dry quote issued a deposit address')
    extra_fields = ('virtualChainRecipient', 'virtualChainRefundRecipient', 'customRecipientMsg')
    chain_deposits = quote.get('chainDepositAddresses')
    if chain_deposits is not None and (not isinstance(chain_deposits, list) or any(
        not isinstance(item, dict) or type(item.get('address')) is not str
        or (item.get('memo') is not None and type(item['memo']) is not str) for item in chain_deposits
    )):
        raise RuntimeError('Invalid NEAR chain deposit list')
    if (quote.get('depositMemo') is not None
            or any(item['address'] == address and item.get('memo') is not None for item in chain_deposits or [])
            or any(part.get(field) is not None for part in (request, quote) for field in extra_fields)):
        raise RuntimeError('Unsupported NEAR deposit or recipient instructions')
    if any(read_decimal(quote[field]) < 0 for field in ('amountInUsd', 'amountOutUsd')):
        raise RuntimeError('NEAR quote response has a negative USD value')
    amount_in, minimum_in, amount_out, minimum_out = (
        read_positive_atomic(quote[field]) for field in ('amountIn', 'minAmountIn', 'amountOut', 'minAmountOut')
    )
    # The live BTC and ZEC fixtures both use eight decimals. Compare without rounding.
    for field, atomic in (('amountInFormatted', amount_in), ('amountOutFormatted', amount_out)):
        if read_decimal(quote[field]) != Decimal(f'{atomic}e-8'):
            raise RuntimeError('NEAR formatted amount does not match base units')
    mode = request['swapType']
    fixed_amount = amount_out if mode == 'EXACT_OUTPUT' else amount_in
    slippage_multiplier = 10_000 - request['slippageTolerance']
    too_low_in = mode != 'EXACT_INPUT' and minimum_in < amount_in * slippage_multiplier // 10_000
    too_low_out = mode != 'EXACT_OUTPUT' and minimum_out < amount_out * slippage_multiplier // 10_000
    if (minimum_in > amount_in or minimum_out > amount_out
            or fixed_amount != read_positive_atomic(request['amount']) or too_low_in or too_low_out):
        raise RuntimeError('NEAR quote has inconsistent amounts or minima')
    deadline = deadline_identity(quote['deadline'])
    now = datetime.datetime.now(datetime.timezone.utc)
    if deadline <= (now.replace(microsecond=0), now.microsecond * 1000):
        raise RuntimeError('NEAR production quote deadline is not in the future')
    return reviewed_request(request), address, amount_in, minimum_in, amount_out, minimum_out, deadline


def require_status_details(details):
    if details is None:
        return
    if not isinstance(details, dict):
        raise RuntimeError('Invalid NEAR status details')
    for field in ('amountIn', 'amountOut', 'depositedAmount', 'refundedAmount'):
        raw = details.get(field)
        formatted = details.get(field + 'Formatted')
        usd = details.get(field + 'Usd')
        if raw is not None:
            raw = read_decimal(raw)
            if raw < 0 or raw != raw.to_integral_value():
                raise RuntimeError('NEAR status amount is not a whole number of base units')
        if formatted is not None:
            formatted = read_decimal(formatted)
            if (formatted < 0 or (field == 'amountIn' and formatted == 0)
                    or 100_000_000 % formatted.as_integer_ratio()[1] != 0
                    or (raw is not None and formatted != Decimal(f'{int(raw)}e-8'))):
                raise RuntimeError('NEAR status value does not match base units')
        if usd is not None:
            read_decimal(usd)
    slippage = details.get('slippage')
    if slippage is not None and (type(slippage) is not int or not -(2 ** 31) <= slippage < 2 ** 31):
        raise RuntimeError('Invalid NEAR status slippage')
    for field in ('originChainTxHashes', 'destinationChainTxHashes'):
        hashes = details.get(field)
        if hashes is not None and (
            not isinstance(hashes, list) or any(not isinstance(item, dict) or type(item.get('hash')) is not str
                                                for item in hashes)
        ):
            raise RuntimeError('Invalid NEAR status transaction references')


def main():
    for mode,incoming in [('FLEX_INPUT',True),('EXACT_INPUT',False),('EXACT_OUTPUT',False)]:
        quote(mode,incoming)
        print('Unauthenticated '+mode+' dry contract passed.')
    result=quote('EXACT_INPUT',False,dry=False)
    reviewed = reviewed_quote(result)
    address=result['quote']['depositAddress']
    if address.lower().startswith('tex'):
        raise RuntimeError('Provider requires a TEX transparent intermediate; publication stopped')
    if not re.fullmatch(r'(?:t[13][1-9A-HJ-NP-Za-km-z]{33}|(?:u1|zs1)[0-9a-z]{30,1022})', address):
        raise RuntimeError('Unrecognized provider Zcash deposit address type')
    status,poll=request('/v0/status?'+urllib.parse.urlencode({'depositAddress':address}))
    if status!=200 or poll['status']!='PENDING_DEPOSIT':
        raise RuntimeError('Unauthenticated unused-quote status contract changed')
    read_timestamp(poll['updatedAt'])
    require_status_details(poll.get('swapDetails'))
    if reviewed_quote(poll['quoteResponse']) != reviewed:
        raise RuntimeError('NEAR status changed the reviewed funding quote')
    status,_=request('/v0/deposit/submit',{})
    if status not in (400,422):
        raise RuntimeError('Unauthenticated deposit input-validation contract changed: HTTP '+str(status))
    receipt=dict(success=True,source_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                 utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 dry_modes_passed=3,unused_quote_status_passed=True,provider_deposit_requires_transparent_intermediate=False,
                 deposit_validation_passed=True,funds_transferred=False,wallet_secrets_used=False,
                 transport='Tor SOCKS5 with remote DNS; no direct fallback')
    out=ROOT/'artifact';out.mkdir(exist_ok=True)
    (out/'near-contract.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('Live NEAR contracts passed. No funds or wallet secrets were used; no blockchain transaction was submitted.')

if __name__=='__main__': main()
