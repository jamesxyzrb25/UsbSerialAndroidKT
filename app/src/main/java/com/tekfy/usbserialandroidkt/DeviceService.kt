package com.tekfy.usbserialandroidkt

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.PlaybackStateCompat
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.viewbinding.BuildConfig
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.tekfy.usbserialandroidkt.util.HexDump
import java.io.IOException
import java.util.*

class DeviceService:Service(), CoroutinesServiceInterface, SerialInputOutputManager.Listener {
    val CHANNEL_ID = "ForegroundServiceChannel"

    enum class UsbPermission {
        Unknown, Requested, Granted, Denied
    }

    //TODO IMPLEMENTAR HILO PARA ALGUNAS FUNCIONES COMO READ (AVISARME CUANDO PIERDA CONEXION)
    private lateinit var myRunnable: MyRunnable
    private var mainLooper: Handler? = null

    val INTENT_ACTION_GRANT_USB: String = BuildConfig.LIBRARY_PACKAGE_NAME + ".GRANT_USB"
    val WRITE_WAIT_MILLIS = 2000
    val READ_WAIT_MILLIS = 2000

    var broadcastReceiver: BroadcastReceiver? = null


    var usbIoManager: SerialInputOutputManager? = null
    var usbSerialPort: UsbSerialPort? = null
    var usbPermission: UsbPermission = UsbPermission.Unknown
    private var connected:Boolean = false

    private val host = "127.0.0.1" // Cambiar por el host del servidor socket
    private val port = 9999 // Cambiar por el número de puerto del servidor socket
    private lateinit var messageSocket:String

