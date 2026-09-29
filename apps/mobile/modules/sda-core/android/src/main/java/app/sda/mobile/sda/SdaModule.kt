package app.sda.mobile.sda

import android.net.Uri
import com.sda.nativebridge.SdaEngine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import java.security.MessageDigest

/** Android Expo bridge. Supports raw E-AC-3/JOC elementary streams only. */
class SdaModule : Module() {
    private var handle: Long = 0L
    private var feedThread: Thread? = null
    @Volatile
    private var stopped = false
    @Volatile
    private var feedError: String? = null
    @Volatile
    private var paused = false
    @Volatile
    private var feedDone = false
    @Volatile
    private var hrtfLoadStatus = "未加载"
    @Volatile
    private var activeInput: InputStream? = null
    @Volatile
    private var generation = 0L
    private val nativeLock = Object()
    private val lifecycleLock = Object()

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureEngine(): Long = synchronized(nativeLock) {
        if (handle != 0L) return@synchronized handle
        val context = appContext?.reactContext ?: throw RuntimeException("no react context")
        val hrtfDir = java.io.File(context.filesDir, "hrtf")
        check(hrtfDir.mkdirs() || hrtfDir.isDirectory) { "Cannot create KU100 asset directory: $hrtfDir" }
        val names = context.assets.list("hrtf")?.toList().orEmpty()
        val manifestName = "hrtf-set.json"
        check(manifestName in names && names.any { it.endsWith("_dry.f32") } && names.any { it.endsWith("_wet.f32") }) {
            "Packaged KU100 HRTF asset set is incomplete"
        }
        names.forEach { name ->
            val packaged = context.assets.open("hrtf/$name").use { it.readBytes() }
            val destination = java.io.File(hrtfDir, name)
            val matches = if (name.endsWith(".f32")) {
                val expected = MessageDigest.getInstance("SHA-256").digest(packaged)
                    .joinToString("") { "%02x".format(it) }
                destination.isFile && destination.length() == packaged.size.toLong() && sha256(destination) == expected
            } else destination.isFile && destination.length() == packaged.size.toLong() &&
                destination.readBytes().contentEquals(packaged)
            if (!matches) destination.writeBytes(packaged)
        }
        check(java.io.File(hrtfDir, manifestName).isFile) { "KU100 hrtf-set.json was not copied" }
        hrtfLoadStatus = "KU100 资源已就绪，正在加载"
        val config = """{"sampleRate":48000,"outputChannels":2,"layout":"7.1.4"}"""
        val ptr = SdaEngine.nativeInit(config, java.io.File(hrtfDir, manifestName).absolutePath)
        if (ptr == 0L) {
            val detail = SdaEngine.nativeInitError()
            hrtfLoadStatus = "KU100 加载失败: ${if (detail.isBlank()) "nativeInit failed" else detail}"
            throw RuntimeException(hrtfLoadStatus)
        }
        handle = ptr
        hrtfLoadStatus = "已加载 KU100 D1 (Apache-2.0)"
        val result = SdaEngine.nativeStart(ptr)
        if (result != 0) {
            SdaEngine.nativeClose(ptr)
            handle = 0L
            throw RuntimeException("nativeStart failed: $result")
        }
        ptr
    }

    private fun stopFeedThread() {
        synchronized(lifecycleLock) {
            stopped = true
            try {
                activeInput?.close()
            } catch (_: Throwable) {
            }
            activeInput = null
            val worker = feedThread
            worker?.interrupt()
            if (worker != null) {
                worker.join(2_000)
                check(!worker.isAlive) { "Timed out stopping audio feed thread; native handle retained" }
            }
            feedThread = null
        }
    }

