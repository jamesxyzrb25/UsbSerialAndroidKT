package com.tekfy.usbserialandroidkt

import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CoroutinesService: LifecycleService() {
    fun sendMessageSocket(host:String, port:Int, message:String){
        val serviceLifecycle = lifecycleScope

        serviceLifecycle.launch(Dispatchers.IO){
            val client = SocketClient(host, port)
            val response = client.send(message)
            Log.d("Socket", "Respuesta del servidor: $response")
        }
    }
}