package app.sda.mobile.sda

import com.sda.nativebridge.SdaEngine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

/**
 * Expo module wrapping the shared JNI bridge (com.sda.nativebridge.SdaEngine).
 *
 * `initBundled()` stages the bundled JOC/Atmos fixture and HRTF set to
 * filesDir, initialises + starts the engine, and keeps a loop thread feeding
 * the clip - the JS side just calls it once and listens for status. PCM never
 * crosses to JS.
 */
class SdaModule : Module() {
  private var handle: Long = 0L
  private var feedThread: Thread? = null
  @Volatile private var stopped = false

  override fun definition() = ModuleDefinition {
    Name("SdaEngine")

    AsyncFunction("initBundled") {
      val context = appContext?.reactContext
        ?: throw RuntimeException("no react context")
      val filesDir = context.filesDir

      // Stage HRTF set + stream from native assets into filesDir.
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

      val config = """{"sampleRate":48000,"outputChannels":2,"layout":"7.1.4"}"""
      val ptr = SdaEngine.nativeInit(config, File(hrtfDir, "hrtf-set.json").absolutePath)
      if (ptr == 0L) throw RuntimeException("nativeInit failed (see logcat: SdaEngine)")
      handle = ptr
      val rc = SdaEngine.nativeStart(ptr)
      if (rc != 0) throw RuntimeException("nativeStart failed: $rc")

      // Loop-feed the clip; the engine FIFO absorbs it and the AAudio writer
      // drains at device pace.
      stopped = false
      val worker = Thread {
        val bytes = stream.readBytes()
        val chunk = 24 * 1024
        while (!stopped) {
          var fed = 0
          while (fed < bytes.size && !stopped) {
            val end = minOf(fed + chunk, bytes.size)
            SdaEngine.nativeFeed(handle, bytes.copyOfRange(fed, end))
            fed = end
            try { Thread.sleep(10) } catch (_: InterruptedException) { return@Thread }
          }
          try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
        }
      }
      worker.name = "sda-js-feed"
      worker.start()
    }

    Function("status") { ->
      if (handle == 0L) "{}" else SdaEngine.nativeStatus(handle)
    }

    Function("setVolume") { volume: Float ->
      if (handle != 0L) SdaEngine.nativeSetVolume(handle, volume)
    }

    OnDestroy {
      stopped = true
      if (handle != 0L) {
        SdaEngine.nativeClose(handle)
        handle = 0L
      }
    }
  }
}
