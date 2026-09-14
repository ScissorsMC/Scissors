# Cancel minecart spawns in full chunks

- Recorded: 2026-09-13, after the population-removal implementation
- Applies to: Minecraft `26.2`
- Paper: `50018799f282010f7eb15d32c29cadbbe7c9d740`

The operator replaced the removal policy with spawn cancellation. At 100 minecarts in one
chunk, return false for another new spawn. Do not remove any cart or cargo. Do not sort by age.

Keep one integer count in `ChunkEntitySlices`. Update it during existing add/remove operations.
Count all minecart types and vertical sections together. Read and update the count in O(1).
Do not scan chunks every tick or count neighboring chunks.

Check the count in `EntityLookup.addEntity` before publishing IDs or client tracking. Cover
new spawns and world generation. Preserve Paper's return path so placement and dispenser
callers keep their items when the spawn fails. Log cancellations at most once per five minutes.

Leave existing carts alone, including saved piles and carts crossing chunk borders. Pass the
existing spawn-event flag from `ServerLevel` so dimension transfers do not count as new spawns.
These existing carts still contribute to the count. An existing pile can exceed 100; this is
a spawn limit, not a purge or a guarantee about the total number of visible carts.

Keep the collision-work correction unchanged. Test spawn refusal, preserved carts and cargo,
saved piles, movement, dimension-transfer admission, all cart types, chunk independence,
vertical sections, removal, unload, and transient slice replacement.

Run `.\gradlew.bat :scissors-server:test --tests org.bukkit.support.suite.NormalTestSuite`.
Do not run a Minecraft server as agent verification.

## Verification

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

All 11 spawn-limit tests and 25 collision tests passed, with no skipped tests in these classes.
Paper-server regeneration printed the existing `Failed to read file: src/main/resources/logo.png`
message but completed successfully. Source whitespace checks passed. The raw staged patch check
flags blank context prefixes; the check with `core.whitespace=-blank-at-eol` passed. A separate
check found no trailing whitespace in added source lines.

The updated runtime-test artifact is `scissors-server/build/libs/scissors-paperclip-26.2.jar`.
No server was started during verification.
