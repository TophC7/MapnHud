# Fox Map — TODO

## MAP

Everything related to the minimap: rendering, visuals, data, and interaction.

#### Open tweaks

- [ ] Tune height/edge shading factors after more play-testing
- [ ] Short grass: `SKIP_BLOCKS` logic is correct but may still look visually noisy
- [ ] Water chunk border seams: row 0 gets NORMAL brightness when north neighbor is not yet cached, causing visible lighter borders on water that only correct on rescan

### Iteration 4: Viewport-First Renderer + Heightfield Lighting

Architectural refactor for better visuals and cleaner shading logic.

#### Viewport-first renderer

- [ ] Cache raw column data (base color, rendered height, water metadata) instead of final shaded colors
- [ ] Shade during assembly in one continuous pass over the visible viewport
- [ ] Chunk seams become impossible since shading never crosses cache boundaries

#### Heightfield lighting

- [ ] Compute smoothed heightfield normals/gradients from the visible viewport
- [ ] Directional light + ambient term for natural terrain relief
- [ ] Optional curvature/AO term for valleys and overhangs
- [ ] Apply material color and water tint last

Both depend on the viewport-first renderer being in place first, since heightfield
lighting needs the full visible heightfield in one pass.

### Iteration 5: Map Polish

- [ ] Chunk boundary lines (toggleable)
- [ ] North indicator on map frame
- [ ] Persistence: save chunk color cache to disk for explored areas

### Backlog

#### Server-Side Player Radar (optional install)

- [ ] `PlayerDistancePayload`: server broadcasts distance + angle for beyond-render-distance players
- [ ] Tiered update rates: <1000 blocks every 2-3s, >1000 blocks every 10-15s
- [ ] Player cap per packet (~30 nearest)
- [ ] Opt-in system: server config + per-player `/mapnhud hide` command
- [ ] Paper plugin variant: same packet format, different platform API

#### Compatibility

- [ ] Pluggable player dot colors (FTB Teams / vanilla teams / UUID fallback)

---

## HUD

Overlays, panels, and UI elements outside the minimap.

### Config

- [ ] Keybinding: toggle minimap visibility

### Tab List Overhaul

- [ ] Custom layout with player distances
- [ ] Dimension annotation ("In The Nether") for other-dimension players
- [ ] Modded dimension name formatting (strip namespace, title-case)

### Block Tooltip (Jade-Lite)

- [ ] Raycast to block name + icon + source mod
- [ ] Compact horizontal bar near crosshair
- [ ] Fade in/out transition
- [ ] Auto-disable if Jade is installed

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

### Config System

- `ModConfigSpec` CLIENT type: saved to `.minecraft/config/mapnhud-client.toml`
- `.set()` writes in-memory only; `SPEC.save()` flushes to disk
- `IConfigScreenFactory` registered via `ModContainer.registerExtensionPoint()` for mod list gear icon
- Vanilla tab system: `TabNavigationBar` + `TabManager` + `GridLayoutTab`
- Config values cached per-tick in `MinimapKeybinds` to avoid config tree traversal on render path
