package com.streamflixreborn.streamflix.utils

/**
 * Secure access to secret keys via C++ library compiled with NDK.
 * Strings are XOR-encrypted at compile-time in the .so — not extractable
 * with a standard Java/Kotlin decompiler.
 */
object Keys {
    private val isLoaded = try {
        System.loadLibrary("streamflix-keys")
        true
    } catch (_: Throwable) {
        false
    }

    fun getUprotApiBase(): String = if (isLoaded) runCatching { nativeGetUprotApiBase() }.getOrDefault("") else ""
    fun getUprotDirectApiBase(): String = if (isLoaded) runCatching { nativeGetUprotDirectApiBase() }.getOrDefault("") else ""
    fun getUprotDirectKey(): String = if (isLoaded) runCatching { nativeGetUprotDirectKey() }.getOrDefault("") else ""
    fun getUprotEntryUrl(): String = if (isLoaded) runCatching { nativeGetUprotEntryUrl() }.getOrDefault("") else ""
    fun getUprotJsName(): String = if (isLoaded) runCatching { nativeGetUprotJsName() }.getOrDefault("") else ""
    fun getUprotSearchToken(): String = if (isLoaded) runCatching { nativeGetUprotSearchToken() }.getOrDefault("") else ""

    private external fun nativeGetUprotApiBase(): String
    private external fun nativeGetUprotDirectApiBase(): String
    private external fun nativeGetUprotDirectKey(): String
    private external fun nativeGetUprotEntryUrl(): String
    private external fun nativeGetUprotJsName(): String
    private external fun nativeGetUprotSearchToken(): String
}

