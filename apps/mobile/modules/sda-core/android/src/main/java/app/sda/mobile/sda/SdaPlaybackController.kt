package app.sda.mobile.sda

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sda.nativebridge.SdaEngine
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Single process owner for the JNI handle, input worker, and playback state. */
object SdaPlaybackController {
    @Volatile private var app: Context? = null
    @Volatile private var handle = 0L
    @Volatile private var output: Media3Output? = null
    @Volatile private var feedThread: Thread? = null
    @Volatile var stopped = false
        private set
    @Volatile var feedError: String? = null
        private set
    @Volatile var paused = false
        private set
    @Volatile var feedDone = false
        private set
    @Volatile var hrtfLoadStatus = "KU100 尚未加载"
        private set
    @Volatile private var activeInput: InputStream? = null
    @Volatile private var generation = 0L
    @Volatile var activeIsMp3 = false
        private set
    @Volatile private var currentVolume = 0.25f
    @Volatile var title = "SDA 空间音频"
        private set
    @Volatile var playing = false
        private set
    @Volatile var loading = false
        private set
    @Volatile var userPaused = false
        internal set
    @Volatile private var currentUri = ""

    fun setTitle(value: String) { title = value }
    fun playbackState(): String {
        val active = loading || playing || (handle != 0L && !feedDone)
        val state = "{\"uri\":\"${currentUri.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"title\":\"${title.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"playing\":$active,\"paused\":$paused,\"loading\":$loading,\"userPaused\":$userPaused,\"feedDone\":$feedDone}"
        Log.i("SdaPlaybackState", "handle=$handle active=$active loading=$loading playing=$playing paused=$paused feedDone=$feedDone title=$title")
        return state
    }
    private val nativeLock = Object()
    private val lifecycleLock = Object()

    fun attach(context: Context) { app = context.applicationContext }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureEngine(): Long = synchronized(nativeLock) {
        if (handle != 0L) return@synchronized handle
        val context = app ?: error("playback controller is not attached")
        val hrtfDir = File(context.filesDir, "hrtf")
        check(hrtfDir.mkdirs() || hrtfDir.isDirectory) { "Cannot create KU100 asset directory" }
        val names = context.assets.list("hrtf")?.toList().orEmpty()
        check("hrtf-set.json" in names && names.any { it.endsWith("_dry.f32") } && names.any { it.endsWith("_wet.f32") }) { "Packaged KU100 HRTF asset set is incomplete" }
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
        if (ptr == 0L) { hrtfLoadStatus = "KU100 native 加载失败: ${SdaEngine.nativeInitError().ifBlank { "nativeInit failed" }}"; error(hrtfLoadStatus) }
        check(SdaEngine.nativeHrtfLoaded(ptr)) { "native engine did not confirm KU100 load" }
        handle = ptr
        hrtfLoadStatus = "KU100 D1 已由 native 加载"
        val output = Media3Output(context.applicationContext)
        this.output = output
        val started = SdaEngine.nativeStart(ptr, output)
        if (started != 0) {
            output.close()
            this.output = null
            SdaEngine.nativeClose(ptr)
            handle = 0L
            error("nativeStart failed: $started")
        }
        ptr
    }

    private fun closeEngine() = synchronized(nativeLock) {
        if (handle != 0L) { SdaEngine.nativeClose(handle); handle = 0L }
    }

    private fun stopWorker() {
        synchronized(lifecycleLock) {
            stopped = true
            try { activeInput?.close() } catch (_: Throwable) { }
            activeInput = null
            val worker = feedThread
            worker?.interrupt()
            if (worker != null) { worker.join(2_000); check(!worker.isAlive) { "Timed out stopping media feed thread; native handle retained" } }
            feedThread = null
        }
    }