    init{

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (INTENT_ACTION_GRANT_USB == intent.action) {
                    usbPermission = if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        )
                    ) UsbPermission.Granted else UsbPermission.Denied
                    //connect()
                }
            }
        }
        mainLooper = Handler(Looper.getMainLooper())
    }

    override fun onCreate() {
        super.onCreate()
        myRunnable = MyRunnable()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Toast.makeText(this, "Enta al onStartCommand del servicio", Toast.LENGTH_SHORT).show()

        val extras = intent!!.extras

        val deviceIdArg = extras!!.getInt("deviceId")
        val portArg = extras!!.getInt("port")
        val baudRateArg = extras!!.getInt("baudRate")
        val withIoManagerArg = extras!!.getBoolean("withIoManager")

        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, 0
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foreground Service")
            .setContentText("Arduino foreground service is running")
            .setSmallIcon(R.drawable.ic_services)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_service, "Stop Service", pendingIntent)
            .build()
        startForeground(1, notification)
        //do heavy work on a background thread
        if(!connected){
            connect(deviceIdArg, portArg, baudRateArg, withIoManagerArg)
        }

        //stopSelf();
        //do heavy work on a background thread
        //stopSelf();
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        //Toast.makeText(this, "Enta al onDestroy del com.tekfy.usbserialandroidkt.DeviceService", Toast.LENGTH_SHORT).show()
        disconnect()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    private fun connect(
        deviceIdArg: Int,
        portArg: Int,
        baudRateArg: Int,
        withIoManagerArg: Boolean
    ) {
        Toast.makeText(this, "Connect DeviceService $deviceIdArg, $portArg, $baudRateArg, $withIoManagerArg", Toast.LENGTH_SHORT).show()
        var device: UsbDevice? = null
        val usbManager = this.getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) if (v.deviceId == deviceIdArg) device = v
        if (device == null) {
            status("connection failed: device not found")
            return
        }
        var driver: UsbSerialDriver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.getPorts().size < portArg) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.getPorts().get(portArg)
        val usbConnection = usbManager.openDevice(driver.getDevice())
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(
                driver.getDevice()
            )
        ) {
            usbPermission = UsbPermission.Requested
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val usbPermissionIntent =
                PendingIntent.getBroadcast(this, 0, Intent(INTENT_ACTION_GRANT_USB), flags)
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice())) status("connection failed: permission denied") else status(
                "connection failed: open failed"
            )
            return
        }
        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(baudRateArg, 8, 1, UsbSerialPort.PARITY_NONE)
            if (withIoManagerArg) {
                usbIoManager = SerialInputOutputManager(usbSerialPort, this)
                 usbIoManager!!.start()
            }
            status("connected")
            connected = true
            //controlLines.start()
            /*send("from machine import Pin")
            send("import utime")
            send("led = Pin(19, Pin.OUT")
            send("led.value(1)")
            send("utime.sleep(5)")
            send("led.value(0)")
            send("utime.sleep(5)")
            send("led.value(1)")*/

            myRunnable.start()
        } catch (e: Exception) {
            status("connection failed: " + e.message)
            disconnect()
        }
    }

    private fun disconnect() {
        connected = false
        //controlLines.stop()
        myRunnable.stop()
        if (usbIoManager != null) {
            usbIoManager!!.setListener(null)
            usbIoManager!!.stop()
        }
        usbIoManager = null
        try {
            usbSerialPort?.close()
        } catch (ignored: IOException) {
        }
        usbSerialPort = null
    }

    private fun send(str: String) {
        if (!connected) {
            Toast.makeText(this, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val data = str.toByteArray()
            val spn = SpannableStringBuilder()
            spn.append(
                """send ${data.size} bytes
"""
            )
            spn.append(HexDump.dumpHexString(data)).append("\n")
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            Toast.makeText(this, spn, Toast.LENGTH_SHORT).show()
            //receiveText.append(spn)
            usbSerialPort?.write(data, WRITE_WAIT_MILLIS)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            onRunError(e)
        }
    }

    private fun read() {
        if (!connected) {
            Toast.makeText(this, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val buffer = ByteArray(8192)
            val len: Int? = usbSerialPort?.read(buffer, READ_WAIT_MILLIS)
            len?.let { Arrays.copyOf(buffer, it) }?.let { receive(it) }
        } catch (e: IOException) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.message)
            disconnect()
        }
    }

    private fun receive(data: ByteArray) {
        val spn = SpannableStringBuilder()
        spn.append(
            """receive ${data.size} bytes
"""
        )
        if (data.size > 0){
            spn.append(HexDump.dumpHexString(data)).append("\n")


            messageSocket = spn.toString()
            sendMessageSocket()

        }
        Toast.makeText(this, spn, Toast.LENGTH_SHORT).show()
    }

    fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
              $str
              
              """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        Toast.makeText(this, spn, Toast.LENGTH_SHORT).show()
        //receiveText.append(spn)
    }

    override fun onNewData(data: ByteArray?) {
        mainLooper?.post(Runnable {
            if (data != null) {
                receive(data)
            }
        })
    }

    override fun onRunError(e: Exception?) {
        mainLooper?.post(Runnable {
            status("Error connection lost: " + e!!.message)
            disconnect()
        })
    }

    internal class MyRunnable{
        private val runnable:Runnable

        init{
            runnable = Runnable { this.run() }
        }

        private fun run() {
            DeviceService().status("Entra al run de la clase runnable")
            if(!DeviceService().connected) return
            try{
                DeviceService().mainLooper?.postDelayed(runnable, refreshInterval.toLong())
                DeviceService().read()
            }catch (e:IOException){
                DeviceService().status("Runnable failed: ${e.message} -> stopped refresh on run")
            }
        }

        fun start(){
            if(!DeviceService().connected) return
            try{
                run()
            }catch (e:IOException){
                DeviceService().status("Runnable failed: ${e.message} -> stopped refresh on start")
            }
        }

        fun stop(){
            DeviceService().mainLooper?.removeCallbacks(runnable)
        }

        companion object {
            private const val refreshInterval = 200 // msec
        }
    }

    override fun sendMessageSocket() {

        val coroutinesService = CoroutinesService()
        coroutinesService.sendMessageSocket(host,port,messageSocket)
    }

}