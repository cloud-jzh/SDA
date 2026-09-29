package app.sda.mobile.sda

import android.net.Uri
import com.sda.nativebridge.SdaEngine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Android host for the shared Rust decoder, renderer, and AAudio transport. */
class SdaModule : Module() {
    private var handle = 0L
    private var feedThread: Thread? = null
    @Volatile private var stopped = false
    @Volatile private var feedError: String? = null
    @Volatile private var paused = false
    @Volatile private var feedDone = false
    @Volatile private var hrtfLoadStatus = "KU100 尚未加载"
    @Volatile private var activeInput: InputStream? = null
    @Volatile private var generation = 0L
    @Volatile private var activeIsMp3 = false
    private val nativeLock = Object()
    private val lifecycleLock = Object()

    private fun sha256(file: File): String {
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
        val context = appContext?.reactContext ?: error("no react context")
        val hrtfDir = File(context.filesDir, "hrtf")
        check(hrtfDir.mkdirs() || hrtfDir.isDirectory) { "Cannot create KU100 asset directory" }
        val names = context.assets.list("hrtf")?.toList().orEmpty()
        check("hrtf-set.json" in names && names.any { it.endsWith("_dry.f32") } && names.any { it.endsWith("_wet.f32") }) {
            "Packaged KU100 HRTF asset set is incomplete"
        }
        names.forEach { name ->
            val packaged = context.assets.open("hrtf/$name").use { it.readBytes() }
            val destination = File(hrtfDir, name)
            val matches = if (name.endsWith(".f32")) {
                val expected = MessageDigest.getInstance("SHA-256").digest(packaged).joinToString("") { "%02x".format(it) }
                destination.isFile && destination.length() == packaged.size.toLong() && sha256(destination) == expected
            } else destination.isFile && destination.length() == packaged.size.toLong() && destination.readBytes().contentEquals(packaged)
            if (!matches) destination.writeBytes(packaged)
        }
        hrtfLoadStatus = "KU100 已校验，正在 native 加载"
        val ptr = SdaEngine.nativeInit("""{"sampleRate":48000,"outputChannels":2,"layout":"7.1.4"}""", File(hrtfDir, "hrtf-set.json").absolutePath)
        if (ptr == 0L) {
            hrtfLoadStatus = "KU100 native 加载失败: ${SdaEngine.nativeInitError().ifBlank { "nativeInit failed" }}"
            error(hrtfLoadStatus)
        }
        check(SdaEngine.nativeHrtfLoaded(ptr)) { "native engine did not confirm KU100 load" }
        handle = ptr
        hrtfLoadStatus = "KU100 D1 已由 native 加载"
        val started = SdaEngine.nativeStart(ptr)
        if (started != 0) {
            SdaEngine.nativeClose(ptr)
            handle = 0L
            error("nativeStart failed: $started")
        }
        ptr
    }

    private fun closeCurrentEngine() = synchronized(nativeLock) {
        if (handle != 0L) {
            SdaEngine.nativeClose(handle)
            handle = 0L
        }
    }

    private fun stopFeedThread() {
        synchronized(lifecycleLock) {
            stopped = true
            try { activeInput?.close() } catch (_: Throwable) { }
            activeInput = null
            val worker = feedThread
            worker?.interrupt()
            if (worker != null) {
                worker.join(2_000)
                check(!worker.isAlive) { "Timed out stopping media feed thread; native handle retained" }
            }
            feedThread = null
        }
    }

