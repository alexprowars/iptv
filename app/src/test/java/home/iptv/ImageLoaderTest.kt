package home.iptv

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.imageDecoderEnabled
import coil3.request.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = IptvApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderTest {
    @Test fun logosLoadOverHttpAndRespectDiskCacheControl() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<IptvApplication>()
        // Robolectric's native ImageDecoder does not support files; retain the real HTTP/cache setup.
        val loader = SingletonImageLoader.get(app).newBuilder().imageDecoderEnabled(false).build()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "image/png")
                .setHeader("Cache-Control", if (request.path.orEmpty().contains("true")) "max-age=600" else "no-store")
                .setBody(Buffer().write(png))
        }
        server.start()
        try {
            for (cacheable in listOf(true, false)) {
                val request = ImageRequest.Builder(app).data(server.url("/logo-$cacheable.png").toString())
                    .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false).build()
                val first = loader.execute(request)
                assertTrue((first as? ErrorResult)?.throwable?.stackTraceToString() ?: first.toString(), first is SuccessResult)
                assertEquals(DataSource.NETWORK, (first as SuccessResult).dataSource)
                val second = loader.execute(request)
                assertTrue((second as? ErrorResult)?.throwable?.stackTraceToString() ?: second.toString(), second is SuccessResult)
                assertEquals(if (cacheable) DataSource.DISK else DataSource.NETWORK, (second as SuccessResult).dataSource)
            }
            assertEquals(3, server.requestCount)
        } finally {
            loader.shutdown()
            server.shutdown()
        }
    }
}
