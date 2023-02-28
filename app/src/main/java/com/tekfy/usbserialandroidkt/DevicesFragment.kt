package com.tekfy.usbserialandroidkt

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.lang.String
import java.util.*


class DevicesFragment : ListFragment() {
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

    private val listItems:ArrayList<ListItem> = ArrayList()
    private var listAdapter: ArrayAdapter<ListItem>? = null
    private var baudRate = 19200
    private var withIoManager = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        listAdapter = object:ArrayAdapter<ListItem>(requireActivity(), 0,listItems) {
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var view = view
                val item = listItems[position]
                if (view == null) view =
                    requireActivity().layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val text1: TextView = view!!.findViewById(R.id.text1)
                val text2: TextView = view!!.findViewById(R.id.text2)
                if (item.driver == null) text1.text =
                    "<no driver>" else if (item.driver!!.ports.size === 1) text1.setText(
                    item.driver!!.javaClass.getSimpleName().replace("SerialDriver", "")
                ) else text1.setText(
                    item.driver!!.javaClass.getSimpleName()
                        .replace("SerialDriver", "") + ", Port " + item.port
                )
                text2.text = String.format(
                    Locale.US,
                    "Vendor %04X, Product %04X",
                    item.device!!.vendorId,
                    item.device!!.productId
                )
                return view!!
            }
        }
    }

    /*private fun <T> ArrayAdapter(requireContext: Context, resource: Int, objects: List<T>, function: () -> Unit): ArrayAdapter<T>? {
        return ArrayAdapter(requireContext, resource, 0, objects)
    }*/



    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = requireActivity().layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)
        setEmptyText("<no USB devices found>")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_devices, menu)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        return if (id == R.id.refresh) {
            refresh()
            true
        } else if (id == R.id.baud_rate) {
            val values = resources.getStringArray(R.array.baud_rates)
            val pos = Arrays.asList(*values).indexOf(baudRate.toString())
            val builder = AlertDialog.Builder(
                activity
            )
            builder.setTitle("Baud rate")
            builder.setSingleChoiceItems(values, pos) { dialog: DialogInterface, which: Int ->
                baudRate = values[which].toInt()
                dialog.dismiss()
            }
            builder.create().show()
            true
        } else if (id == R.id.read_mode) {
            val values = resources.getStringArray(R.array.read_modes)
            val pos =
                if (withIoManager) 0 else 1 // read_modes[0]=event/io-manager, read_modes[1]=direct
            val builder = AlertDialog.Builder(
                activity
            )
            builder.setTitle("Read mode")
            builder.setSingleChoiceItems(values, pos) { dialog: DialogInterface, which: Int ->
                withIoManager = which == 0
                dialog.dismiss()
            }
            builder.create().show()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    fun refresh() {
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
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
        listAdapter!!.notifyDataSetChanged()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {

        val item = listItems[position - 1]
        if (item.driver == null) {
            Toast.makeText(activity, "no driver", Toast.LENGTH_SHORT).show()
        } else {
            val args = Bundle()
            item.device?.let { args.putInt("device", it.deviceId) }
            item.port?.let { args.putInt("port", it) }
            args.putInt("baud", baudRate)
            args.putBoolean("withIoManager", withIoManager)
            val fragment: Fragment = TerminalFragment()
            fragment.arguments = args
            requireFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "terminal")
                .addToBackStack(null).commit()
        }
    }
}