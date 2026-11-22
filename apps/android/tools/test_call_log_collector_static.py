#!/usr/bin/env python3
"""Host checks for the call-log collector module and manifest boundary."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CALL_LOG_ROOT = ROOT / "call-log-collector"
SETTINGS_GRADLE = ROOT / "settings.gradle.kts"
APP_BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"
APP_MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
ASSISTANT_HOLDER_BUILD_GRADLE = ROOT / "assistant-holder" / "build.gradle.kts"
ASSISTANT_HOLDER_MANIFEST = ROOT / "assistant-holder" / "src" / "main" / "AndroidManifest.xml"
FORBIDDEN_SURFACES = ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts"
CALL_LOG_READER = CALL_LOG_ROOT / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "calls" / "AndroidCallLogReader.kt"
CALL_LOG_CAPABILITY_PROVIDER = CALL_LOG_ROOT / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "calls" / "AndroidCallLogCapabilityProvider.kt"
CALL_LOG_SCHEDULER = CALL_LOG_ROOT / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "calls" / "CallLogSyncScheduler.kt"
CALL_LOG_JOB_SERVICE = CALL_LOG_ROOT / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "calls" / "CallLogSyncJobService.kt"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
ALLOWED_PRODUCTION_DEPENDENCIES = {
    ("implementation", 'project(":capability-ports")'),
    ("implementation", 'project(":capability-sync-runtime")'),
    ("implementation", 'project(":core-model")'),
    ("implementation", 'project(":encrypted-store")'),
    ("implementation", '"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"'),
}
ALLOWED_TEST_DEPENDENCIES = {
    ("testImplementation", '"junit:junit:4.13.2"'),
}


def manifest_permissions(root: ET.Element) -> list[str | None]:
    return [
        element.get(f"{ANDROID_NS}name")
        for element in root
        if isinstance(element.tag, str)
        and (element.tag == "uses-permission" or element.tag.startswith("uses-permission-"))
    ]


def _skip_string_or_comment(source: str, index: int) -> int:
    if source.startswith("//", index):
        newline = source.find("\n", index + 2)
        return len(source) if newline == -1 else newline + 1
    if source.startswith("/*", index):
        depth = 1
        index += 2
        while index < len(source):
            if source.startswith("/*", index):
                depth += 1
                index += 2
            elif source.startswith("*/", index):
                depth -= 1
                index += 2
                if depth == 0:
                    return index
            else:
                index += 1
        return len(source)
    if source.startswith('"""', index):
        end = source.find('"""', index + 3)
        return len(source) if end == -1 else end + 3
    quote = source[index]
    if quote not in ('"', "'"):
        return index
    index += 1
    while index < len(source):
        if source[index] == "\\":
            index += 2
        elif source[index] == quote:
            return index + 1
        else:
            index += 1
    return len(source)


def _skip_whitespace_and_comments(source: str, index: int) -> int:
    while index < len(source):
        if source[index].isspace():
            index += 1
        elif source.startswith("//", index) or source.startswith("/*", index):
            index = _skip_string_or_comment(source, index)
        else:
            return index
    return index


def _matching_delimiter(source: str, opening_index: int, opening: str, closing: str) -> int:
    depth = 1
    index = opening_index + 1
    while index < len(source):
        if source.startswith("//", index) or source.startswith("/*", index):
            index = _skip_string_or_comment(source, index)
            continue
        if source[index] in ('"', "'"):
            index = _skip_string_or_comment(source, index)
            continue
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise ValueError(f"unbalanced {opening}{closing} in source")


def _is_kotlin_identifier_start(character: str) -> bool:
    return character == "_" or character.isalpha()


def _is_kotlin_identifier_part(character: str) -> bool:
    return _is_kotlin_identifier_start(character) or character.isdigit()


def _read_kotlin_identifier(source: str, index: int) -> tuple[str, int]:
    if index >= len(source) or not _is_kotlin_identifier_start(source[index]):
        return "", index
    end = index + 1
    while end < len(source) and _is_kotlin_identifier_part(source[end]):
        end += 1
    return source[index:end], end


