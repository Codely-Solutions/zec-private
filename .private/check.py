"""Fork invariants. Static checks supplement, and never replace, Kotlin tests."""
import hashlib, json, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMON = Path('ui-lib/src/main/java/co/electriccoin/zcash/ui/common')

def check(root=ROOT):
    errors=[]
    def need(ok, message):
        if not ok: errors.append(message)
    source=(root/COMMON/'datasource/NearSwapDataSource.kt').read_text()
    need('appFees = emptyList()' in source, 'Affiliate-free quote construction changed')
    need('AppFee(' not in source and 'affiliateAddress' not in source, 'Affiliate recipient reintroduced')
    need('referral =' not in source, 'Quote referral reintroduced')
    provider=(root/COMMON/'provider/NearApiProvider.kt').read_text()
    need(not re.search(r'Authorization|AUTH_TOKEN|Bearer |X-API-Key|eyJhbGci',provider), 'Partner authentication reintroduced')
    need(provider.count('https://1click.chaindefuser.com/v0/')==4,'NEAR endpoint changed')
    need('httpClientProvider.create().use' in provider,'Tor preference routing changed')
    usecase=(root/COMMON/'usecase/RequestSwapQuoteUseCase.kt').read_text()
    need(usecase.count('accountDataSource.requestNextShieldedAddress()')==3,'Fresh shielded recipient/refund derivation changed')
    need('destinationAddress = newAddress' in usecase and usecase.count('refundAddress = newAddress')==2,'Address routing changed')
    for p in (root/'ui-lib/src/main').rglob('*.kt'):
        text=p.read_text()
        need(not re.search(r'AFFILIATE_FEE_BPS|AFFILIATE_ADDRESS|eyJhbGciOiJSUzI1Ni',text),'Obsolete affiliate/JWT reference: '+str(p.relative_to(root)))
    quote=(root/COMMON/'model/near/NearSwapQuote.kt').read_text()
    need('override val affiliateFee: BigDecimal = BigDecimal.ZERO' in quote,'Affiliate display is nonzero')
    props=(root/'gradle.properties').read_text()
    need('ZCASH_RELEASE_PACKAGE_NAME=com.personal.zecprivate\n' in props,'Package identity changed')
    need('IS_RELEASE_BUILD_DEBUGGABLE=false' in props,'Release debuggability enabled')
    need('IS_SIGN_RELEASE_BUILD_WITH_DEBUG_KEY=false' in props,'Debug signing enabled')
    return errors

if __name__=='__main__':
    errors=check()
    if errors: raise SystemExit('\n'.join(errors))
    print('Static fork invariants passed; behavioral tests remain required.')
