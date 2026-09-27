package com.sda.engine

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    init { System.loadLibrary("sda_native") }

    private external fun nativeInit(configJson: String, hrtfPath: String): Long
    private external fun nativeStart(ptr: Long): Int
    private external fun nativeFeed(ptr: Long, bytes: ByteArray): Int
    private external fun nativeStatus(ptr: Long): String
    private external fun nativeClose(ptr: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.textSize = 16f
        tv.setPadding(48, 96, 48, 48)
        setContentView(tv)

        // Stage engine inputs from assets to filesDir (JNI takes real paths).
        // HRTF is a whole directory: hrtf-set.json references the FIR files
        // relative to itself, so all of them must sit side by side.
        val hrtfDir = File(filesDir, "hrtf")
        hrtfDir.mkdirs()
        for (name in assets.list("hrtf") ?: emptyArray()) {
            assets.open("hrtf/$name").use { input ->
                File(hrtfDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        val stream = File(filesDir, "song.eac3")
        assets.open("song.eac3").use { input ->
            stream.outputStream().use { output -> input.copyTo(output) }
        }

        val config = """{"sampleRate":48000,"outputChannels":2,"layout":"7.1.4"}"""
        val hrtfJson = File(hrtfDir, "hrtf-set.json").absolutePath
        val ptr = nativeInit(config, hrtfJson)
        if (ptr == 0L) {
            tv.text = "nativeInit failed (see logcat: SdaEngine)"
            return
        }
        val rc = nativeStart(ptr)
        if (rc != 0) {
            tv.text = "nativeStart failed: $rc"
            nativeClose(ptr)
            return
        }

        // Feed with clock-drift-free backpressure: keep the decoded codec
        // clock at most ~2 s ahead of the consumption clock reported by the
        // engine. Fixed-sleep pacing drifts against the audio device clock
        // and corrupts the ring over a long file.
        Thread {
            val bytes = stream.readBytes()
            val chunk = 24 * 1024
            val maxLead = 2L * 48000L
            var fed = 0
            var lastPushed = 0
            while (fed < bytes.size) {
                val rawStatus = nativeStatus(ptr)
                if (fed == 0) android.util.Log.i("SdaEngine", "status=$rawStatus")
                val obj = org.json.JSONObject(rawStatus)
                val decoded = obj.optLong("decodedSamplePos")
                val consumed = obj.optLong("consumedSamplePos")
                if (decoded - consumed > maxLead) {
                    Thread.sleep(20)
                    continue
                }
                val end = minOf(fed + chunk, bytes.size)
                lastPushed = nativeFeed(ptr, bytes.copyOfRange(fed, end))
                fed = end
                runOnUiThread {
                    val seconds = decoded * 1000L / 48000L / 1000L
                    tv.text = "playing song.eac3, at ~${seconds}s / 211s (frames $lastPushed)"
                }
            }
            runOnUiThread { tv.text = "playback complete (song.eac3)" }
        }.start()
    }
}
