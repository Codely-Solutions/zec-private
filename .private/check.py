"""Fork invariants. Static checks supplement, and never replace, Kotlin tests."""
import hashlib, json, re, subprocess, sys
from pathlib import Path
from check_exclusions import check as check_exclusions

ROOT = Path(__file__).resolve().parents[1]
COMMON = Path('ui-lib/src/main/java/co/electriccoin/zcash/ui/common')

def check(root=ROOT):
    errors=check_exclusions(root)
    def need(ok, message):
        if not ok: errors.append(message)
    source=(root/COMMON/'datasource/NearSwapDataSource.kt').read_text()
    need('appFees = emptyList()' in source, 'Affiliate-free quote construction changed')
    need('AppFee(' not in source and 'affiliateAddress' not in source, 'Affiliate recipient reintroduced')
    need('referral =' not in source, 'Quote referral reintroduced')
    need('require(depositType != AddressType.Tex)' in source,
         'Swap deposits can invoke the SDK transparent-intermediate flow')
    provider=(root/COMMON/'provider/NearApiProvider.kt').read_text()
    need(not re.search(r'Authorization|AUTH_TOKEN|Bearer |X-API-Key|eyJhbGci',provider), 'Partner authentication reintroduced')
    need(provider.count('https://1click.chaindefuser.com/v0/')==4,'NEAR endpoint changed')
    need('httpClientProvider.create().use' in provider,'Tor preference routing changed')
    usecase=(root/COMMON/'usecase/RequestSwapQuoteUseCase.kt').read_text()
    need(usecase.count('val newAddress = requestFreshAddress() ?: return')==3,
         'Fresh shielded recipient/refund derivation changed')
    need('accountDataSource.requestNextShieldedAddress(requireFresh = true)' in usecase,
         'Swap address requests may fall back to an existing address')
    accounts=(root/COMMON/'datasource/AccountDataSource.kt').read_text()
    need('throw FreshShieldedAddressUnavailableException()' in accounts,
         'Strict fresh-address failure behavior changed')
    need('destinationAddress = newAddress' in usecase and usecase.count('refundAddress = newAddress')==2,'Address routing changed')
    for p in (root/'ui-lib/src/main').rglob('*.kt'):
        text=p.read_text()
        need(not re.search(r'AFFILIATE_FEE_BPS|AFFILIATE_ADDRESS|eyJhbGciOiJSUzI1Ni',text),'Obsolete affiliate/JWT reference: '+str(p.relative_to(root)))
    quote=(root/COMMON/'model/near/NearSwapQuote.kt').read_text()
    need('override val affiliateFee: BigDecimal = BigDecimal.ZERO' in quote,'Affiliate display is nonzero')
    fee_copy=(root/'ui-lib/src/main/res/ui/non_translatable/values/private_strings.xml').read_text()
    need('Anonymous API platform fee: 0.25%% where applicable.' in fee_copy,'Fee note can crash Android formatted-resource rendering')
    mapper=(root/'ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/swap/quote/SwapQuoteVMMapper.kt').read_text()
    need('layout = SwapQuoteInfoLayout.STACKED' in mapper,'Provider costs can collapse the quote label into a vertical column')
    build=(root/'app/build.gradle.kts').read_text()
    need('abiFilters.add("arm64-v8a")' in build and 'armeabi-v7a' not in build,
         'Production ARM64-only packaging changed')
    props=(root/'gradle.properties').read_text()
    need('ZCASH_RELEASE_PACKAGE_NAME=com.personal.zecprivate\n' in props,'Package identity changed')
    need('IS_RELEASE_BUILD_DEBUGGABLE=false' in props,'Release debuggability enabled')
    need('IS_SIGN_RELEASE_BUILD_WITH_DEBUG_KEY=false' in props,'Debug signing enabled')
    target=(root/'app/src/zcashmainnetFossRelease/AndroidManifest.xml').read_text()
    need('android:authorities="com.personal.zecprivate.foss.provider"' in target,
         'FOSS provider authority can collide with the official wallet')
    share=(root/'ui-lib/src/main/java/co/electriccoin/zcash/ui/util/FileShareUtil.kt').read_text()
    need('"co.electriccoin.zcash.' not in share,'File sharing still uses upstream authorities')
    need((root/'app/src/main/assets/UPSTREAM-LICENSE.txt').read_bytes().replace(b'\r\n',b'\n') ==
         (root/'LICENSE').read_bytes().replace(b'\r\n',b'\n'),'Bundled upstream license differs')
    for p in (root/'ui-lib/src/test').rglob('*.kt'):
        need('requestQuote(any(), any(), any(), any(), any(), any(), any(), any())' not in p.read_text(),
             'Obsolete affiliate argument in mock: '+str(p.relative_to(root)))
    return errors

if __name__=='__main__':
    errors=check()
    if errors: raise SystemExit('\n'.join(errors))
    print('Static fork invariants passed; behavioral tests remain required.')
