package network.libertychat.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ModemPreset enum.
 * Tests Meshtastic-compatible modem preset matching.
 */
class ModemPresetTest {
    // ========== findByParams Tests ==========

    @Test
    fun `findByParams finds SHORT_TURBO`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 7,
                bandwidth = 500_000,
                codingRate = 5,
            )
        assertEquals(ModemPreset.SHORT_TURBO, preset)
    }

    @Test
    fun `findByParams finds SHORT_FAST`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 7,
                bandwidth = 250_000,
                codingRate = 5,
            )
        assertEquals(ModemPreset.SHORT_FAST, preset)
    }

    @Test
    fun `findByParams finds SHORT_SLOW`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 8,
                bandwidth = 250_000,
                codingRate = 5,
            )
        assertEquals(ModemPreset.SHORT_SLOW, preset)
    }

    @Test
    fun `findByParams finds MEDIUM_FAST`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 9,
                bandwidth = 250_000,
                codingRate = 5,
            )
        assertEquals(ModemPreset.MEDIUM_FAST, preset)
    }

    @Test
    fun `findByParams finds MEDIUM_SLOW`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 10,
                bandwidth = 250_000,
                codingRate = 5,
            )
        assertEquals(ModemPreset.MEDIUM_SLOW, preset)
    }

    @Test
    fun `findByParams finds LONG_FAST`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 11,
                bandwidth = 250_000,
                codingRate = 5,
            )
        assertEquals(ModemPreset.LONG_FAST, preset)
    }

    @Test
    fun `findByParams finds LONG_MODERATE`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 11,
                bandwidth = 125_000,
                codingRate = 8,
            )
        assertEquals(ModemPreset.LONG_MODERATE, preset)
    }

    @Test
    fun `findByParams finds LONG_SLOW`() {
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 12,
                bandwidth = 125_000,
                codingRate = 8,
            )
        assertEquals(ModemPreset.LONG_SLOW, preset)
    }

    @Test
    fun `findByParams returns null for non-matching params`() {
        // Invalid combination - no preset has SF=12 with 500kHz bandwidth
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 12,
                bandwidth = 500_000,
                codingRate = 5,
            )
        assertNull(preset)
    }

    @Test
    fun `findByParams returns null for partial match`() {
        // SF and BW match LONG_FAST, but CR is wrong
        val preset =
            ModemPreset.findByParams(
                spreadingFactor = 11,
                bandwidth = 250_000,
                codingRate = 8, // Should be 5 for LONG_FAST
            )
        assertNull(preset)
    }

    // ========== DEFAULT Preset Tests ==========

    @Test
    fun `DEFAULT is MEDIUM_FAST`() {
        // LCS: Medium Fast rather than upstream's Long Fast — LCS deployments lean
        // on rooftop repeaters, where the extra speed beats Long Fast's reach.
        assertEquals(ModemPreset.MEDIUM_FAST, ModemPreset.DEFAULT)
    }

    @Test
    fun `DEFAULT has expected parameters`() {
        assertEquals(9, ModemPreset.DEFAULT.spreadingFactor)
        assertEquals(250_000, ModemPreset.DEFAULT.bandwidth)
        assertEquals(5, ModemPreset.DEFAULT.codingRate)
    }

    // ========== Enum Completeness Tests ==========

    @Test
    fun `all 9 presets exist`() {
        assertEquals(9, ModemPreset.entries.size)
    }

    @Test
    fun `long range turbo preset has LCS parameters`() {
        val turbo = ModemPreset.LONG_RANGE_TURBO
        assertEquals(11, turbo.spreadingFactor)
        assertEquals(500_000, turbo.bandwidth)
        assertEquals(8, turbo.codingRate)
    }

    @Test
    fun `all presets have valid spreading factors`() {
        ModemPreset.entries.forEach { preset ->
            assert(preset.spreadingFactor in 7..12) {
                "${preset.name} has invalid SF: ${preset.spreadingFactor}"
            }
        }
    }

    @Test
    fun `all presets have valid coding rates`() {
        ModemPreset.entries.forEach { preset ->
            assert(preset.codingRate in 5..8) {
                "${preset.name} has invalid CR: ${preset.codingRate}"
            }
        }
    }

    @Test
    fun `all presets have valid bandwidths`() {
        val validBandwidths = setOf(125_000, 250_000, 500_000)
        ModemPreset.entries.forEach { preset ->
            assert(preset.bandwidth in validBandwidths) {
                "${preset.name} has invalid BW: ${preset.bandwidth}"
            }
        }
    }

    @Test
    fun `all presets have non-empty display names`() {
        ModemPreset.entries.forEach { preset ->
            assert(preset.displayName.isNotBlank()) {
                "${preset.name} has empty display name"
            }
        }
    }

    @Test
    fun `all presets have non-empty descriptions`() {
        ModemPreset.entries.forEach { preset ->
            assert(preset.description.isNotBlank()) {
                "${preset.name} has empty description"
            }
        }
    }

    // ========== LCS: Badge Tests ==========

    @Test
    fun `medium presets are badged for repeater use`() {
        assertEquals("Good range with rooftop repeaters", ModemPreset.MEDIUM_FAST.lcsBadge)
        assertEquals("Works well with repeaters", ModemPreset.MEDIUM_SLOW.lcsBadge)
    }

    @Test
    fun `long fast is badged for ground radios and short fast for voice`() {
        // Long Fast lost the "Default" badge when Medium Fast became the default;
        // it is now positioned for radios working each other directly.
        assertEquals("Good for ground radios", ModemPreset.LONG_FAST.lcsBadge)
        assertEquals("Best for voice over LoRa", ModemPreset.SHORT_FAST.lcsBadge)
    }

    @Test
    fun `no preset is badged Default any more`() {
        // The default is conveyed by being pre-selected in the wizard, not by a
        // badge — so a stale "Default" chip on a non-default preset would lie.
        val badged = ModemPreset.entries.filter { it.lcsBadge == "Default" }
        assertEquals("Unexpected Default badge on: ${badged.map { it.name }}", 0, badged.size)
    }

    @Test
    fun `presets without a badge report null rather than an empty string`() {
        // The UI renders the chip on a null check, so an empty string would draw
        // an empty chip rather than no chip.
        ModemPreset.entries.forEach { preset ->
            preset.lcsBadge?.let { badge ->
                assert(badge.isNotBlank()) { "${preset.name} has a blank badge" }
            }
        }
        assertNull(ModemPreset.LONG_SLOW.lcsBadge)
        assertNull(ModemPreset.SHORT_TURBO.lcsBadge)
    }
}
