package com.openandroidintelligence.conversation.model

private val WIRE_ID_REGEX = Regex("^[A-Za-z0-9._~-]+$")

@JvmInline
value class ApprovalId(val value: String) {
    init {
        require(value.isNotBlank()) { "ApprovalId cannot be blank" }
        require(WIRE_ID_REGEX.matches(value)) { "ApprovalId contains invalid characters: $value" }
    }
}

/** Which tier one button offers (contract §7.2). `UNKNOWN` means "not guessed". */
enum class ApprovalChoice(val wireValue: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(value: String?): ApprovalChoice =
            entries.firstOrNull { it.wireValue == value?.trim() } ?: UNKNOWN
    }
}

/** How one button is drawn. A presentation hint from the Gateway, never a tier. */
enum class ApprovalOptionStyle(val wireValue: String) {
    /** The emphasised choice: allow this one command. */
    PRIMARY("primary"),

    /** A quieter grant that lasts longer than one command. */
    SECONDARY("secondary"),

    /** The refusal: destructive semantics, never the default press. */
    DANGER("danger"),

    NEUTRAL("neutral"),
    ;

    companion object {
        fun of(value: String?): ApprovalOptionStyle =
            entries.firstOrNull { it.wireValue == value?.trim() } ?: NEUTRAL
    }
}

/** What the Gateway settled an approval as, including the two it owns. */
enum class ApprovalOutcome(val wireValue: String) {
    ALLOWED_ONCE("once"),
    ALLOWED_SESSION("session"),
    ALLOWED_ALWAYS("always"),
    DENIED("deny"),
    /** Nobody answered in time; the command did not run. */
    TIMED_OUT("timeout"),
    /** Nobody could be given the chance to answer; the command did not run. */
    WITHDRAWN("withdrawn"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(value: String?): ApprovalOutcome =
            entries.firstOrNull { it.wireValue == value?.trim() } ?: UNKNOWN
    }
}

/**
 * The outcome a press on this tier means, in the Gateway's own vocabulary.
 *
 * The four tiers a client may press share one `wireValue` set across both enums,
 * so this is a translation of the same token rather than a second table that has
 * to be kept in step by hand.
 */
fun ApprovalChoice.toOutcome(): ApprovalOutcome = ApprovalOutcome.of(wireValue)

/**
 * The tier this outcome names, or `null` when only the Gateway could have
 * produced it: `timeout`, `withdrawn` and an unrecognised value are not tiers a
 * phone ever pressed.
 */
val ApprovalOutcome.asChoice: ApprovalChoice?
    get() = ApprovalChoice.of(wireValue).takeIf { it != ApprovalChoice.UNKNOWN }

data class ApprovalOption(
    val choice: ApprovalChoice,
    /** The Gateway's own wording when it sends one; otherwise the phone localises. */
    val label: String? = null,
    val style: ApprovalOptionStyle = ApprovalOptionStyle.NEUTRAL,
)

/**
 * How urgent the host considers the command it is asking about.
 *
 * A closed set the shared fixture fixes at `info | elevated | critical`: a value
 * outside it is not a fifth tier this phone may invent, so [of] answers `null`
 * and the card simply draws no severity line.
 */
enum class ApprovalSeverity(val wireValue: String) {
    INFO("info"),
    ELEVATED("elevated"),
    CRITICAL("critical"),
    ;

    companion object {
        fun of(value: String?): ApprovalSeverity? =
            entries.firstOrNull { it.wireValue == value?.trim() }
    }
}

/**
 * One command-execution approval the Gateway asked the phone to decide.
 *
 * Only [approvalId] is an authority: the host's session key and request id stay
 * on the Gateway, so a client can answer the card it was shown and nothing else
 * (contract §7.2). [options] is whatever the Gateway offered — a smart deny has
 * two tiers, a full prompt has four — and the UI must render exactly those.
 */
data class ApprovalRequest(
    val approvalId: ApprovalId,
    val conversationId: ConversationId?,
    val command: String,
    val reason: String,
    val severity: ApprovalSeverity? = null,
    val options: List<ApprovalOption>,
    val timeoutSeconds: Long,
    val requestedAt: Long,
    /**
     * When the Gateway stops counting.
     *
     * The Gateway's own `expiresAt` is authoritative when it sends one; the
     * derived value only covers a payload that omits it, so the phone never
     * counts down to a deadline the Gateway does not share.
     */
    val expiresAt: Long = requestedAt + timeoutSeconds.coerceAtLeast(0L) * 1_000L,
)

/**
 * The answer to one decision submission, as a closed set.
 *
 * `SUBMITTED` is the only success. `ALREADY_RESOLVED` and `EXPIRED` are facts
 * about the approval rather than transport failures: another device may have
 * answered it, or the window may have closed while the press was in flight.
 */
enum class ApprovalSubmissionOutcome {
    SUBMITTED,
    ALREADY_RESOLVED,
    EXPIRED,
    NOT_FOUND,
    UNSUPPORTED,
    FAILED,
}

data class ApprovalSubmissionResult(
    val outcome: ApprovalSubmissionOutcome,
    /** The tier the Gateway recorded, when it answered with one. */
    val choice: ApprovalChoice? = null,
    val errorCode: String? = null,
    /**
     * The terminal outcome the Gateway already recorded, when the press arrived
     * after the approval was settled by [ApprovalOutcome.TIMED_OUT] or
     * [ApprovalOutcome.WITHDRAWN].
     *
     * Those two are not tiers a client may submit, so they cannot be expressed
     * as a [choice]; without this the card would be left reading "unknown" even
     * though the Gateway told the phone exactly what happened.
     */
    val settledOutcome: ApprovalOutcome? = null,
)
