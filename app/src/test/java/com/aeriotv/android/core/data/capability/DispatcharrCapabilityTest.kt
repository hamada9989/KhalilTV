package com.aeriotv.android.core.data.capability

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the derivation to Dispatcharr's OWN resolution rules. These defaults
 * are easy to get backwards, and getting them backwards silently hides
 * features from users who have them:
 *
 *  - dvr_access ABSENT = "view" (apps/channels/dvr_access.py), not "none".
 *  - vod_movies_enabled / vod_series_enabled ABSENT = ENABLED
 *    (apps/vod/utils.py); only an explicit false disables.
 *  - is_staff / is_superuser read as effective level 10.
 *  - No snapshot at all = Unknown = the affordance stays ENABLED.
 */
class DispatcharrCapabilityTest {

    private fun snapshot(
        level: Int = 1,
        staff: Boolean = false,
        superuser: Boolean = false,
        props: String = "{}",
        systemCatchup: Boolean? = true,
        schema: Int = CAPABILITIES_SCHEMA,
        fetchedAt: Long = 1_000L,
    ) = CapabilitySnapshot(
        userLevel = level,
        isStaff = staff,
        isSuperuser = superuser,
        customPropertiesJson = props,
        fetchedAtMillis = fetchedAt,
        schema = schema,
        isStale = false,
        systemCatchupEnabled = systemCatchup,
    )

    @Test
    fun `no snapshot leaves every capability unknown and allowed`() {
        val caps = deriveCapabilities(null)
        for (capability in Capability.entries) {
            assertEquals(CapabilityState.Unknown, caps[capability])
            assert(caps.allows(capability)) { "$capability must render enabled when unknown" }
        }
    }

    @Test
    fun `an older schema is treated as no snapshot so upgrades re-probe`() {
        val caps = deriveCapabilities(snapshot(schema = CAPABILITIES_SCHEMA - 1))
        assertEquals(CapabilityState.Unknown, caps[Capability.CanManageDvr])
    }

    @Test
    fun `standard account with absent dvr_access can view but not manage`() {
        val caps = deriveCapabilities(snapshot(level = 1, props = "{}"))
        assertEquals(CapabilityState.Allowed, caps[Capability.CanViewDvr])
        assertEquals(CapabilityState.Denied, caps[Capability.CanManageDvr])
    }

    @Test
    fun `standard account with dvr_access manage can manage without being admin`() {
        val caps = deriveCapabilities(
            snapshot(level = 1, props = """{"dvr_access":"manage"}"""),
        )
        assertEquals(CapabilityState.Allowed, caps[Capability.CanManageDvr])
        // Still not an admin: change_stream stays IsAdmin server-side.
        assertEquals(CapabilityState.Denied, caps[Capability.CanSwitchStream])
    }

    @Test
    fun `dvr_access none denies viewing`() {
        val caps = deriveCapabilities(snapshot(level = 1, props = """{"dvr_access":"none"}"""))
        assertEquals(CapabilityState.Denied, caps[Capability.CanViewDvr])
        assertEquals(CapabilityState.Denied, caps[Capability.CanManageDvr])
    }

    @Test
    fun `streamer level below one has no dvr and cannot read settings`() {
        val caps = deriveCapabilities(snapshot(level = 0, props = """{"dvr_access":"manage"}"""))
        assertEquals(CapabilityState.Denied, caps[Capability.CanViewDvr])
        assertEquals(CapabilityState.Denied, caps[Capability.CanReadServerSettings])
    }

    @Test
    fun `admin level ten manages dvr regardless of custom properties`() {
        val caps = deriveCapabilities(snapshot(level = 10, props = """{"dvr_access":"none"}"""))
        assertEquals(CapabilityState.Allowed, caps[Capability.CanManageDvr])
        assertEquals(CapabilityState.Allowed, caps[Capability.CanSwitchStream])
    }

    @Test
    fun `staff and superuser read as effective level ten`() {
        assertEquals(10, deriveCapabilities(snapshot(level = 0, staff = true)).effectiveUserLevel)
        assertEquals(10, deriveCapabilities(snapshot(level = 0, superuser = true)).effectiveUserLevel)
        assertEquals(
            CapabilityState.Allowed,
            deriveCapabilities(snapshot(level = 0, superuser = true))[Capability.CanManageDvr],
        )
    }

    @Test
    fun `vod flags default to enabled and only an explicit false denies`() {
        val absent = deriveCapabilities(snapshot(props = "{}"))
        assertEquals(CapabilityState.Allowed, absent[Capability.CanViewVod])
        assertEquals(CapabilityState.Allowed, absent[Capability.CanViewSeries])

        val disabled = deriveCapabilities(
            snapshot(props = """{"vod_movies_enabled":false,"vod_series_enabled":false}"""),
        )
        assertEquals(CapabilityState.Denied, disabled[Capability.CanViewVod])
        assertEquals(CapabilityState.Denied, disabled[Capability.CanViewSeries])
    }

    @Test
    fun `catchup needs both the per-user flag and the system setting`() {
        assertEquals(
            CapabilityState.Allowed,
            deriveCapabilities(snapshot(systemCatchup = true))[Capability.CanUseCatchup],
        )
        assertEquals(
            CapabilityState.Denied,
            deriveCapabilities(snapshot(props = """{"catchup_enabled":false}"""))[Capability.CanUseCatchup],
        )
        assertEquals(
            CapabilityState.Denied,
            deriveCapabilities(snapshot(systemCatchup = false))[Capability.CanUseCatchup],
        )
        // System flag unreadable: unknown, so the affordance stays.
        val unknown = deriveCapabilities(snapshot(systemCatchup = null))
        assertEquals(CapabilityState.Unknown, unknown[Capability.CanUseCatchup])
        assert(unknown.allows(Capability.CanUseCatchup))
    }

    @Test
    fun `an unknown custom properties key never breaks derivation`() {
        val caps = deriveCapabilities(
            snapshot(props = """{"some_future_key":{"nested":[1,2]},"dvr_access":"view"}"""),
        )
        assertEquals(CapabilityState.Allowed, caps[Capability.CanViewDvr])
        assertEquals(CapabilityState.Denied, caps[Capability.CanManageDvr])
    }

    @Test
    fun `malformed custom properties falls back to server defaults`() {
        val caps = deriveCapabilities(snapshot(props = "not json at all"))
        assertEquals(CapabilityState.Allowed, caps[Capability.CanViewDvr])
        assertEquals(CapabilityState.Allowed, caps[Capability.CanViewVod])
    }

    @Test
    fun `a session correction overrides the derived state`() {
        val id = "playlist-under-test"
        CapabilityCorrections.clear(id)
        val base = deriveCapabilities(snapshot(level = 1))
        assertEquals(CapabilityState.Denied, base[Capability.CanManageDvr])
        CapabilityCorrections.record(id, Capability.CanManageDvr, CapabilityState.Allowed)
        val corrected = CapabilityCorrections.apply(id, base)
        assertEquals(CapabilityState.Allowed, corrected[Capability.CanManageDvr])
        CapabilityCorrections.clear(id)
        assertEquals(CapabilityState.Denied, CapabilityCorrections.apply(id, base)[Capability.CanManageDvr])
    }
}