    override fun definition() = ModuleDefinition {
        Name("SdaEngine")

        AsyncFunction("playUri") { uriString: String, displayName: String, headYawDegrees: Double ->
            require(headYawDegrees.isFinite() && headYawDegrees in -180.0..180.0) { "Head yaw must be finite and between -180 and 180 degrees" }
            val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in setOf("eac3", "ec3")) {
                throw IllegalArgumentException("Only raw .eac3/.ec3 elementary streams are supported; MP4/MKV/MP3 are not demuxed/decoded yet")
            }
            stopFeedThread()
            val context = appContext?.reactContext ?: throw RuntimeException("no react context")
            val uri = Uri.parse(uriString)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw RuntimeException("Cannot open selected document: $uri")
            synchronized(nativeLock) {
                if (handle != 0L) {
                    SdaEngine.nativeFinish(handle)
                    SdaEngine.nativeClose(handle)
                    handle = 0L
                }
            }
            val ptr = ensureEngine()
            if (SdaEngine.nativeSetHeadYaw(ptr, headYawDegrees.toFloat()) != 0) {
                throw RuntimeException("Native head yaw command failed during startup")
            }
            val workerGeneration = synchronized(lifecycleLock) {
                generation += 1
                generation
            }
            feedError = null
            feedDone = false
            paused = false
            stopped = false
            activeInput = input
            val worker = Thread({
                try {
                    input.use { stream ->
                        val buffer = ByteArray(24 * 1024)
                        var lastConsumed = 0L
                        var lastProgressNs = System.nanoTime()
                        val maxLead = 48_000L
                        while (!stopped) {
                            val state = synchronized(nativeLock) {
                                if (generation != workerGeneration || handle != ptr || stopped) return@Thread
                                JSONObject(SdaEngine.nativeStatus(ptr))
                            }
                            val decoded = state.getLong("decodedSamplePos")
                            val consumed = state.getLong("consumedSamplePos")
                            val fifoFrames = state.optInt("fifoFrames", 0)
                            if (consumed != lastConsumed) {
                                lastConsumed = consumed
                                lastProgressNs = System.nanoTime()
                            }
                            check(decoded == 0L || System.nanoTime() - lastProgressNs < 15_000_000_000L) {
                                "Audio consumption stalled (decoded=$decoded consumed=$consumed)"
                            }
                            if (paused || decoded - consumed > maxLead || fifoFrames > maxLead) {
                                Thread.sleep(20)
                                continue
                            }
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            val result = synchronized(nativeLock) {
                                if (generation != workerGeneration || handle != ptr || stopped) -1 else SdaEngine.nativeFeed(
                                    ptr,
                                    buffer.copyOf(count)
                                )
                            }
                            check(result >= 0) { "nativeFeed failed: $result" }
                        }
                        if (!stopped) {
                            val eofDeadline = System.nanoTime() + 15_000_000_000L
                            synchronized(nativeLock) {
                                if (generation == workerGeneration && handle == ptr && !stopped) {
                                    val result = SdaEngine.nativeFinish(ptr)
                                    check(result >= 0) { "nativeFinish failed: $result" }
                                }
                            }
                            while (!stopped) {
                                val state = synchronized(nativeLock) {
                                    if (generation != workerGeneration || handle != ptr || stopped) return@Thread
                                    JSONObject(SdaEngine.nativeStatus(ptr))
                                }
                                val decoded = state.getLong("decodedSamplePos")
                                val consumed = state.getLong("consumedSamplePos")
                                val remaining = if (decoded > consumed) decoded - consumed else 0L
                                if (remaining == 0L) break
                                check(System.nanoTime() < eofDeadline) { "Timed out waiting for audio drain at EOF" }
                                Thread.sleep(20)
                            }
                            synchronized(nativeLock) {
                                if (generation == workerGeneration && handle == ptr && !stopped) {
                                    SdaEngine.nativeClose(ptr)
                                    handle = 0L
                                    feedDone = true
                                }
                            }
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Throwable) {
                    feedError = error.message ?: error.toString()
                    synchronized(nativeLock) {
                        if (generation == workerGeneration && handle == ptr) {
                            SdaEngine.nativeClose(ptr)
                            handle = 0L
                        }
                    }
                    feedDone = true
                }
            }, "sda-content-feed")
            feedThread = worker
            worker.start()
            displayName
        }

        Function("pause") { ->
            synchronized(nativeLock) {
                if (handle == 0L) return@synchronized false
                check(SdaEngine.nativePause(handle, true) == 0) { "native pause failed" }
                paused = true
                true
            }
        }

        Function("resume") { ->
            synchronized(nativeLock) {
                if (handle == 0L) return@synchronized false
                check(SdaEngine.nativePause(handle, false) == 0) { "native resume failed" }
                paused = false
                true
            }
        }

        Function("stop") { ->
            stopFeedThread()
            synchronized(nativeLock) {
                if (handle != 0L) {
                    SdaEngine.nativeFinish(handle)
                    SdaEngine.nativeClose(handle)
                    handle = 0L
                }
            }
            feedDone = true
            true
        }

        Function("status") { ->
            synchronized(nativeLock) {
                if (handle == 0L) "{}" else SdaEngine.nativeStatus(handle)
            }
        }

        Function("objects") { ->
            synchronized(nativeLock) {
                if (handle == 0L) "{}" else SdaEngine.nativeObjects(handle)
            }
        }

        Function("setHeadYaw") { degrees: Double ->
            require(degrees.isFinite() && degrees in -180.0..180.0) { "Head yaw must be finite and between -180 and 180 degrees" }
            synchronized(nativeLock) {
                check(
                    handle != 0L && SdaEngine.nativeSetHeadYaw(
                        handle,
                        degrees.toFloat()
                    ) == 0
                ) { "Native head yaw command failed" }
            }
        }

        Function("resetHeadPose") { ->
            synchronized(nativeLock) {
                check(handle != 0L && SdaEngine.nativeResetHeadPose(handle) == 0) { "Native head pose reset failed" }
            }
        }

        Function("hrtfStatus") { -> hrtfLoadStatus }

        Function("feedError") { -> feedError }

        Function("feedDone") { -> feedDone }

        Function("setVolume") { volume: Float ->
            synchronized(nativeLock) {
                if (handle != 0L) SdaEngine.nativeSetVolume(handle, volume)
            }
        }

        OnDestroy {
            stopFeedThread()
            synchronized(nativeLock) {
                if (handle != 0L) {
                    SdaEngine.nativeClose(handle)
                    handle = 0L
                }
            }
        }
    }
}
