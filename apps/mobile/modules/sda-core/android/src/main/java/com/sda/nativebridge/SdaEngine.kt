package com.sda.nativebridge

/**
 * Shared JNI bridge for the SDA native engine. Any host app (Expo module,
 * demo activity) binds its native methods here so the Rust export names stay
 * independent of the calling package.
 */
object SdaEngine {
    init { System.loadLibrary("sda_native") }

    external fun nativeInit(configJson: String, hrtfPath: String): Long
    external fun nativeInitError(): String
    external fun nativeStart(ptr: Long): Int
    external fun nativeFeed(ptr: Long, bytes: ByteArray): Int
    external fun nativeStatus(ptr: Long): String
    external fun nativeObjects(ptr: Long): String
    external fun nativeSetHeadYaw(ptr: Long, degrees: Float): Int
    external fun nativeResetHeadPose(ptr: Long): Int
    external fun nativeFinish(ptr: Long): Int
    external fun nativePause(ptr: Long, paused: Boolean): Int
    external fun nativeSetVolume(ptr: Long, volume: Float): Int
    external fun nativeClose(ptr: Long)
}
