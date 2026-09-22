package com.openandroidintelligence.plugin.ui

/**
 * A declarative UI contribution declared by a plugin and rendered by the host.
 *
 * Per device-plugin-package-v1 §8 a plugin may only submit structured JSON over
 * a fixed component whitelist. It may not submit HTML, JavaScript, a WebView
 * address, a host view class, an arbitrary Intent or an executable expression.
 * A contribution that cannot be validated is dropped, which makes that one
 * contribution unavailable without disturbing the plugin's other verified
 * capabilities.
 */
class UiRejected(code: String) : IllegalArgumentException(code)

/** The only component kinds a plugin may declare. */
enum class UiComponentKind(val id: String) {
    SECTION("section"),
    TEXT("text"),
    STATUS("status"),
    TOGGLE("toggle"),
    SELECT("select"),
    BUTTON("button"),
    PERMISSION_REQUEST("permission-request"),
    CAPABILITY_PICKER("capability-picker"),
    ;

    companion object {
        fun byId(id: String): UiComponentKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The only actions a component may submit. An action is an identifier that the
 * kernel re-authorises when the user activates the component; it is never a
 * callback, expression or navigation target supplied by the plugin.
 */
enum class UiActionId(val id: String) {
    SET_SETTING("set-setting"),
    INVOKE_CAPABILITY("invoke-capability"),
    REQUEST_GRANT("request-grant"),
    SELECT_PROVIDER("select-provider"),
    REFRESH_CARD("refresh-card"),
    ;

    companion object {
        fun byId(id: String): UiActionId? = entries.firstOrNull { it.id == id }
    }
}

/** Actions each component kind is allowed to carry. */
internal val ALLOWED_ACTIONS: Map<UiComponentKind, Set<UiActionId>> = mapOf(
    UiComponentKind.SECTION to emptySet(),
    UiComponentKind.TEXT to setOf(UiActionId.REFRESH_CARD),
    UiComponentKind.STATUS to setOf(UiActionId.REFRESH_CARD),
    UiComponentKind.TOGGLE to setOf(UiActionId.SET_SETTING),
    UiComponentKind.SELECT to setOf(UiActionId.SET_SETTING),
    UiComponentKind.BUTTON to setOf(
        UiActionId.INVOKE_CAPABILITY,
        UiActionId.REFRESH_CARD,
        UiActionId.REQUEST_GRANT,
    ),
    UiComponentKind.PERMISSION_REQUEST to setOf(UiActionId.REQUEST_GRANT),
    UiComponentKind.CAPABILITY_PICKER to setOf(UiActionId.SELECT_PROVIDER),
)

/** Status severities; anything else is dropped rather than rendered as-is. */
enum class UiSeverity(val id: String) {
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    ;

    companion object {
        fun byId(id: String): UiSeverity? = entries.firstOrNull { it.id == id }
    }
}

data class UiSelectOption(val id: String, val label: String)

sealed interface UiComponent {
    val id: String
    val label: String?
    val action: UiActionId?

    data class Section(
        override val id: String,
        override val label: String?,
        val children: List<UiComponent>,
    ) : UiComponent {
        override val action: UiActionId? get() = null
    }

    data class Text(
        override val id: String,
        override val label: String?,
        val value: String,
        override val action: UiActionId?,
    ) : UiComponent

    data class Status(
        override val id: String,
        override val label: String?,
        val value: String,
        val severity: UiSeverity,
        override val action: UiActionId?,
    ) : UiComponent

    data class Toggle(
        override val id: String,
        override val label: String?,
        val value: Boolean,
        override val action: UiActionId?,
    ) : UiComponent

    data class Select(
        override val id: String,
        override val label: String?,
        val value: String,
        val options: List<UiSelectOption>,
        override val action: UiActionId?,
    ) : UiComponent

    data class Button(
        override val id: String,
        override val label: String?,
        override val action: UiActionId?,
    ) : UiComponent

    data class PermissionRequest(
        override val id: String,
        override val label: String?,
        val capability: String,
        override val action: UiActionId?,
    ) : UiComponent

    data class CapabilityPicker(
        override val id: String,
        override val label: String?,
        val capability: String,
        override val action: UiActionId?,
    ) : UiComponent
}

/**
 * One named place where a plugin may contribute UI: a settings group or a
 * status card.
 */
data class UiContribution(
    val id: String,
    val title: String?,
    val root: UiComponent,
)

object DeclarativeUiSchema {
    internal const val MAX_COMPONENTS = 256
    internal const val MAX_DEPTH = 8
    internal const val MAX_TEXT_LENGTH = 4_096
    internal const val MAX_CAPABILITY_LENGTH = 256

    /**
     * Content that would smuggle markup, script, a navigation target or a host
     * type through a field the whitelist does not otherwise describe.
     */
    internal val FORBIDDEN_FRAGMENTS = listOf(
        "<script",
        "<html",
        "javascript:",
        "intent:",
        "webview:",
        "file://",
        "content://",
    )

    /**
     * Parses one UI contribution.
     *
     * Unknown fields are rejected rather than ignored: a plugin that names a
     * field the schema does not describe is either newer than this host or
     * hostile, and silently rendering the rest would hide both.
     *
     * @throws UiRejected when the contribution cannot be rendered safely.
     */
    fun parse(text: String): UiContribution {
        val root = Json.parse(text)
        val obj = root as? Json.JObject ?: throw UiRejected("UI_NOT_OBJECT")

        val id = obj.requireString("id")
        val title = obj.optionalString("title")
        val allowed = setOf("id", "title", "root")
        obj.rejectUnknownFields(allowed, "contribution")

        val rootComponent = obj.field("root") as? Json.JObject
            ?: throw UiRejected("UI_MISSING_ROOT")

        val counter = ComponentCounter()
        val component = parseComponent(rootComponent, depth = 1, counter = counter)
        return UiContribution(id = id, title = title, root = component)
    }

    private fun parseComponent(
        node: Json.JObject,
        depth: Int,
        counter: ComponentCounter,
    ): UiComponent {
        if (depth > MAX_DEPTH) throw UiRejected("UI_TOO_DEEP")
        if (!counter.take()) throw UiRejected("UI_TOO_MANY_COMPONENTS")

        val kindId = node.requireString("type")
        val kind = UiComponentKind.byId(kindId) ?: throw UiRejected("UI_UNKNOWN_COMPONENT:$kindId")
        val id = node.requireString("id")
        node.rejectUnknownFields(ALLOWED_FIELDS.getValue(kind), "component")

        val label = node.optionalString("label")?.also { checkText(it, "label") }
        val action = node.optionalString("action")?.let { actionId ->
            val parsed = UiActionId.byId(actionId) ?: throw UiRejected("UI_UNKNOWN_ACTION:$actionId")
            if (parsed !in ALLOWED_ACTIONS.getValue(kind)) {
                throw UiRejected("UI_ACTION_NOT_ALLOWED:${kind.id}:$actionId")
            }
            parsed
        }

        return when (kind) {
            UiComponentKind.SECTION -> {
                val childrenNode = node.field("children") as? Json.JArray
                    ?: throw UiRejected("UI_MISSING_CHILDREN")
                UiComponent.Section(
                    id = id,
                    label = label,
                    children = childrenNode.items.map { child ->
                        val childObject = child as? Json.JObject
                            ?: throw UiRejected("UI_CHILD_NOT_OBJECT")
                        parseComponent(childObject, depth + 1, counter)
                    },
                )
            }

            UiComponentKind.TEXT -> UiComponent.Text(
                id = id,
                label = label,
                value = node.requireString("value").also { checkText(it, "value") },
                action = action,
            )

            UiComponentKind.STATUS -> {
                val severityId = node.requireString("severity")
                val severity = UiSeverity.byId(severityId)
                    ?: throw UiRejected("UI_UNKNOWN_SEVERITY:$severityId")
                UiComponent.Status(
                    id = id,
                    label = label,
                    value = node.requireString("value").also { checkText(it, "value") },
                    severity = severity,
                    action = action,
                )
            }

            UiComponentKind.TOGGLE -> UiComponent.Toggle(
                id = id,
                label = label,
                value = (node.field("value") as? Json.JBoolean
                    ?: throw UiRejected("UI_MISSING_VALUE")).value,
                action = action,
            )

            UiComponentKind.SELECT -> {
                val optionsNode = node.field("options") as? Json.JArray
                    ?: throw UiRejected("UI_MISSING_OPTIONS")
                if (optionsNode.items.isEmpty()) throw UiRejected("UI_EMPTY_OPTIONS")
                val options = optionsNode.items.map { option ->
                    val optionObject = option as? Json.JObject
                        ?: throw UiRejected("UI_OPTION_NOT_OBJECT")
                    optionObject.rejectUnknownFields(setOf("id", "label"), "option")
                    UiSelectOption(
                        id = optionObject.requireString("id"),
                        label = optionObject.requireString("label")
                            .also { checkText(it, "option.label") },
                    )
                }
                val value = node.requireString("value").also { checkText(it, "value") }
                if (options.none { it.id == value }) throw UiRejected("UI_VALUE_NOT_AN_OPTION")
                UiComponent.Select(
                    id = id,
                    label = label,
                    value = value,
                    options = options,
                    action = action,
                )
            }

            UiComponentKind.BUTTON -> UiComponent.Button(id = id, label = label, action = action)

            UiComponentKind.PERMISSION_REQUEST,
            UiComponentKind.CAPABILITY_PICKER,
            -> {
                val capability = node.requireString("capability")
                    .also { checkCapability(it) }
                if (kind == UiComponentKind.PERMISSION_REQUEST) {
                    UiComponent.PermissionRequest(
                        id = id,
                        label = label,
                        capability = capability,
                        action = action,
                    )
                } else {
                    UiComponent.CapabilityPicker(
                        id = id,
                        label = label,
                        capability = capability,
                        action = action,
                    )
                }
            }
        }
    }

    /**
     * The strict field set per component kind. Listing the allowed names is what
     * rejects HTML, script, address, class, Intent and expression fields: there
     * is no name under which they are accepted.
     */
    private val ALLOWED_FIELDS: Map<UiComponentKind, Set<String>> = mapOf(
        UiComponentKind.SECTION to setOf("type", "id", "label", "children"),
        UiComponentKind.TEXT to setOf("type", "id", "label", "value", "action"),
        UiComponentKind.STATUS to setOf("type", "id", "label", "value", "severity", "action"),
        UiComponentKind.TOGGLE to setOf("type", "id", "label", "value", "action"),
        UiComponentKind.SELECT to setOf("type", "id", "label", "value", "options", "action"),
        UiComponentKind.BUTTON to setOf("type", "id", "label", "action"),
        UiComponentKind.PERMISSION_REQUEST to setOf("type", "id", "label", "capability", "action"),
        UiComponentKind.CAPABILITY_PICKER to setOf("type", "id", "label", "capability", "action"),
    )

    internal fun checkText(value: String, field: String) {
        if (value.length > MAX_TEXT_LENGTH) throw UiRejected("UI_TEXT_TOO_LONG:$field")
        val lowered = value.lowercase()
        val fragment = FORBIDDEN_FRAGMENTS.firstOrNull { it in lowered }
        if (fragment != null) throw UiRejected("UI_FORBIDDEN_CONTENT:$field:$fragment")
    }

    /**
     * Capability identifiers are reverse-domain names with an optional version
     * suffix; anything else is not addressable by the kernel's grant store.
     */
    internal fun checkCapability(value: String) {
        if (value.length > MAX_CAPABILITY_LENGTH) throw UiRejected("UI_CAPABILITY_TOO_LONG")
        if (!CAPABILITY_PATTERN.matches(value)) throw UiRejected("UI_BAD_CAPABILITY:$value")
        val lowered = value.lowercase()
        val fragment = FORBIDDEN_FRAGMENTS.firstOrNull { it in lowered }
        if (fragment != null) throw UiRejected("UI_FORBIDDEN_CONTENT:capability:$fragment")
    }

    private val CAPABILITY_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9-]+)+(@[0-9]+\\.[0-9]+\\.[0-9]+)?$")

