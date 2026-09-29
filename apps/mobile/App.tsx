import React from "react";
import * as DocumentPicker from "expo-document-picker";
import { StatusBar, StyleSheet, Text, TouchableOpacity, View } from "react-native";
import { MobileObjectScene, type MobileObjectPoint } from "./src/MobileObjectScene";

interface PlaybackStatus {
  consumedSamplePos: number;
  decodedSamplePos: number;
  positionMs: number;
  fifoFrames: number;
  pendingBatches: number;
  paused: boolean;
}
interface ObjectPoint extends MobileObjectPoint { samplePos: number; hasPos: boolean }
const ADM_AXES = "x 右 · y 前 · z 上";
interface SdaEngineModule {
  playUri(uri: string, displayName: string, headYawDegrees: number): Promise<string>;
  pause(): boolean;
  resume(): boolean;
  stop(): boolean;
  status(): string;
  objects(): string;
  setHeadYaw(degrees: number): void;
  resetHeadPose(): void;
  feedError(): string | null;
  feedDone(): boolean;
  setVolume(volume: number): void;
  hrtfStatus(): string;
  stereoBedMode(): boolean;
}
interface State {
  busy: boolean;
  playing: boolean;
  ended: boolean;
  paused: boolean;
  selectedUri: string;
  fileName: string;
  positionMs: number;
  decodedMs: number;
  fifoFrames: number;
  objects: ObjectPoint[];
  hrtfStatus: string;
  headYaw: number;
  error: string | null;
}

