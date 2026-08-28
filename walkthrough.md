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

## 2026-08-28T00:05:28+08:00 — Durable server parking and wake lifecycle

### Changes

- Added dimension-scoped `SavedData` containing the untouched MTS vehicle NBT, original UUID, entity factory ID, lifecycle state, transition generation, position, encompassing bounds, and cached entity-collision AABBs.
- Added an in-memory chunk-to-UUID index derived from persisted bounds. Player proximity checks now visit only records intersecting nearby chunks and run at a configurable interval.
- Implemented the active-to-parked transaction: capture and synchronously persist `PARKING_PENDING`, remove the MTS vehicle/parts and vanilla builder, then synchronously persist `PARKED`.
- Implemented the parked-to-active transaction: synchronously persist `WAKING`, reconstruct through the registered MTS entity factory using a copy of the original NBT, verify the original UUID, register the entity and parts, then remove the record.
- Added delayed and periodic crash reconciliation. Pending/waking records with a live entity are removed; records without a live entity become parked. Proximity wake is held until the initial reconciliation window completes so a vanilla builder still loading from a crash cannot be duplicated.
- Added fail-safe handling for missing packs, reconstruction failures, write failures, unsupported schemas, malformed records, and duplicate UUID records. Unreadable data is quarantined and written back unchanged rather than discarded.
- Used NeoForge's temporary-file replacement writer for immediate transition durability instead of waiting for Minecraft's periodic save.

### Reasoning

The durable record is authoritative throughout both transitions. Every crash boundary therefore has either a restorable record, a normal live builder, or both; reconciliation deterministically selects the live entity when present and otherwise retains the record. Full-table scans are confined to rare reconciliation passes, while the normal wake path scales with occupied nearby chunks.

### Verification

- `git diff --check` passed.
- The complete `mcinterfaceneoforge1211 build` passed with Gradle 8.8. Compilation, resources, assembly, checks, and both module builds completed successfully.

## 2026-08-28T01:20:48+08:00 — Static client proxies, collision, and complete wake triggers

### Changes

- Added chunk-watch-scoped parked snapshot synchronization. Each player receives a proxy only while watching at least one indexed chunk occupied by that vehicle, with overlap reference counts preventing duplicate add/remove traffic.
- Compressed full render snapshots and split them into independently validated 24 KiB packets. Clients assemble generation-matched chunks before decoding, so large multipart vehicles do not rely on one oversized NBT payload.
- Added a client-only parked proxy manager. It reconstructs the normal vehicle and parts without a vanilla builder, registers them with `NEVER` update time, performs one explicit geometry/light/bounds initialization pass, and retains no snapshot NBT after construction.
- Added generation ordering and deferred construction so stale packets cannot replace newer state and a parked proxy cannot duplicate an active builder that is still being removed.
- Added view-distance and frustum rejection for parked vehicles and their parts. Static proxies also skip per-frame sound, dynamic-light, and particle evaluation while continuing to reuse the existing model/GPU buffer caches.
- Connected cached parked AABBs to the existing NeoForge entity collision mixin. Server collision queries use the chunk index, return collision for the current movement, and defer the wake transition until the next world tick to avoid entity creation inside the vanilla collision call stack.
- Added exact click/attack/control wake behavior for both vehicle and part UUIDs. A server-bound entity packet can synchronously wake its parked owner and then replay the original packet against the restored object in the same handler.
- Added explosion-start wake and conservative activity blockers for recent displacement/teleport-like movement, active collision, open interactions, radios, crafting, and linked fluid transfers.

### Reasoning

The client proxy deliberately uses the original MTS render graph so pack models, text, instruments, and multipart transforms remain compatible, but it is excluded from every update queue and dynamic effect path. Server collision and interaction remain authoritative: cached geometry handles the transition tick, while any operation that needs mutable vehicle state first restores the full entity graph.

### Verification

- `git diff --check` passed.
- The full `mcinterfaceneoforge1211 build` passed after the networking, rendering, collision, and wake changes. Both `mccore` and NeoForge modules compiled and assembled successfully; only existing deprecation/removal warnings remain.

## 2026-08-28T01:29:24+08:00 — Diagnostics and persistence regression tests

### Changes

- Added the permission-level-2 `/mtsparking` command family:
  - `status` reports lifecycle counts, quarantined records, indexed chunks, aliases, and synchronized players;
  - `verify` audits UUID aliases, live-entity exclusivity, chunk-index coverage, and dangling references;
  - `wake <uuid>` restores the parked vehicle owning a vehicle or part UUID;
  - `wakeall` provides an operator recovery path before maintenance or rollback.