def _compact_kotlin_code(source: str) -> str:
    compacted: list[str] = []
    index = 0
    while index < len(source):
        if source.startswith("//", index) or source.startswith("/*", index) or source[index] in ('"', "'"):
            index = _skip_string_or_comment(source, index)
        elif source[index].isspace():
            index += 1
        else:
            compacted.append(source[index])
            index += 1
    return "".join(compacted)


def _kotlin_code_tokens(
    source: str,
    start: int = 0,
    end: int | None = None,
) -> list[tuple[str, int, int]]:
    limit = len(source) if end is None else end
    tokens: list[tuple[str, int, int]] = []
    index = start
    while index < limit:
        if source.startswith("//", index) or source.startswith("/*", index) or source[index] in ('"', "'"):
            index = min(limit, _skip_string_or_comment(source, index))
            continue
        if source[index].isspace():
            index += 1
            continue
        word, word_end = _read_kotlin_identifier(source, index)
        if word:
            tokens.append((word, index, word_end))
            index = word_end
            continue
        tokens.append((source[index], index, index + 1))
        index += 1
    return tokens


def _find_kotlin_class_body(source: str, name: str) -> tuple[int, int] | None:
    tokens = _kotlin_code_tokens(source)
    top_level_braces = 0
    for index, (value, _, _) in enumerate(tokens):
        if value == "{":
            top_level_braces += 1
            continue
        if value == "}":
            top_level_braces = max(0, top_level_braces - 1)
            continue
        if (
            top_level_braces != 0
            or value != "class"
            or index + 1 >= len(tokens)
            or tokens[index + 1][0] != name
        ):
            continue
        parentheses = 0
        brackets = 0
        header_braces = 0
        for header_index in range(index + 2, len(tokens)):
            header_value, header_start, header_end = tokens[header_index]
            if header_value == "(":
                parentheses += 1
            elif header_value == ")":
                parentheses = max(0, parentheses - 1)
            elif header_value == "[":
                brackets += 1
            elif header_value == "]":
                brackets = max(0, brackets - 1)
            elif header_value == "{":
                if parentheses == 0 and brackets == 0 and header_braces == 0:
                    return header_end, _matching_delimiter(source, header_start, "{", "}")
                header_braces += 1
            elif header_value == "}":
                if header_braces == 0:
                    return None
                header_braces -= 1
    return None


def _find_top_level_on_stop_job(
    source: str,
    class_body: tuple[int, int],
) -> tuple[str, int, int] | None:
    class_start, class_end = class_body
    tokens = _kotlin_code_tokens(source, class_start, class_end)
    braces = 0
    for index, (value, _, _) in enumerate(tokens):
        if value == "{":
            braces += 1
            continue
        if value == "}":
            braces = max(0, braces - 1)
            continue
        if (
            braces != 0
            or value != "override"
            or index + 3 >= len(tokens)
            or tokens[index + 1][0] != "fun"
            or tokens[index + 2][0] != "onStopJob"
            or tokens[index + 3][0] != "("
        ):
            continue
        parameters_start = tokens[index + 3][2]
        parameters_end = _matching_delimiter(source, tokens[index + 3][1], "(", ")")
        parameter_values = [
            parameter[0]
            for parameter in _kotlin_code_tokens(source, parameters_start, parameters_end)
        ]
        if parameter_values != ["params", ":", "JobParameters"]:
            continue
        after_parameters = next(
            (
                token_index
                for token_index, token in enumerate(tokens)
                if token[1] > parameters_end
            ),
            len(tokens),
        )
        if (
            after_parameters + 2 >= len(tokens)
            or tokens[after_parameters][0] != ":"
            or tokens[after_parameters + 1][0] != "Boolean"
        ):
            continue
        body_marker, marker_start, marker_end = tokens[after_parameters + 2]
        if body_marker == "{":
            return "block", marker_end, _matching_delimiter(source, marker_start, "{", "}")
        if body_marker == "=":
            return "expression", marker_end, class_end
    return None


