package com.tekfy.usbserialandroidkt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.tekfy.usbserialandroidkt.databinding.ActivityMainBinding
import java.util.ArrayList

class MainActivity : AppCompatActivity(){
    private lateinit var binding: ActivityMainBinding

    private val EXTENDED_TIMEOUT = 5000
    private var mThread:HandlerThread? = null
    private var mHandler:Handler? = null
    private var mRunnable: MyTimerRunnable? = null

    inner class ListItem(device: UsbDevice?, port: Int, driver: UsbSerialDriver?) {
        var device: UsbDevice?
        var port:Int?
        var driver: UsbSerialDriver?

        init {
            this.device = device
            this.port= port
            this.driver = driver
        }
    }

    private val listItems: ArrayList<ListItem> = ArrayList()
    private var baudRate = 9600
    private var withIoManager = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)*/

        if(savedInstanceState == null){
            /*mThread = HandlerThread("MyThread")
            mThread!!.start()
            mHandler = Handler(mThread!!.looper)
            mRunnable = MyTimerRunnable()*/
            refreshX()
        }

        /*binding.buttonStartService.isEnabled = false
        binding.buttonStartService.isClickable = false

        binding.buttonStopService.setOnClickListener{
            stopService()
        }*/
        finish()
    }

    /*override fun onResume() {
        super.onResume()
        mHandler?.removeCallbacks(mRunnable!!)
        mHandler?.postDelayed(mRunnable!!, EXTENDED_TIMEOUT.toLong())

    }*/

    /*override fun onDestroy() {
        super.onDestroy()
        mHandler?.removeCallbacks(mRunnable!!)
        mThread?.quitSafely()
    }*/

    fun initialize(){
        try{
            val item = listItems[0]
            if (item.driver == null){
                Toast.makeText(this, "<no driver>", Toast.LENGTH_SHORT).show()
            }else if(item.driver!!.ports.size === 1){
                Toast.makeText(this, item.driver!!.javaClass.getSimpleName().replace("SerialDriver", ""), Toast.LENGTH_SHORT).show()

                startService()
            }else{
                Toast.makeText(this, item.driver!!.javaClass.getSimpleName()
                    .replace("SerialDriver", "") + ", Port " + item.port, Toast.LENGTH_SHORT).show()
            }
        }catch (e:java.lang.Exception){
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshX(){
        try{
            val usbManager = this.getSystemService(Context.USB_SERVICE) as UsbManager
            val usbDevice = usbManager.deviceList.values.first()
            Toast.makeText(this, "device list length ${usbManager.deviceList.size
            }", Toast.LENGTH_SHORT).show()
            if(usbManager.hasPermission(usbDevice)){
                Toast.makeText(this, "Permiso otorgado", Toast.LENGTH_SHORT).show()
                val usbDefaultProber: UsbSerialProber = UsbSerialProber.getDefaultProber()
                val usbCustomProber: UsbSerialProber = CustomProber.getCustomProber()
                listItems.clear()
                for (device in usbManager.deviceList.values) {
                    var driver: UsbSerialDriver = usbDefaultProber.probeDevice(device)
                    if (driver == null) {
                        driver = usbCustomProber.probeDevice(device)
                    }
                    if (driver != null) {
                        for (port in 0 until driver.getPorts().size) listItems.add(
                            ListItem(
                                device,
                                port,
                                driver
                            )
                        )

                    } else {
                        listItems.add(ListItem(device, 0, null))
                    }
                }
                initialize()
            }else{
                Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
                val usbPermissionIntent = PendingIntent.getBroadcast(this, 0, Intent(), 0)
                usbManager.requestPermission(usbDevice, usbPermissionIntent)
            }
        }catch (e:Exception){
            Toast.makeText(this,"No device detected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED" == intent.action) {

            Toast.makeText(this, "USB device detected", Toast.LENGTH_SHORT).show()
        }
        super.onNewIntent(intent)
    }

    fun startService() {

        try{
            val item = listItems[0]
            if(item.driver == null){
                Toast.makeText(this, "no driver", Toast.LENGTH_SHORT).show()
            }else{
                val args = Bundle()
                args.putInt("deviceId", item.device!!.deviceId)
                args.putInt("port", item.port!!)
                args.putInt("baudRate", baudRate)
                args.putBoolean("withIoManager", withIoManager)

                val serviceIntent = Intent(this, DeviceService::class.java)
                serviceIntent.putExtras(args)
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }catch (e:Exception){
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }

    }

    fun stopService() {
        val serviceIntent = Intent(this, DeviceService::class.java)
        stopService(serviceIntent)
    }

    private inner class MyTimerRunnable:Runnable{
        override fun run() {
            refreshX()
        }

    }
}