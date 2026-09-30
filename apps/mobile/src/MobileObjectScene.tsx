import React, { useMemo, useRef } from "react";
import { PanResponder, View, type GestureResponderEvent, type PanResponderGestureState } from "react-native";
import { Canvas, useFrame, useThree } from "@react-three/fiber/native";
import * as THREE from "three";
import { PALETTE, Room, Listener, GenelecSpeaker, GenelecSub } from "../../../packages/renderer/src/scene-models";
import { rotateScene } from "./scene-gesture";
import { LAYOUT_7_1_4 } from "../../../packages/renderer/src/layouts";
import { admToScenePosition, SCENE_FLOOR_Y, SCENE_ROOM_HALF_EXTENT, SCENE_WALL_HEIGHT, SCENE_WALL_MID_Y, smoothScenePosition, speakerScenePosition } from "../../../packages/renderer/src/scene-coordinates";

export interface MobileObjectPoint { id: number; pos: [number, number, number]; gainDb: number }

function ObjectPoint({ object }: { object: MobileObjectPoint }) {
  const group = useRef<THREE.Group>(null);
  const target = useRef(new THREE.Vector3(...admToScenePosition(object.pos)));
  useFrame((_, dt) => {
    if (!group.current) return;
    target.current.set(...admToScenePosition(object.pos));
    group.current.position.set(...smoothScenePosition(group.current.position.toArray() as [number, number, number], target.current.toArray() as [number, number, number], dt));
  });
  const color = useMemo(() => new THREE.Color().setHSL(0.55 - object.pos[2] * 0.25, 0.9, 0.6), [object.pos[2]]);
  return <group ref={group} position={admToScenePosition(object.pos)}>
    <mesh><sphereGeometry args={[0.09, 12, 12]} /><meshBasicMaterial color={color} transparent opacity={0.1} depthTest={false} depthWrite={false} /></mesh>
    <mesh><sphereGeometry args={[0.06, 10, 10]} /><meshBasicMaterial color={color} transparent opacity={1} depthTest={false} depthWrite={false} /></mesh>
  </group>;
}

function Scene({ objects, cameraInput }: { objects: readonly MobileObjectPoint[]; cameraInput: { rotation: { x: number; y: number }; distance: number } }) {
  const camera = useThree((state) => state.camera);
  useFrame(() => {
    const spherical = new THREE.Spherical(cameraInput.distance, Math.PI / 2.9 + cameraInput.rotation.y, cameraInput.rotation.x);
    camera.position.setFromSpherical(spherical);
    camera.position.y += 0.5;
    camera.lookAt(0, 0.5, 0);
  });
  return <>
    <color attach="background" args={[PALETTE.dark.bg]} />
    <ambientLight intensity={0.75} /><directionalLight position={[2.5, 4, 2]} intensity={1.2} />
    <Room p={PALETTE.dark} />
    <gridHelper args={[SCENE_ROOM_HALF_EXTENT * 2, 10, PALETTE.dark.gridMain, PALETTE.dark.floorGrid]} position={[0, SCENE_FLOOR_Y, 0]} />
    {LAYOUT_7_1_4.map((speaker) => {
      const position = speakerScenePosition(speaker);
      const facing = new THREE.Object3D();
      facing.position.set(...position);
      facing.lookAt(0, speaker.isLfe ? SCENE_FLOOR_Y + 0.13 : 0, 0);
      return <group key={speaker.name} position={position} quaternion={facing.quaternion}>
        {speaker.isLfe ? <GenelecSub /> : <GenelecSpeaker />}
      </group>;
    })}
    <Listener />
    {objects.map((object) => <ObjectPoint key={object.id} object={object} />)}
  </>;
}

export function MobileObjectScene({ objects }: { objects: readonly MobileObjectPoint[] }) {
  const input = useRef({ rotation: { x: 0.72, y: 0.25 }, distance: 7 });
  const previousPinch = useRef(0);
  const previousDrag = useRef<{ x: number; y: number } | null>(null);
  const viewportHeight = useRef(300);
  const pan = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderGrant: (event: GestureResponderEvent) => {
      const touches = event.nativeEvent.touches;
      previousDrag.current = touches.length === 1 ? { x: touches[0]!.pageX, y: touches[0]!.pageY } : null;
      previousPinch.current = touches.length >= 2 ? Math.hypot(touches[0]!.pageX - touches[1]!.pageX, touches[0]!.pageY - touches[1]!.pageY) : 0;
    },
    onPanResponderMove: (event: GestureResponderEvent) => {
      const touches = event.nativeEvent.touches;
      if (touches.length >= 2) {
        previousDrag.current = null;
        const pinch = Math.hypot(touches[0]!.pageX - touches[1]!.pageX, touches[0]!.pageY - touches[1]!.pageY);
        if (previousPinch.current > 0) input.current.distance = THREE.MathUtils.clamp(input.current.distance * previousPinch.current / Math.max(1, pinch), 3.5, 12);
        previousPinch.current = pinch;
      } else if (touches.length === 1) {
        const point = { x: touches[0]!.pageX, y: touches[0]!.pageY };
        if (previousDrag.current) {
          input.current.rotation = rotateScene(input.current.rotation, point.x - previousDrag.current.x, point.y - previousDrag.current.y, viewportHeight.current);
        }
        previousDrag.current = point;
        previousPinch.current = 0;
      }
    },
    onPanResponderRelease: () => { previousDrag.current = null; previousPinch.current = 0; },
    onPanResponderTerminate: () => { previousDrag.current = null; previousPinch.current = 0; },
  })).current;
  return <View style={{ flex: 1, overflow: "hidden" }} onLayout={event => { viewportHeight.current = event.nativeEvent.layout.height; }} {...pan.panHandlers}>
    <Canvas camera={{ position: [5, 4.2, 6], fov: 50 }} gl={{ antialias: false, alpha: false }}>
      <Scene objects={objects} cameraInput={input.current} />
    </Canvas>
  </View>;
}
