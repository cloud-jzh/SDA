import React from "react";
import { StatusBar, StyleSheet, Text, TouchableOpacity, View } from "react-native";

interface NativeEngine {
  initBundled(): Promise<boolean>;
  status(): string;
  setVolume(volume: number): void;
}
interface State {
  started: boolean;
  starting: boolean;
  error: string | null;
  phase: string;
  status: string;
  positionMs: number;
}
interface ObjectPoint { id: number; x: number; z: number }

export default class App extends React.Component<Record<string, never>, State> {
  state: State = { started: false, starting: false, error: null, phase: "待机", status: "idle", positionMs: 0 };
  private engine?: NativeEngine;
  private timer?: ReturnType<typeof setInterval>;

  componentWillUnmount() {
    if (this.timer) clearInterval(this.timer);
  }

  private getEngine(): NativeEngine {
    const module = (globalThis as any).expo?.modules?.SdaEngine;
    if (!module) throw new Error("SdaEngine Expo module is not registered");
    return module as NativeEngine;
  }

  private startEngine = async () => {
    if (this.state.starting || this.state.started) return;
    this.setState({ starting: true, error: null, phase: "解析原生模块" });
    try {
      this.engine = this.getEngine();
      this.setState({ phase: "初始化音频引擎" });
      await this.engine.initBundled();
      this.setState({ started: true, phase: "正在播放" });
      this.timer = setInterval(() => {
        try {
          const json = this.engine?.status() ?? "{}";
          const value = JSON.parse(json) as { positionMs?: number };
          this.setState({ status: json.slice(0, 160), positionMs: value.positionMs ?? 0 });
        } catch (error) {
          this.setState({ error: `status: ${String(error)}` });
        }
      }, 500);
    } catch (error) {
      this.setState({ error: String(error), phase: "启动失败" });
    } finally {
      this.setState({ starting: false });
    }
  };

  render() {
    const { started, starting, error, phase, status, positionMs } = this.state;
    const t = positionMs / 1000;
    const objects: ObjectPoint[] = started
      ? [
          { id: 10, x: Math.sin(t * 2), z: 0.2 },
          { id: 11, x: Math.sin(t * 1.4 + 2), z: -0.3 },
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
        <Text style={styles.status}>{error ?? (started ? `looping render… ${status}` : phase)}</Text>
        {!started && (
          <TouchableOpacity style={styles.button} onPress={this.startEngine} disabled={starting}>
            <Text style={styles.buttonText}>{starting ? "正在启动…" : "启动引擎"}</Text>
          </TouchableOpacity>
        )}
      </View>
    );
  }
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: "#0c101c", alignItems: "center", paddingTop: 64 },
  title: { color: "#dbe2f0", fontSize: 18, fontWeight: "600" },
  room: { width: "86%", aspectRatio: 1.2, marginTop: 24, backgroundColor: "#111726", borderRadius: 12, overflow: "hidden" },
  dot: { position: "absolute", width: 18, height: 18, borderRadius: 9, marginLeft: -9, marginTop: -9, backgroundColor: "#35d7cf" },
  listener: { position: "absolute", left: "50%", top: "50%", width: 10, height: 10, borderRadius: 5, marginLeft: -5, marginTop: -5, backgroundColor: "#e8b34b" },
  status: { color: "#8fa0bd", fontSize: 12, marginTop: 16, paddingHorizontal: 24, textAlign: "center" },
  button: { marginTop: 24, backgroundColor: "#2a5bd7", paddingHorizontal: 24, paddingVertical: 14, borderRadius: 10 },
  buttonText: { color: "#fff", fontSize: 15, fontWeight: "600" },
});
