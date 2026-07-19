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

## Verified correction

1. `ServerGamePacketListenerImpl.handleSetCreativeModeSlot` folds `ItemStack.validateStrict(...).isSuccess()` into
   `validData`, so invalid stacks are refused for both slot placement and the drop path.
2. `ItemStack.MAP_CODEC` sanitizes on decode, so any stack deserialized from player data, world data, loot, or
   commands has invalid bundle entries removed immediately (with one warn naming the item), and the next save persists
   the clean stack. Wire-only filtering left the stored payload alive and the discard only became visible per send.
3. `ItemStack.sanitizeBundleContents` also runs in the network encode in `createOptionalStreamCodec` as defense in
   depth for stacks created at runtime (plugins, API). It returns the original stack untouched when nothing needs
   removal.

When referencing `LOGGER` from the `MAP_CODEC` field initializer, qualify it as `ItemStack.LOGGER`; the field is
declared later in the class and a simple name there is an illegal forward reference.

## Related lessons

- Client saved toolbars are stored client-side; the server first sees the item in the creative slot packet. Refusing
  there leaves the bad item client-local, which is the best achievable outcome for the hotbar file itself.
- `io.papermc.paper.util.sanitizer.ItemComponentSanitizer` fails static initialization in the unit-test JVM, so the
  outgoing item encode path cannot be exercised in tests; test the sanitizer helper directly instead.
- Unverified vector: a bundle whose entries are individually valid but whose total `weight()` errors is not filtered
  by the sanitizer. No client crash was observed for it; revalidate if one appears.

## Regression checks

1. `ItemStackValidationTest` covers strict rejection and `sanitizeBundleContents` keep/remove behavior.
2. Manual: place a bundle containing an invalid template in a creative saved toolbar, load it, and hover it. The
   server must refuse the item and no client may crash, including after rejoin with such a bundle already stored.
