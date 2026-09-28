package app.sda.mobile.sda

import com.sda.nativebridge.SdaEngine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import java.io.File

/** Expo bridge. PCM stays native; the feed loop follows the consumed clock. */
class SdaModule : Module() {
  private var handle: Long = 0L
  private var feedThread: Thread? = null
  @Volatile private var stopped = false
  private var feedError: String? = null
  private val nativeLock = Object()

  override fun definition() = ModuleDefinition {
    Name("SdaEngine")

    AsyncFunction("initBundled") {
      val context = appContext?.reactContext ?: throw RuntimeException("no react context")
      val filesDir = context.filesDir

      val hrtfDir = File(filesDir, "hrtf")
      hrtfDir.mkdirs()
      context.assets.list("hrtf")?.forEach { name ->
        context.assets.open("hrtf/$name").use { input ->
          File(hrtfDir, name).outputStream().use { output -> input.copyTo(output) }
        }
      }
      val stream = File(filesDir, "joc_atmos_1s.eac3")
      context.assets.open("joc_atmos_1s.eac3").use { input ->
        stream.outputStream().use { output -> input.copyTo(output) }
      }

      synchronized(nativeLock) {
        if (handle != 0L) throw RuntimeException("engine already initialized")
        stopped = false
        feedError = null
        val config = """{"sampleRate":48000,"outputChannels":2,"layout":"7.1.4"}"""
        val ptr = SdaEngine.nativeInit(config, File(hrtfDir, "hrtf-set.json").absolutePath)
        if (ptr == 0L) throw RuntimeException("nativeInit failed (see logcat: SdaEngine)")
        handle = ptr
        val rc = SdaEngine.nativeStart(ptr)
        if (rc != 0) {
          SdaEngine.nativeClose(ptr)
          handle = 0L
          throw RuntimeException("nativeStart failed: $rc")
        }
      }

      val worker = Thread {
        try {
          val bytes = stream.readBytes()
          val chunk = 24 * 1024
          var lastConsumed = 0L
          var lastProgressNs = System.nanoTime()
          val maxLeadSamples = 48000L
          while (!stopped) {
            val status = synchronized(nativeLock) {
              if (handle == 0L) return@Thread
              JSONObject(SdaEngine.nativeStatus(handle))
            }
            val decoded = status.getLong("decodedSamplePos")
            val consumed = status.getLong("consumedSamplePos")
            val now = System.nanoTime()
            if (consumed != lastConsumed) {
              lastConsumed = consumed
              lastProgressNs = now
            }
            check(decoded == 0L || now - lastProgressNs < 15_000_000_000L) {
              "audio consumption stalled (decoded=$decoded consumed=$consumed)"
            }

            if (decoded - consumed > maxLeadSamples) {
              Thread.sleep(20)
              continue
            }

            var fed = 0
            while (fed < bytes.size && !stopped) {
              val end = minOf(fed + chunk, bytes.size)
              val result = synchronized(nativeLock) {
                if (handle == 0L) -1 else SdaEngine.nativeFeed(handle, bytes.copyOfRange(fed, end))
              }
              check(result >= 0) { "nativeFeed failed: $result" }
              fed = end
            }
            Thread.sleep(1200)
          }
        } catch (error: InterruptedException) {
          Thread.currentThread().interrupt()
        } catch (error: Throwable) {
          feedError = error.message ?: error.toString()
        }
      }.apply {
        name = "sda-js-feed"
        start()
      }
      feedThread = worker
      true
    }

    Function("status") { ->
      synchronized(nativeLock) {
        if (handle == 0L) "{}" else SdaEngine.nativeStatus(handle)
      }
    }

    Function("setVolume") { volume: Float ->
      synchronized(nativeLock) {
        if (handle != 0L) SdaEngine.nativeSetVolume(handle, volume)
      }
    }

    Function("feedError") { -> feedError }

    OnDestroy {
      stopped = true
      val worker = feedThread
      worker?.interrupt()
      worker?.join()
      feedThread = null
      synchronized(nativeLock) {
        if (handle != 0L) {
          SdaEngine.nativeClose(handle)
          handle = 0L
        }
      }
    }
  }
}
