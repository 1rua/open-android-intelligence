from pathlib import Path
import os
import subprocess
import sys
root = Path.cwd()
temporary = Path(sys.argv[1])
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
def jar(relative):
    return next(p for p in (cache / relative).glob("*/*.jar") if not p.name.endswith("-sources.jar"))
compiler = jar("org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21")
stdlib = jar("org.jetbrains.kotlin/kotlin-stdlib/2.0.21")
reflect = jar("org.jetbrains.kotlin/kotlin-reflect/2.0.21")
trove = jar("org.jetbrains.intellij.deps/trove4j/1.0.20200330")
annotations = jar("org.jetbrains/annotations/13.0")
coroutines = next((cache / "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm").glob("*/*/*.jar"))
java = Path(os.environ.get("JAVA_HOME", str(root / ".toolchains/jdk-17.0.20+8"))) / "bin/java"
sources = [
    root / "apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt",
    root / "apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/CapabilityGrant.kt",
    root / "apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt",
    root / "apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt",
    root / "apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginManifest.kt",
    root / "apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/Json.kt",
    Path(__file__).with_name("BoundaryProbe.kt")
]
output = temporary / "probe-classes"
command = [str(java), "-cp", os.pathsep.join(map(str,[compiler,stdlib,reflect,trove,annotations,coroutines])),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
    "-classpath", str(stdlib), "-d", str(output), *map(str,sources)]
log = temporary / "boundary-probe.log"
with log.open("w") as handle:
    result = subprocess.run(command, stdout=handle, stderr=subprocess.STDOUT)
    if result.returncode == 0:
        result = subprocess.run([str(java), "-cp", str(output)+os.pathsep+str(stdlib), "BoundaryProbeKt",
            str(root), str(temporary)], stdout=handle, stderr=subprocess.STDOUT)
print(log.read_text())
raise SystemExit(result.returncode)