def _only_optional_semicolon_until(source: str, start: int, end: int) -> bool:
    semicolon_seen = False
    index = start
    while index < end:
        if source[index].isspace():
            index += 1
        elif source.startswith("//", index) or source.startswith("/*", index):
            index = min(end, _skip_string_or_comment(source, index))
        elif source[index] == ";" and not semicolon_seen:
            semicolon_seen = True
            index += 1
        else:
            return False
    return True


def _direct_retry_lambda_uses_fresh_runtime_factory(
    source: str,
    start: int,
    boundary: int,
) -> bool:
    tokens = _kotlin_code_tokens(source, start, boundary)
    if len(tokens) < 2 or tokens[0][0] != "retryCallLogJobAfterStop" or tokens[1][0] != "{":
        return False
    lambda_start = tokens[1][1]
    lambda_end = _matching_delimiter(source, lambda_start, "{", "}")
    if lambda_end >= boundary:
        return False
    compacted_body = _compact_kotlin_code(source[tokens[1][2] : lambda_end]).rstrip(";")
    return (
        compacted_body == "CallLogRuntimeFactoryRegistry.create(applicationContext)"
        and _only_optional_semicolon_until(source, lambda_end + 1, boundary)
    )


def _block_return_uses_fresh_runtime_factory(
    source: str,
    body_start: int,
    body_end: int,
) -> bool:
    all_returns: list[int] = []
    top_level_returns: list[int] = []
    braces = 0
    parentheses = 0
    brackets = 0
    for value, _, token_end in _kotlin_code_tokens(source, body_start, body_end):
        if value == "return":
            all_returns.append(token_end)
            if braces == 0 and parentheses == 0 and brackets == 0:
                top_level_returns.append(token_end)
        if value == "{":
            braces += 1
        elif value == "}":
            braces = max(0, braces - 1)
        elif value == "(":
            parentheses += 1
        elif value == ")":
            parentheses = max(0, parentheses - 1)
        elif value == "[":
            brackets += 1
        elif value == "]":
            brackets = max(0, brackets - 1)
    return (
        len(all_returns) == 1
        and len(top_level_returns) == 1
        and _direct_retry_lambda_uses_fresh_runtime_factory(source, top_level_returns[0], body_end)
    )


def _on_stop_job_uses_fresh_runtime_factory(source: str) -> bool:
    class_body = _find_kotlin_class_body(source, "CallLogSyncJobService")
    if class_body is None:
        return False
    on_stop_job = _find_top_level_on_stop_job(source, class_body)
    if on_stop_job is None:
        return False
    kind, result_start, result_end = on_stop_job
    if kind == "block":
        return _block_return_uses_fresh_runtime_factory(source, result_start, result_end)
    return _direct_retry_lambda_uses_fresh_runtime_factory(source, result_start, result_end)


def _dependencies_blocks(build: str) -> list[str]:
    blocks: list[str] = []
    index = 0
    curly_depth = 0
    paren_depth = 0
    bracket_depth = 0
    while index < len(build):
        if build.startswith("//", index) or build.startswith("/*", index):
            index = _skip_string_or_comment(build, index)
            continue
        if build[index] in ('"', "'"):
            index = _skip_string_or_comment(build, index)
            continue
        if build[index] == "{":
            curly_depth += 1
            index += 1
            continue
        if build[index] == "}":
            curly_depth -= 1
            index += 1
            continue
        if build[index] == "(":
            paren_depth += 1
            index += 1
            continue
        if build[index] == ")":
            paren_depth -= 1
            index += 1
            continue
        if build[index] == "[":
            bracket_depth += 1
            index += 1
            continue
        if build[index] == "]":
            bracket_depth -= 1
            index += 1
            continue
        if build[index].isalpha() or build[index] == "_":
            start = index
            index += 1
            while index < len(build) and (build[index].isalnum() or build[index] == "_"):
                index += 1
            configuration = build[start:index]
            opening = _skip_whitespace_and_comments(build, index)
            if (
                configuration == "dependencies"
                and curly_depth == 0
                and paren_depth == 0
                and bracket_depth == 0
                and opening < len(build)
                and build[opening] == "{"
            ):
                closing = _matching_delimiter(build, opening, "{", "}")
                blocks.append(build[opening + 1 : closing])
                index = closing + 1
            continue
        index += 1
    if not blocks:
        raise ValueError("Gradle source has no dependencies block")
    return blocks


