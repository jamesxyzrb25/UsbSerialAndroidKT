package com.tekfy.usbserialandroidkt

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager


class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var mFragmentManager: FragmentManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        /*val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar);
        supportFragmentManager.addOnBackStackChangedListener(this)*/
        /*if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .add(R.id.fragment, DevicesFragment(), "devices").commit() else onBackStackChanged()*/
        mFragmentManager = supportFragmentManager
        mFragmentManager.addOnBackStackChangedListener { onBackStackChanged() }
        val deviceFragment = DevicesFragment()

        if(savedInstanceState == null){
            mFragmentManager.beginTransaction()
                .add(R.id.fragment, deviceFragment, DevicesFragment::class.java.name).commit()
        }else{
            onBackStackChanged()
        }

    }



    override fun onBackStackChanged() {
        //supportActionBar!!.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED" == intent.action) {
            val terminal = supportFragmentManager.findFragmentByTag("terminal") as TerminalFragment?
            terminal?.status("USB device detected")
        }
        super.onNewIntent(intent)
    }
}