package dev.docuconf.kotlin.core

/** The stable error codes of SPEC §11.2 item 5. */
public object Codes {
    public const val MISSING_REQUIRED: String = "missing_required"
    public const val INVALID_TYPE: String = "invalid_type"
    public const val OUT_OF_RANGE: String = "out_of_range"
    public const val PATTERN_MISMATCH: String = "pattern_mismatch"
    public const val NOT_IN_ENUM: String = "not_in_enum"
    public const val INVALID_SCHEME: String = "invalid_scheme"
    public const val TOO_FEW_ITEMS: String = "too_few_items"
    public const val TOO_MANY_ITEMS: String = "too_many_items"
    public const val FILE_MISSING: String = "file_missing"
    public const val FILE_UNREADABLE: String = "file_unreadable"
    public const val FILE_TOO_LARGE: String = "file_too_large"
    public const val FILE_MALFORMED: String = "file_malformed"
    public const val SCHEMA_MISMATCH: String = "schema_mismatch"
    public const val CERTIFICATE_INVALID: String = "certificate_invalid"
    public const val CERTIFICATE_EXPIRING: String = "certificate_expiring"
    public const val CERTIFICATE_NAME_MISMATCH: String = "certificate_name_mismatch"
    public const val KEY_MISMATCH: String = "key_mismatch"
    public const val KEYSTORE_UNREADABLE: String = "keystore_unreadable"
}

/**
 * One problem with one input. [input] is a variable name or a file input name. Messages never
 * contain the value of a secret.
 */
public data class Violation(val code: String, val input: String, val message: String) {
    override fun toString(): String = "$input: $code: $message"
}

/**
 * Thrown at boot with every violation found, not just the first. Its message is the report printed
 * on a failed boot:
 *
 * ```
 * docuconf: 2 configuration problems:
 *   PORT: out_of_range: "0" is below min 1
 *   DATABASE_URL: missing_required: required, but not set
 * ```
 */
public class ConfigViolationException(public val violations: List<Violation>) :
    RuntimeException(format(violations)) {
    public companion object {
        public fun format(violations: List<Violation>): String =
            "docuconf: ${violations.size} configuration problem${if (violations.size == 1) "" else "s"}:\n" +
                violations.joinToString("\n") { "  $it" }
    }
}