- Added NeoForge ModDevGradle JUnit support against the actual `mts` mod and Java 21 toolchain.
- Added persistence regression coverage for complete record round trips, unsupported schema rejection, and lossless quarantine/write-back of unreadable records.
- Exposed quarantined-record counts without making malformed data active or deleting it.

### Reasoning

The feature changes world authority, so a successful compile is insufficient evidence. Operators need a low-cost way to inspect and recover live worlds, while automated tests must prove that unknown data remains recoverable instead of being normalized away or silently dropped.

### Verification

- All three `ParkedVehicleSavedDataTest` tests passed under the NeoForge JUnit launch environment on Temurin Java 21.0.12.
- The full `mcinterfaceneoforge1211 build` passed with 13 tasks, including compilation, assembly, the JUnit suite, checks, and both module builds.
- `git diff --check` passed.

## 2026-08-28T01:33:26+08:00 — Atomic file persistence test

### Changes

- Split the durable save implementation into a production `ServerLevel` adapter and a package-scoped file/provider primitive, with both paths using the same NeoForge atomic compressed-NBT writer.
- Added a fourth regression test that writes a real `mts_parked_vehicles.dat` into an isolated temporary directory, reads it back through `NbtIo`, verifies the parked UUID is present, removes it, and persists the cleanup.

### Verification

- All four NeoForge JUnit tests passed with zero failures in 0.12 seconds of test execution time.

## 2026-08-28T01:49:49+08:00 — Runtime parity, rollback, and bounded snapshot memory

### Changes

- Added a uniquely named `mccore` ModDev source set and combined it with the NeoForge interface source set as one local `mts` mod. Release jars remain unchanged: they still embed the relocated core artifact.
- Added bounded automatic restoration when parked proxies are disabled. After startup reconciliation, persisted parked records wake in configurable batches instead of remaining hidden during rollback or creating a one-tick entity spike.
- Capped each synchronized render snapshot at 512 × 24 KiB compressed and 64 MiB after NBT decompression. Both locally constructed and decoded packets validate chunk counts, indexes, and lengths before client allocation.
- Added protocol regression tests for negative/excessive chunk counts, oversized chunks, and valid removal packets.
- Added `PARKED_VEHICLE_PROXY.md` with configuration, commands, recovery, controlled before/after evidence collection, and downgrade procedure.

### Reasoning

Development runs must load the same logical class graph as assembled jars; otherwise a successful unit test can coexist with a dedicated-server classloading failure. Rollback also needs to be a first-class lifecycle, and compressed data requires independent compressed and expanded limits so a malformed or pathological snapshot cannot turn a TPS optimization into a client allocation failure.

### Verification

- A NeoForge 21.1.77 dedicated development server on Java 21 discovered `mts`, loaded IV 25.0.0, generated the world, and reached `Done` on isolated port 25585. No MTS mixin, core-class, or parked-proxy exception occurred.
- The expanded eight-test JUnit suite passed under the NeoForge test launch environment.

## 2026-08-28T01:54:30+08:00 — Final clean-build and artifact gate

### Verification

- A from-scratch `clean build` completed all 17 NeoForge/core tasks successfully on Java 21; both JUnit suites reported 8 tests, 0 failures, 0 errors, and 0 skipped tests.
- Inspected the 6,237,510-byte `Immersive Vehicles-1.21.1-25.0.0.jar`: it contains the mod metadata, mixin manifest, embedded `mccore` classes, parking protocol, SavedData, server manager, and client proxy manager.
- Repeated the dedicated-server gate from the final compiled classes. NeoForge discovered `mts`, loaded IV 25.0.0, and reached `Done (9.093s)` on isolated port 25585 without parking-related exceptions.
- `git diff --check` passed; generated runtime world/config/log files remain ignored and are not part of the source commit.

## 2026-08-28T10:52:23+08:00 — Allocation-aware model loading and cache lifecycle

### Changes

