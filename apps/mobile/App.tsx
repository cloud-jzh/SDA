import React from "react";
import * as DocumentPicker from "expo-document-picker";
import { StatusBar, StyleSheet, Text, TouchableOpacity, View } from "react-native";

interface PlaybackStatus {
  consumedSamplePos: number;
  decodedSamplePos: number;
  positionMs: number;
  fifoFrames: number;
  pendingBatches: number;
  paused: boolean;
}
interface SdaEngineModule {
  playUri(uri: string, displayName: string): Promise<string>;
  pause(): boolean;
  resume(): boolean;
  stop(): boolean;
  status(): string;
  feedError(): string | null;
  feedDone(): boolean;
  setVolume(volume: number): void;
}
interface State {
  busy: boolean;
  playing: boolean;
  paused: boolean;
  fileName: string;
  positionMs: number;
  decodedMs: number;
  fifoFrames: number;
  error: string | null;
}

export default class App extends React.Component<Record<string, never>, State> {
  state: State = {
    busy: false,
    playing: false,
    paused: false,
    fileName: "",
    positionMs: 0,
    decodedMs: 0,
    fifoFrames: 0,
    error: null,
  };
  private engine?: SdaEngineModule;
  private poller?: ReturnType<typeof setInterval>;

  componentWillUnmount() {
    if (this.poller) clearInterval(this.poller);
  }

  private getEngine(): SdaEngineModule {
    const module = (globalThis as any).expo?.modules?.SdaEngine;
    if (!module) throw new Error("SdaEngine native module is not registered");
    this.engine = module as SdaEngineModule;
    return this.engine;
  }

  private chooseFile = async () => {
    if (this.state.busy) return;
    this.setState({ busy: true, error: null });
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: "application/octet-stream",
        copyToCacheDirectory: false,
        multiple: false,
      });
      if (result.canceled) return;
      const asset = result.assets[0];
      if (!asset) throw new Error("文件选择未返回媒体条目");
      const extension = asset.name.split(".").pop()?.toLowerCase();
      if (extension !== "eac3" && extension !== "ec3") {
        throw new Error("首版仅支持裸 .eac3/.ec3 音频流；MP4、MKV 和 MP3 暂不支持");
      }
      const engine = this.getEngine();
      if (!this.poller) {
        this.poller = setInterval(() => this.pollStatus(), 250);
      }
      await engine.playUri(asset.uri, asset.name);
      this.setState({ fileName: asset.name, playing: true, paused: false, error: null });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    } finally {
      this.setState({ busy: false });
    }
  };

  private pollStatus() {
    try {
      const engine = this.engine;
      if (!engine) return;
      const value = JSON.parse(engine.status()) as Partial<PlaybackStatus>;
      const feedError = engine.feedError();
      const feedDone = engine.feedDone();
      this.setState({
        positionMs: value.positionMs ?? 0,
        decodedMs: ((value.decodedSamplePos ?? 0) * 1000) / 48000,
        fifoFrames: value.fifoFrames ?? 0,
        paused: value.paused ?? this.state.paused,
        playing: feedDone ? false : this.state.playing,
        error: feedError ?? this.state.error,
      });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    }
  }

  private togglePause = () => {
    try {
      const engine = this.getEngine();
      const paused = !this.state.paused;
      if (paused) engine.pause(); else engine.resume();
      this.setState({ paused });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    }
  };

  private stop = () => {
    try {
      this.engine?.stop();
      this.setState({ playing: false, paused: false, positionMs: 0, decodedMs: 0, fifoFrames: 0 });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    }
  };

  render() {
    const { busy, playing, paused, fileName, positionMs, decodedMs, fifoFrames, error } = this.state;
    const objects = playing
      ? [
          { id: 10, x: Math.sin((positionMs / 1000) * 2), z: 0.2 },
          { id: 11, x: Math.sin((positionMs / 1000) * 1.4 + 2), z: -0.3 },
        ]
      : [];
    return (
      <View style={styles.root}>
        <StatusBar barStyle="light-content" />
        <Text style={styles.title}>SDA · 空间音频解码器</Text>
        <View style={styles.room}>
          {objects.map((object) => (
            <View key={object.id} style={[styles.dot, { left: `${50 + object.x * 40}%`, top: `${50 - object.z * 40}%` }]} />
          ))}
          <View style={styles.listener} />
        </View>
        <Text style={styles.file}>{fileName || "选择 E-AC-3/JOC 音频"}</Text>
        <Text style={styles.status}>
          {error ?? (playing
            ? `${paused ? "已暂停" : "播放中"} · ${formatTime(positionMs)} · 解码 ${formatTime(decodedMs)} · FIFO ${fifoFrames}`
            : "首版支持裸 .eac3/.ec3 文件；MP4/MKV/MP3 暂不支持")}
        </Text>
        <View style={styles.controls}>
          <TouchableOpacity style={styles.primaryButton} onPress={this.chooseFile} disabled={busy}>
            <Text style={styles.buttonText}>{busy ? "正在打开…" : playing ? "打开其他文件" : "选择文件"}</Text>
          </TouchableOpacity>
          {playing && (
            <>
              <TouchableOpacity style={styles.iconButton} onPress={this.togglePause}>
                <Text style={styles.buttonText}>{paused ? "继续" : "暂停"}</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.iconButton} onPress={this.stop}>
                <Text style={styles.buttonText}>停止</Text>
              </TouchableOpacity>
            </>
          )}
        </View>
      </View>
    );
  }
}

function formatTime(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: "#0c101c", alignItems: "center", paddingTop: 48 },
  title: { color: "#dbe2f0", fontSize: 18, fontWeight: "600" },
  room: { width: "86%", aspectRatio: 1.2, marginTop: 18, backgroundColor: "#111726", borderRadius: 8, overflow: "hidden" },
  dot: { position: "absolute", width: 18, height: 18, borderRadius: 9, marginLeft: -9, marginTop: -9, backgroundColor: "#35d7cf" },
  listener: { position: "absolute", left: "50%", top: "50%", width: 10, height: 10, borderRadius: 5, marginLeft: -5, marginTop: -5, backgroundColor: "#e8b34b" },
  file: { color: "#dbe2f0", fontSize: 14, marginTop: 18, maxWidth: "88%" },
  status: { color: "#8fa0bd", fontSize: 12, marginTop: 10, paddingHorizontal: 20, textAlign: "center" },
  controls: { flexDirection: "row", alignItems: "center", justifyContent: "center", flexWrap: "wrap", marginTop: 20, gap: 10 },
  primaryButton: { backgroundColor: "#2a5bd7", paddingHorizontal: 22, paddingVertical: 13, borderRadius: 8 },
  iconButton: { backgroundColor: "#293244", paddingHorizontal: 16, paddingVertical: 13, borderRadius: 8 },
  buttonText: { color: "#fff", fontSize: 14, fontWeight: "600" },
});
