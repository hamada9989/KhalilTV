package com.aeriotv.android.core.sync

import com.aeriotv.android.BuildConfig

/**
 * Drive sync configuration. The OAuth 2.0 Web Client ID is sourced from
 * BuildConfig at build time (Gradle reads it from `local.properties`
 * via the `GOOGLE_DRIVE_WEB_CLIENT_ID=...` key or the environment variable
 * of the same name). Keeping it out of source means git stays clean of
 * project-specific OAuth credentials.
 *
 * To enable Drive Sync on a build:
 *   1. Create a Google Cloud project, enable Drive API, set up the OAuth
 *      consent screen with the `https://www.googleapis.com/auth/drive.appdata`
 *      scope.
 *   2. Create OAuth client IDs: one Android (signing-cert SHA-1 registered)
 *      and one Web (used here as the server client id for the GoogleIdToken
 *      request).
 *   3. Add `GOOGLE_DRIVE_WEB_CLIENT_ID=<your-web-client-id>` to
 *      `local.properties` (gitignored) and rebuild.
 */
object SyncConfig {

    val WEB_CLIENT_ID: String get() = BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID

    fun isConfigured(): Boolean = WEB_CLIENT_ID.isNotBlank()

    /** Drive REST AppData scope. Files written here are scoped per-app. */
    const val DRIVE_APPDATA_SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"
}
