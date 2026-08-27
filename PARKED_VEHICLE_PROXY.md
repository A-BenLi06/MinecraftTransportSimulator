# Parked Vehicle Proxy Operations Guide

This branch adds an opt-in lifecycle for idle vehicles on NeoForge 1.21.1. An eligible vehicle is durably serialized, removed from the normal MTS and Minecraft entity update paths, and represented by indexed static collision plus a non-ticking client render proxy. Activity restores the original UUID and complete NBT entity graph.

## Safety model

- The feature is disabled by default. Install the same build and configuration on the server and every client before enabling it.
- Back up every dimension's `data/mts_parked_vehicles.dat` together with the normal world data before testing or upgrading.
- A parked record is authoritative. Missing packs, unsupported record schemas, reconstruction failures, and interrupted transitions retain data instead of converting the vehicle to an item or deleting it.
- Startup waits 40 ticks before reconciling interrupted transitions. A live vehicle wins over a stale record; an absent vehicle leaves the record parked.
- Unknown records are quarantined and written back unchanged. Do not delete the SavedData file to address a load error.

## Configuration

The `parking` section of `mtsconfig.json` contains:

| Setting | Default | Purpose |
| --- | ---: | --- |
| `enabled` | `false` | Allows eligible vehicles to enter the parked lifecycle. |
| `stableDelaySeconds` | `5` | Continuous eligibility required before parking. |
| `maximumLinearSpeed` | `0.002` | Maximum motion in blocks per tick. |
| `maximumAngularSpeed` | `0.01` | Maximum rotation in degrees per tick. |
| `wakeDistance` | `16` | Player-proximity wake radius. |
| `wakeScanIntervalTicks` | `5` | Interval for chunk-indexed proximity queries. |
| `clientRenderDistance` | `256` | Maximum proxy synchronization distance. |
| `staticCollision` | `true` | Keeps cached collision while the active entity is absent. |
| `restoreWhenDisabled` | `true` | Restores parked records after the feature is disabled. |
| `restoreBatchSize` | `4` | Maximum rollback restorations per server tick. |

A vehicle remains active when occupied, moving, rotating, being towed, colliding, damaged, remotely controlled, interacting, playing a radio, tracking navigation/radar/missile state, using electrical controls, or running an active engine, gun, effector, crafting task, or linked transfer.

## Wake paths

Any of these restores the full server entity before mutable behavior continues:

- a player enters `wakeDistance`;
- a click, attack, or normal MTS entity-control packet addresses the vehicle or any persisted part UUID;
- an entity intersects cached collision;
- an explosion begins in the indexed bounds;
- an operator uses a recovery command.

The server uses a dimension-local chunk-to-UUID index. Proximity and collision work scale with nearby occupied chunks rather than the total number of parked records.

## Operator commands

All commands require permission level 2.

- `/mtsparking status` — lifecycle, quarantine, index, synchronization, and rollback-queue counts.
- `/mtsparking verify` — checks aliases, index coverage, dangling references, and live/parked exclusivity.
- `/mtsparking wake <uuid>` — restores the parked owner of a vehicle or part UUID.
- `/mtsparking wakeall` — attempts immediate restoration of every parked record.

## Rollout and evidence collection

Use the same world copy, player position, view/simulation distances, Java arguments, packs, and test duration for baseline and optimized runs.

1. Back up the world and capture `/mtsparking status` and `verify` before the run.
2. Baseline: keep `parking.enabled=false`, ensure `records=0`, wait for the server to settle, then capture a 5–10 minute Spark profile and client F3 memory after the same camera route.
3. Optimized: enable parking on both sides, wait until the target vehicles are parked, record `status`, and repeat the identical profile and route.
4. Save `latest.log`, Spark URLs/files, F3 screenshots, JVM arguments, mod list, pack list, vehicle count, parked count, and median/p95 MSPT. Report both heap used and committed memory; committed memory alone is not retained-object evidence.
5. Exercise each wake path, restart between PARKING_PENDING/PARKED/WAKING fault-injection cases when practical, and run `verify` after every recovery test.

For an upstream issue or PR, attach before/after call trees showing MTS entity/part update cost, an object histogram or heap dump showing retained vehicle/model state, the above controlled-run metadata, and a minimal reproduction world. Do not claim a memory or TPS percentage without a comparable measured pair.

## Rollback

1. Set `parking.enabled=false` while keeping `restoreWhenDisabled=true` and this build installed.
2. Start the server and wait until `/mtsparking status` reports `records=0` and `rollbackQueue=0`. Increase `restoreBatchSize` only if the server has sufficient tick headroom.
3. Run `/mtsparking verify`; it must report no failures.
4. Stop the server normally, back up the restored world, and only then replace or remove this build.

If a record cannot restore because a vehicle pack is missing, reinstall the exact pack first. The record remains in SavedData and is intentionally not converted to `ItemVehicle`.
