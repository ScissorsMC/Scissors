# Vehicle collision and minecart spawn limits: Folia and ASP port

## Scope

- Minecraft: `26.2`.
- Source Scissors Paper: `50018799f282010f7eb15d32c29cadbbe7c9d740`.
- Folia: `14b7fee5c866fca9a40ede4a58998fb928140f65`.
- Folia's underlying Paper: `5d4f9bd0e4f6b1ef70d07feb411580cef7d1a746`.
- ASP: `f36d683cb8714491141d5131662fc98b1a0b9841`.
- ASP's underlying Paper: `50018799f282010f7eb15d32c29cadbbe7c9d740`.
- Port of the tested Scissors behavior. Keep feature patch `0007` in both patch sets.

## Behavior

The old push/pickup limit did not bound the earlier movement collision query.
Route both movement collision paths through the vehicle query. Give each vehicle
separate movement and push/pickup budgets per tick. Each phase allows at most
`min(maxEntityCollisions, 16)` candidates, clamped to zero, and 256 scan steps.
Count chunk, section and entity visits, including rejected boxes. Charge candidates
before predicates or events can run another query. Keep block collisions unchanged.

Cancel a new minecart spawn if its chunk already contains at least 100 minecarts.
Maintain one counter through the existing chunk-slice add/remove operations.
Count all minecart types and vertical sections together. Do not scan nearby chunks.
Do not track ages, sort carts, scan each tick, or remove carts and cargo.
Saved piles and existing carts that move or change dimensions remain intact.
Count these carts too, so new spawns remain blocked while the chunk is full.
Throttle each warning category globally to one message per five minutes.

## Folia checks

`EntityLookup.addEntity` checks chunk ownership before the spawn limit.
`ServerEntityLookup.checkThread` uses `TickThread.ensureTickThread` for that chunk.
Keep the counter in `ChunkEntitySlices`; do not add a shared mutable count map.
Existing slice transfers use the same add/remove methods.

Folia's `Level.getGameTime()` reads the current region's cached non-redstone tick
time when region data is available. Keep that clock for the collision budget.
The population test must mock `RegionizedWorldData` because Folia's entity removal
callback removes the entity from the current region data.

## ASP checks

Keep ASP's `ChunkEntitySlices.entities` field public. `NMSSlimeChunk.getEntities`
reads this list to save entities. The port must not replace that upstream change
with Paper's private field. Existing slice methods own additions and removals.

`SlimeLevelInstance` installs `SlimeEntityDataLoader` as its entity data controller.
That loader returns saved entity NBT to the normal Moonrise load path.
`NewChunkHolder.loadInEntityChunk` calls `ChunkEntitySlices.readEntities`, then
`EntityLookup.addEntityChunkEntities`. This is a saved-data load, not a new spawn.
Preserve saved slime-world carts, including piles above 100. Count them when loaded.

## Verification

The following commands passed in both target repositories:

```powershell
.\gradlew.bat :scissors-server:test --tests org.bukkit.support.suite.NormalTestSuite
.\gradlew.bat applyAllPatches --no-parallel --max-workers=1
.\gradlew.bat build
.\gradlew.bat :scissors-server:createPaperclipJar
git diff --check
git diff --cached --check
```

Full patch regeneration also passed. Start with `rebuildFoliaPatches` in Folia or
`rebuildAspaperPatches` in ASP. Then run `rebuildPaperServerPatches`,
`rebuildServerPatches`, and `rebuildMinecraftPatches` as separate Gradle commands.
Use `--no-parallel --max-workers=1` for patch tasks to avoid root Git index races.
Paper-server regeneration reported `Failed to read file: src/main/resources/logo.png`
in both forks but completed successfully. The port did not change that upstream file.

All 25 vehicle collision tests and 11 population tests passed in each fork.
The tests cover repeated queries, the 376-cart fixture, scan exhaustion, spawn
cancellation, mixed cart types, chunk boundaries, movement, saved piles, unloads,
dimension transfers, and cargo preservation. No Minecraft server was started.
The target JARs are `scissors-server/build/libs/scissors-paperclip-26.2.jar`.
