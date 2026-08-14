# Item component serialization complexity

- Recorded: 2026-08-14
- Applies to: Minecraft 26.2
- Paper: `c9e894d3cc03f21f80de4f4061a795e11941e89a`
- Revalidate after changing any version above

## Crash mechanism

The reported item used `minecraft:lock` to carry an exact item-component predicate. That predicate contained a
`minecraft:custom_model_data.colors` integer array with 28,051 entries. A chest snapshot serializes and deserializes
each stored item through `DataComponentPatch.CODEC`; the unbounded list therefore made DFU's component codecs consume
the server thread repeatedly in `ContainerHelper.saveAllItems` and `loadAllItems`.

Limiting only `custom_model_data.colors` is incomplete. The same work can move to its `floats`, `flags`, or `strings`
lists, another collection-bearing component, arbitrary `custom_data`, or a future component. `lock` is particularly
useful as a carrier because `LockCode -> ItemPredicate -> DataComponentExactPredicate` can nest any persistent item
component.

## Verified correction

`DataComponentPatch` is the common boundary for the complete component patch, so it applies one structural budget to
every persistent component rather than maintaining a component allow/deny list:

- at most 1,024 encoded nodes and 131,072 string characters per component;
- at most 4,096 encoded nodes across one item patch;
- at most 32 levels of component value nesting;
- untrusted, length-delimited component payloads are capped at 32 KiB before their stream codec runs (256 KiB for the
  two vanilla book-content components, whose legitimate maximum is string-heavy rather than collection-heavy);
- the component count cannot exceed the registered type count and duplicate network component types are refused
  before their value codec runs.

Persistent decode measures the raw dynamic value before dispatching to the component codec and removes only an
oversized entry. This prevents the reported list from ever reaching `ListCodec`. Persistent and network encode measure
already-constructed values with the same `CountingOps` budget, so plugins and stored in-memory objects cannot bypass
the rule. Untrusted creative-item decode skips an over-byte-budget component and then structurally validates every
decoded value, preserving the valid remainder of the item. The decoded patch records that sanitation occurred and
propagates that marker through `ItemStack`; the creative handler uses it to send an explicit slot correction even when
the retained item otherwise matches the sanitized server copy. Without that marker, the client's original local
saved-toolbar stack could keep the stripped component. Warnings are rate-limited and identify the removed component,
boundary, reason, and limits.

The 1,024-node per-component limit is above vanilla's largest item collection carriers in this version: 64 simple
bundle entries and 27 container slots remain below it. Rich plugin/command-created component graphs beyond the limit
are deliberately restricted. Vanilla books retain their 100-page, 1,024-character-per-page envelope through the
separate string and untrusted-byte allowances.

## Regression checks

`OversizedItemComponentTest` verifies that:

1. the reported oversized `lock -> custom_model_data.colors` shape is removed before persistent component decode;
2. loading a complete stored item keeps a valid sibling component while removing the lock;
3. an unrelated oversized `custom_data` value is subject to the same generic rule;
4. an oversized length-delimited lock is skipped before its network codec runs while a valid sibling survives;
5. a programmatically constructed oversized lock is removed on persistent encode; and
6. an ordinary exact-component lock predicate round-trips unchanged.

`DecimatorTest.stripsUndecodableCreativeComponents` separately verifies that an oversized creative component is
stripped without discarding the stone, all packet bytes are consumed, and the sanitation marker survives for the
creative handler's client correction.
