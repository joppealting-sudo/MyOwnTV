package tv.own.owntv.provider.solcon.multicast

import android.content.Context
import android.net.wifi.WifiManager
import tv.own.owntv.provider.solcon.SolconStreamPolicy
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP multicast receiver adapted from cgang/myiptv's MIT-licensed RTP transport.
 *
 * The receiver is intentionally bounded: packet loss is preferable to an unbounded queue on a TV.
 * No payload bytes, local addresses, or provider URLs are logged.
 */
class RtpTransport(
    context: Context,
    private val decision: SolconStreamPolicy.Decision,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : Closeable {
    init {
        require(queueCapacity > 0)
        require(decision.isMulticast)
    }

    data class Stats(
        val receivedPackets: Long,
        val emittedPackets: Long,
        val droppedPackets: Long,
        val malformedPackets: Long,
        val missingPackets: Long,
        val bufferedPackets: Int,
    )

    private val appContext = context.applicationContext
    private val endpoint = URI(decision.normalizedUrl)
    private val group = InetAddress.getByName(endpoint.host)
    private val port = endpoint.port
    private val selection = MulticastNetworkSelector.select(appContext)
    private val socket = MulticastSocket(null)
    private val queue = LinkedBlockingQueue<ByteArray>(queueCapacity)
    private val reorder = RtpReorderBuffer()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val receivedPackets = AtomicLong(0)
    private val emittedPackets = AtomicLong(0)
    private val droppedPackets = AtomicLong(0)
    private val malformedPackets = AtomicLong(0)
    private var receiverThread: Thread? = null

    private val multicastLock: WifiManager.MulticastLock? = if (selection.isWifi) {
        appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock(RtpTransport::class.java.name)
            ?.apply { setReferenceCounted(false) }
    } else {
        null
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            socket.networkInterface = selection.networkInterface
            multicastLock?.acquire()
            socket.joinGroup(InetSocketAddress(group, port), selection.networkInterface)
            receiverThread = Thread(::receiveLoop).apply {
                isDaemon = true
                start()
            }
        } catch (failure: Throwable) {
            close()
            if (failure is IOException) throw failure
            throw IOException(failure)
        }
    }

    @Throws(IOException::class)
    fun takePayload(): ByteArray? {
        if (closed.get() && queue.isEmpty()) return null
        return try {
            val payload = queue.take()
            if (payload === END_OF_STREAM) null else payload
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(interrupted)
        }
    }

    fun stats(): Stats = Stats(
        receivedPackets = receivedPackets.get(),
        emittedPackets = emittedPackets.get(),
        droppedPackets = droppedPackets.get() + reorder.discardedPackets,
        malformedPackets = malformedPackets.get(),
        missingPackets = reorder.missingPackets,
        bufferedPackets = reorder.bufferedCount,
    )

    private fun receiveLoop() {
        val receiveBuffer = ByteArray(MAX_DATAGRAM_BYTES)
        val datagram = DatagramPacket(receiveBuffer, receiveBuffer.size)
        while (!closed.get()) {
            try {
                datagram.length = receiveBuffer.size
                socket.receive(datagram)
                receivedPackets.incrementAndGet()
                val bytes = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                if (decision.transport == SolconStreamPolicy.Transport.UDP_MULTICAST) {
                    enqueue(bytes)
                } else {
                    receiveRtpOrRaw(bytes)
                }
            } catch (_: java.net.SocketException) {
                if (!closed.get()) malformedPackets.incrementAndGet()
                break
            } catch (_: IOException) {
                if (!closed.get()) malformedPackets.incrementAndGet()
            } catch (_: IllegalArgumentException) {
                malformedPackets.incrementAndGet()
            }
        }
    }

    private fun receiveRtpOrRaw(bytes: ByteArray) {
        val parsed = RtpPacket.parse(bytes, bytes.size)
        if (!parsed.isRtp) {
            enqueue(parsed.payloadBytes())
            return
        }
        reorder.offer(parsed).forEach { enqueue(it.payloadBytes()) }
    }

    private fun enqueue(payload: ByteArray) {
        if (payload.isEmpty() || closed.get()) return
        if (!queue.offer(payload)) {
            queue.poll()
            droppedPackets.incrementAndGet()
            if (!queue.offer(payload)) {
                droppedPackets.incrementAndGet()
                return
            }
        }
        emittedPackets.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.leaveGroup(InetSocketAddress(group, port), selection.networkInterface) }
        runCatching { socket.close() }
        receiverThread?.interrupt()
        receiverThread = null
        reorder.clear()
        queue.clear()
        queue.offer(END_OF_STREAM)
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 512
        private const val MAX_DATAGRAM_BYTES = 65_535
        private val END_OF_STREAM = ByteArray(0)
    }
}
