package com.metavirtuoso.sundial.ui

/** The screen the instrument is fitted to. Watches get their own geometry, labels and chrome. */
enum class InstrumentLayout {
    PHONE,
    WATCH_ROUND,
    WATCH_RECT;

    val isWatch: Boolean get() = this != PHONE
}
