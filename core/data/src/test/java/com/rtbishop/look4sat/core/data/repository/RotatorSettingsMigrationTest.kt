package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatorSettingsMigrationTest {

    @Test
    fun migratesLegacyNetworkRotctldSettings() {
        val migrated = migrateLegacyRotatorSettings(
            legacySettings(
                rotatorState = true,
                rotatorAddress = "rotator.local",
                rotatorPort = "4533",
                rotatorFormat = "P \$AZ \$EL"
            )
        )

        assertTrue(migrated.enabled)
        assertEquals(RotatorProtocol.ROTCTLD, migrated.protocol)
        assertEquals(RotatorTransport.TCP, migrated.transport)
        assertEquals("rotator.local", migrated.host)
        assertEquals(4533, migrated.port)
    }

    @Test
    fun networkProfileWinsWhenBothLegacyPathsWereEnabled() {
        val migrated = migrateLegacyRotatorSettings(
            legacySettings(
                rotatorState = true,
                rotatorAddress = "network-rotator",
                bluetoothRotatorState = true,
                bluetoothRotatorAddress = "00:11:22:33:44:55"
            )
        )

        assertEquals(RotatorTransport.TCP, migrated.transport)
        assertEquals("network-rotator", migrated.host)
        assertEquals("", migrated.deviceAddress)
    }

    @Test
    fun migratesBluetoothCustomTemplateWithoutPretendingItIsRotctld() {
        val migrated = migrateLegacyRotatorSettings(
            legacySettings(
                bluetoothRotatorState = true,
                bluetoothRotatorAddress = "00:11:22:33:44:55",
                bluetoothRotatorFormat = "P \$AZ \$EL"
            )
        )

        assertTrue(migrated.enabled)
        assertEquals(RotatorProtocol.CUSTOM_TEMPLATE, migrated.protocol)
        assertEquals(RotatorTransport.BLUETOOTH_SPP, migrated.transport)
        assertEquals("00:11:22:33:44:55", migrated.deviceAddress)
        assertEquals("P \$AZ \$EL", migrated.customPointTemplate)
    }

    @Test
    fun recognizesLegacyGs232AndEasyCommTemplates() {
        assertEquals(
            RotatorProtocol.GS232,
            inferLegacyRotatorProtocol("W\$AZ \$EL\\r", RotatorTransport.USB_SERIAL)
        )
        assertEquals(
            RotatorProtocol.EASYCOMM_II,
            inferLegacyRotatorProtocol("AZ\$AZ EL\$EL\\r", RotatorTransport.TCP)
        )
    }

    @Test
    fun disabledLegacySettingsRemainDisabled() {
        val migrated = migrateLegacyRotatorSettings(legacySettings())

        assertFalse(migrated.enabled)
        assertEquals(RotatorProtocol.ROTCTLD, migrated.protocol)
        assertEquals(RotatorTransport.TCP, migrated.transport)
    }

    private fun legacySettings(
        rotatorState: Boolean = false,
        rotatorAddress: String = "127.0.0.1",
        rotatorPort: String = "4533",
        rotatorFormat: String = "P \$AZ \$EL",
        bluetoothRotatorState: Boolean = false,
        bluetoothRotatorAddress: String = "",
        bluetoothRotatorFormat: String = "P \$AZ \$EL"
    ) = RCSettings(
        rotatorState = rotatorState,
        rotatorAddress = rotatorAddress,
        rotatorPort = rotatorPort,
        rotatorFormat = rotatorFormat,
        frequencyState = false,
        frequencyAddress = "127.0.0.1",
        frequencyPort = "4532",
        frequencyFormat = "F \$FREQ",
        bluetoothRotatorState = bluetoothRotatorState,
        bluetoothRotatorFormat = bluetoothRotatorFormat,
        bluetoothRotatorName = "Legacy",
        bluetoothRotatorAddress = bluetoothRotatorAddress,
        bluetoothFrequencyState = false,
        bluetoothFrequencyFormat = "F \$FREQ",
        bluetoothFrequencyAddress = ""
    )
}
