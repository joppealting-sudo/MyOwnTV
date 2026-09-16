package tv.own.owntv.provider.solcon.multicast

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/** Creates an independent multicast data source for every Media3 load. */
@UnstableApi
class RtpDataSourceFactory(
    context: Context,
) : DataSource.Factory {
    private val appContext = context.applicationContext

    override fun createDataSource(): DataSource = RtpDataSource(appContext)
}
