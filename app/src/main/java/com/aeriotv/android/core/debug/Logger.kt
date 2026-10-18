package com.aeriotv.android.core.debug

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Top-level `debugLog(tag, message)` shortcut so call sites all over the
 * app don't have to inject a Hilt-provided DebugLogger. Mirrors iOS
 * `debugLog(_:category:level:)` (Aerio/Shared/DebugLogger.swift line 12).
 *
 * Pulls the singleton DebugLogger out of the application graph the first
 * time it's invoked; cached after that. Safe to call from non-Composable,
 * non-coroutine contexts.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugLoggerEntryPoint {
    fun debugLogger(): DebugLogger
}

private object LoggerCache {
    @Volatile
    var instance: DebugLogger? = null
}

private fun resolve(context: Context): DebugLogger {
    LoggerCache.instance?.let { return it }
    return synchronized(LoggerCache) {
        LoggerCache.instance ?: run {
            val app = context.applicationContext
            val entry = EntryPointAccessors.fromApplication(app, DebugLoggerEntryPoint::class.java)
            entry.debugLogger().also { LoggerCache.instance = it }
        }
    }
}

fun debugLog(context: Context, tag: String, message: String) =
    resolve(context).log(tag, DebugLogger.Level.INFO, message)

fun debugLogWarn(context: Context, tag: String, message: String, t: Throwable? = null) =
    resolve(context).log(tag, DebugLogger.Level.WARN, message, t)

fun debugLogError(context: Context, tag: String, message: String, t: Throwable? = null) =
    resolve(context).log(tag, DebugLogger.Level.ERROR, message, t)
