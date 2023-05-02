package com.tekfy.usbserialandroidkt.util

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URISyntaxException

object SocketHandler {
    lateinit var  mSocket: Socket

    @Synchronized
    fun setSocket(){
        try{
            mSocket = IO.socket("http://192.168.18.6:3000/gps/posteo_enviados")
        }catch (e:URISyntaxException){
            Log.e("Error URI","Error $e")
        }
    }

    @Synchronized
    fun getSocket():Socket{
        return mSocket
    }
}