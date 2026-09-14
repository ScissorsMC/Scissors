# Bound vehicle collision work before movement

- Recorded: 2026-09-13
- Applies to: Minecraft `26.2`
- Paper: `50018799f282010f7eb15d32c29cadbbe7c9d740`
- Revalidate these paths after an upstream update.

## Failure

The previous patch limited push and pickup queries. It did not limit movement collisions.
The operator reported a severe TPS drop with 376 minecarts, despite overflow warnings.

`Entity.move` calls `Entity.collide`. Paper then calls `CollisionUtil.getEntityHardCollisions`.
Minecarts are hard-colliding sources. Paper collected every intersecting entity for each source.
It then called `AbstractMinecart.canCollideWith`, which can fire `VehicleEntityCollisionEvent`.
The old movement behavior performs this work before its bounded push query.
The experimental behavior can repeat movement, pickup, and push checks within one tick.

For 376 intersecting carts, one movement query per cart could perform `376 * 375 = 141,000`
collision checks. The later warning did not prove that this earlier work was bounded.

The previous limited lookup also counted intersecting results, not all visited entities.
Entities outside the query box could still cause a full section scan.

## Correction

- Use the shared vehicle query in both `CollisionUtil.getEntityHardCollisions` and
  `EntityGetter.getEntityCollisions`. Keep their existing spectator, collision, and shape checks.
- Give each vehicle separate movement and push/pickup budgets. Share each budget across all
  queries in one world game tick. Do not use the vehicle's `tickCount` to reset it.
- Use `max(0, min(maxEntityCollisions, 16))` raw candidates per phase per tick.
  Charge candidates before predicates or collision events can make a reentrant query.
- Use at most 256 scan steps per phase per tick. Count chunk, section, and entity visits.
  Count entities that do not intersect. Stop before reading the next bounding box.
- Collect at most one extra candidate to detect overflow. Do not evaluate its collision predicate.
- Use Moonrise's existing section collections. Do not create a second spatial index.
- Do not call Paper's global dragon-part scan. In this version, `EnderDragonPart` inherits
  `isCollidable(false) == false` and `canBeCollidedWith(...) == false`. Vehicle movement and
  push/pickup cannot use these parts. Keep the normal world query unchanged for other callers.
- Skip excess collision work. Keep the vehicles, their contents, and block collisions.
- Log the vehicle, position, dimension, and limits. Keep the global five-minute warning interval.

For `N` vehicles and candidate limit `L`, these two phases now inspect at most `2 * N * L`
candidates and perform at most `2 * N * 256` scan steps per tick. Each phase uses at most
`L + 1` temporary candidate slots. This bound does not grow with the number of repeated rail steps.
At the default limit of 8, the 376-cart movement fixture performs 3,008 collision checks.

## Compatibility limits

These are deliberate safety limits, not claimed vanilla population limits. Vanilla does not bound
the number of vehicles in one position. Crowded vehicles can skip contacts and passenger pickup.
Repeated contacts at high experimental rail speeds also share the per-tick budget. A dense nearby
section can exhaust the scan budget even when its entities do not intersect the vehicle.
Movement cannot consume the separate push/pickup budget. Non-vehicle source queries remain unchanged.

A zero or negative `maxEntityCollisions` now skips both vehicle collision phases. It does not
disable block collision. Commands, plugins, loaded entities, dispensers, and placed vehicles all
use the same collision code after creation. No spawn-only guard or persistent flag is required.

## Regression checks

`VehicleCollisionLimitTest` uses real vehicles, section collections, and collision methods with
a mocked level. It does not run a Minecraft server. Check the 376-cart fixture, rejected predicates,
reentrant queries, repeated steps, both movement sinks, both minecart behaviors, vehicle variants,
nonintersecting boxes, empty chunks, multiple sections, disabled limits, and next-tick recovery.

Run `.\gradlew.bat :scissors-server:test --tests org.bukkit.support.suite.NormalTestSuite`.
The server test task selects suite classes only;
do not filter it directly to `VehicleCollisionLimitTest`.

These tests measure work counts. They do not measure TPS. Use the final Paperclip JAR for an
operator-run comparison with the reported setup. Other entity-tick costs require a profile.