export default class App extends React.Component<Record<string, never>, State> {
  state: State = {
    busy: false,
    playing: false,
    ended: false,
    paused: false,
    selectedUri: "",
    fileName: "",
    positionMs: 0,
    decodedMs: 0,
    fifoFrames: 0,
    objects: [],
    hrtfStatus: "KU100 尚未加载",
    headYaw: 0,
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
        type: "*/*",
        copyToCacheDirectory: true,
        multiple: false,
      });
      if (result.canceled) return;
      const asset = result.assets[0];
      if (!asset) throw new Error("文件选择未返回媒体条目");
      const extension = asset.name.split(".").pop()?.toLowerCase();
      if (extension !== "eac3" && extension !== "ec3" && extension !== "mp3") {
        throw new Error("支持裸 .eac3/.ec3 和普通立体声 .mp3 文件");
      }
      if (this.state.playing) this.engine?.stop();
      this.setState({ selectedUri: asset.uri, fileName: asset.name, playing: false, ended: false, paused: false, positionMs: 0, decodedMs: 0, fifoFrames: 0, objects: [], error: null });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    } finally {
      this.setState({ busy: false });
    }
  };

  private playSelected = async () => {
    if (this.state.busy || !this.state.selectedUri) return;
    this.setState({ busy: true, error: null, ended: false, paused: false, positionMs: 0, decodedMs: 0, fifoFrames: 0, objects: [] });
    try {
      const engine = this.getEngine();
      if (!this.poller) this.poller = setInterval(() => this.pollStatus(), 80);
      this.setState({ hrtfStatus: engine.hrtfStatus() });
      await engine.playUri(this.state.selectedUri, this.state.fileName, this.state.headYaw);
      this.setState({ playing: true, ended: false, paused: false, error: null });
    } catch (error) {
      this.setState({ playing: false, error: error instanceof Error ? error.message : String(error) });
    } finally {
      this.setState({ busy: false });
    }
  };

  private pollStatus() {
    try {
      const engine = this.engine;
      if (!engine || (!this.state.playing && !this.state.busy)) return;
      const value = JSON.parse(engine.status()) as Partial<PlaybackStatus>;
      const feedError = engine.feedError();
      const feedDone = engine.feedDone();
      const objects = JSON.parse(engine.objects()) as Record<string, ObjectPoint>;
      this.setState({
        positionMs: value.positionMs ?? 0,
        decodedMs: ((value.decodedSamplePos ?? 0) * 1000) / 48000,
        fifoFrames: value.fifoFrames ?? 0,
        objects: feedDone ? [] : Object.values(objects).filter((object) => object.hasPos && object.pos.every(Number.isFinite)),
        paused: value.paused ?? this.state.paused,
        playing: feedDone ? false : this.state.playing,
        ended: feedDone && !feedError,
        hrtfStatus: engine.hrtfStatus(),
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

  private adjustYaw = (delta: number) => {
    try {
      const headYaw = Math.max(-180, Math.min(180, this.state.headYaw + delta));
      if (this.state.playing && !this.state.ended) this.getEngine().setHeadYaw(headYaw);
      this.setState({ headYaw });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    }
  };

  private resetYaw = () => {
    try {
      if (this.state.playing && !this.state.ended) this.getEngine().resetHeadPose();
      this.setState({ headYaw: 0 });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    }
  };

  private stop = () => {
    try {
      this.engine?.stop();
      this.setState({ playing: false, ended: false, paused: false, positionMs: 0, decodedMs: 0, fifoFrames: 0, objects: [] });
    } catch (error) {
      this.setState({ error: error instanceof Error ? error.message : String(error) });
    }
  };

  render() {
    const { busy, playing, ended, paused, selectedUri, fileName, positionMs, decodedMs, fifoFrames, objects, headYaw, error, hrtfStatus } = this.state;
    const isMp3 = fileName.toLowerCase().endsWith(".mp3");
    const emptyMessage = isMp3
      ? "MP3 普通立体声床声 · 不含 Atmos 对象"
      : ended
        ? "已到文件末尾 · 对象位置已清空"
        : !playing
          ? selectedUri ? "文件已就绪 · 可调整朝向后播放" : "选择 E-AC-3/JOC 音频后查看对象位置"
          : objects.length === 0
            ? "当前播放位置没有有效对象坐标"
            : `${objects.length} 个对象 · 消费时钟 ${formatTime(positionMs)}`;
    return (
      <View style={styles.root}>
        <StatusBar barStyle="light-content" />
        <Text style={styles.title}>SDA · 空间音频</Text>
        <View style={styles.scene}><MobileObjectScene objects={objects} /></View>
        <Text style={styles.file} numberOfLines={1}>{fileName || "选择 E-AC-3/JOC 音频"}</Text>
        <Text style={styles.status}>{emptyMessage} · {ADM_AXES}</Text>
        <Text style={styles.status}>{hrtfStatus}</Text>
        <Text style={styles.status}>
          {error ?? (playing
            ? `${paused ? "已暂停" : "播放中"} · ${formatTime(positionMs)} · 解码 ${formatTime(decodedMs)} · FIFO ${fifoFrames}`
            : ended ? "播放结束 · 可直接重放或调整试听朝向" : "支持裸 E-AC-3/JOC 与 MP3 立体声床声")}
        </Text>
        <View style={styles.controls}>
          <TouchableOpacity style={styles.primaryButton} onPress={this.chooseFile} disabled={busy}>
            <Text style={styles.buttonText}>{busy ? "正在处理…" : "选择文件"}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.primaryButton} onPress={this.playSelected} disabled={busy || !selectedUri || playing}>
            <Text style={styles.buttonText}>{busy ? "正在启动…" : ended ? "重放" : "播放"}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconButton} onPress={this.togglePause} disabled={!playing}>
            <Text style={styles.buttonText}>{paused ? "继续" : "暂停"}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconButton} onPress={this.stop} disabled={!playing}>
            <Text style={styles.buttonText}>停止</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconButton} onPress={this.adjustYaw.bind(this, 15)}>
            <Text style={styles.buttonText}>朝左 15°</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconButton} onPress={this.adjustYaw.bind(this, -15)}>
            <Text style={styles.buttonText}>朝右 15°</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconButton} onPress={this.resetYaw}>
            <Text style={styles.buttonText}>朝向复位 · {headYaw}°</Text>
          </TouchableOpacity>
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
  root: { flex: 1, backgroundColor: "#0c101c", alignItems: "center", paddingTop: 42, paddingHorizontal: 16 },
  title: { color: "#dbe2f0", fontSize: 18, fontWeight: "600" },
  scene: { width: "100%", flex: 1, minHeight: 260, marginTop: 14, backgroundColor: "#111726", borderRadius: 8, overflow: "hidden" },
  file: { color: "#dbe2f0", fontSize: 14, marginTop: 12, maxWidth: "95%" },
  status: { color: "#8fa0bd", fontSize: 12, marginTop: 8, paddingHorizontal: 8, textAlign: "center" },
  controls: { flexDirection: "row", alignItems: "center", justifyContent: "center", flexWrap: "wrap", marginTop: 14, marginBottom: 16, gap: 8 },
  primaryButton: { backgroundColor: "#2a5bd7", minHeight: 46, justifyContent: "center", paddingHorizontal: 20, paddingVertical: 12, borderRadius: 8 },
  iconButton: { backgroundColor: "#293244", minHeight: 46, justifyContent: "center", paddingHorizontal: 14, paddingVertical: 12, borderRadius: 8 },
  buttonText: { color: "#fff", fontSize: 14, fontWeight: "600" },
});
