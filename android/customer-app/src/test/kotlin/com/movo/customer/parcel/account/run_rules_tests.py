"""Run JVM-only account rules without starting another Gradle daemon."""
import glob
import pathlib
import subprocess
import tempfile

cache = pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1'
def jars(group, artifact, version='*'):
    return glob.glob(str(cache / group / artifact / version / '*' / '*.jar'))
compiler = sum((jars('org.jetbrains.kotlin', a, '1.9.25') for a in ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlin-daemon-embeddable']), [])
compiler += jars('org.jetbrains.intellij.deps', 'trove4j') + jars('org.jetbrains', 'annotations')
runtime = jars('org.jetbrains.kotlin','kotlin-stdlib','1.9.25') + jars('org.jetbrains.kotlin','kotlin-test','1.9.25') + jars('org.jetbrains.kotlin','kotlin-test-junit','1.9.25') + jars('junit','junit','4.13.2') + jars('org.hamcrest','hamcrest-core')
root = pathlib.Path(__file__).resolve().parents[7]
source = root / 'main/kotlin/com/movo/customer/parcel/account/AccountRules.kt'
test = pathlib.Path(__file__).with_name('AccountRulesTest.kt')
with tempfile.TemporaryDirectory() as out:
    sources = [str(test)] + ([str(source)] if source.exists() else [])
    result = subprocess.run(['java','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',':'.join(runtime),'-d',out,*sources])
    if result.returncode:
        raise SystemExit(result.returncode)
    raise SystemExit(subprocess.run(['java','-cp',out+':'+':'.join(runtime),'org.junit.runner.JUnitCore','com.movo.customer.parcel.account.AccountRulesTest']).returncode)
