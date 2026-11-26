package ru.jbosint.lab.server

import android.content.Context
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Минимальный HTTP-сервер на ServerSocket, слушающий только 127.0.0.1.
 * Работает в процессе приложения (запускается из LabService).
 */
class LabHttpServer(
    private val context: Context,
    private val port: Int = 8081
) {
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connections = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "jb-lab-conn").apply { isDaemon = true }
    }

    @Volatile
    var running: Boolean = false
        private set

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        server = socket
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "jb-lab-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            connections.execute {
                runCatching { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ').filter { it.isNotBlank() }
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).trim().lowercase()] =
                        line.substring(index + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
            val body = if (length > 0) {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(bytes, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, Charsets.UTF_8)
            } else {
                ""
            }
            val response = runCatching {
                LabApiModule.handle(context, method, path, headers, body)
            }.getOrElse {
                LabHttpResponse(
                    500,
                    "{\"detail\":\"${(it.message ?: "ошибка").replace("\"", "'")}\"}"
                )
            }
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var value = input.read()
        if (value == -1) return null
        while (value != -1 && value != '\n'.code) {
            if (value != '\r'.code) builder.append(value.toChar())
            value = input.read()
        }
        return builder.toString()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread = null
        runCatching { connections.shutdownNow() }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
