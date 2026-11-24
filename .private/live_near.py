"""Unfunded API contract checks using public SDK addresses, never a real wallet."""
import datetime,json,os,re,subprocess,time,urllib.parse
from decimal import Decimal
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE='https://1click.chaindefuser.com'
FIXTURE=json.loads((ROOT/'.private/public-api-fixture.json').read_text())

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
            text=raw.decode('utf-8',errors='replace')
            try:
                parsed=json.loads(text)
                reason=str(parsed.get('message',parsed.get('error','HTTP error')))
            except json.JSONDecodeError:
                title=re.search(r'<title>(.*?)</title>',text,re.I|re.S)
                reason=title[1].strip() if title else text[:200]
            reason=re.sub(r'[A-Za-z0-9_-]{30,}','[redacted]',reason)[:400]
            return status,{'http_error':reason}
        return status,json.loads(raw, parse_float=Decimal)

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
        reason=result.get('http_error','') if result else ''
        raise RuntimeError('Unauthenticated '+mode+' quote failed: HTTP '+str(status)+' '+reason)
    echo=result['quoteRequest']
    if reviewed_request(echo) != reviewed_request(body):
        raise RuntimeError('NEAR quote echo is incompatible; publication stopped')
    return result

def read_positive_atomic(value):
    if type(value) not in (int, str) or not re.fullmatch(r'[0-9]{1,128}', str(value)):
        raise RuntimeError('NEAR quote or status amount is not a bounded whole number of base units')
    amount = int(value)
    if amount <= 0:
        raise RuntimeError('NEAR quote or status amount must be positive')
    return amount


def reviewed_request(request):
    route_fields = ('swapType', 'originAsset', 'destinationAsset', 'depositType', 'refundType',
                    'recipientType', 'refundTo', 'recipient')
    if (type(request['dry']) is not bool or type(request['slippageTolerance']) is not int
            or request.get('depositMode', 'SIMPLE') != 'SIMPLE'):
        raise RuntimeError('Unsupported NEAR quote request terms')
    return tuple(request[field] for field in route_fields) + (
        request['dry'], request['slippageTolerance'], read_positive_atomic(request['amount']),
    )


def reviewed_quote(result):
    request = result['quoteRequest']
    quote = result['quote']
    address = quote['depositAddress']
    extra_fields = ('virtualChainRecipient', 'virtualChainRefundRecipient', 'customRecipientMsg')
    if (quote.get('depositMemo') is not None
            or any(item['address'] == address and item.get('memo') is not None
                   for item in quote.get('chainDepositAddresses') or [])
            or any(part.get(field) is not None for part in (request, quote) for field in extra_fields)):
        raise RuntimeError('Unsupported NEAR deposit or recipient instructions')
    if quote.get('amountInUsd') is None or quote.get('amountOutUsd') is None:
        raise RuntimeError('NEAR production quote response is incomplete')
    amount_in, minimum_in, amount_out, minimum_out = (
        read_positive_atomic(quote[field]) for field in ('amountIn', 'minAmountIn', 'amountOut', 'minAmountOut')
    )
    # The live BTC and ZEC fixtures both use eight decimals. Compare without rounding.
    for field, atomic in (('amountInFormatted', amount_in), ('amountOutFormatted', amount_out)):
        if Decimal(str(quote[field])) != Decimal(f'{atomic}e-8'):
            raise RuntimeError('NEAR formatted amount does not match base units')
    if (minimum_in > amount_in or minimum_out > amount_out
            or amount_in != read_positive_atomic(request['amount'])
            or minimum_out < amount_out * (10_000 - request['slippageTolerance']) // 10_000):
        raise RuntimeError('NEAR production quote has inconsistent amounts or minima')
    deadline = quote['deadline']
    if datetime.datetime.fromisoformat(deadline).tzinfo is None:
        raise RuntimeError('NEAR quote deadline is missing a timezone')
    return reviewed_request(request), address, amount_in, minimum_in, amount_out, minimum_out, deadline


def main():
    for mode,incoming in [('FLEX_INPUT',True),('EXACT_INPUT',False),('EXACT_OUTPUT',False)]:
        quote(mode,incoming)
        print('Unauthenticated '+mode+' dry contract passed.')
    result=quote('EXACT_INPUT',False,dry=False)
    reviewed = reviewed_quote(result)
    address=result['quote']['depositAddress']
    if address.lower().startswith('tex'):
        raise RuntimeError('Provider requires a TEX transparent intermediate; publication stopped')
    if not address.startswith(('t1','t3','u1','zs1')):
        raise RuntimeError('Unrecognized provider Zcash deposit address type')
    status,poll=request('/v0/status?'+urllib.parse.urlencode({'depositAddress':address}))
    if status!=200 or poll['status']!='PENDING_DEPOSIT':
        raise RuntimeError('Unauthenticated unused-quote status contract changed')
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
