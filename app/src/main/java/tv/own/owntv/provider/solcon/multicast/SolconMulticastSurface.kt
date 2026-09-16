package tv.own.owntv.provider.solcon.multicast

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi

/** SurfaceView bridge for the multicast Media3 engine. */
@UnstableApi
@Composable
fun SolconMulticastSurface(
    engine: SolconMulticastEngine,
    modifier: Modifier = Modifier,
    keepAwake: Boolean = true,
) {
    val callback = remember(engine) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                engine.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                engine.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine.setSurface(null)
            }
        }
    }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = keepAwake
                holder.addCallback(callback)
            }
        },
        update = { view ->
            view.keepScreenOn = keepAwake
            if (view.holder.surface?.isValid == true) engine.setSurface(view.holder.surface)
        },
        modifier = modifier,
    )

    DisposableEffect(engine, callback) {
        onDispose { engine.setSurface(null) }
    }
}
