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
| `/aimbot range 48` | Set target search range (3–128 blocks) |
| `/aimbot fov 120` | Set target field of view (5–180 degrees) |
| `/aimbot smooth 65` | Set aim smoothing (1–100%; 100 is instant) |
| `/aimbot click on` / `/aimbot click off` | Toggle auto-clicking |
| `/aimbot hit on` / `/aimbot hit off` | Toggle close-range auto-hit |
| `/aimbot cps 8` | Set auto-click rate (1–20 CPS) |
| `/aimbot bow on` / `/aimbot bow off` | Toggle bow trajectory prediction |
| `/aimbot crossbow on` / `/aimbot crossbow_off` | Toggle crossbow trajectory prediction |

## Behaviour

- Targets other visible, alive players. It ignores the local player, spectators, invisible players, and teammates/allies.
- Selects a target inside the configured field of view and range.
- Leads moving targets using their relative movement.
- For drawn bows and loaded crossbows, estimates projectile travel time, arrow drag, and gravity to aim ahead of the target.
- Auto-click mode attacks on the configured tick interval when the vanilla attack cooldown is ready.
- Auto-hit mode only attempts attacks inside 3.2 blocks. Auto-click mode uses a 5-block distance gate.
- All aiming and attack actions run client-side. The server still decides whether an attack is valid.

## Build

Requires JDK 25 and Gradle 9.2.1 or later:

```bash
gradle --no-daemon clean build
```

The JAR is generated in `build/libs/`.

## Install

1. Download the `kib-aimbot` artifact from the repository's **Actions** workflow.
2. Extract the JAR from the artifact ZIP.
3. Put the JAR in the Minecraft instance's `mods` folder alongside Fabric API.
4. Launch Minecraft 26.3 using Java 25.

This is a client mod; it is not installed on a dedicated server. Servers may restrict or prohibit automated aiming and attacks, and their anti-cheat systems can still detect or reject them.

## Limitations

Projectile prediction is an estimate, not a perfect simulation of every server's latency, arrow physics, potion effects, or target movement. It does not attempt to bypass anti-cheat protections.
