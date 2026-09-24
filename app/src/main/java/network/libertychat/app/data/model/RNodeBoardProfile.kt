package network.libertychat.app.data.model

/**
 * Heltec V4 hardware maximum, in dBm.
 *
 * Top-level rather than a companion constant so the enum entries below can use
 * it in their constructor arguments without touching the companion object.
 */
private const val HELTEC_V4_TX_CEILING = 28

/** RAK hardware maximum, in dBm. */
private const val RAK_TX_CEILING = 22

/** LILYGO hardware maximum, in dBm. */
private const val LILYGO_TX_CEILING = 20

/**
 * Ceiling used when nothing identifies the board, in dBm.
 *
 * The RAK figure rather than the LILYGO one: it is the common case across the
 * boards LCS ships, and RNode firmware clamps a request the radio cannot meet,
 * so an unnamed LILYGO asked for 22 will still transmit at its own maximum.
 */
private const val UNKNOWN_BOARD_TX_CEILING = 22

/** Matches a "v4" token so "Heltec V3"/"V2" are not caught. */
private val HELTEC_V4_SUFFIX = Regex("""v\s*4\b""")

/** Name fragments that identify a RAK board. */
private val RAK_HINTS = listOf("rak")

/**
 * Name fragments that identify a LILYGO board.
 *
 * Checked before the ESP32-S3 USB fallback so a LILYGO that names itself — the
 * T3S3 shares the Heltec V4's native-USB descriptor — keeps its own 20 dBm
 * ceiling instead of inheriting 28.
 */
private val LILYGO_HINTS =
    listOf(
        "lilygo", "t3s3", "t3-s3", "tbeam", "t-beam",
        "techo", "t-echo", "tdeck", "t-deck", "lora32",
    )

/**
 * LCS: RNode board families, distinguished by their TX-power ceiling.
 *
 * Heltec V4 runs to 28 dBm, RAK to 22, LILYGO to 20. The interface wizard uses
 * this to pre-fill a sensible TX power per board instead of one flat number,
 * while regional regulatory limits still clamp the result — see
 * [FrequencyRegion.defaultTxPowerFor].
 *
 * Detection caveat: the RNode firmware product code (which names the board
 * exactly — `PRODUCT_H32_V4 = 0xC3`) is only read by the flasher module, which
 * the interface wizard cannot reach. What the wizard has is the USB descriptor
 * or the Bluetooth advertised name, so [detect] works from those.
 */
enum class RNodeBoardProfile(
    val displayName: String,
    val txPowerCeiling: Int,
) {
    /** Heltec (WiFi LoRa 32) V4 — 28 dBm. */
    HELTEC_V4("Heltec V4", HELTEC_V4_TX_CEILING),

    /** RAK (RAK4631 and relatives) — 22 dBm. */
    RAK("RAK", RAK_TX_CEILING),

    /** LILYGO (T3S3, T-Beam, T-Echo, T-Deck) — 20 dBm. */
    LILYGO("LILYGO", LILYGO_TX_CEILING),

    /** Board not identified — assume the common 22 dBm ceiling. */
    UNKNOWN("RNode", UNKNOWN_BOARD_TX_CEILING),
    ;

    companion object {
        /** Espressif's USB vendor ID. */
        const val ESPRESSIF_VENDOR_ID = 0x303A

        /**
         * ESP32-S3 native "USB JTAG/serial debug unit" product ID.
         *
         * This is how a Heltec V4 presents over USB: it drives the port from the
         * ESP32-S3 directly rather than through a UART bridge, so there is no
         * CP210x/CH340/FTDI descriptor carrying a board name.
         */
        const val ESP32S3_USB_JTAG_PRODUCT_ID = 0x1001

        /** The profile assumed when nothing identifies the board. */
        val DEFAULT = UNKNOWN

        /**
         * Resolve the board profile from whatever identity the wizard has.
         *
         * Precedence:
         *  1. An explicit "heltec … v4" in any name string -> [HELTEC_V4].
         *  2. An explicit RAK or LILYGO name -> [RAK] / [LILYGO].
         *  3. The ESP32-S3 native-USB descriptor -> [HELTEC_V4].
         *  4. Anything else -> [UNKNOWN].
         *
         * Step 3 is a judgement call, not a certainty: `303A:1001` means "an
         * ESP32-S3 talking over its own USB peripheral", which is true of the
         * Heltec V4 and could be true of another ESP32-S3 RNode that does not
         * identify itself by name. Step 2 keeps that fallback honest. If it does
         * guess wrong the user can still type a different TX power — this only
         * chooses the pre-filled default, and RNode firmware clamps a request
         * the radio cannot meet.
         */
        fun detect(
            usbDevice: DiscoveredUsbDevice?,
            bluetoothDeviceName: String? = null,
        ): RNodeBoardProfile {
            val names =
                listOfNotNull(
                    bluetoothDeviceName,
                    usbDevice?.productName,
                    usbDevice?.manufacturerName,
                ).map { it.lowercase() }

            if (names.any { isHeltecV4Name(it) }) return HELTEC_V4
            if (names.any { name -> RAK_HINTS.any { it in name } }) return RAK
            if (names.any { name -> LILYGO_HINTS.any { it in name } }) return LILYGO

            val isEsp32S3NativeUsb =
                usbDevice != null &&
                    usbDevice.vendorId == ESPRESSIF_VENDOR_ID &&
                    usbDevice.productId == ESP32S3_USB_JTAG_PRODUCT_ID
            return if (isEsp32S3NativeUsb) HELTEC_V4 else DEFAULT
        }

        /** True when [lowercaseName] names a Heltec V4 (and not a V2/V3/T114). */
        private fun isHeltecV4Name(lowercaseName: String): Boolean =
            "heltec" in lowercaseName && HELTEC_V4_SUFFIX.containsMatchIn(lowercaseName)
    }
}
