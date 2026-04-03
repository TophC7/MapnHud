# Fox Map — TODO

## Iteration 2: Chunk-Cache Architecture — DONE

- [x] `ChunkColorCache` with `Long2ObjectOpenHashMap`, event-driven scan queue
- [x] `ChunkScanner` with `BlockColors` tint registry + `BlockColorExtractor` texture sampling
- [x] `MinimapAssembler` composites visible area from cached tiles
- [x] `MinimapRenderer` owns DynamicTexture lifecycle, dirty-flag from cache
- [x] `MinimapLayer` with rotation, smooth scrolling, player dots
- [x] Elevation shading + water depth shading (vanilla algorithm)
- [x] Biome tinting via BlockColors (per-block accuracy: spruce vs oak vs birch)
- [x] Z keybind for zoom level cycling

---

## Iteration 3: Rendering Quality (current)

Known issues from testing that need fixing:

### Elevation / Depth
- [x] Two-layer shading: sea-level-relative height modifier + north-south edge detection
- [x] LOWEST brightness level for steep drops, wider contrast range
- [x] Leaf blocks darkened for tree visibility against grass
- [ ] Tune height/edge factors after more play-testing

### Water
- [x] Water renders floor block with biome-tinted overlay, alpha scales by depth
- [x] Biome water tint via `BiomeColors.WATER_COLOR_RESOLVER`
- [x] Seagrass/kelp rendered as underwater floor with water tint overlay
- [x] Lava not treated as water (FluidTags.WATER check)

### Vegetation
- [x] Crop rendering at all growth stages with actual texture color
- [ ] Short grass: `SKIP_BLOCKS` logic is correct, the green is `GRASS_BLOCK` underneath. May need investigation if still visually noisy

---

## Iteration 4: Viewport-First Renderer + Heightfield Lighting

Architectural refactor for better visuals and cleaner shading logic.

### Viewport-first renderer
- [ ] Cache raw column data (base color, rendered height, water metadata) instead of final shaded colors
- [ ] Shade during assembly in one continuous 128x128 pass over the visible viewport
- [ ] Chunk seams become impossible since shading never crosses cache boundaries

### Heightfield lighting
- [ ] Compute smoothed heightfield normals/gradients from the visible viewport
- [ ] Directional light + ambient term for natural terrain relief
- [ ] Optional curvature/AO term for valleys and overhangs
- [ ] Apply material color and water tint last

Both depend on the viewport-first renderer being in place first, since heightfield
lighting needs the full visible heightfield in one pass.

---

## Iteration 5: Polish & Config

- [ ] Config via `ModConfigSpec`: map size, corner position, toggle, keybinds
- [ ] Keybinding: toggle minimap visibility
- [ ] North indicator on map frame
- [ ] Chunk boundary lines (toggleable)
- [ ] Persistence: save chunk color cache to disk for explored areas

---

## Backlog

### Server-Side Enhancement (optional install)
- [ ] `PlayerDistancePayload`: server broadcasts distance + angle for beyond-render-distance players
- [ ] Tiered update rates: <1000 blocks every 2-3s, >1000 blocks every 10-15s
- [ ] Player cap per packet (~30 nearest)
- [ ] Opt-in system: server config + per-player `/foxmap hide` command
- [ ] Paper plugin variant: same packet format, different platform API

### Tab List Overhaul
- [ ] Custom layout with player distances
- [ ] Dimension annotation ("In The Nether") for other-dimension players
- [ ] Modded dimension name formatting (strip namespace, title-case)

### Block Tooltip (Jade-Lite)
- [ ] Raycast to block name + icon + source mod
- [ ] Compact horizontal bar near crosshair
- [ ] Fade in/out transition
- [ ] Auto-disable if Jade is installed

### HUD Info Overlay
- [ ] Coordinates, biome, light level, compass heading
- [ ] Configurable anchor position
- [ ] Vanilla font, shadow text, semi-transparent background

### Compatibility
- [ ] Pluggable player dot colors (FTB Teams / vanilla teams / UUID fallback)

---

## Research Notes

### Key Vanilla References
- `MapItem.update()`: terrain scanning algorithm, elevation shading, water depth
- `MapColor` / `MapColor.Brightness`: 64 colors x 4 shades
- `BlockColors`: per-block tint registry (biome-dependent, hardcoded, or none)
- `Heightmap.Types.WORLD_SURFACE`: what vanilla maps use (includes non-collidable)

### NeoForge Client Events
- `ChunkEvent.Load` / `Unload`: fires on BOTH client and server
- `LevelEvent.Load`: dimension change detection, cache clear
- `ClientTickEvent.Post`: from `net.neoforged.neoforge.client.event` (not `.event.tick`)
- NO client-side block change event exists, use dirty timer or mixin

### Color Pipeline
- `BlockColorExtractor`: samples average texture color per block from atlas at init
- `BlockColors.getColor()`: returns per-block tint (-1 if untinted)
- Tinted blocks: final color = texture grayscale x biome/hardcoded tint (GPU shader replication)
- `getPixelRGBA` and `calculateRGBColor` both return ABGR despite their names
