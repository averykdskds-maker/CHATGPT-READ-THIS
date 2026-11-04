# Kib Aimbot

Client-side Fabric mod for **Minecraft Java 26.3**, **Fabric Loader 0.20.1+**, **Fabric API 0.165.0+26.3**, and **Java 25**.

The GitHub Actions workflow builds the mod and uploads the compiled JAR as an artifact.

## Commands

Enter these in Minecraft chat:

| Command | Effect |
|---|---|
| `/aimbot on` | Enable target selection and aiming |
| `/aimbot off` | Disable the mod |
| `/aimbot status` | Show current settings |
| `/aimbot range 48` | Set target search range (3–128 blocks; aiming only) |
| `/aimbot fov 120` | Set target field of view (5–180 degrees) |
| `/aimbot smooth 65` | Set aim smoothing (1–100%; 100 is instant) |
| `/aimbot camera 50` | Reduce manual mouse-look sensitivity to 50% of its original setting (1–100%) |
| `/aimbot camera reset` | Restore the original manual mouse-look sensitivity |
| `/aimbot click on` / `/aimbot click off` | Toggle auto-click attempts |
| `/aimbot hit on` / `/aimbot hit off` | Toggle automatic hit attempts |
| `/aimbot cps 8` | Set auto-click attempt rate (1–20 CPS) |
| `/aimbot bow on` / `/aimbot bow off` | Toggle bow trajectory prediction |
| `/aimbot crossbow on` / `/aimbot crossbow off` | Toggle crossbow trajectory prediction |

## Behaviour

- Targets other visible, alive players. It ignores the local player, spectators, invisible players, and teammates/allies.
- Selects a target inside the configured field of view and search range.
- The separate `camera` command changes Minecraft's vanilla mouse sensitivity for manual camera movement; it does not change automated target rotation.
- Leads moving targets using their relative movement.
- For drawn bows and loaded crossbows, estimates projectile travel time, arrow drag, and gravity to aim ahead of the target.
- Auto-click mode follows the configured attempt interval. Auto-hit checks every client tick.
- **Every automatic melee attempt is gated by Minecraft's current vanilla crosshair hit result.** The mod only invokes the normal vanilla attack routine when the crosshair has selected the exact player currently being aimed at.
- The vanilla attack routine applies the current item's attack-range rules. The mod does not modify attack reach or directly send an attack against an arbitrary entity.
- Automatic attacks are suppressed while a GUI is open, the mouse is not grabbed, or the player is using an item. Vanilla attack cooldown is also respected.

The target search range controls aiming only. It does not increase attack range. A server may still apply stricter validation or prohibit automated aiming and attacks.

## Build

Requires JDK 25 and Gradle 9.8.0:

```bash
gradle --no-daemon clean build
```

The JAR is generated in `build/libs/kib-aimbot-1.0.2.jar`.

## Install

1. Download the `kib-aimbot-mc26.3-v1.0.2` artifact from the repository's **Actions** workflow.
2. Extract the JAR from the artifact ZIP.
3. Put the JAR in the Minecraft instance's `mods` folder alongside Fabric API.
4. Remove the older `kib-aimbot-1.0.0.jar` first.
5. Launch Minecraft 26.3 using Java 25.

This is a client mod; it is not installed on a dedicated server. Servers may restrict or prohibit automated aiming and attacks, and their anti-cheat systems can still detect or reject them.

## Limitations

Projectile prediction is an estimate, not a perfect simulation of every server's latency, arrow physics, potion effects, or target movement. The mod does not attempt to bypass anti-cheat protections.
