package tv.own.owntv.provider.solcon.multicast

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import tv.own.owntv.provider.solcon.SolconStreamPolicy
import java.io.IOException

/** Media3 DataSource backed by the bounded multicast transport. */
@UnstableApi
class RtpDataSource(
    context: Context,
) : BaseDataSource(true) {
    private val appContext = context.applicationContext
    private var transport: RtpTransport? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var currentPayload: ByteArray? = null
    private var currentOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        val decision = SolconStreamPolicy.classify(dataSpec.uri.toString())
        if (!decision.isMulticast) throw IOException()

        val nextTransport = RtpTransport(appContext, decision)
        try {
            nextTransport.start()
        } catch (failure: Throwable) {
            nextTransport.close()
            if (failure is IOException) throw failure
            throw IOException(failure)
        }

        transport = nextTransport
        currentUri = Uri.parse(decision.normalizedUrl)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT

        var payload = currentPayload
        if (payload == null || currentOffset >= payload.size) {
            payload = transport?.takePayload() ?: return C.RESULT_END_OF_INPUT
            currentPayload = payload
            currentOffset = 0
        }

        val count = minOf(length, payload.size - currentOffset)
        System.arraycopy(payload, currentOffset, buffer, offset, count)
        currentOffset += count
        if (currentOffset >= payload.size) {
            currentPayload = null
            currentOffset = 0
        }
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        transport?.close()
        transport = null
        currentPayload = null
        currentOffset = 0
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    fun stats(): RtpTransport.Stats? = transport?.stats()
}
