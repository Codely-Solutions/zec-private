"""Reproduce the reported resource crash in unsigned CI, then restore exact source bytes."""
import hashlib,json,subprocess,xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
RESOURCE=ROOT/'ui-lib/src/main/res/ui/non_translatable/values/private_strings.xml'
CLASS='co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteVMMapperTest'
REPORT=ROOT/('ui-lib/build/test-results/testZcashmainnetFossReleaseUnitTest/TEST-'+CLASS+'.xml')
EXPECTED={
    'incomingQuoteFeeNoteRendersWithoutFormatConversionFailure',
    'outgoingExactInputFeeNoteRendersWithoutFormatConversionFailure',
    'outgoingExactOutputFeeNoteRendersWithoutFormatConversionFailure',
}

def main():
    original=RESOURCE.read_bytes()
    if b'0.25%% where applicable' not in original:
        raise RuntimeError('Corrected fee text is required before negative-control testing')
    REPORT.unlink(missing_ok=True)
    try:
        RESOURCE.write_bytes(original.replace(
            b'Anonymous API platform fee: 0.25%% where applicable. Other swap costs may apply; review the quoted amount received.',
            b'Unauthenticated quotes may include an extra 0.25% platform fee.'))
        result=subprocess.run([
            str(ROOT/'gradlew'),'--no-daemon','--no-watch-fs','--max-workers=2',
            '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g',
            '-Pkotlin.compiler.execution.strategy=in-process',
            ':ui-lib:testZcashmainnetFossReleaseUnitTest','--tests',CLASS+'.*FeeNoteRendersWithoutFormatConversionFailure',
        ],cwd=ROOT,timeout=1100)
        if result.returncode==0 or not REPORT.is_file():
            raise RuntimeError('Original quote crash was not reproduced by the targeted tests')
        cases=ET.fromstring(REPORT.read_bytes()).findall('testcase')
        if {case.get('name') for case in cases}!=EXPECTED:
            raise RuntimeError('Negative control did not execute all three quote-rendering regressions')
        for case in cases:
            failure=case.find('failure')
            if failure is None or 'UnknownFormatConversionException' not in ET.tostring(failure,encoding='unicode'):
                raise RuntimeError('Negative control failed for a different reason')
    finally:
        RESOURCE.write_bytes(original)
    if RESOURCE.read_bytes()!=original:
        raise RuntimeError('Negative control changed production source')
    receipt=dict(source_sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                 original_crash_reproduced=True,quote_modes_tested=3,source_restored=True,
                 restored_resource_sha256=hashlib.sha256(original).hexdigest())
    output=ROOT/'artifact';output.mkdir(exist_ok=True)
    (output/'quote-rendering-negative.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('All three quote modes rejected the original malformed fee text; exact fixed source restored.')

if __name__=='__main__': main()
