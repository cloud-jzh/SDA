//! SDA mobile engine facade.
//!
//! Wires the two Rust halves of SDA together for mobile hosts (Android JNI,
//! iOS Swift):
//!
//! ```text
//! demuxed codec chunks (from JS/Kotlin/Java)
//!     │  MobileEngine::feed()
//!     ▼
//! sda_core::StreamingDecoder  →  FrameData (planar PCM + object events)
//!     │                         (PCM never crosses back over the FFI)
//!     ▼
//! sda_native_renderer::Engine + spawn_render_worker  →  StereoFifo
//!     │
//!     ▼
//! platform audio output (Android: AAudio sink; desktop sidecar: CPAL)
//!     plus install_event_sink(...) for ACK / object-activity events
//! ```
//!
//! Build (Android):
//! ```text
//! export MACINDECODE_AC4_SPEC_DIR=<repo>/tmp/MacinDecode-AC4-Core/spec
//! cargo ndk -t arm64-v8a --platform 26 -- build --release
//! ```
//!
//! Milestone status (docs/android-porting-plan.md):
//! - T1.6 (this crate): facade skeleton — decoder feed + renderer engine
//!   construction + event sink plumbing.
//! - T1.7/T1.8: AudioSink trait, stereo FIFO wiring, clock/backpressure and
//!   seek land here next.

use std::collections::VecDeque;
use std::sync::Mutex;

use sda_core::{FrameData, StreamingDecoder};
pub use sda_native_renderer::{
    install_event_sink, Engine, Event, EventSink, RuntimeTelemetry,
};

/// Host-provided engine configuration (JSON-friendly mirror of the desktop
/// sidecar's `Configure` command).
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineConfig {
    pub sample_rate: u32,
    pub output_channels: u16,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self { sample_rate: 48000, output_channels: 2 }
    }
}

/// One decoded frame batch drained from the decoder, reduced to what the
/// mobile bridge needs. PCM samples stay inside the engine (see module docs);
/// hosts only see metadata plus a watermark so they know when to feed more.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DecodeStatus {
    pub codec: String,
    /// Frames decoded and pushed into the renderer since the last poll.
    pub frames_pushed: u32,
    /// Absolute codec sample position of the newest decoded frame.
    pub sample_pos: u64,
    pub errors: Vec<String>,
}

/// Errors surfaced across the FFI boundary as plain strings.
pub type EngineResult<T> = Result<T, String>;

/// Mobile engine: owns the decoder and the renderer engine.
pub struct MobileEngine {
    config: EngineConfig,
    decoder: StreamingDecoder,
    renderer: Engine,
    /// Decoded frames waiting to be handed to the renderer worker (T1.7/T1.8
    /// replace this queue with the stereo FIFO + render-thread pipeline).
    pending: Mutex<VecDeque<FrameData>>,
}

impl MobileEngine {
    /// Create the engine. `hrtf_dir` is reserved for T1.12 (HRTF asset
    /// loading); the renderer's current defaults apply until then.
    pub fn new(config: EngineConfig, _hrtf_dir: Option<&str>) -> EngineResult<MobileEngine> {
        if config.output_channels != 2 {
            return Err(format!(
                "mobile engine currently renders stereo only, got {} channels",
                config.output_channels
            ));
        }
        Ok(MobileEngine {
            renderer: Engine::new(config.sample_rate, config.output_channels),
            decoder: StreamingDecoder::new("auto")?,
            config,
            pending: Mutex::new(VecDeque::new()),
        })
    }

    pub fn config(&self) -> &EngineConfig {
        &self.config
    }

    /// Feed demuxed bitstream bytes (any chunking; the decoder re-frames).
    /// Decoded frames are queued internally for the renderer (T1.7 wires the
    /// render worker in).
    pub fn feed(&mut self, data: &[u8]) -> EngineResult<DecodeStatus> {
        self.decoder.push(data)?;
        let mut frames_pushed = 0_u32;
        let mut sample_pos = 0_u64;
        {
            let mut pending = self.pending.lock().expect("pending lock");
            while let Some(frame) = self.decoder.next_frame() {
                sample_pos = frame.sample_pos;
                pending.push_back(frame);
                frames_pushed += 1;
            }
        }
        Ok(DecodeStatus {
            codec: self.decoder.codec_name().to_string(),
            frames_pushed,
            sample_pos,
            errors: self.decoder.drain_errors(),
        })
    }

    /// Drain decoded frames queued since the last call. Exposed for tests and
    /// for T1.7's render-worker handoff; FFI hosts should not need this.
    pub fn take_pending_frames(&self) -> Vec<FrameData> {
        let mut pending = self.pending.lock().expect("pending lock");
        pending.drain(..).collect()
    }

    /// Codec in use (meaningful after auto-detection).
    pub fn codec_name(&self) -> &str {
        self.decoder.codec_name()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn joc_fixture() -> Vec<u8> {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../harletty-bridge/harletty/tests/fixtures/joc_atmos_1s.eac3"
        );
        std::fs::read(path)
            .unwrap_or_else(|error| panic!("fixture missing ({error}); run `git submodule update --init`"))
    }

    #[test]
    fn engine_feeds_decoder_and_queues_frames() {
        let mut engine = MobileEngine::new(EngineConfig::default(), None).unwrap();
        let bytes = joc_fixture();
        let status = engine.feed(&bytes).unwrap();
        assert_eq!(status.codec, "eac3");
        assert!(status.frames_pushed > 0, "fixture must decode frames");
        assert!(status.sample_pos > 0);
        assert!(status.errors.is_empty());

        let frames = engine.take_pending_frames();
        assert_eq!(frames.len() as u32, status.frames_pushed);
        assert!(frames[0].channels.iter().all(|channel| !channel.is_empty()));
        assert!(!frames[0].events_json().is_empty() || !frames[0].labels.is_empty());

        // Draining twice yields nothing new until more input arrives.
        assert!(engine.take_pending_frames().is_empty());
        assert_eq!(engine.codec_name(), "eac3");
    }

    #[test]
    fn rejects_non_stereo_output() {
        let error = MobileEngine::new(
            EngineConfig { sample_rate: 48000, output_channels: 6 },
            None,
        )
        .unwrap_err();
        assert!(error.contains("stereo"));
    }
}