    private class ComponentCounter {
        private var remaining = MAX_COMPONENTS
        fun take(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }
}

private fun Json.JObject.rejectUnknownFields(allowed: Set<String>, subject: String) {
    val unknown = fields.map { it.first }.firstOrNull { it !in allowed }
    if (unknown != null) throw UiRejected("UI_UNKNOWN_FIELD:$subject:$unknown")
}

private fun Json.JObject.requireString(name: String): String =
    (field(name) as? Json.JString)?.value ?: throw UiRejected("UI_MISSING_FIELD:$name")

private fun Json.JObject.optionalString(name: String): String? {
    val value = field(name) ?: return null
    return (value as? Json.JString)?.value ?: throw UiRejected("UI_BAD_FIELD_TYPE:$name")
}

/**
 * Minimal JSON reader.
 *
 * The platform `org.json` is replaced by a throwing stub in local JVM unit
 * tests, so schema parsing cannot depend on it.
 */
sealed interface Json {
    data class JObject(val fields: List<Pair<String, Json>>) : Json {
        fun field(name: String): Json? = fields.firstOrNull { it.first == name }?.second
    }

    data class JArray(val items: List<Json>) : Json
    data class JString(val value: String) : Json
    data class JBoolean(val value: Boolean) : Json
    object JNull : Json

