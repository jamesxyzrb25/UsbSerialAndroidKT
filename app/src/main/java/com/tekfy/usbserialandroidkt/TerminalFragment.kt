package com.tekfy.usbserialandroidkt

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import androidx.viewbinding.BuildConfig
import java.io.IOException
import java.util.*

import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.tekfy.usbserialandroidkt.util.HexDump
import kotlin.properties.Delegates


class TerminalFragment() : Fragment(), SerialInputOutputManager.Listener {

    enum class UsbPermission {
        Unknown, Requested, Granted, Denied
    }

    val INTENT_ACTION_GRANT_USB: String = BuildConfig.LIBRARY_PACKAGE_NAME + ".GRANT_USB"
    val WRITE_WAIT_MILLIS = 2000
    val READ_WAIT_MILLIS = 2000

    private var deviceId:Int =0
    private var portNum:Int =0
    private var baudRate:Int = 0
    private var withIoManager = false

    var broadcastReceiver: BroadcastReceiver? = null
    private var mainLooper: Handler? = null
    private lateinit var receiveText: TextView
    private lateinit var controlLines: ControlLines

    var usbIoManager: SerialInputOutputManager? = null
    var usbSerialPort: UsbSerialPort? = null
    var usbPermission: UsbPermission = UsbPermission.Unknown
    private var connected:Boolean = false

