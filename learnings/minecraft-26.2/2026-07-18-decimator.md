# Decimator

- Recorded: 2026-07-18
- Applies to: Minecraft 26.2
- Paper: `75c0b485bf038c175d6f3e6efc67519cd5cd524d`
- Adventure: 5.2.0
- Revalidate after changing any version above

## Mistake preserved

The first codec validator rejected validation whenever a component visit was already active. Normal login re-entered
validation through this production path:

```text
server_data encoding
  -> AdventureComponent.getStyle()
  -> deepConverted()
  -> WrapperAwareSerializer
  -> ComponentSerialization
```

It threw `Cannot validate a component during an active visit` and disconnected players.

The correction was to make validation re-entrant:

- Nested validation reuses the active validation context and its remaining budget.
- Validation entered from a normal truncating visit temporarily installs a fail-on-limit context, then restores the
  previous context.
- Re-entry must never reset the expansion budget.

## Related lessons

- A per-`TranslatableContents` counter is insufficient because every nested component receives a fresh allowance. Share
  one budget across the full expansion graph.
- Protect both styled and unstyled Minecraft visitors.
- Minecraft and Adventure flatten through separate implementations; bound both `TranslatableContents` and
  `PaperAdventure.FLATTENER`.
- A literal Minecraft component does not reproduce the login failure. Test an Adventure-backed component created with
  `PaperAdventure.asVanilla(...)`.
- Adventure conversion requires the `@VanillaFeature` test environment.
- In 26.2, a verbose `CustomName` decode warning means the malicious component was rejected and discarded. It is not
  evidence of component expansion.

## Regression checks

1. Players can join without a `server_data` encoder error.
2. A normal Adventure-backed component encodes successfully.
3. A repeated-placeholder Minecraft component is bounded during flattening and rejected by
   `ComponentSerialization.CODEC`.
4. Adventure flattening bounds repeated placeholders and nested mappings.
5. A Decimator `CustomName` produces no expanded output, client disconnect, or server resource spike.
