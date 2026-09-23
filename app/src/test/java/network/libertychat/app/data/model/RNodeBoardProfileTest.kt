package network.libertychat.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for LCS board-profile detection and the board-aware TX power
 * default it feeds.
 */
class RNodeBoardProfileTest {
    private fun usb(
        vendorId: Int = 0x1A86,
        productId: Int = 0x7523,
        productName: String? = null,
        manufacturerName: String? = null,
    ) = DiscoveredUsbDevice(
        deviceId = 1,
        vendorId = vendorId,
        productId = productId,
        deviceName = "/dev/bus/usb/001/002",
        manufacturerName = manufacturerName,
        productName = productName,
        serialNumber = null,
        driverType = "CDC-ACM",
        hasPermission = true,
    )

    private fun esp32S3Usb(
        productName: String? = "USB JTAG/serial debug unit",
        manufacturerName: String? = "Espressif",
    ) = usb(
        vendorId = RNodeBoardProfile.ESPRESSIF_VENDOR_ID,
        productId = RNodeBoardProfile.ESP32S3_USB_JTAG_PRODUCT_ID,
        productName = productName,
        manufacturerName = manufacturerName,
    )

    // ========== Ceilings ==========

    @Test
    fun `heltec v4 ceiling is 28 dBm`() {
        assertEquals(28, RNodeBoardProfile.HELTEC_V4.txPowerCeiling)
    }

    @Test
    fun `standard board ceiling is 22 dBm`() {
        assertEquals(22, RNodeBoardProfile.STANDARD.txPowerCeiling)
    }

    // ========== Detection ==========

    @Test
    fun `esp32-s3 native usb is treated as heltec v4`() {
        // This is what the V4 presents as: 303A:1001, no board name in the descriptor.
        assertEquals(RNodeBoardProfile.HELTEC_V4, RNodeBoardProfile.detect(esp32S3Usb()))
    }

    @Test
    fun `uart bridge device is a standard board`() {
        // CH340 bridge - not an ESP32-S3 native USB device.
        assertEquals(RNodeBoardProfile.STANDARD, RNodeBoardProfile.detect(usb()))
    }

    @Test
    fun `named heltec v4 is detected over usb`() {
        assertEquals(
            RNodeBoardProfile.HELTEC_V4,
            RNodeBoardProfile.detect(usb(productName = "Heltec V4")),
        )
    }

    @Test
    fun `named heltec v3 is not a v4`() {
        assertEquals(
            RNodeBoardProfile.STANDARD,
            RNodeBoardProfile.detect(usb(productName = "Heltec WiFi LoRa 32 V3")),
        )
    }

    @Test
    fun `named board wins over the esp32-s3 usb fallback`() {
        // A LILYGO T3S3 is also an ESP32-S3 on native USB, but names itself, so
        // it must not inherit the 28 dBm default.
        assertEquals(
            RNodeBoardProfile.STANDARD,
            RNodeBoardProfile.detect(esp32S3Usb(productName = "LILYGO T3S3")),
        )
    }

    @Test
    fun `heltec v4 detected from bluetooth name`() {
        assertEquals(
            RNodeBoardProfile.HELTEC_V4,
            RNodeBoardProfile.detect(usbDevice = null, bluetoothDeviceName = "RNode Heltec v4"),
        )
    }

    @Test
    fun `unknown device falls back to the safe standard profile`() {
        assertEquals(
            RNodeBoardProfile.STANDARD,
            RNodeBoardProfile.detect(usbDevice = null, bluetoothDeviceName = "RNode 1a2b"),
        )
    }

    // ========== Board-aware region default ==========

    @Test
    fun `us region gives heltec v4 a 28 dBm default`() {
        val us = FrequencyRegions.findById("us_915")!!
        assertEquals(28, us.defaultTxPowerFor(RNodeBoardProfile.HELTEC_V4.txPowerCeiling))
    }

    @Test
    fun `us region keeps other boards at 22 dBm`() {
        val us = FrequencyRegions.findById("us_915")!!
        assertEquals(22, us.defaultTxPowerFor(RNodeBoardProfile.STANDARD.txPowerCeiling))
    }

    @Test
    fun `regulatory limit still clamps the heltec v4 ceiling`() {
        // EU 868 is well under 28 dBm by regulation - the board ceiling must
        // never raise a default above the band limit.
        val eu = FrequencyRegions.findById("eu_868_m")!!
        assertEquals(
            minOf(RNodeBoardProfile.HELTEC_V4.txPowerCeiling, eu.maxTxPower),
            eu.defaultTxPowerFor(RNodeBoardProfile.HELTEC_V4.txPowerCeiling),
        )
    }

    @Test
    fun `no region default ever exceeds its regulatory maximum`() {
        FrequencyRegions.regions.forEach { region ->
            val heltec = region.defaultTxPowerFor(RNodeBoardProfile.HELTEC_V4.txPowerCeiling)
            assert(heltec <= region.maxTxPower) {
                "${region.id} Heltec V4 default $heltec exceeds max ${region.maxTxPower}"
            }
        }
    }
}
