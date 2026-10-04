package org.offlinemesh.app.transport

import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAwareProbeTest {

    private val supported = WifiAwareProbe.Snapshot(
        model = "Google Pixel 8", sdkInt = 35, hasFeature = true, available = true,
        maxDataPaths = 8, maxDataInterfaces = 2, maxPublishSessions = 8, maxSubscribeSessions = 8,
        pairingSupported = false, instantModeSupported = true, cipherSuites = 3, error = null
    )

    @Test
    fun `phone without the feature is reported as BLE only`() {
        val text = WifiAwareProbe.format(supported.copy(hasFeature = false))
        assertTrue(text, text.contains("NOT SUPPORTED"))
        assertTrue(text, !text.contains("Data paths"))
    }

    @Test
    fun `supported and available phone is reported usable`() {
        val text = WifiAwareProbe.format(supported)
        assertTrue(text, text.contains("usable for v3"))
        assertTrue(text, text.contains("Data paths / interfaces: 8 / 2"))
        assertTrue(text, text.contains("Aware 4.0 pairing: no"))
    }

    @Test
    fun `supported but unavailable phone says so instead of claiming usable`() {
        val text = WifiAwareProbe.format(supported.copy(available = false))
        assertTrue(text, text.contains("off/busy now"))
        assertTrue(text, !text.contains("usable for v3"))
    }

    @Test
    fun `fields an older Android cannot answer print as unknown not no`() {
        val text = WifiAwareProbe.format(supported.copy(pairingSupported = null))
        assertTrue(text, text.contains("Aware 4.0 pairing: unknown on this Android version"))
    }

    @Test
    fun `error wins over an optimistic verdict`() {
        val text = WifiAwareProbe.format(supported.copy(error = "SecurityException: x"))
        assertTrue(text, text.contains("UNKNOWN"))
    }
}
