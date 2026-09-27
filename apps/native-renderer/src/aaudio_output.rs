//! Android AAudio output (plan T2.2): blocking-write sink over the engine
//! FIFO.
//!
//! MuMu 实证：AAudio 的数据回调模式（setDataCallback）在进程重开后会破坏回调
//! 内存（SEGV_ACCERR，见提交 1cb24b4），因此本实现与桌面 CPAL 相反——
//! 自起 writer 线程，用 `AAudioStream_write` 从 FIFO 拉取推送，复刻设备
//! 回调的完整契约：
//! 1. 每轮入口 `apply_flush_from_consumer()`（seek/pause 的 epoch 确认）
//! 2. `callback_output_enabled` 水位门（未使能时写静音但仍推进消费时钟）
//! 3. 结尾 `record_callback()`（消费时钟 + underrun 统计）
//!
//! 采样率协商：先请求内部时钟 48 kHz；设备拒绝时退回设备默认率打开（此时
//! 消费速率 != 渲染率，表现为变速——完整重采样在计划 T1.11/T2.2 后续）。

use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Instant;

use super::record_callback;
use super::{render_command, stereo_fifo, AudioOutput, RuntimeTelemetry};

#[allow(non_camel_case_types)]
type AAudioStream = *mut core::ffi::c_void;
#[allow(non_camel_case_types)]
type AAudioStreamBuilder = *mut core::ffi::c_void;

const AAUDIO_OK: i32 = 0;
const AAUDIO_FORMAT_PCM_FLOAT: i32 = 1;
const AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;
const AAUDIO_SHARING_MODE_SHARED: i32 = 1;
const INTERNAL_SAMPLE_RATE: i32 = 48000;
const WRITE_TIMEOUT_NS: i64 = 200_000_000; // 200 ms

#[link(name = "aaudio")]
unsafe extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut AAudioStreamBuilder) -> i32;
    fn AAudioStreamBuilder_setPerformanceMode(builder: AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setSharingMode(builder: AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: AAudioStreamBuilder, rate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: AAudioStreamBuilder, count: i32);
    fn AAudioStreamBuilder_setFormat(builder: AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_openStream(builder: AAudioStreamBuilder, stream: *mut AAudioStream) -> i32;
    fn AAudioStreamBuilder_delete(builder: AAudioStreamBuilder);
    fn AAudioStream_getFramesPerBurst(stream: AAudioStream) -> i32;
    fn AAudioStream_getSampleRate(stream: AAudioStream) -> i32;
    fn AAudioStream_requestStart(stream: AAudioStream) -> i32;
    fn AAudioStream_close(stream: AAudioStream) -> i32;
    fn AAudioStream_write(stream: AAudioStream, buffer: *const core::ffi::c_void, num_frames: i32, timeout_ns: i64) -> i32;
}

fn android_log(message: &str) {
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
    }
    let tag = b"SdaAAudio\0".to_vec();
    let mut text = message.as_bytes().to_vec();
    text.push(0);
    unsafe {
        __android_log_write(4, tag.as_ptr(), text.as_ptr());
    }
}

/// Blocking-write AAudio sink. Construct one per engine, hand it to
/// `AudioOutput::run`.
pub struct AAudioWriterSink {
    /// Sample rate requested at open; 0 lets the device pick (fallback path).
    pub requested_sample_rate: i32,
}

impl Default for AAudioWriterSink {
    fn default() -> Self {
        Self { requested_sample_rate: INTERNAL_SAMPLE_RATE }
    }
}

#[derive(Clone, Copy)]
struct SendStream(AAudioStream);
unsafe impl Send for SendStream {}

impl AudioOutput for AAudioWriterSink {
    fn run(
        self: Arc<Self>,
        fifo: Arc<stereo_fifo::StereoFifo>,
        telemetry: Arc<RuntimeTelemetry>,
        commands: Arc<render_command::RenderCommandQueue>,
    ) {
        unsafe {
            let mut builder: AAudioStreamBuilder = core::ptr::null_mut();
            if AAudio_createStreamBuilder(&mut builder) != AAUDIO_OK {
                android_log("createStreamBuilder failed");
                return;
            }
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setSampleRate(builder, self.requested_sample_rate);

            let mut stream: AAudioStream = core::ptr::null_mut();
            let mut result = AAudioStreamBuilder_openStream(builder, &mut stream);
            let mut granted_rate = self.requested_sample_rate;
            if result != AAUDIO_OK {
                // Fallback: device default rate. The engine clock stays
                // 48 kHz, so until input resampling lands (T1.11) playback
                // pitch shifts.
                AAudioStreamBuilder_setSampleRate(builder, 0);
                result = AAudioStreamBuilder_openStream(builder, &mut stream);
                if result != AAUDIO_OK {
                    android_log(&format!("openStream failed: {result}"));
                    AAudioStreamBuilder_delete(builder);
                    return;
                }
                granted_rate = AAudioStream_getSampleRate(stream);
            }
            let burst = AAudioStream_getFramesPerBurst(stream).max(1) as usize;
            android_log(&format!("aaudio sink: burst={burst} rate={granted_rate}"));
            if AAudioStream_requestStart(stream) != AAUDIO_OK {
                android_log("requestStart failed");
                AAudioStream_close(stream);
                AAudioStreamBuilder_delete(builder);
                return;
            }
            AAudioStreamBuilder_delete(builder);

            let handle = SendStream(stream);
            std::thread::Builder::new()
                .name("sda-aaudio-writer".into())
                .spawn(move || {
                    writer_loop(handle, burst, fifo, telemetry, commands);
                })
                .expect("spawn aaudio writer");
        }
    }
}

fn writer_loop(
    stream: SendStream,
    frames_per_burst: usize,
    fifo: Arc<stereo_fifo::StereoFifo>,
    telemetry: Arc<RuntimeTelemetry>,
    commands: Arc<render_command::RenderCommandQueue>,
) {
    let stream = stream.0;
    let channels = 2_usize;
    let mut block = vec![0.0_f32; frames_per_burst * channels];
    let mut idle_polls = 0_u32;
    let _ = commands;
    loop {
        // Device-callback contract (mirrors output_manager): flush epoch ack
        // first, then the enabled gate, then consume and report.
        fifo.apply_flush_from_consumer();
        let enabled = telemetry.callback_output_enabled.load(Ordering::Acquire);
        let popped = fifo.pop_into_f32(&mut block, channels);
        if popped == 0 {
            idle_polls += 1;
            if idle_polls > 4 {
                std::thread::sleep(std::time::Duration::from_millis(4));
            }
        } else {
            idle_polls = 0;
        }
        // Zero-fill the tail: the stream keeps consuming so the codec clock
        // advances and underruns surface in telemetry.
        if popped < frames_per_burst {
            for sample in block[popped * channels..].iter_mut() {
                *sample = 0.0;
            }
        }
        let started = Instant::now();
        let written = unsafe {
            AAudioStream_write(
                stream,
                block.as_ptr() as *const core::ffi::c_void,
                frames_per_burst as i32,
                WRITE_TIMEOUT_NS,
            )
        };
        if written < 0 {
            android_log(&format!("write failed: {written}"));
            return;
        }
        record_callback(&telemetry, started, frames_per_burst, popped, enabled && popped > 0);
    }
}
