"""Compare ordinary API clients; no credentials, deposits or wallet data."""
import datetime,json,os,re,subprocess,tempfile,urllib.error,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
fixture=json.loads((root/'.private/public-api-fixture.json').read_text())
body=json.dumps(dict(dry=True,swapType='FLEX_INPUT',slippageTolerance=100,
    originAsset='nep141:btc.omft.near',destinationAsset='nep141:zec.omft.near',amount='1000000',
    refundTo=fixture['bitcoin'],recipient=fixture['unified'],depositType='ORIGIN_CHAIN',
    refundType='ORIGIN_CHAIN',recipientType='DESTINATION_CHAIN',appFees=[],
    deadline=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat()))
def summary(raw):
    text=raw.decode('utf-8',errors='replace')
    try:
        data=json.loads(text)
        if isinstance(data,list): return 'JSON token list'
        if 'quote' in data: return 'JSON quote response'
        text=str(data.get('message',data.get('error','JSON response')))
    except json.JSONDecodeError: pass
    text=re.sub(r'[A-Za-z0-9_-]{30,}','[redacted]',text)
    text=re.sub(r'\b(?:\d{1,3}\.){3}\d{1,3}\b','[IP]',text)
    return text[:300]
with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as temp:
    temp=Path(temp);request_file=temp/'request.json';request_file.write_text(body)
    for path,payload in [('/v0/tokens',None),('/v0/quote',body.encode())]:
        url='https://1click.chaindefuser.com'+path
        try:
            req=urllib.request.Request(url,data=payload,headers={} if payload is None else {'Content-Type':'application/json'})
            with urllib.request.urlopen(req,timeout=20) as result:
                print('urllib',path,result.status,summary(result.read(4096)))
        except urllib.error.HTTPError as error:
            print('urllib',path,error.code,'server='+str(error.headers.get('Server')),summary(error.read(4096)))
        output=temp/'response'
        args=['curl','--silent','--show-error','--max-time','20','--output',str(output),'--write-out','%{http_code}',url]
        if payload is not None:args+=['--header','Content-Type: application/json','--data-binary','@'+str(request_file)]
        result=subprocess.run(args,capture_output=True,text=True,timeout=25)
        print('curl',path,result.stdout,summary(output.read_bytes()[:4096]) if output.exists() else 'No body')
