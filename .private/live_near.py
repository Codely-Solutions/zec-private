"""Unfunded API contract checks using public SDK addresses, never a real wallet."""
import datetime,json,subprocess,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE='https://1click.chaindefuser.com'
FIXTURE=json.loads((ROOT/'.private/public-api-fixture.json').read_text())

def request(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    for attempt in range(3):
        req=urllib.request.Request(BASE+path,data=data,
                                   headers={} if data is None else {'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(req,timeout=15) as response:
                raw=response.read(4*1024*1024+1)
                if len(raw)>4*1024*1024: raise RuntimeError('Oversized API response')
                return response.status,json.loads(raw)
        except urllib.error.HTTPError as error:
            if error.code==429 or error.code>=500:
                if attempt<2:
                    time.sleep(attempt+1);continue
            return error.code,None
        except (urllib.error.URLError,TimeoutError):
            if attempt<2:
                time.sleep(attempt+1);continue
            raise RuntimeError('NEAR API unavailable; publication stopped') from None

def quote(mode,incoming,dry=True):
    body=dict(dry=dry,swapType=mode,slippageTolerance=100,
              originAsset='nep141:btc.omft.near' if incoming else 'nep141:zec.omft.near',
              destinationAsset='nep141:zec.omft.near' if incoming else 'nep141:btc.omft.near',
              amount='100000' if mode=='EXACT_OUTPUT' else ('1000000' if incoming else '100000000'),
              refundTo=FIXTURE['bitcoin'] if incoming else FIXTURE['unified'],
              recipient=FIXTURE['unified'] if incoming else FIXTURE['bitcoin'],
              depositType='ORIGIN_CHAIN',refundType='ORIGIN_CHAIN',recipientType='DESTINATION_CHAIN',
              deadline=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat(),
              appFees=[])
    status,result=request('/v0/quote',body)
    if status not in (200,201) or not result:
        raise RuntimeError('Unauthenticated '+mode+' quote failed: HTTP '+str(status))
    echo=result['quoteRequest']
    if (echo['swapType']!=mode or echo['recipient']!=body['recipient'] or echo['refundTo']!=body['refundTo']):
        raise RuntimeError('NEAR quote echo is incompatible; publication stopped')
    if not dry:
        required=('depositAddress','amountIn','amountInFormatted','amountInUsd','minAmountIn',
                  'amountOut','amountOutFormatted','amountOutUsd','minAmountOut','deadline')
        if any(result['quote'].get(field) is None for field in required):
            raise RuntimeError('NEAR production quote response is incomplete')
    return result

def main():
    for mode,incoming in [('FLEX_INPUT',True),('EXACT_INPUT',False),('EXACT_OUTPUT',False)]:
        quote(mode,incoming)
        print('Unauthenticated '+mode+' dry contract passed.')
    result=quote('EXACT_INPUT',False,dry=False)
    address=result['quote']['depositAddress']
    if address.lower().startswith('tex'):
        raise RuntimeError('Provider requires a TEX transparent intermediate; publication stopped')
    if not address.startswith(('t1','t3','u1','zs1')):
        raise RuntimeError('Unrecognized provider Zcash deposit address type')
    status,poll=request('/v0/status?'+urllib.parse.urlencode({'depositAddress':address}))
    if status!=200 or poll['status']!='PENDING_DEPOSIT':
        raise RuntimeError('Unauthenticated unused-quote status contract changed')
    if (poll['quoteResponse']['quoteRequest']['refundTo']!=FIXTURE['unified'] or
            poll['quoteResponse']['quoteRequest']['recipient']!=FIXTURE['bitcoin']):
        raise RuntimeError('NEAR status changed recipient or refund destination')
    status,_=request('/v0/deposit/submit',{})
    if status not in (400,422):
        raise RuntimeError('Unauthenticated deposit input-validation contract changed: HTTP '+str(status))
    receipt=dict(success=True,source_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                 utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 dry_modes_passed=3,unused_quote_status_passed=True,provider_deposit_requires_transparent_intermediate=False,
                 deposit_validation_passed=True,funds_transferred=False,wallet_secrets_used=False)
    out=ROOT/'artifact';out.mkdir(exist_ok=True)
    (out/'near-contract.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('Live NEAR contracts passed. No funds or wallet secrets were used; no blockchain transaction was submitted.')

if __name__=='__main__': main()
