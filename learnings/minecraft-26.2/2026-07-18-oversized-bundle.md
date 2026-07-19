# Oversized/invalid bundle contents crash clients

- Recorded: 2026-07-18
- Applies to: Minecraft 26.2
- Paper: `75c0b485bf038c175d6f3e6efc67519cd5cd524d`
- Revalidate after changing any version above

## Crash mechanism

A bundle whose `minecraft:bundle_contents` holds an `ItemStackTemplate` that fails strict validation (for example a
netherite hoe with `max_stack_size=69`, which is damageable and stackable) crashes every vanilla client that receives
it. The client logs `Can't create item stack with properties ...` for each render frame because
`ItemStackTemplate.create()` validates and returns `ItemStack.EMPTY`, then crashes with
`IllegalStateException: Stack must be non-empty` in `BundleContents$Mutable.toImmutable` via
`BundleMouseActions.onStopHovering` when the hover ends.

## Mistake preserved

The first fix strengthened `ItemStack.validateComponents` to recurse into contained items, which blocked `/give`
(`ItemInput`) and `ItemStackTemplate.create()`. That is creation-side only. It did nothing for:

- Items entering through `ServerboundSetCreativeModeSlotPacket` (creative saved toolbars), because that path never
  calls `validateStrict`; the handler only compared top-level count against max stack size.
- Items already in player or world storage, which the lenient NBT codecs load unquestioned and the server then sends
  to every viewing client, crashing them.

Per the project objective, the payload must be eliminated, not survived: strip it server-side so no client ever
receives it.

## Second mistake preserved: refusing the packet is not eliminating it

The next fix made `handleSetCreativeModeSlot` refuse the invalid item by folding `validateStrict` into `validData`.
The server then never stored it — but the item still crashed the client. A creative saved toolbar is stored
client-side: loading it applies the item to the client's own inventory view *and* sends the creative slot packet. When
the server refused the item it stored nothing and sent nothing, so the client kept its local crashing copy until a
relog forced a resync. That is why "discards on login" worked while "load from hotbar" did not.

The trap: even placing a corrected item does not fix it. `broadcastChanges()` only resends slots whose server-tracked
remote state differs from the actual slot, and `setRemoteSlot()` marks the slot as already synced. The server believes
the client is up to date and sends nothing. The client's copy must be corrected with an *explicit* packet.

## Third mistake preserved: fixing one component, not the class

The bundle-only sanitizer (`sanitizeBundleContents`) covered `minecraft:bundle_contents` and nothing else. But
`validateComponents` recurses into three nested-item components — `minecraft:container` (shulker boxes and other
block-entity items), `minecraft:bundle_contents`, and `minecraft:charged_projectiles` (crossbows). A shulker box or a
crossbow carrying the same invalid nested item was validated-and-rejected on creation but would still be *encoded to
clients unsanitized*, because the encode chokepoint only stripped bundles. A partial fix like that gets probed and
re-exploited through the next component; the fix must cover the whole class.

The generalization is cheap because `validateComponents` is already recursive: an entry that passes it is a fully
valid subtree, so a single non-recursive filter over exactly the three components it checks is sufficient — no manual
tree walking, and it stays aligned with the authoritative validator if Mojang changes what counts as a contained item.

## Verified correction

The universal chokepoint is the outgoing item encode: every client-bound stack funnels through
`createOptionalStreamCodec`'s encode (`STREAM_CODEC` and `OPTIONAL_LIST_STREAM_CODEC` both delegate to
`OPTIONAL_STREAM_CODEC`, and both trusted/untrusted variants come from that factory; there is no `writeItem` bypass).
Sanitizing there means no client can receive a crashing nested item regardless of ingress.

1. `ItemStack.sanitizeNestedItems` strips invalid entries from all three nested-item components (bundle, charged
   projectiles, container), keeping valid remainder, and returns the same instance when nothing changed. It runs at:
   - the network encode in `createOptionalStreamCodec` — the authoritative guarantee no client receives a bad stack;
   - `ItemStack.MAP_CODEC` decode — cleans anything loaded from player/world data, loot, or commands, logging one warn
     and persisting the clean stack on next save;
   - `ServerGamePacketListenerImpl.handleSetCreativeModeSlot` — for client-supplied creative items.
2. `handleSetCreativeModeSlot` additionally drops the item to `EMPTY` if it still fails `validateStrict` after
   sanitizing (covers a top-level-invalid item that is not a nested-item case), and — whenever it altered what the
   client sent — sends an explicit `ClientboundContainerSetSlotPacket` with the server's real slot content so the
   client's local crashing copy is overwritten immediately, no relog. The event and slot-change comparison use the
   sanitized item, so plugins never see the crashing original.

When referencing `LOGGER` from the `MAP_CODEC` field initializer, qualify it as `ItemStack.LOGGER`; the field is
declared later in the class and a simple name there is an illegal forward reference.

## Related lessons

- A client saved toolbar is stored client-side, so the server first sees the item in the creative slot packet.
  Refusing it there is not enough: the client already applied it locally. Always push an explicit slot correction so
  the crashing copy is replaced without a relog. Do not assume `broadcastChanges()` will resend it — `setRemoteSlot`
  suppresses the update.
- `io.papermc.paper.util.sanitizer.ItemComponentSanitizer` fails static initialization in the unit-test JVM, so the
  outgoing item encode path cannot be exercised in tests; test the sanitizer helper directly instead.
- A plugin that makes a creative item invalid after `InventoryCreativeEvent` (via `event.setCursor`) is still safe
  for other players and on save: the encode chokepoint sanitizes every client-bound stack and the load path cleans
  storage. The placing player's own client is the only one that could see it, and only until the slot next resyncs;
  this is a plugin acting against its own server, not a remote exploit vector.
- Unverified vector: a bundle whose entries are individually valid but whose total `weight()` errors is not filtered
  by the sanitizer. No client crash was observed for it; revalidate if one appears.

## Regression checks

1. `ItemStackValidationTest` covers strict rejection; `sanitizeNestedItems` keep/remove behavior for all three
   nested-item components (bundle, charged projectiles, container); and load-path (`MAP_CODEC`) elimination. Each
   sanitize case also asserts the result passes `validateStrict`.
2. Manual: place a bundle containing an invalid template in a creative saved toolbar, load it from the hotbar, and
   hover it. The invalid entry must disappear immediately on load (valid remainder kept, or the slot cleared) with no
   relog and no client crash. Verified working 2026-07-18.
3. Manual: rejoin with such a bundle already in stored inventory. It must be sanitized on login before any client
   render.
4. Manual: repeat check 2 with the invalid item inside a shulker box (`container`) and inside a charged crossbow
   (`charged_projectiles`), confirming the same elimination — these share the crash class and the same code path.
