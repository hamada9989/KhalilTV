package com.aeriotv.android.core.cast

import android.content.Context
import com.aeriotv.android.BuildConfig
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Sender-side Cast configuration (GH #33). The Cast framework instantiates this
 * (named by the OPTIONS_PROVIDER_CLASS_NAME manifest meta-data) the first time
 * CastContext.getSharedInstance() runs, to learn which receiver App ID to launch
 * and how.
 *
 * setAndroidReceiverCompatible(true) is the flag that makes Cast Connect work:
 * without it the framework launches the Cast WEB receiver even when the target
 * is a Chromecast-with-Google-TV, which cannot play AerioTV's raw MPEG-TS. With
 * it, when the target is an Android TV device that has AerioTV installed, the
 * framework launches the AerioTV Android-TV app as the receiver so playback runs
 * through the app's own ExoPlayer (raw TS + ffmpeg AC-3).
 *
 * The receiver App ID comes from BuildConfig.CAST_RECEIVER_APP_ID, which is
 * empty until the owner registers a Cast App ID in the Cast Developer Console
 * and associates this package. CastContext is only ever warmed when that id is
 * non-blank (see MainActivity), so getCastOptions() is not reached with an empty
 * id in practice; the DEFAULT_MEDIA_RECEIVER fallback below is defence in depth
 * so a stray CastContext request can never crash on an invalid application id.
 */
class AerioCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val receiverAppId = BuildConfig.CAST_RECEIVER_APP_ID.ifBlank {
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        }

        val launchOptions = LaunchOptions.Builder()
            // Cast Connect back ON (Logan 2026-09-13), measured basis: the Cast
            // web receiver's Chromium renderer presents only about 46 fps with
            // double-vsync intervals on a Google TV Streamer at 720p60 and
            // 1080p60, a ceiling every web-receiver app shares, while the native
            // Android TV app renders a full 60 fps. So a target that HAS AerioTV
            // installed must run the native receiver: the framework launches the
            // AerioTV Android TV app, which tunes the channel itself (no phone
            // proxy, no output profile, AC-3 passthrough exactly as in normal
            // playback). Targets without the app (legacy Chromecast dongles, Nest
            // displays) are unaffected: the framework falls back to the web
            // receiver on its own and the sender keeps its phone-local HLS proxy
            // path (see AerioCastSender's receiver-type handshake).
            .setAndroidReceiverCompatible(true)
            // No CredentialsData: the AerioTV Android TV receiver authenticates
            // nothing. It validates an incoming load against ITS OWN playlist and
            // effective base (AerioCastReceiverController -> resolveForPlayback),
            // so the sender never ships credentials or a resolved stream URL.
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(receiverAppId)
            .setLaunchOptions(launchOptions)
            // The framework's own notification/lock-screen media UI is redundant
            // with AerioTV's MediaSession-driven controls; leave the defaults.
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
