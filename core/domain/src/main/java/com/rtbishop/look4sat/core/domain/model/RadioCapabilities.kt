/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.model

enum class RadioProtocolFamily {
    YAESU_CAT,
    ICOM_CIV
}

enum class RadioBand {
    HF,
    SIX_METERS,
    TWO_METERS,
    SEVENTY_CENTIMETERS,
    TWENTY_THREE_CENTIMETERS
}

data class RadioCapabilities(
    val fullDuplex: Boolean,
    val receiveOnly: Boolean,
    val frequencyReadback: Boolean,
    val singleRadioSplit: Boolean,
    val satelliteMode: Boolean,
    val dataMode: Boolean,
    val ptt: Boolean,
    val narrowFm: Boolean,
    val canSetTxFrequencyWhileTransmitting: Boolean,
    val bands: Set<RadioBand>
)

data class RadioModelDescriptor(
    val model: String,
    val protocolFamily: RadioProtocolFamily,
    val capabilities: RadioCapabilities,
    val baudRates: List<Int>,
    val serialStopBits: Int,
    val defaultCivAddress: Int? = null
)

object RadioModelCatalog {
    private val hfVhfUhfBands = setOf(
        RadioBand.HF,
        RadioBand.SIX_METERS,
        RadioBand.TWO_METERS,
        RadioBand.SEVENTY_CENTIMETERS
    )
    private val satelliteBands = setOf(
        RadioBand.TWO_METERS,
        RadioBand.SEVENTY_CENTIMETERS,
        RadioBand.TWENTY_THREE_CENTIMETERS
    )

    private val descriptors = listOf(
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_YAESU_FT817,
            protocolFamily = RadioProtocolFamily.YAESU_CAT,
            capabilities = RadioCapabilities(
                fullDuplex = false,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = false,
                satelliteMode = false,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = true,
                bands = hfVhfUhfBands
            ),
            baudRates = listOf(4_800, 9_600, 38_400),
            serialStopBits = 2
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_YAESU_FT857,
            protocolFamily = RadioProtocolFamily.YAESU_CAT,
            capabilities = RadioCapabilities(
                fullDuplex = false,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = false,
                satelliteMode = false,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = false,
                bands = hfVhfUhfBands
            ),
            baudRates = listOf(4_800, 9_600, 38_400),
            serialStopBits = 2
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC705,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = false,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = true,
                satelliteMode = false,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = true,
                bands = hfVhfUhfBands
            ),
            baudRates = listOf(4_800, 9_600, 19_200),
            serialStopBits = 1,
            defaultCivAddress = 0xA4
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC9700,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = true,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = true,
                satelliteMode = true,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = true,
                bands = satelliteBands
            ),
            baudRates = listOf(4_800, 9_600, 19_200, 38_400),
            serialStopBits = 1,
            defaultCivAddress = 0xA2
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC910,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = true,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = true,
                satelliteMode = true,
                dataMode = false,
                ptt = true,
                narrowFm = false,
                canSetTxFrequencyWhileTransmitting = true,
                bands = satelliteBands
            ),
            baudRates = listOf(9_600, 19_200, 4_800, 1_200, 300),
            serialStopBits = 1,
            defaultCivAddress = 0x60
        )
    )
    private val byModel = descriptors.associateBy(RadioModelDescriptor::model)

    val supportedModels: List<String> = descriptors.map(RadioModelDescriptor::model)

    fun resolve(model: String): RadioModelDescriptor =
        byModel[model] ?: checkNotNull(byModel[RadioControlSettings.MODEL_YAESU_FT817])
}

fun radioModelDescriptor(model: String): RadioModelDescriptor = RadioModelCatalog.resolve(model)
