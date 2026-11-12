package com.rtbishop.look4sat.feature.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

internal data class PendingUsbSelection(
    val target: String,
    val name: String,
    val address: String,
    val model: String,
    val token: String = UUID.randomUUID().toString()
) {
    // A permission belongs to this attachment, not another radio with the same VID/PID.
    fun matches(device: UsbDevice): Boolean {
        val parts = address.split(':')
        return parts.size in 5..6 && parts[0].toIntOrNull() == device.deviceId &&
            parts[3].toIntOrNull() == device.vendorId && parts[4].toIntOrNull() == device.productId
    }

    fun accepts(token: String?, currentTarget: String, currentModel: String, usbSelected: Boolean): Boolean =
        token == this.token && currentTarget == target && currentModel == model && usbSelected
}

internal val pendingUsbSelectionSaver = listSaver<MutableState<PendingUsbSelection?>, String>(
    save = { state -> state.value?.let { listOf(it.target, it.name, it.address, it.model, it.token) } ?: emptyList() },
    restore = { values -> mutableStateOf(if (values.size == 5)
        PendingUsbSelection(values[0], values[1], values[2], values[3], values[4]) else null) }
)

internal fun usbPermissionIntent(context: Context, action: String, selection: PendingUsbSelection): PendingIntent =
    PendingIntent.getBroadcast(context, 0,
        Intent(action).setPackage(context.packageName)
            .setData(Uri.Builder().scheme("look4sat-usb").authority(context.packageName)
                .appendPath(selection.token).build()),
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)

internal fun authorizedUsbDevice(manager: UsbManager, selection: PendingUsbSelection): UsbDevice? =
    manager.deviceList.values.firstOrNull { selection.matches(it) && manager.hasPermission(it) }
