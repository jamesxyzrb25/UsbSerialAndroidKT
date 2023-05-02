package com.tekfy.usbserialandroidkt

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.util.Log
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.tekfy.usbserialandroidkt.databinding.ActivityMainBinding
import com.tekfy.usbserialandroidkt.util.PermissionUtils
import com.tekfy.usbserialandroidkt.util.SocketHandler
import io.socket.client.Socket;
import java.util.ArrayList
import java.util.Calendar

class MainActivity : AppCompatActivity(){
    private lateinit var binding: ActivityMainBinding

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
    private var permissionDenied = false
    val handler = Handler()
    private var location:Location? = null

    private val listItems: ArrayList<ListItem> = ArrayList()
    private var baudRate = 9600
    private var withIoManager = true

    private lateinit var mSocket: Socket
    private lateinit var messageSocket:String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try{
            SocketHandler.setSocket()
            mSocket = SocketHandler.getSocket()

            mSocket.connect()

        }catch (e:Exception){
            e.printStackTrace()
            Log.d("Fail","Fail to connect socket")
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = locationManager.getBestProvider(Criteria(), false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            location = locationManager.getLastKnownLocation(provider!!)
            showCurrentLocation(location)

        }
        handler.postDelayed(runnable, 8000)

        if(savedInstanceState == null){
            refreshX()
            //enableMyLocation()
        }

        finish()
    }

    private fun showCurrentLocation(location:Location?){
        val latitude = String.format("%.6f",location?.latitude)
        val longitude = String.format("%.6f",location?.longitude)

        val date = setDateTimeFormat()
        val dateFormatted = "${date[2]}-${date[1]}-${date[0]} ${date[3]}:${date[4]}:${date[5]}"

        Toast.makeText(this,"Location: $latitude, $longitude",Toast.LENGTH_SHORT).show()
        Toast.makeText(this,"Fecha: ${date[0]}/${date[1]}/${date[2]} ${date[3]}:${date[4]}:${date[5]}",Toast.LENGTH_SHORT).show()

        val messageData = JsonDataParser(latitude, longitude, dateFormatted, "8")
        messageSocket = Gson().toJson(messageData)
        Log.i("Message Socket", "Mensaje: $messageSocket")
        mSocket.emit("message",messageSocket)

    }

    private fun setDateTimeFormat(): Array<Any> {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR).toString()
        val month = (calendar.get(Calendar.MONTH)+1)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minutes = calendar.get(Calendar.MINUTE)
        val seconds = calendar.get(Calendar.SECOND)

        val strMonth = if(month < 10) "0$month" else month
        val strDay = if(day <10) "0$day" else day
        val strHour = if(hour <10) "0$hour" else hour
        val strMinutes = if(minutes <10) "0$minutes" else minutes
        val strSeconds= if(seconds <10) "0$seconds" else seconds

        return arrayOf(strDay,strMonth,year,strHour,strMinutes,strSeconds)
    }

    val runnable = object : Runnable {
        override fun run() {
            showCurrentLocation(location)
            // Aquí va la lógica de la función que desea ejecutar cada 8 segundos
            handler.postDelayed(this, 8000) // Ejecuta de nuevo este Runnable después de 8 segundos
        }
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED ){
            //map.isMyLocationEnabled = true
            Toast.makeText(this,"Permisos aceptados -----",Toast.LENGTH_SHORT).show()
            return
        }
        if(ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
            || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)){
            PermissionUtils.RationaleDialog.newInstance(LOCATION_PERMISSION_REQUEST_CODE, true)
                .show(supportFragmentManager, "dialog")

            return
        }

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if(requestCode != LOCATION_PERMISSION_REQUEST_CODE){
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }

        if(PermissionUtils.isPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_FINE_LOCATION)
            ||PermissionUtils.isPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_COARSE_LOCATION)){
            enableMyLocation()
        }else{
            permissionDenied = true
        }

    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        if(permissionDenied){
            showMissingPermissionError()
            permissionDenied = false
        }
    }

    private fun showMissingPermissionError() {
        PermissionUtils.PermissionDeniedDialog.newInstance(true)
            .show(supportFragmentManager, "dialog")
    }

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

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE=21
    }

    private inner class MyTimerRunnable:Runnable{
        override fun run() {
            refreshX()
        }

    }

    data class JsonDataParser(
        @SerializedName("LatitudTrack") val latitudTrack:String,
        @SerializedName("LongitudTrack") val longitudTrack:String,
        @SerializedName("FechaHoraTrack") val fechaHoraTrack:String,
        @SerializedName("FrecuenciaPosteo") val frecuenciaPosteo:String,
    )

}