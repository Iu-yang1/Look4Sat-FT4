/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.model

data class DatabaseState(
    val numberOfRadios: Int,
    val numberOfSatellites: Int,
    val updateTimestamp: Long,
    val contentVersion: Long = 0L
)

data class PassesSettings(
    val showDeepSpace: Boolean = true,
    val hoursAhead: Int,
    val minElevation: Double,
    val aosStartMinute: Int = 0,
    val aosEndMinute: Int = 23 * 60 + 59,
    val invertAosTimeWindow: Boolean = false,
    val selectedModes: List<String>
)

data class RCSettings(
    val rotatorState: Boolean,
    val rotatorAddress: String,
    val rotatorPort: String,
    val rotatorFormat: String,
    val frequencyState: Boolean,
    val frequencyAddress: String,
    val frequencyPort: String,
    val frequencyFormat: String,
    val frequencyOffsetHz: Long = 0L,
    val bluetoothRotatorState: Boolean,
    val bluetoothRotatorFormat: String,
    val bluetoothRotatorName: String,
    val bluetoothRotatorAddress: String,
    val bluetoothFrequencyState: Boolean,
    val bluetoothFrequencyFormat: String,
    val bluetoothFrequencyAddress: String
)

data class OtherSettings(
    val stateOfAutoUpdate: Boolean,
    val stateOfSensors: Boolean,
    val stateOfSweep: Boolean,
    val stateOfUtc: Boolean,
    val stateOfLightTheme: Boolean,
    val stateOfNightMode: Boolean = false,
    val shouldSeeWarning: Boolean,
    val shouldSeeWhatsNew: Boolean,
    val sstvMode: String = "Auto",
    val lowElevation: Double = 15.0,
    val highElevation: Double = 45.0
)

data class Ft4Settings(
    val operatorCallsign: String = "",
    val decodeEnabled: Boolean = true,
    val decodeDepth: Int = 3,
    val ntpSynchronizationEnabled: Boolean = false,
    val gnssSynchronizationEnabled: Boolean = false,
    val audioInputDeviceKey: String = ""
)

data class DataSourcesSettings(
    val satelliteUrls: List<String>,
    val transceiversUrls: List<String>,
    val satelliteEnabled: List<Boolean> = emptyList(),
    val transceiversEnabled: List<Boolean> = emptyList()
) {
    fun isSatelliteEnabled(index: Int): Boolean = satelliteEnabled.getOrElse(index) { true }
    fun isTransceiverEnabled(index: Int): Boolean = transceiversEnabled.getOrElse(index) { true }
}

data class RadioControlSettings(
    val enabled: Boolean,
    val radioModel: String,
    val txRadioAddress: String,
    val rxRadioAddress: String,
    val txRadioName: String,
    val rxRadioName: String,
    val baudRate: Int,
    /** Use one Icom radio with VFO-A/B split or supported satellite MAIN/SUB control. */
    val splitMode: Boolean = false,
    val catTransport: String = TRANSPORT_BLUETOOTH,
    /** Dedicated satellite MAIN/SUB when supported; ordinary VFO-A/B split otherwise. */
    val duplexMode: String = DUPLEX_MODE_SPLIT
) {
    companion object {
        const val MODEL_YAESU_FT817 = "Yaesu FT-817/818"
        const val MODEL_YAESU_FT857 = "Yaesu FT-857/897"
        const val MODEL_ICOM_IC705 = "Icom IC-705"
        const val MODEL_ICOM_IC9700 = "Icom IC-9700"
        const val MODEL_ICOM_IC910 = "Icom IC-910/D/H"
        const val TRANSPORT_BLUETOOTH = "BLUETOOTH"
        const val TRANSPORT_USB = "USB"
        const val TRANSPORT_TCP = "TCP"
        const val DUPLEX_MODE_SPLIT = "SPLIT"
        const val DUPLEX_MODE_SATELLITE = "SATELLITE"

        val SUPPORTED_RADIOS = listOf(
            MODEL_YAESU_FT817,
            MODEL_YAESU_FT857,
            MODEL_ICOM_IC705,
            MODEL_ICOM_IC9700,
            MODEL_ICOM_IC910
        )
        val ICOM_RADIOS = setOf(MODEL_ICOM_IC705, MODEL_ICOM_IC9700, MODEL_ICOM_IC910)
        val SATELLITE_MODE_RADIOS = setOf(MODEL_ICOM_IC9700, MODEL_ICOM_IC910)
        val SUPPORTED_TRANSPORTS = listOf(TRANSPORT_BLUETOOTH, TRANSPORT_USB, TRANSPORT_TCP)

        /** Baud rates available for Yaesu radios. */
        val BAUD_RATES_YAESU = listOf(4800, 9600, 38400)
        /** Baud rates available for modern Icom CI-V USB/Bluetooth interfaces. */
        val BAUD_RATES_ICOM  = listOf(4800, 9600, 19200, 38400, 57600, 115200)
        /** Hamlib documents the IC-910 family serial interface as 300–19200 baud. */
        val BAUD_RATES_IC910 = listOf(9600, 19200, 4800, 1200, 300)
    }
}
