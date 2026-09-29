import React, { useMemo, useRef } from "react";
import { PanResponder, View, type GestureResponderEvent, type PanResponderGestureState } from "react-native";
import { Canvas, useFrame, useThree } from "@react-three/fiber/native";
import * as THREE from "three";
import { LAYOUT_7_1_4 } from "../../../packages/renderer/src/layouts";
import { admToScenePosition, SCENE_FLOOR_Y, SCENE_ROOM_HALF_EXTENT, SCENE_WALL_HEIGHT, SCENE_WALL_MID_Y, smoothScenePosition, speakerScenePosition } from "../../../packages/renderer/src/scene-coordinates";

export interface MobileObjectPoint { id: number; pos: [number, number, number]; gainDb: number }

function Room() {
  const geometry = useMemo(() => new THREE.EdgesGeometry(new THREE.BoxGeometry(SCENE_ROOM_HALF_EXTENT * 2, SCENE_WALL_HEIGHT, SCENE_ROOM_HALF_EXTENT * 2)), []);
  return <group>
    <lineSegments geometry={geometry} position={[0, SCENE_WALL_MID_Y, 0]}><lineBasicMaterial color="#718078" /></lineSegments>
    <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, SCENE_FLOOR_Y, 0]}>
      <planeGeometry args={[SCENE_ROOM_HALF_EXTENT * 2, SCENE_ROOM_HALF_EXTENT * 2]} /><meshBasicMaterial color="#242c2b" transparent opacity={0.55} side={THREE.DoubleSide} />
    </mesh>
    <gridHelper args={[SCENE_ROOM_HALF_EXTENT * 2, 10, "#52635d", "#35413c"]} position={[0, SCENE_FLOOR_Y + 0.01, 0]} />
  </group>;
}

function Listener() {
  return <group position={[0, 0, 0]}>
    <mesh scale={[0.8, 1.05, 0.88]}><sphereGeometry args={[0.16, 14, 12]} /><meshStandardMaterial color="#d2d8d4" roughness={0.6} /></mesh>
    <mesh position={[0, -0.18, 0]}><cylinderGeometry args={[0.07, 0.08, 0.12, 16]} /><meshStandardMaterial color="#78837e" /></mesh>
    <mesh position={[0, 0, -0.15]}><coneGeometry args={[0.025, 0.07, 10]} /><meshBasicMaterial color="#f0b44b" /></mesh>
  </group>;
}

function ObjectPoint({ object }: { object: MobileObjectPoint }) {
  const group = useRef<THREE.Group>(null);
  const target = useRef(new THREE.Vector3(...admToScenePosition(object.pos)));
  useFrame((_, dt) => {
    if (!group.current) return;
    target.current.set(...admToScenePosition(object.pos));
    group.current.position.set(...smoothScenePosition(group.current.position.toArray() as [number, number, number], target.current.toArray() as [number, number, number], dt));
  });
  const color = useMemo(() => new THREE.Color().setHSL(0.53 - object.pos[2] * 0.2, 0.85, 0.58), [object.pos[2]]);
  return <group ref={group} position={admToScenePosition(object.pos)}>
    <mesh><sphereGeometry args={[0.16, 14, 12]} /><meshBasicMaterial color={color} transparent opacity={0.2} depthWrite={false} /></mesh>
    <mesh><sphereGeometry args={[0.065, 12, 10]} /><meshStandardMaterial color={color} emissive="#124b47" /></mesh>
  </group>;
}

function Scene({ objects, cameraInput }: { objects: readonly MobileObjectPoint[]; cameraInput: { rotation: { x: number; y: number }; distance: number } }) {
  const camera = useThree((state) => state.camera);
  useFrame(() => {
    const spherical = new THREE.Spherical(cameraInput.distance, Math.PI / 2.9 + cameraInput.rotation.y, cameraInput.rotation.x);
    camera.position.setFromSpherical(spherical);
    camera.lookAt(0, 0.35, 0);
  });
  return <>
    <ambientLight intensity={1.2} /><directionalLight position={[3, 6, 4]} intensity={1.4} />
    <Room />
    {LAYOUT_7_1_4.map((speaker) => {
      const position = speakerScenePosition(speaker);
      return <group key={speaker.name} position={position}>
        <mesh rotation={[Math.PI / 2, 0, 0]}><cylinderGeometry args={[speaker.isLfe ? 0.1 : 0.075, speaker.isLfe ? 0.1 : 0.075, 0.12, 12]} /><meshStandardMaterial color={speaker.isLfe ? "#ba8750" : "#e8ecea"} metalness={0.25} roughness={0.45} /></mesh>
        {!speaker.isLfe && <mesh position={[0, 0, 0.085]}><sphereGeometry args={[0.027, 10, 8]} /><meshBasicMaterial color="#40d6c6" /></mesh>}
      </group>;
    })}
    <Listener />
    {objects.map((object) => <ObjectPoint key={object.id} object={object} />)}
  </>;
}

export function MobileObjectScene({ objects }: { objects: readonly MobileObjectPoint[] }) {
  const input = useRef({ rotation: { x: 0.72, y: 0.25 }, distance: 7 });
  const previousPinch = useRef(0);
  const pan = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderGrant: (event: GestureResponderEvent) => {
      const touches = event.nativeEvent.touches;
      previousPinch.current = touches.length >= 2 ? Math.hypot(touches[0]!.pageX - touches[1]!.pageX, touches[0]!.pageY - touches[1]!.pageY) : 0;
    },
    onPanResponderMove: (event: GestureResponderEvent, gesture: PanResponderGestureState) => {
      const touches = event.nativeEvent.touches;
      if (touches.length >= 2) {
        const pinch = Math.hypot(touches[0]!.pageX - touches[1]!.pageX, touches[0]!.pageY - touches[1]!.pageY);
        if (previousPinch.current > 0) input.current.distance = THREE.MathUtils.clamp(input.current.distance * previousPinch.current / Math.max(1, pinch), 3.5, 12);
        previousPinch.current = pinch;
      } else {
        input.current.rotation.x -= gesture.dx * 0.006;
        input.current.rotation.y = THREE.MathUtils.clamp(input.current.rotation.y + gesture.dy * 0.006, -0.45, 1.0);
        previousPinch.current = 0;
      }
    },
  })).current;
  return <View style={{ flex: 1, overflow: "hidden" }} {...pan.panHandlers}>
    <Canvas camera={{ position: [5, 4.2, 5], fov: 48 }} gl={{ antialias: false, alpha: false }}>
      <Scene objects={objects} cameraInput={input.current} />
    </Canvas>
  </View>;
}
