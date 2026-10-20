package com.openandroidintelligence.mobile.assistant

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor

internal fun Bundle.parcelFileDescriptor(key: String): ParcelFileDescriptor? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, ParcelFileDescriptor::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable<ParcelFileDescriptor>(key)
    }
