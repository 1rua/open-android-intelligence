#!/usr/bin/env python3
"""Run push JVM contracts without Gradle or competing for its test reports.

Uses the existing toolchain/cache and compiled support modules. It compiles the
CURRENT runtime/codec/transport and their tests into a separate build child.
The --notification-sources mode recompiles the real collector/service sources,
but neither mode runs Compose or Android system lifecycles/device acceptance.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


def main() -> int:
    repo = next(path for path in Path(__file__).resolve().parents if (path / "gateway-contract").is_dir())
    toolchains = repo / ".toolchains"
    if not toolchains.is_dir():
        toolchains = (repo / "../../.toolchains").resolve()
    cache = toolchains / "gradle-user-home/caches/modules-2/files-2.1"
    notification_sources = "--notification-sources" in sys.argv[1:]
    if set(sys.argv[1:]) - {"--notification-sources"}:
        raise RuntimeError("Only --notification-sources is supported")
    output = repo / ("apps/android/notification-collector/build/source-standalone-verification" if notification_sources
                     else "apps/android/app/build/push-standalone-verification")
    output.mkdir(parents=True, exist_ok=True)
    java = toolchains / "jdk-21-extract/jbrsdk_jcef-21.0.10-linux-x64-b1163.110/bin/java"

    def artifact(package: str, version: str) -> Path:
        matches = list((cache / package / version).glob("*/*.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"Existing cached artifact required: {package}/{version}")
        return matches[0]

    stdlib = artifact("org.jetbrains.kotlin/kotlin-stdlib", "2.1.20")
    coroutines = artifact("org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm", "1.9.0")
    compiler = [
        artifact("org.jetbrains.kotlin/kotlin-compiler-embeddable", "2.1.20"),
        stdlib,
        artifact("org.jetbrains.kotlin/kotlin-script-runtime", "2.1.20"),
        artifact("org.jetbrains.kotlin/kotlin-reflect", "1.6.10"),
        artifact("org.jetbrains.kotlin/kotlin-daemon-embeddable", "2.1.20"),
        artifact("org.jetbrains.intellij.deps/trove4j", "1.0.20200330"),
        artifact("org.jetbrains/annotations", "13.0"),
        coroutines,
    ]
    dependencies = [stdlib, coroutines, artifact("junit/junit", "4.13.2"),
                    artifact("org.hamcrest/hamcrest-core", "1.3"),
                    toolchains / "android-sdk/platforms/android-36/android.jar"]
    for module in (("gateway-client", "encrypted-store", "core-model", "policy-engine") if notification_sources else ("gateway-client", "encrypted-store", "core-model")):
        jar = repo / f"apps/android/{module}/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
        if not jar.is_file():
            raise RuntimeError(f"Existing compiled support module required: {module}")
        dependencies.append(jar)
    if notification_sources:
        source = repo / "apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications"
        tests = repo / "apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications"
        sources = sorted(source.glob("*.kt"))
        test_sources = [path for path in sorted(tests.glob("*Test.kt")) if path.stem != "NotificationListenerServiceManifestTest"]
        sources.extend(test_sources)
        test_classes = [path.stem for path in test_sources]
        test_package = "com.openandroidintelligence.notifications."
    else:
        source = repo / "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push"
        tests = Path(__file__).resolve().parent
        sources = [source / name for name in ("PushModels.kt", "EncryptedPushStateStore.kt", "DevicePushRuntime.kt", "GatewayPushTransport.kt")]
        sources.append(repo / "apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/SmsScope.kt")
        # The picker DTO lives beside UI. Read its exact declaration, without a behavior stub.
        declaration = next(line for line in (source / "PushSettingsScreen.kt").read_text().splitlines()
                           if line.startswith("data class PushEndpointOption("))
        dto = output / "PushEndpointOption.kt"
        dto.write_text("package com.openandroidintelligence.mobile.push\n" + declaration + "\n")
        sources.append(dto)
        test_classes = ["DevicePushRuntimeTest", "GatewayPushTransportTest", "PushRevocationTest", "PushStateCodecMigrationTest", "NotificationPushScopePersistenceTest", "PushEndpointAvailabilityTest", "SmsPushScopeTest"]
        sources.extend(tests / f"{name}.kt" for name in test_classes)
        test_package = "com.openandroidintelligence.mobile.push."
    compiled = output / "classes"
    classpath = ":".join(str(path) for path in dependencies)
    command = [str(java), "-cp", ":".join(str(path) for path in compiler),
               "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
               "-classpath", classpath, "-d", str(compiled), *(str(path) for path in sources)]
    with (output / "compile.log").open("w") as log:
        result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((output / "compile.log").read_text())
        return result.returncode
    with (output / "junit.log").open("w") as log:
        result = subprocess.run([str(java), "-cp", str(compiled) + ":" + classpath, "org.junit.runner.JUnitCore",
                                 *(test_package + name for name in test_classes)],
                                cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    print((output / "junit.log").read_text())
    (output / "result.json").write_text(json.dumps({"runner": "Kotlin CLI + JUnitCore; no Gradle",
                                                    "classes": test_classes, "notificationSources": notification_sources, "exitCode": result.returncode}, indent=2) + "\n")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