def _dependency_declarations_from_body(body: str) -> list[tuple[str, str]]:
    declarations: list[tuple[str, str]] = []
    index = 0
    curly_depth = 0
    while index < len(body):
        if body.startswith("//", index) or body.startswith("/*", index):
            index = _skip_string_or_comment(body, index)
            continue
        if body[index] in ('"', "'"):
            index = _skip_string_or_comment(body, index)
            continue
        if body[index] == "{":
            curly_depth += 1
            index += 1
            continue
        if body[index] == "}":
            curly_depth -= 1
            index += 1
            continue
        if body[index].isalpha() or body[index] == "_":
            start = index
            index += 1
            while index < len(body) and (body[index].isalnum() or body[index] == "_"):
                index += 1
            configuration = body[start:index]
            opening = _skip_whitespace_and_comments(body, index)
            if curly_depth == 0 and opening < len(body) and body[opening] == "(":
                closing = _matching_delimiter(body, opening, "(", ")")
                argument = " ".join(body[opening + 1 : closing].split())
                declarations.append((configuration, argument))
                index = closing + 1
            continue
        index += 1
    return declarations


def dependency_declarations(build: str) -> list[tuple[str, str]]:
    declarations: list[tuple[str, str]] = []
    for body in _dependencies_blocks(build):
        declarations.extend(_dependency_declarations_from_body(body))
    return declarations


def production_dependencies(build: str) -> list[tuple[str, str]]:
    return [
        declaration
        for declaration in dependency_declarations(build)
        if declaration[0] != "testImplementation"
    ]


def test_dependencies(build: str) -> list[tuple[str, str]]:
    return [
        declaration
        for declaration in dependency_declarations(build)
        if declaration[0] == "testImplementation"
    ]


def assert_production_dependencies_allowed(build: str) -> None:
    actual = production_dependencies(build)
    if sorted(actual) != sorted(ALLOWED_PRODUCTION_DEPENDENCIES):
        raise AssertionError(
            f"production dependency set differs from allowlist: {sorted(actual)!r}"
        )
    actual_test = test_dependencies(build)
    if sorted(actual_test) != sorted(ALLOWED_TEST_DEPENDENCIES):
        raise AssertionError(
            f"test dependency set differs from allowlist: {sorted(actual_test)!r}"
        )


