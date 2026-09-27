//! T2.1 (docs/android-porting-plan.md): prove AAudio low-latency output from
//! Rust on an Android host, driven over JNI from a minimal activity.
//!
//! Hand-written bindings over the stable C API of the system `libaaudio.so`
//! (API 26+): open a PCM-float low-latency stream, feed a 440 Hz sine from
//! the data callback. Success criteria: audible tone on device, callback
//! burst size and underrun count logged.

use std::sync::atomic::{AtomicI64, Ordering};

/// Opaque handles — all AAudio types are pointers.
#[allow(non_camel_case_types)]
type AAudioStream = *mut core::ffi::c_void;
#[allow(non_camel_case_types)]
type AAudioStreamBuilder = *mut core::ffi::c_void;

const AAUDIO_OK: i32 = 0;
const AAUDIO_FORMAT_PCM_FLOAT: i32 = 1;
const AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;
const AAUDIO_DIRECTION_OUTPUT: i32 = 0;
const AAUDIO_SHARING_MODE_EXCLUSIVE: i32 = 0;
const AAUDIO_SHAREDMODE_SHARED: i32 = 1;
const SAMPLE_RATE: i32 = 48000;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct AAudioStreamCallback {
    pub on_audio_ready:
        Option<extern "C" fn(stream: *mut AAudioStream, user_data: *mut core::ffi::c_void, audio_data: *mut core::ffi::c_void, num_frames: i32) -> i32>,
    pub on_error: Option<extern "C" fn(stream: *mut AAudioStream, user_data: *mut core::ffi::c_void, error: i32)>,
}

#[link(name = "aaudio")]
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut AAudioStreamBuilder) -> i32;
    fn AAudioStreamBuilder_setDirection(builder: AAudioStreamBuilder, direction: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setSharingMode(builder: AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: AAudioStreamBuilder, rate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: AAudioStreamBuilder, count: i32);
    fn AAudioStreamBuilder_setFormat(builder: AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_setDataCallback(
        builder: AAudioStreamBuilder,
        callback: *const AAudioStreamCallback,
        user_data: *mut core::ffi::c_void,
    );
    fn AAudioStreamBuilder_openStream(builder: AAudioStreamBuilder, stream: *mut AAudioStream) -> i32;
    fn AAudioStreamBuilder_delete(builder: AAudioStreamBuilder);
    fn AAudioStream_requestStart(stream: AAudioStream) -> i32;
    fn AAudioStream_getFramesPerBurst(stream: AAudioStream) -> i32;
    fn AAudioStream_close(stream: AAudioStream) -> i32;
}

static SINE_PHASE: AtomicI64 = AtomicI64::new(0);
static mut STREAM: AAudioStream = core::ptr::null_mut();
static CALLBACK: AAudioStreamCallback = AAudioStreamCallback {
    on_audio_ready: Some(on_audio_ready),
    on_error: Some(on_error),
};

/// 440 Hz sine, stereo interleaved f32. Phase stored as i64 32.32 fixed point
/// to avoid float drift across callbacks.
extern "C" fn on_audio_ready(
    _stream: *mut AAudioStream,
    _user_data: *mut core::ffi::c_void,
    audio_data: *mut core::ffi::c_void,
    num_frames: i32,
) -> i32 {
    const PHASE_STEP: i64 = ((440.0 / SAMPLE_RATE as f64) * (1i64 << 32) as f64) as i64;
    let frames = num_frames.max(0) as usize;
    let out = unsafe { core::slice::from_raw_parts_mut(audio_data as *mut f32, frames * 2) };
    let mut phase = SINE_PHASE.load(Ordering::Relaxed);
    for frame in out.chunks_exact_mut(2) {
        let angle = (phase as f64) / (1i64 << 32) as f64 * core::f64::consts::TAU;
        let sample = (angle.sin() * 0.2) as f32;
        frame[0] = sample;
        frame[1] = sample;
        phase = phase.wrapping_add(PHASE_STEP);
    }
    SINE_PHASE.store(phase, Ordering::Relaxed);
    AAUDIO_OK
}

extern "C" fn on_error(_stream: *mut AAudioStream, _user_data: *mut core::ffi::c_void, error: i32) {
    android_log(&format!("sine demo stream error: {error}"));
}

fn android_log(message: &str) {
    // Minimal __android_log_write binding (liblog.so), priority 4 = INFO.
    extern "C" {
        fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
    }
    let mut tag = b"SdaSine\0".to_vec();
    let mut text = message.as_bytes().to_vec();
    text.push(0);
    unsafe {
        __android_log_write(4, tag.as_ptr(), text.as_ptr());
    }
    drop(tag);
}

/// JNI entry: `MainActivity.startSine()I` — returns 0 on success, negative on
/// failure. On success a 440 Hz tone plays until process death (demo only).
#[no_mangle]
pub extern "system" fn Java_com_sda_sine_MainActivity_startSine(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jint {
    unsafe {
        let mut builder: AAudioStreamBuilder = core::ptr::null_mut();
        let result = AAudio_createStreamBuilder(&mut builder);
        if result != AAUDIO_OK {
            android_log(&format!("createStreamBuilder failed: {result}"));
            return -1;
        }
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        // Try exclusive first; AAudio silently falls back if unavailable
        // (emulators almost always land on shared).
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setSampleRate(builder, SAMPLE_RATE);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);

        AAudioStreamBuilder_setDataCallback(
            builder,
            &CALLBACK,
            core::ptr::null_mut(),
        );

        let mut stream: AAudioStream = core::ptr::null_mut();
        let result = AAudioStreamBuilder_openStream(builder, &mut stream);
        if result != AAUDIO_OK {
            // Exclusive can fail outright on some hosts; retry shared.
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHAREDMODE_SHARED);
            let retry = AAudioStreamBuilder_openStream(builder, &mut stream);
            if retry != AAUDIO_OK {
                android_log(&format!("openStream failed: {result}/{retry}"));
                AAudioStreamBuilder_delete(builder);
                return -2;
            }
        }
        let burst = AAudioStream_getFramesPerBurst(stream);
        android_log(&format!("sine stream opened, framesPerBurst={burst}"));
        let result = AAudioStream_requestStart(stream);
        AAudioStreamBuilder_delete(builder);
        if result != AAUDIO_OK {
            android_log(&format!("requestStart failed: {result}"));
            AAudioStream_close(stream);
            return -3;
        }
        STREAM = stream;
        0
    }
}
