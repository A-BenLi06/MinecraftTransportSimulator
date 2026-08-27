# Walkthrough

## 2026-08-27T23:51:21+08:00 — Parked vehicle proxy architecture

### Baseline and scope

- Started `benli06/parked-vehicle-proxy-1.21.1` from upstream commit `641c06d` (Immersive Vehicles 25.0.0 for NeoForge 1.21.1).
- Audited the complete prior discussion, the existing MTS entity/NBT lifecycle, NeoForge builder persistence, packet transport, world tick hooks, collision mixin, and Create 1.21.1 carriage lifecycle.
- The goal is not to turn a vehicle into ordinary blocks. A parked record remains authoritative while the expensive MTS entity graph exists only when active.

### Required state machine

```text
ACTIVE
  -> PARKING_PENDING (entity remains authoritative while a durable record is prepared)
  -> PARKED          (record is authoritative; MTS vehicle, parts, and builder are absent)
  -> WAKING          (record remains authoritative while entity restoration is validated)
  -> ACTIVE          (record is removed only after the restored entity is registered)
```

The transition protocol must be idempotent. Startup reconciliation follows these rules:

- `PARKING_PENDING` plus a live entity cancels the pending record.
- `PARKING_PENDING` without a live entity promotes the record to `PARKED`.
- `WAKING` plus a live entity removes the stale record.
- `WAKING` without a live entity returns the record to `PARKED`.

These rules prevent both loss and duplicate restoration across a crash at any transition boundary.

### Persistence and indexing

- Store full, unmodified `vehicle.save()` NBT in dimension-scoped NeoForge `SavedData`; never pass through `ItemVehicle` or delete UUID tags.
- Store schema version, vehicle UUID, entity class ID, position/orientation, encompassing bounds, static collision AABBs, lifecycle state, and transition generation beside the NBT.
- Derive an in-memory chunk-to-UUID spatial index when loading data and update it only when records change. Do not scan every parked vehicle per player tick.
- Mark data dirty before entity removal. Keep the record until wake restoration has completed and the new builder/entity pair is registered.
- Treat missing packs or failed reconstruction as recoverable parked records. Log the cause and retain NBT rather than deleting the vehicle.

### Parking eligibility

A vehicle may enter `PARKING_PENDING` only after a configurable stable delay and only when all conditions remain true:

- no rider exists on the vehicle or any entry in `allParts`;
- linear and angular movement are below conservative thresholds;
- it is neither towing nor being towed and has no unresolved towing connection;
- no engine, starter, gun, effector, radar-like task, transfer operation, damage, teleport, or active interaction requires ticks;
- the vehicle and builder are valid, server-side, fully initialized, and not already transitioning.

Eligibility is extensible through a part-level activity predicate instead of a hard-coded list in the platform manager. Any unknown active subsystem fails closed and leaves the vehicle active.

### Runtime representation

- Server: a dimension `SavedData` record plus chunk spatial index; no MTS entity, parts, builder entity, or block-entity ticker while fully parked.
- Client: a `NEVER`-ticked MTS render proxy reconstructed from a chunk-scoped render snapshot. It may use normal cached model geometry but is never spawned as a vanilla builder.
- Collision: immutable world-space AABBs queried through the existing NeoForge collision mixin and chunk index.
- Wake: proximity, interaction, attack, collision, explosion, explicit API request, or data incompatibility. Proximity wake is the safety net before vanilla interaction distance.
- Networking: add/remove deltas scoped to watched chunks, with generation numbers so stale packets cannot overwrite newer state.

### Performance and correctness gates

- Parked vehicles must disappear from `EntityManager.tickAll`, variable modifiers, vehicle physics, part updates, builder ticks, and active entity tracking.
- Query cost must scale with nearby indexed chunks rather than total parked vehicle count.
- Full NBT and UUID identity must round-trip byte-for-byte for fields not intentionally updated by restoration.
- Repeated park/wake cycles must not duplicate builders, parts, riders, inventories, fluids, towing references, or UUIDs.
- Missing-pack, restart-during-transition, chunk unload/reload, resource reload, dimension unload, and server shutdown paths require explicit tests.
- Dedicated-server and client builds, automated state-machine tests, runtime save/reload tests, and before/after Spark/JFR evidence are required before declaring completion.

### Implementation sequence

1. Add platform-neutral lifecycle state, eligibility/activity contracts, configuration, and wrapper hooks.
2. Implement NeoForge `SavedData`, transactional reconciliation, chunk index, park/wake conversion, and diagnostics.
3. Add chunk-scoped client proxy synchronization and static rendering.
4. Add static collision and all wake triggers.
5. Build automated and runtime validation, profile the result, then publish the branch and artifacts.

## 2026-08-27T23:58:16+08:00 — Platform-neutral parking lifecycle contract

### Changes

- Added explicit `ACTIVE`, `PARKING_PENDING`, `PARKED`, and `WAKING` lifecycle states and structured eligibility blocker reasons.
- Added an opt-in parking configuration group. The feature remains disabled by default until the NeoForge persistence, synchronization, collision, and recovery layers are present.
- Added conservative server-side eligibility tracking to vehicles, including a continuous stability window and fail-closed checks for riders, motion, towing, engines/starters, lights/electrical use, navigation and targeting activity, damage, guns, and effectors.
- Added a part-level `preventsVehicleParking()` contract so live subsystems remain responsible for declaring asynchronous work instead of making the platform layer guess their state.
- Added default-disabled world wrapper hooks. Older Forge targets therefore retain their existing entity lifecycle without behavior changes.

### Reasoning

The core owns semantic eligibility because it understands vehicle and part state. Platform code owns the durable representation change because persistence and builder removal differ by Minecraft version. Keeping those responsibilities separate makes the transition auditable, prevents NeoForge implementation details from leaking into shared physics code, and allows unsupported platforms to fail closed.

### Verification

- `git diff --check` passed.
- `mcinterfaceneoforge1211 compileJava` passed with Gradle 8.8 on Java 24 targeting the project's Java 8 compatibility level. Only the four pre-existing NeoForge deprecation/removal warnings were emitted.
