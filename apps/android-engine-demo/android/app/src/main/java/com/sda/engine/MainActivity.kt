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
        val stream = File(filesDir, "joc_atmos_1s.eac3")
        assets.open("joc_atmos_1s.eac3").use { input ->
            stream.outputStream().use { output -> input.copyTo(output) }
        }

        val config = """{"sampleRate":48000,"outputChannels":2}"""
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

        // Feed the whole clip; the engine FIFO absorbs it and the AAudio
        // writer drains at the device pace.
        Thread {
            val bytes = stream.readBytes()
            var fed = 0
            val chunk = 24 * 1024
            var lastPushed = 0
            while (fed < bytes.size) {
                val end = minOf(fed + chunk, bytes.size)
                lastPushed = nativeFeed(ptr, bytes.copyOfRange(fed, end))
                fed = end
                runOnUiThread {
                    tv.text = "playing JOC/Atmos clip, frames decoded: $lastPushed, feed ${(fed * 100) / bytes.size}%"
                }
                Thread.sleep(40)
            }
            runOnUiThread { tv.text = tv.text.toString() + ", feed complete" }
        }.start()
    }
}
