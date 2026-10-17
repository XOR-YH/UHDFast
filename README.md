# UHDFast

A client-side performance mod built specifically for **Intel Xe** and **Intel UHD Graphics**
(integrated GPUs) on Minecraft 1.21.1 / NeoForge.

Most performance mods are tuned for discrete NVIDIA and AMD cards. UHDFast does the opposite:
it detects Intel Xe architecture (Iris Xe, UHD, Arc-class iGPUs) and applies optimizations
targeted at that hardware, with a safe fallback path for every other GPU.

## Features

- **Xe architecture detection** — identifies your Intel iGPU generation and execution unit
  count, then scales its optimizations accordingly
- **GPU chunk meshing** — compute-shader based chunk mesh building to reduce CPU work during
  chunk loading and rebuilding
- **Entity render optimization** — batching, render distance limits, frustum and occlusion
  culling
- **Entity tick LOD** — lowers AI update frequency for distant entities, with position
  interpolation so reduced ticking does not cause visible stutter
- **Chunk preloading and caching** — preloads chunks in your direction of travel to smooth out
  hitching, and caches generated chunk data
- **Memory and texture management** — unified memory handling, texture streaming, and an Xe
  shader compile cache
- **Frame pacing** — optional frame rate limiter and VSync tuning
- **In-game configuration** — a full settings screen, plus a performance HUD toggled with F6

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1 or newer
- Java 21

## Installation

1. Install NeoForge 21.1+ for Minecraft 1.21.1.
2. Drop the `uhdfast-<version>.jar` into your `mods` folder.
3. Launch the game. UHDFast is client-side only, so it does not need to be installed on the
   server.

## Compatibility

- Client-side only — safe to add to any server
- Optional integration with Distant Horizons and Chunky
- Automatically detects Sodium, Embeddium, Rubidium, Xenon, Iris/Oculus, OptiFine, Starlight,
  FerriteCore, ImmediatelyFast, ModernFix, EntityCulling and CullLeaves, and adjusts its own
  GPU pipelines to avoid conflicts

## Building from source

```
./gradlew build
```

The compiled jar is written to `build/libs/`.

## Status

This is an early beta (`0.1.0-beta`). The GPU pipelines are tuned for Intel Xe and may behave
differently on other vendors. Bug reports and feedback are welcome on the
[issue tracker](https://github.com/XOR-YH/UHDFast/issues).

## License

MIT — see [LICENSE](LICENSE).
