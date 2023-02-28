package com.tekfy.usbserialandroidkt

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber

class CustomProber {
    // e.g. Digispark CDC
    companion object {
        fun getCustomProber():UsbSerialProber{
            var customTable = ProbeTable()
            customTable.addProduct(0x16d0, 0x087e,CdcAcmSerialDriver::class.java)
            return UsbSerialProber(customTable)
        }
    }
}