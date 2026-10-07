/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTransport

class AndroidRotatorTransportFactory(
    private val serialFactory: AndroidRadioTransportFactory
) {
    fun create(settings: RotatorSettings): ControlTransport {
        require(settings.protocol.supportsTransport(settings.transport)) {
            "${settings.protocol} does not support ${settings.transport}"
        }
        return when (settings.transport) {
            RotatorTransport.BLUETOOTH_SPP -> serialFactory.create(
                RadioControlSettings.TRANSPORT_BLUETOOTH,
                settings.deviceAddress,
                settings.baudRate,
                SERIAL_STOP_BITS
            )
            RotatorTransport.USB_SERIAL -> serialFactory.create(
                RadioControlSettings.TRANSPORT_USB,
                settings.deviceAddress,
                settings.baudRate,
                SERIAL_STOP_BITS
            )
            RotatorTransport.TCP -> TcpRadioTransport(settings.host, settings.port)
            RotatorTransport.UDP -> UdpControlTransport(
                host = settings.host,
                port = settings.port,
                localPort = if (
                    settings.protocol == RotatorProtocol.PST_ROTATOR && settings.port < 65_535
                ) settings.port + 1 else null
            )
        }
    }

    private companion object {
        const val SERIAL_STOP_BITS = 1
    }
}
