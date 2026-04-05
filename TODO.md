# Fox Map — TODO

## MAP

Everything related to the minimap: rendering, visuals, data, and interaction.

#### Open tweaks

- [ ] Tune height/edge shading factors after more play-testing

#### Tuning (needs play-testing)

All rendering values are now live-tunable via the Rendering tab config screen.
Play-test in varied terrain and decide on final defaults for:

- [ ] Lighting: ambient, light angle, sun height, terrain smoothness
- [ ] AO: strength, max darkening
- [ ] Terrain: height scale, height min/max, leaf shade
- [ ] Water: base alpha, per-depth, max alpha

### Iteration 5: Cave Mode

Flood-fill reachability from player position determines visible space.
Reuses the full surface rendering pipeline (shading, AO, water blending).
Walls are black, floors are colored by the block underfoot.

- [x] Underground detection: WORLD_SURFACE heightmap with hysteresis
- [x] Flood fill reachability: 2D BFS with step delta 2, radius 100
- [x] Floor coloring: reuses surface scanColumn pipeline
- [x] Wall rendering: black for non-reachable columns
- [x] Config: cave auto-switch toggle (on/off)
- [x] Cave stats overlay provider (configurable info line)
- [x] Live-only visibility (no persistence yet, fog of war is iteration 6+)
- [ ] Surface/cave indicator on map frame
- [ ] Nether dimension handling (always cave mode? ceiling detection?)
- [ ] End dimension handling (never cave mode? open sky detection?)

### Iteration 6: Map Polish + Expanded View

- [ ] Chunk boundary lines (toggleable)
- [ ] North indicator on map frame
- [ ] Fog of war: track explored areas, dim visited regions, black for unexplored
  - [ ] Applies to both surface and cave modes
  - [ ] Persistence: save explored state to disk per dimension
- [ ] Fullscreen/expanded map view (keybind to open)
- [ ] Keybinds: toggle minimap visibility, zoom, cave mode toggle
- [ ] Mouse interaction in expanded view (scroll zoom, drag pan)
- [ ] Biome tint strength config option

### Backlog

#### Waypoints (ties into CustomPortalsFoxified)

- [ ] Right-click map to place waypoint
- [ ] Death marker auto-placed on death
- [ ] Portal-linked waypoints (integration with CustomPortalsFoxified)
- [ ] Waypoint rendering on minimap and expanded view

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
