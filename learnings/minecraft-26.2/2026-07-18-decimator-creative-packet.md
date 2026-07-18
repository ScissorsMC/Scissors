# Decimator payloads in creative slot packets kill the connection

- Recorded: 2026-07-18
- Applies to: Minecraft 26.2
- Paper: `75c0b485bf038c175d6f3e6efc67519cd5cd524d`
- Supplements: `2026-07-18-decimator.md`
- Revalidate after changing any version above

## Symptom preserved

After the translatable-expansion bounds landed, sending a Decimator item through the creative inventory produced only
this server log and a disconnect:

```text
<player> lost connection: Internal Exception: io.netty.handler.codec.DecoderException:
    Failed to decode packet 'serverbound/minecraft:set_creative_mode_slot'
```

The payload was being stopped, but by throwing inside the packet stream decode. Any failure there — the Scissors
component-expansion validator, the NBT accounter quota, Paper codec depth tracking — kills the connection and logs no
cause. That is softening, not elimination: a player merely carrying such an item is kicked every time their client
resends it, and the operator cannot tell what happened.

## Verified correction

`ServerboundSetCreativeModeSlotPacket` wraps its item stream codec (`LENIENT_ITEM_STREAM_CODEC`). A decode failure
consumes the remainder of the frame (it is unusable after a partial decode, and leftover bytes would otherwise trip
the packet-size check), logs one warning with the truncated failure reason, and yields `ItemStack.EMPTY`. The handler
then processes an empty stack, so the item is eliminated while the connection survives.

## Related lessons

- In 26.2 this packet is the only serverbound packet carrying full item data; container clicks use hashed stacks. One
  lenient boundary covers all client-supplied item payloads.
- `ByteBufCodecs.trackDepth` resets its depth state in a `finally`, so catching outside it is safe.
- A hand-built nested `CompoundTag` payload can exceed the NBT accounter quota (~2 MiB accounted) before it exceeds
  the 4096-visit expansion limit; both failure modes must be eliminated identically, and the regression test may
  trigger either.
- Test encoding must bypass the server codecs (`ComponentSerialization.CODEC` validates on encode too); write the
  wire format by hand: varint count, `Item.STREAM_CODEC`, component add/remove counts, `DataComponentType
  .STREAM_CODEC`, then a varint-length-prefixed NBT payload.

## Regression checks

1. `DecimatorTest.discardsUndecodableCreativeItems`: malicious payload decodes to an empty stack with the buffer fully
   consumed and no exception.
2. `DecimatorTest.decodesValidCreativeItems`: the hand-written wire format decodes a benign item intact, proving the
   malicious case fails on content, not framing.
3. Manual: sending a Decimator item from the creative inventory logs
   `Discarding creative inventory item that failed to decode: <reason>` and the player stays connected.
