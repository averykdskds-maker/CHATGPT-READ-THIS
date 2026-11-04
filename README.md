# Kib Aimbot

Client-side Fabric mod for **Minecraft Java 26.3**, **Fabric Loader 0.20.1+**, **Fabric API 0.165.0+26.3**, and **Java 25**.

## Keybinds

Open **Options → Controls → Key Binds → Kib Aimbot** to customise these controls:

| Keybind | Default | Effect |
|---|---:|---|
| **Toggle Aimbot** | R | Turn automatic targeting and aiming on or off |
| **Auto Aimbot Off / Manual Mouse Override** | V | Toggle whether manual mouse movement temporarily takes control |

With Auto Aimbot Off enabled, moving the mouse away from the current nearest target suspends camera rotation and auto-attacks. Kib Aim does not snap back while the view is away. It resumes on the first client tick when your view is within roughly 20 degrees of that nearest target. Disable this mode to allow continuous automatic tracking.

## Commands

Enter these in Minecraft chat:

| Command | Effect |
|---|---|
| `/aimbot on` | Enable target selection and aiming |
| `/aimbot off` | Disable the mod |
| `/aimbot autooff on` / `/aimbot autooff off` | Enable or disable manual mouse override |
| `/aimbot attack on` / `/aimbot attack off` | Toggle the single vanilla-reach auto-attack feature |
| `/aimbot status` | Show current settings |
| `/aimbot range 48` | Set nearest-player search range (3–128 blocks) |
| `/aimbot smooth 65` | Set aim smoothing (1–100%; 100 is instant) |
| `/aimbot crossbow on` / `/aimbot crossbow off` | Toggle crossbow trajectory prediction |

The previous separate `click` and `hit` modes have been combined into one `attack` toggle. Bow aiming has been removed from Kib Aim; Wurst can handle bow aiming.

## Behaviour

- Selects the **physically nearest eligible player** within the configured range, regardless of which player is closest to the crosshair.
- Only considers players who are alive, visible, in line of sight, not invisible to you, and not teammates/allies.
- Since target selection is distance-based rather than crosshair/FOV-based, the nearest eligible player can be behind you; the camera may turn around to that player when automatic aiming is active.
- Manual mouse override can stop tracking and attacks until you manually bring your view back close to the nearest target.
- Bow aiming is intentionally not implemented. When holding a bow, Kib Aim leaves the camera alone so Wurst can provide that feature.
- Charged crossbow prediction remains available and estimates target movement, arrow drag, gravity, and shooter movement. If a sensible ballistic solution is unavailable, it falls back to aiming directly at the opponent instead of choosing a high-arcing shot.
- Automatic melee attacks are optional and disabled by default.
- **Every automatic melee attempt is gated by Minecraft's current vanilla crosshair hit result.** The mod only calls the normal vanilla attack routine when the current hit result is the selected nearest player.
- The vanilla attack routine applies the ordinary item cooldown and interaction-range rules. The mod does not increase reach or send direct entity attack packets.
- Automatic attacks are suppressed during GUIs, when the mouse is not grabbed, while using an item, and during manual mouse override.

The search range controls target selection only; it does not increase attack reach. A vanilla crosshair check is not a promise that an anti-cheat will permit automated attacks or rotations. GrimAC and Vulcan can still flag automated aiming or attacks. This mod does not attempt to bypass or conceal anti-cheat detections; use it only in single-player or on servers where the owner explicitly permits it.

## Build

Requires JDK 25 and Gradle 9.8.0:

```bash
gradle --no-daemon clean build
```

The JAR is generated in `build/libs/kib-aimbot-1.0.4.jar`.

## Install

1. Download the `kib-aimbot-mc26.3-v1.0.4` artifact from the repository's **Actions** workflow.
2. Extract the JAR from the artifact ZIP.
3. Put the JAR in the Minecraft instance's `mods` folder alongside Fabric API.
4. Remove the older Kib Aimbot JAR first.
5. Launch Minecraft 26.3 using Java 25.

This is a client mod; it is not installed on a dedicated server. Automated aiming and attacks may be restricted by servers and their anti-cheat systems.