    fun playUri(uriString: String, displayName: String, headYawDegrees: Double): String {
        require(headYawDegrees.isFinite() && headYawDegrees in -180.0..180.0) { "Head yaw must be between -180 and 180 degrees" }
        val context = app ?: error("playback controller is not attached")
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(ext in setOf("eac3", "ec3", "mp3")) { "仅支持裸 E-AC-3 和 MP3 文件" }
        val uri = Uri.parse(uriString)
        val isMp3 = ext == "mp3"
        val cachePath = if (isMp3 && uri.scheme == "file") File(requireNotNull(uri.path)) else null
        if (isMp3 && cachePath == null && context.contentResolver.getType(uri) == null) error("无法识别所选 MP3 文件 URI")
        val workerGeneration = synchronized(lifecycleLock) { generation += 1; generation }
        loading = true
        stopped = false
        feedError = null
        feedDone = false
        paused = false
        title = displayName
        currentUri = uriString
        playing = false
        userPaused = false
        val mp3Cache = if (isMp3 && cachePath == null) File(context.cacheDir, "sda-${System.nanoTime()}.mp3") else cachePath
        if (isMp3 && cachePath == null) {
            val source = context.contentResolver.openInputStream(uri) ?: error("无法打开所选 MP3 文件")
            source.use { input -> mp3Cache!!.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { check(!stopped && generation == workerGeneration) { "文件复制已取消" }; val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            } }
        }
        stopWorker(); closeEngine(); stopped = false
        SdaPlaybackService.start(context, displayName)
        val ptr = ensureEngine()
        if (isMp3) synchronized(nativeLock) { check(SdaEngine.nativeOpenMp3(ptr, mp3Cache!!.absolutePath) > 0) { "MP3 打开失败: ${SdaEngine.nativeLastError()}" } }
        synchronized(nativeLock) { check(SdaEngine.nativeSetHeadYaw(ptr, headYawDegrees.toFloat()) == 0) { "Native head yaw command failed" } }
        val input = if (!isMp3) context.contentResolver.openInputStream(uri) ?: error("无法重新打开所选 E-AC-3 文件") else null
        feedError = null
        feedDone = false
        paused = false
        stopped = false
        activeIsMp3 = isMp3
        activeInput = input
        loading = false
        playing = true
        userPaused = false
        val worker = Thread({
            try {
                val buffer = ByteArray(24 * 1024)
                input?.use { stream -> while (!stopped) {
                    val status = synchronized(nativeLock) { if (generation != workerGeneration || handle != ptr || stopped) return@Thread; JSONObject(SdaEngine.nativeStatus(ptr)) }
                    if (paused || status.optLong("decodedSamplePos") - status.optLong("consumedSamplePos") > 48_000L || status.optInt("fifoFrames") > 48_000) { Thread.sleep(20); continue }
                    val count = stream.read(buffer); if (count < 0) break; if (count == 0) continue
                    val result = synchronized(nativeLock) { if (handle == ptr && !stopped) SdaEngine.nativeFeed(ptr, buffer.copyOf(count)) else -1 }
                    check(result >= 0) { "nativeFeed failed: $result" }
                } }
                if (isMp3) {
                    var eof = false
                    while (!eof && !stopped) {
                        val status = synchronized(nativeLock) { if (generation != workerGeneration || handle != ptr || stopped) return@Thread; JSONObject(SdaEngine.nativeStatus(ptr)) }
                        if (paused || status.optLong("decodedSamplePos") - status.optLong("consumedSamplePos") > 48_000L || status.optInt("fifoFrames") > 48_000) { Thread.sleep(20); continue }
                        val pulled = synchronized(nativeLock) { if (handle == ptr && !stopped) SdaEngine.nativePullMp3(ptr, 4096) else -1 }
                        if (pulled == -4) eof = true else check(pulled >= 0) { "native MP3 decode failed: ${SdaEngine.nativeLastError()}" }
                    }
                } else if (!stopped) synchronized(nativeLock) { if (handle == ptr) check(SdaEngine.nativeFinish(ptr) >= 0) { "nativeFinish failed" } }
                if (!stopped) {
                    val deadline = System.nanoTime() + 15_000_000_000L
                    while (!stopped) {
                        val status = synchronized(nativeLock) { if (handle != ptr || stopped) return@Thread; JSONObject(SdaEngine.nativeStatus(ptr)) }
                        if (status.optLong("decodedSamplePos") <= status.optLong("consumedSamplePos")) break
                        check(System.nanoTime() < deadline) { "等待 EOF 音频排空超时" }; Thread.sleep(20)
                    }
                    closeEngine(); feedDone = true; loading = false; playing = false; paused = false
                    SdaPlaybackService.stop(context)
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (error: Throwable) { feedError = error.message ?: error.toString(); closeEngine(); feedDone = true; loading = false; playing = false; paused = false; SdaPlaybackService.stop(context) }
            finally { activeInput = null }
        }, "sda-content-feed")
        feedThread = worker; worker.start()
        return displayName
    }

    fun pause(user: Boolean = true): Boolean = synchronized(nativeLock) {
        if (handle == 0L) false else { check(SdaEngine.nativePause(handle, true) == 0); paused = true; if (user) userPaused = true; true }
    }
    fun resume(user: Boolean = true): Boolean = synchronized(nativeLock) {
        if (handle == 0L) false else { check(SdaEngine.nativePause(handle, false) == 0); paused = false; if (user) userPaused = false; true }
    }
    fun duck(enabled: Boolean) = synchronized(nativeLock) { if (handle != 0L) SdaEngine.nativeSetVolume(handle, if (enabled) currentVolume * 0.2f else currentVolume) }
    fun stop() { stopWorker(); closeEngine(); feedDone = false; loading = false; playing = false; paused = false; userPaused = true; app?.let(SdaPlaybackService::stop) }
    fun status(): String = synchronized(nativeLock) { if (handle == 0L) "{}" else SdaEngine.nativeStatus(handle) }
    fun objects(): String = synchronized(nativeLock) { if (handle == 0L) "{}" else SdaEngine.nativeObjects(handle) }
    fun setHeadYaw(degrees: Double) { require(degrees.isFinite() && degrees in -180.0..180.0); synchronized(nativeLock) { check(handle != 0L && SdaEngine.nativeSetHeadYaw(handle, degrees.toFloat()) == 0) } }
    fun resetHeadPose() = synchronized(nativeLock) { check(handle != 0L && SdaEngine.nativeResetHeadPose(handle) == 0) }
    fun hrtfStatus() = if (handle == 0L) hrtfLoadStatus else if (SdaEngine.nativeHrtfLoaded(handle)) "KU100 D1 已由 native 加载" else "KU100 native 未加载"
    fun nativeLastError() = SdaEngine.nativeLastError()
    fun setVolume(volume: Float) = synchronized(nativeLock) { currentVolume = volume.coerceIn(0f, 1f); if (handle != 0L) check(SdaEngine.nativeSetVolume(handle, currentVolume) == 0) }
}
