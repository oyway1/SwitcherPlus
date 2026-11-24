package com.switcherplus.app.protocol

import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Blocking TCP client for one Type-1 Switcher device. Call from a background thread. */
class SwitcherClient(
    private val ip: String,
    private val deviceId: String,
    private val deviceKey: String,
    private val timeoutMs: Int = 4000,
) {
    private fun <T> session(block: (send: (String) -> ByteArray, sessionId: String, ts: String) -> T): T {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(ip, SwitcherProtocol.TCP_PORT), timeoutMs)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            val send: (String) -> ByteArray = { hex ->
                out.write(SwitcherProtocol.finalizePacket(hex))
                out.flush()
                readResponse(inp)
            }
            val ts = SwitcherProtocol.timestampHex()
            val loginResp = send(SwitcherProtocol.loginPacket(ts, deviceKey))
            val sessionId = SwitcherProtocol.parseSessionId(loginResp)
            return block(send, sessionId, ts)
        }
    }

    private fun readResponse(inp: InputStream): ByteArray {
        val buf = ByteArray(1024)
        val n = inp.read(buf)
        if (n <= 0) throw java.io.IOException("device closed the connection")
        return buf.copyOf(n)
    }

    fun getState(): SwitcherProtocol.StateResponse = session { send, sid, ts ->
        SwitcherProtocol.parseState(send(SwitcherProtocol.getStatePacket(sid, ts, deviceId)))
    }

    /** Turn on; if [timerMinutes] > 0 the device turns itself off after that time. */
    fun turnOn(timerMinutes: Int = 0) {
        session { send, sid, ts ->
            val r = send(SwitcherProtocol.controlPacket(sid, ts, deviceId, true, timerMinutes))
            if (r.isEmpty()) throw java.io.IOException("empty response")
        }
    }

    fun turnOff() {
        session { send, sid, ts ->
            val r = send(SwitcherProtocol.controlPacket(sid, ts, deviceId, false, 0))
            if (r.isEmpty()) throw java.io.IOException("empty response")
        }
    }
}

/**
 * Listens for the UDP status broadcasts every Switcher device sends roughly every 4 seconds.
 * Blocking; run on a background thread and call [close] to stop.
 */
class SwitcherListener(private val onDevice: (SwitcherProtocol.Broadcast, String) -> Unit) {
    @Volatile private var running = true
    private val sockets = mutableListOf<DatagramSocket>()

    fun run() {
        val threads = SwitcherProtocol.UDP_PORTS.toList().mapNotNull { port: Int ->
            val socket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            synchronized(sockets) { sockets.add(socket) }
            Thread({ loop(socket) }, "switcher-udp-$port").apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
    }

    private fun loop(socket: DatagramSocket) {
        val buf = ByteArray(1024)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = packet.data.copyOf(packet.length)
                val parsed = SwitcherProtocol.parseBroadcast(data) ?: continue
                onDevice(parsed, packet.address.hostAddress ?: continue)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (!running) break
                Thread.sleep(500)
            }
        }
        socket.close()
    }

    fun close() {
        running = false
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }
}
