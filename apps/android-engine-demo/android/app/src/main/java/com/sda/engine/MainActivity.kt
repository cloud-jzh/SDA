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

        // Feed the song paced to roughly real time: the render pipeline keeps
        // only a small FIFO (341 ms target) and the command queue caps PCM at
        // 16 MB, so un-paced feeding of a long file would silently drop
        // chunks. 24 KB at 448 kb/s is ~0.43 s of audio per chunk.
        Thread {
            val bytes = stream.readBytes()
            val chunk = 24 * 1024
            var fed = 0
            var lastPushed = 0
            while (fed < bytes.size) {
                val end = minOf(fed + chunk, bytes.size)
                lastPushed = nativeFeed(ptr, bytes.copyOfRange(fed, end))
                fed = end
                // Pacing: chunk holds ~0.43 s of encoded audio.
                Thread.sleep(430)
                runOnUiThread {
                    val seconds = fed * 8L / 448000L
                    tv.text = "playing song.eac3, fed ${seconds}s / ${bytes.size * 8L / 448000L}s (frames $lastPushed)"
                }
            }
            runOnUiThread { tv.text = "playback complete (song.eac3)" }
        }.start()
    }
}