class CallLogCollectorStaticTest(unittest.TestCase):
    def test_call_log_collector_is_registered_with_only_approved_dependencies(self):
        self.assertIn('\":call-log-collector\"', SETTINGS_GRADLE.read_text(encoding="utf-8"))
        self.assertIn(
            'implementation(project(\":call-log-collector\"))',
            APP_BUILD_GRADLE.read_text(encoding="utf-8"),
        )

        build = (CALL_LOG_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('namespace = "com.openandroidintelligence.calls"', build)
        assert_production_dependencies_allowed(build)

    def test_production_dependency_allowlist_rejects_other_scopes_and_targets(self):
        valid_build = """
            dependencies {
                implementation(project(":capability-ports"))
                implementation(project(":capability-sync-runtime"))
                implementation(project(":core-model"))
                implementation(project(":encrypted-store"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                testImplementation("junit:junit:4.13.2")
            }
        """
        for invalid_declaration in (
            'implementation(project(":transport"))',
            'implementation("com.squareup.okhttp3:okhttp:4.12.0")',
            'api(project(":capability-ports"))',
            'compileOnly("com.example:compile-only:1.0")',
            'runtimeOnly("com.example:runtime-only:1.0")',
            'implementation(\n'
            '                    "com.squareup.okhttp3:okhttp:4.12.0"\n'
            '                )',
            'debugImplementation("com.example:debug-only:1.0")',
        ):
            with self.subTest(invalid_declaration=invalid_declaration):
                self.assertRaises(
                    AssertionError,
                    assert_production_dependencies_allowed,
                    valid_build.replace(
                        'testImplementation("junit:junit:4.13.2")',
                        f"{invalid_declaration}\n"
                        '                testImplementation("junit:junit:4.13.2")',
                    ),
                )

    def test_only_the_closed_call_log_runtime_dependencies_are_allowed(self):
        valid_build = """
            dependencies {
                implementation(project(":capability-ports"))
                implementation(project(":capability-sync-runtime"))
                implementation(project(":core-model"))
                implementation(project(":encrypted-store"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                testImplementation("junit:junit:4.13.2")
            }
        """
        assert_production_dependencies_allowed(valid_build)
        for extra in (
            'implementation(project(":contacts"))',
            'implementation(project(":calendar"))',
            'implementation(project(":sensors"))',
            'implementation("com.example:unapproved:1.0")',
        ):
            with self.subTest(extra=extra):
                self.assertRaises(
                    AssertionError,
                    assert_production_dependencies_allowed,
                    valid_build.replace(
                        'testImplementation("junit:junit:4.13.2")',
                        f"{extra}\n                testImplementation(\"junit:junit:4.13.2\")",
                    ),
                )

    def test_production_dependency_allowlist_scans_a_second_dependencies_block(self):
        first_block = """
            dependencies {
                implementation(project(":capability-ports"))
                implementation(project(":capability-sync-runtime"))
                implementation(project(":core-model"))
                implementation(project(":encrypted-store"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                testImplementation("junit:junit:4.13.2")
            }
        """
        for invalid_declaration in (
            'implementation(project(":transport"))',
            'implementation("com.squareup.okhttp3:okhttp:4.12.0")',
            'debugImplementation("com.example:debug-only:1.0")',
        ):
            with self.subTest(invalid_declaration=invalid_declaration):
                self.assertRaises(
                    AssertionError,
                    assert_production_dependencies_allowed,
                    f"{first_block}\n            dependencies {{\n                {invalid_declaration}\n            }}\n",
                )

    def test_nested_dependencies_block_inside_other_block_is_not_module_block(self):
        build = """
            configure("nested") {
                dependencies {
                    implementation(project(":transport"))
                }
            }
            dependencies {
                implementation(project(":capability-ports"))
                implementation(project(":capability-sync-runtime"))
                implementation(project(":core-model"))
                implementation(project(":encrypted-store"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                testImplementation("junit:junit:4.13.2")
            }
        """
        assert_production_dependencies_allowed(build)

    def test_dependency_keyword_inside_a_raw_string_is_not_a_module_block(self):
        build = (
            'val marker = """dependencies { implementation(project(":transport")) }"""\n'
            'dependencies {\n'
            '    implementation(project(":capability-ports"))\n'
            '    implementation(project(":capability-sync-runtime"))\n'
            '    implementation(project(":core-model"))\n'
            '    implementation(project(":encrypted-store"))\n'
            '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")\n'
            '    testImplementation("junit:junit:4.13.2")\n'
            '}\n'
        )
        assert_production_dependencies_allowed(build)

    def test_main_manifest_is_read_only_for_sms_and_call_log(self):
        root = ET.parse(APP_MANIFEST).getroot()
        declared_permissions = manifest_permissions(root)
        self.assertEqual(
            [
                "android.permission.INTERNET",
                "android.permission.READ_SMS",
                "android.permission.READ_CALL_LOG",
            ],
            declared_permissions,
        )
        telephony_feature = next(
            (
                feature
                for feature in root.findall("uses-feature")
                if feature.get(f"{ANDROID_NS}name") == "android.hardware.telephony"
            ),
            None,
        )
        self.assertIsNotNone(telephony_feature)
        self.assertEqual("false", telephony_feature.get(f"{ANDROID_NS}required"))

    def test_call_log_job_service_is_private_and_platform_bound(self):
        root = ET.parse(APP_MANIFEST).getroot()
        application = root.find("application")
        self.assertIsNotNone(application)
        services = [
            service
            for service in application.findall("service")
            if service.get(f"{ANDROID_NS}name") == "com.openandroidintelligence.calls.CallLogSyncJobService"
        ]
        self.assertEqual(1, len(services))
        self.assertEqual("false", services[0].get(f"{ANDROID_NS}exported"))
        self.assertEqual(
            "android.permission.BIND_JOB_SERVICE",
            services[0].get(f"{ANDROID_NS}permission"),
        )

    def test_call_log_scheduling_has_no_reboot_persistence_surface(self):
        root = ET.parse(APP_MANIFEST).getroot()
        # 只允许私有的 Remote Host 解锁引导，通话记录与短信都不能注册开机任务。
        boot_receivers = [receiver for receiver in root.findall(".//receiver") if any(
            action.get(f"{ANDROID_NS}name") in {"android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED"}
            for action in receiver.findall("intent-filter/action")
        )]
        self.assertEqual([(".RemoteHostBootReceiver", "false")], [
            (receiver.get(f"{ANDROID_NS}name"), receiver.get(f"{ANDROID_NS}exported")) for receiver in boot_receivers
        ])

        scheduler = CALL_LOG_SCHEDULER.read_text(encoding="utf-8")
        self.assertIn(".setPersisted(false)", scheduler)
        self.assertNotIn(".setPersisted(true)", scheduler)

        service = CALL_LOG_JOB_SERVICE.read_text(encoding="utf-8")
        self.assertIn("START_NOT_STICKY", service)
        self.assertTrue(_on_stop_job_uses_fresh_runtime_factory(service))

    def test_stop_retry_factory_lookup_must_be_inside_on_stop_job_lambda(self):
        start_only_lookup = """
            class CallLogSyncJobService {
                override fun onStartJob(params: JobParameters): Boolean {
                    return retryCallLogJobAfterStop {
                        CallLogRuntimeFactoryRegistry.create(applicationContext)
                    }
                }

                override fun onStopJob(params: JobParameters): Boolean {
                    return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                }
            }
        """
        direct_stop_lookup = """
            class CallLogSyncJobService {
                override fun onStopJob(params: JobParameters): Boolean {
                    return retryCallLogJobAfterStop {
                        CallLogRuntimeFactoryRegistry.create( applicationContext )
                    }
                }
            }
        """

        # The previous independent whole-file checks both pass for this bad
        # sample because the registry lookup lives in onStartJob.
        self.assertIn("return retryCallLogJobAfterStop {", start_only_lookup)
        self.assertIn("CallLogRuntimeFactoryRegistry.create(applicationContext)", start_only_lookup)
        self.assertFalse(_on_stop_job_uses_fresh_runtime_factory(start_only_lookup))
        self.assertTrue(_on_stop_job_uses_fresh_runtime_factory(direct_stop_lookup))

    def test_stop_retry_factory_lookup_ignores_kotlin_decoys_and_accepts_expression_body(self):
        bad_override = """
            override fun onStopJob(params: JobParameters): Boolean {
                return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
            }
        """
        fake_declaration = (
            "override fun onStopJob(params: JobParameters): Boolean { "
            "return retryCallLogJobAfterStop { "
            "CallLogRuntimeFactoryRegistry.create(applicationContext) } }"
        )
        decoys = {
            "line comment": f"""
                class CallLogSyncJobService {{
                    // {fake_declaration}
                    {bad_override}
                }}
            """,
            "block comment": f"""
                class CallLogSyncJobService {{
                    /* {fake_declaration} */
                    {bad_override}
                }}
            """,
            "ordinary string": f"""
                class CallLogSyncJobService {{
                    val decoy = \"{fake_declaration}\"
                    {bad_override}
                }}
            """,
            "escaped ordinary string": f"""
                class CallLogSyncJobService {{
                    val decoy = "prefix \\"{fake_declaration}\\" suffix"
                    {bad_override}
                }}
            """,
            "raw string": f'''
                class CallLogSyncJobService {{
                    val decoy = \"\"\"{fake_declaration}\"\"\"
                    {bad_override}
                }}
            ''',
            "local result": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean {
                        val decoy = retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        }
                        return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                    }
                }
            """,
            "local function": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean {
                        fun decoy() = retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        }
                        return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                    }
                }
            """,
            "nested lambda": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean {
                        val decoy = {
                            retryCallLogJobAfterStop {
                                CallLogRuntimeFactoryRegistry.create(applicationContext)
                            }
                        }
                        return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                    }
                }
            """,
            "dead branch": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean {
                        if (false) {
                            return retryCallLogJobAfterStop {
                                CallLogRuntimeFactoryRegistry.create(applicationContext)
                            }
                        }
                        return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                    }
                }
            """,
        }
        expression_body = """
            class CallLogSyncJobService {
                override fun onStopJob(params: JobParameters): Boolean =
                    retryCallLogJobAfterStop {
                        CallLogRuntimeFactoryRegistry.create(applicationContext)
                    }
            }
        """

        for name, source in decoys.items():
            with self.subTest(name=name):
                self.assertFalse(_on_stop_job_uses_fresh_runtime_factory(source))
        self.assertTrue(_on_stop_job_uses_fresh_runtime_factory(expression_body))


    def test_stop_retry_factory_lookup_rejects_suffix_returns_ctor_braces_and_wrong_overload(self):
        good_block = """
            class CallLogSyncJobService {
                override fun onStopJob(params: JobParameters): Boolean {
                    return retryCallLogJobAfterStop {
                        CallLogRuntimeFactoryRegistry.create(applicationContext)
                    }
                }
            }
        """
        good_expression = """
            class CallLogSyncJobService {
                override fun onStopJob(params: JobParameters): Boolean =
                    retryCallLogJobAfterStop {
                        CallLogRuntimeFactoryRegistry.create(applicationContext)
                    }
            }
        """
        reject_with_real_body = {
            "block return with boolean suffix": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean {
                        return retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        } == true
                    }
                }
            """,
            "expression body with boolean suffix": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean =
                        retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        } == true
                }
            """,
            "multiple top-level returns": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters): Boolean {
                        if (ready) {
                            return retryCallLogJobAfterStop {
                                CallLogRuntimeFactoryRegistry.create(applicationContext)
                            }
                        }
                        return retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        }
                    }
                }
            """,
            "deny-first body with constructor anonymous object brace": """
                class CallLogSyncJobService(
                    val decoy: Object = object : Object() {
                        override fun toString() = "decoy"
                    }
                ) {
                    override fun onStopJob(params: JobParameters): Boolean {
                        return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                    }
                }
            """,
            "factory inside wrong-signature overload only": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters, extra: Int): Boolean {
                        return retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        }
                    }
                }
            """,
            "factory in wrong overload and deny-first in real override": """
                class CallLogSyncJobService {
                    override fun onStopJob(params: JobParameters, extra: Int): Boolean {
                        return retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        }
                    }
                    override fun onStopJob(params: JobParameters): Boolean {
                        return retryCallLogJobAfterStop { CallLogRuntime.denyFirst() }
                    }
                }
            """,
        }
        accept_with_real_body = {
            "constructor anonymous object brace keeps real class body": """
                class CallLogSyncJobService(
                    val decoy: Object = object : Object() {
                        override fun toString() = "decoy"
                    }
                ) {
                    override fun onStopJob(params: JobParameters): Boolean {
                        return retryCallLogJobAfterStop {
                            CallLogRuntimeFactoryRegistry.create(applicationContext)
                        }
                    }
                }
            """,
        }

        self.assertTrue(_on_stop_job_uses_fresh_runtime_factory(good_block))
        self.assertTrue(_on_stop_job_uses_fresh_runtime_factory(good_expression))
        for name, source in reject_with_real_body.items():
            with self.subTest(name=name):
                self.assertFalse(_on_stop_job_uses_fresh_runtime_factory(source))
        for name, source in accept_with_real_body.items():
            with self.subTest(name=name):
                self.assertTrue(_on_stop_job_uses_fresh_runtime_factory(source))

    def test_no_manifest_declares_forbidden_phone_permissions(self):
        forbidden_permissions = {
            "android.permission.WRITE_CALL_LOG",
            "android.permission.CALL_PHONE",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PRECISE_PHONE_STATE",
            "android.permission.READ_VOICEMAIL",
            "android.permission.WRITE_VOICEMAIL",
            "android.permission.RECORD_AUDIO",
            "android.permission.PROCESS_OUTGOING_CALLS",
        }
        declared_permissions = {
            permission
            for manifest in ROOT.rglob("AndroidManifest.xml")
            for permission in manifest_permissions(ET.parse(manifest).getroot())
        }
        self.assertFalse(forbidden_permissions & declared_permissions)

    def test_assistant_holder_has_no_call_log_dependency_or_permission(self):
        self.assertNotIn(
            "call-log-collector",
            ASSISTANT_HOLDER_BUILD_GRADLE.read_text(encoding="utf-8"),
        )
        declared_permissions = manifest_permissions(ET.parse(ASSISTANT_HOLDER_MANIFEST).getroot())
        self.assertEqual([], declared_permissions)

    def test_permission_walk_includes_sdk_specific_permission_elements(self):
        root = ET.fromstring(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<uses-permission-sdk-23 android:name="android.permission.CALL_PHONE" />'
            '</manifest>'
        )
        self.assertEqual(["android.permission.CALL_PHONE"], manifest_permissions(root))

    def test_call_log_module_is_in_the_root_forbidden_surface_scan(self):
        guard = FORBIDDEN_SURFACES.read_text(encoding="utf-8")
        self.assertRegex(guard, r'listOf\([^)]*"call-log-collector"')

    def test_call_log_reader_has_the_single_bounded_read_only_provider_surface(self):
        source = CALL_LOG_READER.read_text(encoding="utf-8")
        self.assertEqual(1, source.count("CallLog.Calls.CONTENT_URI"))
        self.assertIn("CallLog.Calls.LIMIT_PARAM_KEY", source)
        self.assertIn(
            'arrayOf("_id", "type", "date", "duration", "number", "number_presentation")',
            source,
        )
        self.assertNotRegex(source, r"(?i)\bLIMIT\s+[0-9?$]")
        self.assertNotRegex(source, r"catch\s*\([^)]*:\s*Throwable\b")
        self.assertEqual(
            ["CallLog.Calls.CONTENT_URI"],
            re.findall(r"CallLog\.[A-Za-z0-9_]+\.CONTENT_URI", source),
        )
        for forbidden_operation in ("insert", "update", "delete", "bulkInsert"):
            with self.subTest(forbidden_operation=forbidden_operation):
                self.assertNotRegex(source, rf"\.{forbidden_operation}\s*\(")
        for forbidden_surface in (
            "Telephony",
            "Telecom",
            "PhoneStateListener",
            "TelephonyManager",
            "TelecomManager",
            "Accessibility",
            "MediaProjection",
            "Socket",
            "URL",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "java.net",
        ):
            with self.subTest(forbidden_surface=forbidden_surface):
                self.assertNotIn(forbidden_surface, source)

    def test_capability_provider_uses_only_the_cursor_helper_for_call_record_id_parsing(self):
        source = CALL_LOG_CAPABILITY_PROVIDER.read_text(encoding="utf-8")
        self.assertEqual(1, source.count('recordId.removePrefix("call:").toLong()'))
        self.assertIn("fun CallsMetadata.toCallLogCursor()", source)
        self.assertEqual(1, len(re.findall(r"catch\s*\([^)]*:\s*Exception\b", source)))
        self.assertIn("Fixed allowlisted audit fields are best effort", source)


if __name__ == "__main__":
    unittest.main()
