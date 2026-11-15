package com.rtbishop.look4sat.feature.settings

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.RadioProtocolFamily
import com.rtbishop.look4sat.core.domain.model.radioModelDescriptor

internal fun radioControlTransports(model: String): List<String> {
    val family = radioModelDescriptor(model).protocolFamily
    return RadioControlSettings.SUPPORTED_TRANSPORTS.filter { transport ->
        when {
            transport == RadioControlSettings.TRANSPORT_VOX -> true
            family == RadioProtocolFamily.VERO_GAIA -> transport == RadioControlSettings.TRANSPORT_BLUETOOTH
            family == RadioProtocolFamily.L4S ->
                transport == RadioControlSettings.TRANSPORT_USB
            model == RadioControlSettings.MODEL_YAESU_FTX1 ->
                transport == RadioControlSettings.TRANSPORT_USB ||
                    transport == RadioControlSettings.TRANSPORT_BLUETOOTH
            else -> true
        }
    }
}

internal fun voxControlSettings(initial: RadioControlSettings, enabled: Boolean): RadioControlSettings =
    initial.copy(enabled = enabled, catTransport = RadioControlSettings.TRANSPORT_VOX)
