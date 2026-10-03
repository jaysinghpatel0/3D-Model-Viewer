# 3D Model Viewer — Android (Kotlin + Filament)

A single-activity Android app that displays several `.glb` 3D models on one screen at the same time.
Each model lives in its own container that can be dragged, pinch-resized, switched into an
interaction mode (rotate / zoom the model itself), labelled with its built-in part names, and closed.

Built for the Infusory Senior Android Developer L1 screening task. Performance on low-end devices was
the main design goal.

- **Language:** Kotlin  **Min SDK:** 24  **UI:** Android Views (custom `View`s, no Fragments)
- **3D library:** Google Filament 1.69.0 (`filament-android`, `gltfio-android`)
- **Demo video:** [Video walkthrough](https://www.loom.com/share/96c0a9d05ec24af28d3e980e86f8daa0)
- **Signed APK:** see the [Releases](../../releases) page

---

## Features

| Requirement | Implementation |
|---|---|
| Single Activity | `MainActivity` only. No Fragments, one canvas. |
| Load 5 `.glb` files | Bundled in `assets/models/`. The **Add model** button lists every `.glb` found there. |
| Many models at once | Any number can be added; tested with 5 simultaneously. |
| Draggable container | One-finger drag moves the container anywhere on screen. |
| Resizable container | Two-finger pinch resizes it; the 3D content scales with it. |
| 3 buttons per model | **3D** (interaction toggle), **Aa** (label toggle), **X** (close). Always visible. |
| Two modes never mix | See the table below. |
| Part labels | Read from `extras.prop` in the GLB JSON, projected every frame, with a connector line. Hidden on load. |
| Close frees resources | Entities, asset, light, skybox, camera, view and scene are all destroyed. |

### Gesture matrix

| Gesture | Normal mode | Interaction mode (**3D** on) |
|---|---|---|
| 1-finger drag | Moves the container | Rotates the 3D model |
| 2-finger pinch | Resizes the container | Zooms the 3D content (0.5x – 5x) |

In interaction mode the container never moves or resizes. In normal mode the model never rotates or zooms.

---

## Library choice: why Filament

- A lightweight native renderer designed for mobile, using OpenGL ES 3.0.
- **One Engine can render many `View`s** (each with its own viewport and camera) inside a single frame.
  This is what makes five models on screen cheap, instead of five separate GL surfaces.
- `gltfio` gives `.glb` loading with asynchronous texture decoding.
- Fine-grained control over what the renderer does per frame (post-processing, quality levels), which
  matters for low-end GPUs.

---

## Architecture

```
+------------------------------------------------------------+
| MainActivity (FrameLayout)                                 |
|   SurfaceView   <- all 3D drawn here (ONE surface)         |
|   OverlayView   <- borders, buttons, labels, ALL touch     |
|   "Add model" button                                       |
+------------------------------------------------------------+
        |                                  |
        v                                  v
 ModelSceneHost  <---------------->   ModelContainer (x N)
 (Engine, Renderer,                   (rectangle, Scene, View,
  render loop, loading,                Camera, transform,
  cleanup)                             label projection)
```

- **One SurfaceView, one Engine, one render loop.** Every model has its own Filament `Scene`, `View` and
  `Camera`. The `View`'s **viewport is the container rectangle**. Moving or resizing a container only
  changes that rectangle; no Android layout pass runs.
- **One transparent `OverlayView`** draws the borders, buttons, labels and connector lines, and owns all
  touch handling. A container is hit-tested on `ACTION_DOWN` and stays locked for the whole gesture, and
  the mode can only change through a button press, so the two modes cannot mix.
- Overlay content of a lower container is clipped where a container above it covers it, because the 3D
  pixels of the upper container sit below the overlay.

### Labels

1. At load time (background thread) the GLB header and JSON chunk are parsed.
2. Every node with `extras.prop` becomes a label. Its world position is computed from the node hierarchy
   (by node index, so duplicate node names are handled correctly).
3. Positions are normalised into the same unit-sphere space the model is rendered in.
4. **Every rendered frame** each anchor is rotated, perspective-projected with the same camera parameters
   Filament uses, and converted to overlay pixels. The overlay draws a dot, a connector line and a text
   box. This step is allocation-free.

### Close

`destroyContainer()` cancels any pending async load, removes the asset's entities from the scene,
destroys the asset, directional light, skybox, camera component, view and scene, and releases the
buffers. Memory returns near baseline (see test results).

---

## Performance decisions

1. **Render on demand.** A dirty flag + `Choreographer`: nothing is drawn unless something changed.
   Idle means zero GPU work. Frame rate is capped at 60 fps.
2. **Capped GL surface size.** The surface is fixed to at most 1600 px on its long side (1280 px on
   devices reporting `isLowRamDevice`) with `SurfaceHolder.setFixedSize`; the system scales it up.
   This cuts fill-rate on high-density screens.
3. **Cheaper pipeline.** FXAA, dithering and shadowing are explicitly disabled, and the HDR colour
   buffer quality is set to LOW (R11G11B10 instead of RGBA16F). MSAA, SSAO, bloom and similar effects
   are left at Filament's defaults, which are off.
4. **Async loading.** File IO runs on a worker thread, and textures are decoded through
   `ResourceLoader.asyncBeginLoad` / `asyncUpdateLoad`, so the UI thread is not blocked. Source data is
   released as soon as loading finishes.
5. **Allocation-free label projection** on the per-frame path.
6. **Procedural environment light.** A 16x16 gradient cube map plus spherical-harmonics ambient, instead
   of shipping an HDR/KTX environment. Almost no RAM or APK cost, and metallic parts do not render black.

### Trade-offs

- Ubershader materials (precompiled) instead of JIT shaders: fast load, slightly heavier fragment shader.
- No anti-aliasing: slight edge jaggies in return for GPU savings.
- Labels are not depth-occluded.
- Containers are square, so resizing is a uniform scale.

---

## Test results

Device: **Vivo Z1x**, Android 11, 6 GB RAM (mid-range), 5 models loaded.

| Metric | Result |
|---|---|
| In-app FPS during drag / rotate / zoom | ~56-60, capped at 60 by design |
| `gfxinfo` janky frames | 26 / 662 (3.93%) |
| `gfxinfo` frame time p50 / p90 / p95 / p99 | 9 / 12 / 15 / 23 ms |
| Memory at app start | 128 MB |
| Memory, 5 models on screen | 290 MB |
| Memory peak while loading | 452 MB (transient) |
| Memory after closing all models | 181 MB |
| 5 add/close cycles (after Force GC) | stable, no upward trend: `<181, 183, 182, 184, 182 MB>` |

Notes:
- `dumpsys gfxinfo` measures UI-thread frames. The 3D scene is drawn on a separate surface, so the
  in-app fps counter (top-left of the screen) is the reference for 3D smoothness.
- The 6 GB Vivo Z1x is stronger than the 2–3 GB target in the task. I did not have real hardware that low.
- Measured with Android Studio Profiler (memory), `adb shell dumpsys gfxinfo` and the in-app fps counter.

---

## Known bugs / limitations

- Labels are not depth-occluded: a label stays visible even when its part is behind the model.
- When zoomed out, labels can overlap each other (no collision avoidance).
- The 2D label overlay and the 3D surface are composited separately, so a one-frame offset can be
  visible during very fast rotation.
- A brief hitch can occur while a model is loading (material and texture upload).
- Portrait orientation only.

---

## What I would improve with more time

- Reduce the ~452 MB loading peak: read the `.glb` straight into a direct `ByteBuffer` instead of reading
  into a `ByteArray` first and copying (the file is held in memory twice while loading).
- Dynamic resolution / per-view render scale driven by measured frame time.
- Depth-aware label occlusion and label collision avoidance.
- Texture downscaling or KTX2 (Basis) compression in a build step.
- An instrumented frame-time test to catch performance regressions.

---

## Project structure

```
app/src/main
 ├─ AndroidManifest.xml
 ├─ assets/models/                      the five .glb files
 └─ java/com/example/a3dmodelviewer
     ├─ MainActivity.kt                 single activity, wiring, model picker
     ├─ render/
     │   ├─ ModelSceneHost.kt           Engine, render loop, async loading, cleanup
     │   ├─ ModelContainer.kt           one model's rectangle, camera, transform, label projection
     │   └─ GlbLabelParser.kt           GLB JSON parsing, extras.prop labels + world positions
     └─ ui/
         └─ OverlayView.kt              buttons, labels, borders, all touch handling
```

---

## Build and run

- Android Studio (bundled JDK), Min SDK 24, Filament 1.69.0.
- Device needs OpenGL ES 3.0.
- The five `.glb` files go in `app/src/main/assets/models/`.
- Open the project, let Gradle sync, then Run.
- A signed release APK is attached to the GitHub Releases page.