    override fun definition() = ModuleDefinition {
        Name("SdaEngine")

        AsyncFunction("playUri") { uriString: String, displayName: String, headYawDegrees: Double ->
            require(headYawDegrees.isFinite() && headYawDegrees in -180.0..180.0) { "Head yaw must be between -180 and 180 degrees" }
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            require(ext in setOf("eac3", "ec3", "mp3")) { "仅支持裸 E-AC-3 和 MP3 文件" }
            val context = appContext?.reactContext ?: error("no react context")
            val uri = Uri.parse(uriString)
            val isMp3 = ext == "mp3"
            val cachePath = if (isMp3 && uri.scheme == "file") File(requireNotNull(uri.path)) else null
            if (isMp3 && cachePath == null && context.contentResolver.getType(uri) == null) {
                error("无法识别所选 MP3 文件 URI")
            }
            val workerGeneration = synchronized(lifecycleLock) { generation += 1; generation }
            stopped = false
            val mp3Cache = if (isMp3 && cachePath == null) File(context.cacheDir, "sda-${System.nanoTime()}.mp3") else cachePath
            if (isMp3 && cachePath == null) {
                val source = context.contentResolver.openInputStream(uri) ?: error("无法打开所选 MP3 文件")
                source.use { input -> mp3Cache!!.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(!stopped && generation == workerGeneration) { "文件复制已取消" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
            }
            closeCurrentEngine()
            val ptr = ensureEngine()
            if (isMp3) check(SdaEngine.nativeOpenMp3(ptr, mp3Cache!!.absolutePath) > 0) { "MP3 打开失败: ${SdaEngine.nativeLastError()}" }
            check(SdaEngine.nativeSetHeadYaw(ptr, headYawDegrees.toFloat()) == 0) { "Native head yaw command failed" }
            val input = if (!isMp3) context.contentResolver.openInputStream(uri) ?: error("无法重新打开所选 E-AC-3 文件") else null
            feedError = null
            feedDone = false
            paused = false
            stopped = false
            activeIsMp3 = isMp3
            activeInput = input
            val worker = Thread({
                try {
                    val buffer = ByteArray(24 * 1024)
                    input?.use { stream ->
                        while (!stopped) {
                            val status = synchronized(nativeLock) {
                                if (generation != workerGeneration || handle != ptr || stopped) return@Thread
                                JSONObject(SdaEngine.nativeStatus(ptr))
                            }
                            if (paused || status.optLong("decodedSamplePos") - status.optLong("consumedSamplePos") > 48_000L || status.optInt("fifoFrames") > 48_000) {
                                Thread.sleep(20); continue
                            }
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            val result = synchronized(nativeLock) { if (handle == ptr && !stopped) SdaEngine.nativeFeed(ptr, buffer.copyOf(count)) else -1 }
                            check(result >= 0) { "nativeFeed failed: $result" }
                        }
                    }
                    if (isMp3) {
                        var eof = false
                        while (!eof && !stopped) {
                            val status = synchronized(nativeLock) {
                                if (generation != workerGeneration || handle != ptr || stopped) return@Thread
                                JSONObject(SdaEngine.nativeStatus(ptr))
                            }
                            if (paused || status.optLong("decodedSamplePos") - status.optLong("consumedSamplePos") > 48_000L || status.optInt("fifoFrames") > 48_000) {
                                Thread.sleep(20); continue
                            }
                            val pulled = synchronized(nativeLock) { if (handle == ptr && !stopped) SdaEngine.nativePullMp3(ptr, 4096) else -1 }
                            if (pulled == -4) eof = true else check(pulled >= 0) { "native MP3 decode failed: ${SdaEngine.nativeLastError()}" }
                        }
                    } else if (!stopped) {
                        synchronized(nativeLock) { if (handle == ptr) check(SdaEngine.nativeFinish(ptr) >= 0) { "nativeFinish failed" } }
                    }
                    if (!stopped) {
                        val deadline = System.nanoTime() + 15_000_000_000L
                        while (!stopped) {
                            val status = synchronized(nativeLock) { if (handle != ptr || stopped) return@Thread else JSONObject(SdaEngine.nativeStatus(ptr)) }
                            if (status.optLong("decodedSamplePos") <= status.optLong("consumedSamplePos")) break
                            check(System.nanoTime() < deadline) { "等待 EOF 音频排空超时" }
                            Thread.sleep(20)
                        }
                        synchronized(nativeLock) { if (handle == ptr && !stopped) { SdaEngine.nativeClose(ptr); handle = 0L; feedDone = true } }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Throwable) {
                    feedError = error.message ?: error.toString()
                    synchronized(nativeLock) { if (handle == ptr) { SdaEngine.nativeClose(ptr); handle = 0L } }
                    feedDone = true
                } finally {
                    activeInput = null
                }
            }, "sda-content-feed")
            feedThread = worker
            worker.start()
            displayName
        }

        Function("pause") { -> synchronized(nativeLock) {
            if (handle == 0L) false else {
                check(SdaEngine.nativePause(handle, true) == 0) { "native pause failed" }
                paused = true
                true
            }
        } }
        Function("resume") { -> synchronized(nativeLock) {
            if (handle == 0L) false else {
                check(SdaEngine.nativePause(handle, false) == 0) { "native resume failed" }
                paused = false
                true
            }
        } }
        Function("stop") { ->
            stopFeedThread()
            closeCurrentEngine()
            feedDone = false
            true
        }
        Function("status") { -> synchronized(nativeLock) { if (handle == 0L) "{}" else SdaEngine.nativeStatus(handle) } }
        Function("objects") { -> synchronized(nativeLock) { if (handle == 0L) "{}" else SdaEngine.nativeObjects(handle) } }
        Function("setHeadYaw") { degrees: Double ->
            require(degrees.isFinite() && degrees in -180.0..180.0)
            synchronized(nativeLock) { check(handle != 0L && SdaEngine.nativeSetHeadYaw(handle, degrees.toFloat()) == 0) { "Native head yaw command failed" } }
        }
        Function("resetHeadPose") { -> synchronized(nativeLock) { check(handle != 0L && SdaEngine.nativeResetHeadPose(handle) == 0) { "Native head pose reset failed" } } }
        Function("hrtfStatus") { -> if (handle == 0L) hrtfLoadStatus else if (SdaEngine.nativeHrtfLoaded(handle)) "KU100 D1 已由 native 加载" else "KU100 native 未加载" }
        Function("feedError") { -> feedError }
        Function("feedDone") { -> feedDone }
        Function("stereoBedMode") { -> activeIsMp3 }
        Function("nativeLastError") { -> SdaEngine.nativeLastError() }
        Function("setVolume") { volume: Float -> synchronized(nativeLock) { if (handle != 0L) check(SdaEngine.nativeSetVolume(handle, volume) == 0) } }
        OnDestroy {
            stopFeedThread()
            closeCurrentEngine()
        }
    }
}
