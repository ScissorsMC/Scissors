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

## Verified correction

1. `ServerGamePacketListenerImpl.handleSetCreativeModeSlot` sanitizes the incoming creative item with
   `sanitizeBundleContents` (keeping any valid remainder), drops it to `EMPTY` if it still fails `validateStrict`, and
   — whenever the item was altered — sends an explicit `ClientboundContainerSetSlotPacket` with the server's real slot
   content. This overwrites the client's local crashing copy immediately, so a toolbar load no longer needs a relog.
   The event and slot-change comparison use the sanitized item, so plugins never see the crashing original.
2. `ItemStack.MAP_CODEC` sanitizes on decode, so any stack deserialized from player data, world data, loot, or
   commands has invalid bundle entries removed immediately (with one warn naming the item), and the next save persists
   the clean stack. Wire-only filtering left the stored payload alive and the discard only became visible per send.
3. `ItemStack.sanitizeBundleContents` also runs in the network encode in `createOptionalStreamCodec` as defense in
   depth for stacks created at runtime (plugins, API). It returns the original stack untouched when nothing needs
   removal.

When referencing `LOGGER` from the `MAP_CODEC` field initializer, qualify it as `ItemStack.LOGGER`; the field is
declared later in the class and a simple name there is an illegal forward reference.

## Related lessons

- A client saved toolbar is stored client-side, so the server first sees the item in the creative slot packet.
  Refusing it there is not enough: the client already applied it locally. Always push an explicit slot correction so
  the crashing copy is replaced without a relog. Do not assume `broadcastChanges()` will resend it — `setRemoteSlot`
  suppresses the update.
- `io.papermc.paper.util.sanitizer.ItemComponentSanitizer` fails static initialization in the unit-test JVM, so the
  outgoing item encode path cannot be exercised in tests; test the sanitizer helper directly instead.
- Not covered: a creative item made invalid by a plugin after `InventoryCreativeEvent` (via `event.setCursor`) is not
  re-sanitized; only the client-supplied item is. The encode-path and load-path sanitizers still clean it for other
  viewers and on save. Revalidate if a plugin-driven vector appears.
- Unverified vector: a bundle whose entries are individually valid but whose total `weight()` errors is not filtered
  by the sanitizer. No client crash was observed for it; revalidate if one appears.

## Regression checks

1. `ItemStackValidationTest` covers strict rejection, `sanitizeBundleContents` keep/remove behavior, and load-path
   (`MAP_CODEC`) elimination.
2. Manual: place a bundle containing an invalid template in a creative saved toolbar, load it from the hotbar, and
   hover it. The invalid entry must disappear immediately on load (valid remainder kept, or the slot cleared) with no
   relog and no client crash. Verified working 2026-07-18.
3. Manual: rejoin with such a bundle already in stored inventory. It must be sanitized on login before any client
   render.
