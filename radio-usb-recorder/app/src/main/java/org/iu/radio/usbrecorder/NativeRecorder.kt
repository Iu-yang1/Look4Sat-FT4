package org.iu.radio.usbrecorder

/**
 * Raw USB transport only: do not replace this with AudioRecord.
 * start() takes ownership of wavFd regardless of success or failure.
 * usbFd remains owned by UsbDeviceConnection; native duplicates it.
 */
internal object NativeRecorder {
    init {
        System.loadLibrary("radio_usb_recorder")
    }

    external fun start(usbFd: Int, wavFd: Int, sampleRate: Int): String?
    external fun snapshot(): LongArray
    external fun stop(): String?
}