    companion object {
        fun parse(text: String): Json {
            val cursor = Cursor(text)
            val value = cursor.readValue()
            cursor.skipWhitespace()
            if (!cursor.isAtEnd()) throw UiRejected("UI_JSON_INVALID:trailingContent")
            return value
        }
    }

    private class Cursor(private val text: String) {
        var index = 0

        fun isAtEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Json {
            skipWhitespace()
            if (isAtEnd()) throw UiRejected("UI_JSON_INVALID:unexpectedEnd")
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JString(readString())
                't' -> { expectLiteral("true"); JBoolean(true) }
                'f' -> { expectLiteral("false"); JBoolean(false) }
                'n' -> { expectLiteral("null"); JNull }
                else -> throw UiRejected("UI_JSON_INVALID:unexpectedToken")
            }
        }

        private fun readObject(): Json {
            expect('{')
            val fields = mutableListOf<Pair<String, Json>>()
            skipWhitespace()
            if (peek() == '}') { index++; return JObject(fields) }
            while (true) {
                skipWhitespace()
                val name = readString()
                skipWhitespace()
                expect(':')
                fields += name to readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return JObject(fields) }
                    else -> throw UiRejected("UI_JSON_INVALID:objectSeparator")
                }
            }
        }

        private fun readArray(): Json {
            expect('[')
            val items = mutableListOf<Json>()
            skipWhitespace()
            if (peek() == ']') { index++; return JArray(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return JArray(items) }
                    else -> throw UiRejected("UI_JSON_INVALID:arraySeparator")
                }
            }
        }

        private fun peek(): Char {
            if (isAtEnd()) throw UiRejected("UI_JSON_INVALID:unexpectedEnd")
            return text[index]
        }

        private fun expect(char: Char) {
            if (peek() != char) throw UiRejected("UI_JSON_INVALID:expected:$char")
            index++
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (isAtEnd()) throw UiRejected("UI_JSON_INVALID:unterminatedString")
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (isAtEnd()) throw UiRejected("UI_JSON_INVALID:badEscape")
                        when (val escaped = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw UiRejected("UI_JSON_INVALID:badUnicode")
                                out.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw UiRejected("UI_JSON_INVALID:badEscape")
                        }
                    }

                    else -> out.append(char)
                }
            }
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) throw UiRejected("UI_JSON_INVALID:literal")
            index += literal.length
        }
    }
}
