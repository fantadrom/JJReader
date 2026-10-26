package com.fantadrom.jjreader.engine

/** Stable local categories only; never export exception messages or service/user payloads. */
enum class RestoreCode {
    ANCHOR_SCROLL, STALE_DOM, ANCHOR_NOT_VISIBLE, LAYOUT_TIMEOUT, LAYOUT_UNSTABLE, RESTORE_INTERRUPTED,
    ACK_TIMEOUT, DOM_TIMEOUT, DOM_MODEL_MISMATCH, READIUM_SCRIPT_MISSING, RESOURCE_NAVIGATION,
    ANCHOR_BOOK, ANCHOR_HASH, ANCHOR_QUOTE, ANCHOR_RANGE, ANCHOR_RESOURCE, JS_RESULT, JS_ERROR,
    CANCELLED, STALE_REVISION, SAVE_ERROR, INTERNAL_ERROR
}
class RestoreFailure(val code: RestoreCode) : IllegalStateException(code.name)
