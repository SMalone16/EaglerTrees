# EaglerTrees

A Paper 1.21.11 classroom plugin for the Eaglercraft server stack. It replaces ordinary natural-tree chopping with directional felling.

## What it does

1. **First axe strike sets the notch.** The plugin records the cardinal side of the trunk that was first struck.
2. **That side becomes the fall direction.** North/south/east/west hits map directly to the direction the tree will fall. If the hit face is ambiguous, the tree falls toward the player.
3. **Natural trees are detected; builds are ignored.** Connected logs must have enough nearby leaves to qualify as a natural tree.
4. **Leaves disappear when the fall begins.** This avoids a giant leaf blob landing with the trunk.
5. **The tree is rotated onto its side.** Connected logs are captured, rotated 90 degrees around the stump, and placed back as real blocks. Log axis data rotates too.
6. **The stump remains.** The lowest log layer stays in place by default.
7. **Branches underneath the trunk become planks.** Ground-level branches in the rotated result are converted to the matching wood planks so limbs do not visually prop the trunk up.
8. **Obstacles are protected.** The plugin lifts the landing shape a limited amount instead of overwriting solid terrain/builds. Branches that still cannot be placed safely are moved upward a few blocks or dropped as items.

## Why the fall is block-based instead of entity-based

The classroom server serves a modern Paper backend through an Eaglercraft/Via compatibility stack. Modern `BlockDisplay` animation is not guaranteed to render correctly for the older browser client. EaglerTrees therefore uses sounds/particles for the fall cue and then performs a deterministic real-block rotation. The result is visible and persistent on both the modern backend and the Eaglercraft client.

## Build

```bash
mvn clean package
mkdir -p dist
cp target/EaglerTrees-1.0.0.jar dist/EaglerTrees-1.0.0.jar
```

## Install

Copy `dist/EaglerTrees-1.0.0.jar` into the Paper server's `plugins/` directory, or select **Eagler Trees** from the classroom plugin picker in `SMalone16/Eaglercraft-1.21.11-Server`.

## Commands

- `/eaglertrees status` — shows plugin state.
- `/eaglertrees reload` — reloads `config.yml` (OP/admin permission).

## Main configuration controls

- `require-axe`: only axes activate tree felling.
- `min-tree-logs` / `min-nearby-leaves`: natural-tree protection thresholds.
- `fall-delay-ticks`: delay between break completion and the final rotated landing.
- `leave-stump`: keep the lowest trunk layer.
- `protect-solid-obstacles`: never overwrite solid builds/terrain with the fallen tree.
- `max-obstacle-lift`: maximum whole-tree lift used to clear the trunk footprint.
- `convert-ground-branches-to-planks`: turns branches underneath the fallen trunk into matching planks.
