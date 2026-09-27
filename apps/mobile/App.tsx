/**
 * SDA mobile (Expo). T2.3: engine-driven shell - JS calls the SdaEngine Expo
 * module (initBundled starts decode -> render -> AAudio with the bundled JOC
 * fixture) and shows live playback status. PCM stays on the native side.
 */

import React, { useEffect, useState } from "react";
import { StatusBar, StyleSheet, Text, TouchableOpacity, View } from "react-native";
import { requireNativeModule } from "expo-modules-core";

const SdaEngine = requireNativeModule("SdaEngine");

interface VisualObject {
  id: number;
  pos: [number, number, number];
}

function parseStatus(statusJson: string): { samplePos: number } {
  try {
    return JSON.parse(statusJson) as { samplePos?: number };
  } catch {
    return {};
  }
}

export default function App() {
  const [started, setStarted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("idle");
  const [samplePos, setSamplePos] = useState(0);

  useEffect(() => {
    if (!started) return;
    const timer = setInterval(() => {
      try {
        const json = SdaEngine.status() as string;
        setStatus(json.slice(0, 160));
        setSamplePos(parseStatus(json).samplePos ?? 0);
      } catch {
        /* engine tearing down */
      }
    }, 500);
    return () => clearInterval(timer);
  }, [started]);

  const startEngine = () => {
    setError(null);
    try {
      SdaEngine.initBundled();
      setStarted(true);
    } catch (e) {
      setError(String(e));
    }
  };

  // Object geometry arrives via the 66ms event stream (T2.5); for now show
  // the playback clock as moving indicators.
  const t = samplePos / 48000;
  const objects: VisualObject[] = started
    ? [
        { id: 10, pos: [Math.sin(t * 2), Math.cos(t * 2), 0.2] },
        { id: 11, pos: [Math.sin(t * 1.4 + 2), Math.cos(t * 1.4 + 2), -0.3] },
      ]
    : [];

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" />
      <Text style={styles.title}>SDA · 空间音频解码器</Text>
      <View style={styles.room}>
        {objects.map((o) => (
          <View
            key={o.id}
            style={[
              styles.dot,
              {
                left: `${50 + o.pos[0] * 40}%`,
                top: `${50 - o.pos[2] * 40}%`,
                backgroundColor: `hsl(${200 - o.pos[2] * 90}, 90%, 60%)`,
              },
            ]}
          />
        ))}
        <View style={styles.listener} />
      </View>
      <Text style={styles.status}>
        {error ?? (started ? `looping render… ${status}` : "演示模式 — 点击启动引擎")}
      </Text>
      {!started && (
        <TouchableOpacity style={styles.button} onPress={startEngine}>
          <Text style={styles.buttonText}>启动引擎（内置 JOC/Atmos 语料循环）</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: "#0c101c",
    alignItems: "center",
    paddingTop: 64,
  },
  title: { color: "#dbe2f0", fontSize: 18, fontWeight: "600" },
  room: {
    width: "86%",
    aspectRatio: 1.2,
    marginTop: 24,
    backgroundColor: "#111726",
    borderRadius: 12,
    overflow: "hidden",
  },
  dot: {
    position: "absolute",
    width: 18,
    height: 18,
    borderRadius: 9,
    marginLeft: -9,
    marginTop: -9,
  },
  listener: {
    position: "absolute",
    left: "50%",
    top: "50%",
    width: 10,
    height: 10,
    borderRadius: 5,
    marginLeft: -5,
    marginTop: -5,
    backgroundColor: "#e8b34b",
  },
  status: {
    color: "#8fa0bd",
    fontSize: 12,
    marginTop: 16,
    paddingHorizontal: 24,
    textAlign: "center",
  },
  button: {
    marginTop: 24,
    backgroundColor: "#2a5bd7",
    paddingHorizontal: 24,
    paddingVertical: 14,
    borderRadius: 10,
  },
  buttonText: { color: "#fff", fontSize: 15, fontWeight: "600" },
});