- Preserved the incremental three-models-per-tick preloader but made eager parsing opt-in through the client `preloadModels` setting, defaulting to demand loading for large content packs.
- Replaced OBJ face strings, boxed `Integer[]` triplets, and per-face lists with reusable primitive integer builders. Faces are triangulated directly into the object index stream while retaining negative-index semantics, comments, arbitrary whitespace, and legacy positive forward references.
- Added a LegacyCompat fast path that avoids parsing and retaining a model when light objects are already defined and no ground-tread path needs geometry-derived migration.
- Added explicit model-cache lifecycle APIs and wired them to client logout and resource-pack closure. Both parsed resources and the 25.0.0 `missingModelTemplate` are released, and the preload queue is regenerated for replacement resources.
- Added OBJ parsing and cache-lifecycle regression suites covering fan triangulation, primitive-buffer growth, negative indices, deferred positive validation, malformed faces, cached payload accounting, and fallback-template invalidation.

### Reasoning

This stage removes allocation bursts at their source and ensures cached model resources have the same lifetime as the client resource generation. Keeping eager preload opt-in prevents unused pack models from becoming permanent session roots, while preserving it for users who prefer lower first-render latency.

### Verification

- The complete NeoForge test suite passed with 13 tests and no failures after compiling both the Java 8 core and Java 21 interface source sets.
- `git diff --check` passed.

## 2026-08-28T11:07:11+08:00 — Lazy geometry, bounded model residency, and shared GPU buffers

### Changes

- Deferred window back-faces, emissive overlays, light covers, flares, and beam meshes until the corresponding render pass and enabled feature actually needs them.
- Added weak, revision-aware sharing for back-face and overlay vertex snapshots. Source geometry mutations invalidate future derived lookups without retaining obsolete resource generations.
- Released every lazily created renderable during entity teardown; previously only the primary renderable was destroyed, leaving auxiliary cached GPU allocations behind.
- Replaced the NeoForge 1.21.1 per-renderable static VBO map with immutable geometry-and-attribute keys plus reference-counted bindings. Identical model instances now share uploaded vertex buffers, while texture/shader-only state changes avoid needless re-uploads.
- Added a configurable, weighted access-order parsed-model cache (`modelCacheMaxMiB`, 256 MiB by default, 0 for unlimited). The cache retains at most the budget or one individually oversized model, and eager preloading stops at the configured limit instead of churning the LRU.
- Added regression coverage for derived-geometry sharing, revision invalidation, resource-generation clearing, and weighted LRU eviction.

### Reasoning

The original allocation pattern multiplied CPU vertex copies and native GPU buffers by entity count, including optional effects that might never render. Sharing immutable geometry at both the CPU and GPU layers removes that multiplier, while lazy construction and weighted residency bound the remaining session footprint without forcing repeated parsing of one oversized model.

### Verification

- The Java 8 core and Java 21 NeoForge interface compiled successfully with Gradle 8.8 on Temurin 21.0.12.
- The complete NeoForge JUnit suite passed with 16 tests, 0 failures, and 0 errors.
- `git diff --check` passed.

## 2026-08-28T11:15:59+08:00 — Active-vehicle broad phase and allocation-stable scheduling

### Changes

- Added an identity-based X/Z uniform-grid index for active vehicles. Ground-device collision, multipart bounds queries, internal hitbox damage, and interaction ray broad phases now examine nearby grid candidates instead of scanning every active vehicle.
- Added a bounded overflow path for unusually large vehicles and large queries, preserving correctness without allowing malformed bounds to allocate an unbounded number of grid buckets.
- Reused each ground-device box's collision candidate list and the manager's query scratch list, avoiding per-query collections in the hot collision path.
- Replaced linked-queue traversal on every entity pass with immutable tick schedule snapshots rebuilt only after additions or removals. Invalidated entities are skipped safely, and vehicles refresh their spatial membership after their complete update.
- Cached each entity's profiler label instead of concatenating its UUID every tick.
- Added collision-box membership revisions and a shared native-wrapper classification cache. The moving native AABB remains current each tick, while collision/attack/click sets are rebuilt only when their identity membership actually changes; the attack wrapper and its backing sets are reused.
- Added deterministic spatial-index regression tests covering multi-cell de-duplication, movement, removals, and oversized-object fallback.

### Reasoning

The previous ground-device path performed up to four full active-vehicle scans per vehicle per collision update, producing quadratic scaling. Native builder entities also allocated two wrapper objects and two classification sets every tick, and the manager repeatedly walked concurrent linked nodes for stable entity populations. The new broad phase, dirty membership revision, and stable schedules make the common steady state proportional to nearby candidates and actual lifecycle changes.

### Verification

- The Java 8 core and Java 21 NeoForge interface compiled successfully.
- The complete NeoForge JUnit suite passed with 18 tests, 0 failures, and 0 errors.
- `git diff --check` passed.
