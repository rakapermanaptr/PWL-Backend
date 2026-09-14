package id.primawash.api.common

/**
 * Every business error code of the API (PRD Lampiran B) plus the transport-level ones from §6.2.
 *
 * Only the codes live here — the Indonesian `message` text belongs to the Service that raises the
 * error, because most messages are parameterised ("Shift Cabang Tebet belum dibuka — …").
 * Inventing a code or rewording a message requires a PRD update and a heads-up to the Android team:
 * the client displays both verbatim.
 */
@Suppress("TooManyFunctions")
object ErrorCodes {
    // Transport level (PRD §6.2)
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val FORBIDDEN = "FORBIDDEN"
    const val BRANCH_SCOPE = "BRANCH_SCOPE"
    const val NOT_FOUND = "NOT_FOUND"
    const val APP_UPDATE_REQUIRED = "APP_UPDATE_REQUIRED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"

    // Branch & device
    const val BRANCH_REQUIRED = "BRANCH_REQUIRED"
    const val BRANCH_INACTIVE = "BRANCH_INACTIVE"
    const val BRANCH_IN_USE = "BRANCH_IN_USE"
    const val OWNER_ONLY = "OWNER_ONLY"

    // M1 additions — not in Lampiran B yet; recorded in docs/prd-gaps-m1.md for the PRD and Android.
    const val DEVICE_UNAUTHORIZED = "DEVICE_UNAUTHORIZED"
    const val ACTIVATION_CODE_INVALID = "ACTIVATION_CODE_INVALID"
    const val STAFF_REQUIRED = "STAFF_REQUIRED"

    // PIN & staff
    const val PIN_FORMAT = "PIN_FORMAT"
    const val PIN_UNKNOWN = "PIN_UNKNOWN"
    const val PIN_WRONG = "PIN_WRONG"
    const val PIN_TAKEN = "PIN_TAKEN"
    const val PIN_CONFLICT = "PIN_CONFLICT"
    const val PIN_LOCKED = "PIN_LOCKED"
    const val STAFF_INACTIVE = "STAFF_INACTIVE"
    const val STAFF_WRONG_BRANCH = "STAFF_WRONG_BRANCH"
    const val STAFF_SELF_DEACTIVATE = "STAFF_SELF_DEACTIVATE"
    const val LAST_OWNER = "LAST_OWNER"
    const val CASHIER_NEEDS_BRANCH = "CASHIER_NEEDS_BRANCH"

    // Shift & cash
    const val SHIFT_NOT_OPEN = "SHIFT_NOT_OPEN"
    const val SHIFT_ALREADY_OPEN = "SHIFT_ALREADY_OPEN"
    const val SHIFT_CLOSED = "SHIFT_CLOSED"
    const val OPENING_CASH_REQUIRED = "OPENING_CASH_REQUIRED"
    const val CASH_LABEL_REQUIRED = "CASH_LABEL_REQUIRED"
    const val AMOUNT_REQUIRED = "AMOUNT_REQUIRED"

    // Order & loyalty
    const val EMPTY_CART = "EMPTY_CART"
    const val INVALID_ITEM = "INVALID_ITEM"
    const val PRICE_CHANGED = "PRICE_CHANGED"
    const val TOTAL_MISMATCH = "TOTAL_MISMATCH"
    const val INSUFFICIENT_POINTS = "INSUFFICIENT_POINTS"
    const val REWARD_NOT_ELIGIBLE = "REWARD_NOT_ELIGIBLE"
    const val REDEEM_OFFLINE = "REDEEM_OFFLINE"
    const val STATUS_CHANGED = "STATUS_CHANGED"

    // Customer & catalog
    const val PHONE_INVALID = "PHONE_INVALID"
    const val PHONE_ALREADY_REGISTERED = "PHONE_ALREADY_REGISTERED"
    const val NAME_REQUIRED = "NAME_REQUIRED"
    const val SERVICE_NAME_TAKEN = "SERVICE_NAME_TAKEN"
    const val PRICE_REQUIRED = "PRICE_REQUIRED"
    const val RATE_INVALID = "RATE_INVALID"
    const val REWARD_INVALID = "REWARD_INVALID"
    const val TARGET_REQUIRED = "TARGET_REQUIRED"

    // Customer import
    const val IMPORT_EMPTY = "IMPORT_EMPTY"
    const val IMPORT_EXCEL = "IMPORT_EXCEL"
    const val IMPORT_EXPIRED = "IMPORT_EXPIRED"
}
