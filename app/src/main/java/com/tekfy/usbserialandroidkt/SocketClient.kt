package com.tekfy.usbserialandroidkt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class SocketClient(private val host: String, private val port: Int) {

    suspend fun send(message: String): String = withContext(Dispatchers.IO) {
        var response = ""

        try {
            // Crea un objeto Socket y establece una conexión con el servidor
            val socket = Socket(host, port)

            // Obtiene una referencia al objeto PrintWriter para enviar datos
            val output = PrintWriter(socket.getOutputStream())

            // Envía el mensaje al servidor a través del socket
            output.println(message)
            output.flush()

            // Lee la respuesta del servidor a través del socket
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            response = input.readLine()

            // Cierra el socket y los flujos de entrada y salida de datos
            input.close()
            output.close()
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        response
    }
}