    init {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (INTENT_ACTION_GRANT_USB == intent.action) {
                    usbPermission = if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false
                        )
                    ) UsbPermission.Granted else UsbPermission.Denied
                    connect()
                }
            }
        }
        mainLooper = Handler(Looper.getMainLooper())
    }

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        setRetainInstance(true)
        deviceId = requireArguments().getInt("device")
        portNum = requireArguments().getInt("port")
        baudRate = requireArguments().getInt("baud")
        withIoManager = requireArguments().getBoolean("withIoManager")
    }

    override fun onResume() {
        super.onResume()
        activity?.registerReceiver(broadcastReceiver, IntentFilter(INTENT_ACTION_GRANT_USB))
        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted) mainLooper?.post(
            Runnable { this.connect() })
    }

    override fun onPause() {
        if (connected) {
            status("disconnected")
            disconnect()
        }
        activity?.unregisterReceiver(broadcastReceiver)
        super.onPause()
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText.setTextColor(resources.getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText.movementMethod = ScrollingMovementMethod.getInstance()
        val sendText = view.findViewById<TextView>(R.id.send_text)
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { v: View? ->
            send(
                sendText.text.toString()
            )
        }
        val receiveBtn = view.findViewById<View>(R.id.receive_btn)
        controlLines = ControlLines(view)
        if (withIoManager) {
            receiveBtn.visibility = View.GONE
        } else {
            receiveBtn.setOnClickListener { v: View? -> read() }
        }
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.clear) {
            receiveText.setText("")
            true
        } else if (id == R.id.send_break) {
            if (!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    usbSerialPort?.setBreak(true)
                    Thread.sleep(100) // should show progress bar instead of blocking UI thread
                    usbSerialPort?.setBreak(false)
                    val spn = SpannableStringBuilder()
                    spn.append("send <break>\n")
                    spn.setSpan(
                        ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                        0,
                        spn.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    receiveText.append(spn)
                } catch (ignored: UnsupportedOperationException) {
                    Toast.makeText(getActivity(), "BREAK not supported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(getActivity(), "BREAK failed: " + e.message, Toast.LENGTH_SHORT)
                        .show()
                }
            }
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    /*
     * Serial
     */
    override fun onNewData(data: ByteArray?) {
        mainLooper?.post(Runnable {
            if (data != null) {
                receive(data)
            }
        })
    }

    override fun onRunError(e: Exception) {
        mainLooper?.post(Runnable {
            status("connection lost: " + e.message)
            disconnect()
        })
    }

    /*
     * Serial + UI
     */
    private fun connect() {
        var device: UsbDevice? = null
        val usbManager = getActivity()?.getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) if (v.deviceId == deviceId) device = v
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
        if (driver.getPorts().size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.getPorts().get(portNum)
        val usbConnection = usbManager.openDevice(driver.getDevice())
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(
                driver.getDevice()
            )
        ) {
            usbPermission = UsbPermission.Requested
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val usbPermissionIntent =
                PendingIntent.getBroadcast(getActivity(), 0, Intent(INTENT_ACTION_GRANT_USB), flags)
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
            usbSerialPort?.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE)
            if (withIoManager) {
                usbIoManager = SerialInputOutputManager(usbSerialPort, this)
                usbIoManager!!.start()
            }
            status("connected")
            connected = true
            controlLines.start()
        } catch (e: Exception) {
            status("connection failed: " + e.message)
            disconnect()
        }
    }

    private fun disconnect() {
        connected = false
        controlLines.stop()
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
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val data = """$str
""".toByteArray()
            val spn = SpannableStringBuilder()
            spn.append(
                """send ${data.size} bytes
"""
            )
            spn.append(HexDump.dumpHexString(data)).append("\n")
            spn.setSpan(
                ForegroundColorSpan(getResources().getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText.append(spn)
            usbSerialPort?.write(data, WRITE_WAIT_MILLIS)
        } catch (e: Exception) {
            onRunError(e)
        }
    }

    private fun read() {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show()
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
        if (data.size > 0) spn.append(HexDump.dumpHexString(data)).append("\n")
        receiveText.append(spn)
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
        receiveText.append(spn)
    }

    internal class ControlLines(view: View):Fragment(){
        private val runnable: Runnable
        private val rtsBtn: ToggleButton
        private val ctsBtn: ToggleButton
        private val dtrBtn: ToggleButton
        private val dsrBtn: ToggleButton
        private val cdBtn: ToggleButton
        private val riBtn: ToggleButton

        init {
            runnable =
                Runnable { this.run() } // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
            rtsBtn = view.findViewById(R.id.controlLineRts)
            ctsBtn = view.findViewById(R.id.controlLineCts)
            dtrBtn = view.findViewById(R.id.controlLineDtr)
            dsrBtn = view.findViewById(R.id.controlLineDsr)
            cdBtn = view.findViewById(R.id.controlLineCd)
            riBtn = view.findViewById(R.id.controlLineRi)
            rtsBtn.setOnClickListener { v: View ->
                toggle(
                    v
                )
            }
            dtrBtn.setOnClickListener { v: View ->
                toggle(
                    v
                )
            }
        }

        private fun toggle(v: View) {
            val btn = v as ToggleButton
            if (!TerminalFragment().connected) {
                btn.isChecked = !btn.isChecked
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show()
                return
            }
            var ctrl = ""
            try {
                if (btn == rtsBtn) {
                    ctrl = "RTS"
                    TerminalFragment().usbSerialPort?.rts = btn.isChecked
                }
                if (btn == dtrBtn) {
                    ctrl = "DTR"
                    TerminalFragment().usbSerialPort?.dtr = btn.isChecked
                }
            } catch (e: IOException) {
                TerminalFragment().status("set" + ctrl + "() failed: " + e.message)
            }
        }

        private fun run() {
            if (!TerminalFragment().connected) return
            try {
                val controlLines: EnumSet<UsbSerialPort.ControlLine> =
                    TerminalFragment().usbSerialPort?.getControlLines() as EnumSet<UsbSerialPort.ControlLine>
                rtsBtn.isChecked = controlLines.contains(UsbSerialPort.ControlLine.RTS)
                ctsBtn.isChecked = controlLines.contains(UsbSerialPort.ControlLine.CTS)
                dtrBtn.isChecked = controlLines.contains(UsbSerialPort.ControlLine.DTR)
                dsrBtn.isChecked = controlLines.contains(UsbSerialPort.ControlLine.DSR)
                cdBtn.isChecked = controlLines.contains(UsbSerialPort.ControlLine.CD)
                riBtn.isChecked = controlLines.contains(UsbSerialPort.ControlLine.RI)
                TerminalFragment().mainLooper?.postDelayed(runnable, Companion.refreshInterval.toLong())
            } catch (e: IOException) {
                TerminalFragment().status("getControlLines() failed: " + e.message + " -> stopped control line refresh")
            }
        }

        fun start() {
            if (!TerminalFragment().connected) return
            try {
                val controlLines: EnumSet<UsbSerialPort.ControlLine> =
                    TerminalFragment().usbSerialPort?.getSupportedControlLines() as EnumSet<UsbSerialPort.ControlLine>
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.visibility =
                    View.INVISIBLE
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.visibility =
                    View.INVISIBLE
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.visibility =
                    View.INVISIBLE
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.visibility =
                    View.INVISIBLE
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD)) cdBtn.visibility =
                    View.INVISIBLE
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI)) riBtn.visibility =
                    View.INVISIBLE
                run()
            } catch (e: IOException) {
                Toast.makeText(
                    getActivity(),
                    "getSupportedControlLines() failed: " + e.message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        fun stop() {
            TerminalFragment().mainLooper?.removeCallbacks(runnable)
            rtsBtn.isChecked = false
            ctsBtn.isChecked = false
            dtrBtn.isChecked = false
            dsrBtn.isChecked = false
            cdBtn.isChecked = false
            riBtn.isChecked = false
        }

        companion object {
            private const val refreshInterval = 200 // msec
        }
    }
}