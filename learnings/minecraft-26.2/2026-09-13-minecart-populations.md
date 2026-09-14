# Limit minecart populations before client tracking

Superseded later on 2026-09-13 by [spawn cancellation](2026-09-13-minecart-spawn-cancellation.md).
The operator withdrew the removal policy. The behavior below is historical, not current.

- Recorded: 2026-09-13
- Applies to: Minecraft `26.2`
- Paper: `50018799f282010f7eb15d32c29cadbbe7c9d740`
- Revalidate these paths after an upstream update.

## Reason and policy

The operator reported 20 server TPS with 3,100 carts after the collision correction.
Nearby clients were still unusable. Collision budgets do not limit client entity counts.

Keep at most 100 minecarts in each chunk. Count all minecart types and vertical sections
together. Keep the oldest carts and discard the newest excess carts. Do not count neighboring
chunks. This is an operator-requested restriction, not a vanilla limit.

For a new cart, record its first admission game tick. Ignore supplied `totalEntityAge`.
For a saved cart, subtract the nonnegative saved `Spigot.ticksLived` from the current game tick.
This estimates age from saved active ticks; it is not an exact creation timestamp. If ages are
equal or absent, keep the carts admitted or loaded first. Use an immutable admission sequence,
not entity IDs. Keep this order during movement and vertical section changes.

## Boundaries and cost

- In `EntityLookup.addEntity`, check the limit before publishing the ID, UUID, or tracking
  callback. New spawns from items, dispensers, commands, and plugins use this path.
- In `addEntityChunk`, select the oldest 100 before admitting any cart from the load batch.
  Cover entity-chunk storage, legacy storage, and world generation. Include existing transient
  residents. Exclude duplicate IDs and UUIDs from population selection. Otherwise, duplicates
  that cannot be admitted could evict valid residents.
- Flatten passenger trees before removing excess parents. Keep and admit their safe passengers.
- In `Entity.setPosRaw`, check the destination chunk before changing the position. This covers
  rail movement and same-world teleports. If Paper prevents removal during a status update,
  refuse the move and preserve the old position and index.
- In `ChunkEntitySlices`, update a lazy age-ordered set during existing add/remove operations.
  Release entries on removal, section moves, and unload. Preserve entries during transient
  slice replacement. There is no per-tick scan and no world-wide population scan.
- Read the count in O(1). Update the index in O(log 100). Select a saved batch of N carts in
  O(N log 100), with at most 100 retained candidates per chunk. Entity decoding still precedes
  selection; this change bounds admitted carts and client tracking, not all NBT decoding work.
- Refuse reentrant admissions while removing a resident, so a removal callback cannot fill
  the reserved slot.

`onTrackingStart` reaches `ChunkMap.addEntity`, which can send spawn packets immediately.
Do not replace batch selection with admitting every cart and removing the newest afterward.
A reverse-age saved pile could then produce thousands of spawn/remove packet pairs.

Use `Entity.setRemoved(DISCARDED, cause)` for excess carts. Do not call the container cart's
`remove` override: it drops cargo for destructive removal reasons. Keep the normal index,
passenger, tracking, and scheduler cleanup. Discard cargo without drops, and do not explode TNT
carts. New placement and dispenser callers retain the original item when admission returns false.
Log removal details with a global five-minute warning interval.

This is a per-chunk minecart limit, not a total visible entity limit. Several nearby full chunks
can still contribute more than 100 carts. Other entity types retain their existing behavior.
The collision budgets remain in place for movement and push/pickup work.

## Regression checks

`MinecartPopulationLimitTest` uses real entities and the real chunk index with a mocked level.
It does not start a Minecraft server. Cover 3,100 fresh spawns, 3,100 saved carts in three load
orders, age ties, all cart types, neighboring and negative chunks, movements, protected removal,
reentrant callbacks, transient residents, slice replacement, unload, world generation, duplicate
UUIDs, container drops, and retained passengers. Assert the count at each tracking callback.

Run `.\gradlew.bat :scissors-server:test --tests org.bukkit.support.suite.NormalTestSuite`.
The server test task selects suite classes only. Do not filter directly to the test class.
These tests verify population and tracking counts, not client FPS.

## Verification on this revision

Passed these commands:

```powershell
.\gradlew.bat :scissors-server:test --tests org.bukkit.support.suite.NormalTestSuite
.\gradlew.bat rebuildPaperPatches --no-parallel --max-workers=1
.\gradlew.bat rebuildPaperServerPatches --no-parallel --max-workers=1
.\gradlew.bat rebuildServerPatches --no-parallel --max-workers=1
.\gradlew.bat rebuildMinecraftPatches --no-parallel --max-workers=1
.\gradlew.bat applyAllPatches --no-parallel --max-workers=1
.\gradlew.bat build
.\gradlew.bat :scissors-server:createPaperclipJar
```

The final build reports 19 population tests and 25 collision tests with no failures or skips.
All server test reports total 9,350 tests, 22 skipped, and no failures or errors.
The initial parallel patch regeneration hit a temporary root Git index lock. The lock cleared
without intervention. The serial retry passed. Paper-server regeneration also printed the
existing `Failed to read file: src/main/resources/logo.png` message, but completed successfully.

`git diff --check` passed for the applied source and unstaged root changes. The raw staged
patch check flags blank context prefixes as trailing whitespace. The check with
`core.whitespace=-blank-at-eol` passed. A separate check found no trailing whitespace in added
source lines. Do not remove required patch context prefixes.

Use `scissors-server/build/libs/scissors-paperclip-26.2.jar` for operator-run client tests.
No Minecraft server was started during verification. Test on a world copy because excess carts
and their cargo are permanently discarded.
