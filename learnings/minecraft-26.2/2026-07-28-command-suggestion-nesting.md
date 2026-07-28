# Command suggestion nesting

- Recorded: 2026-07-28
- Applies to: Minecraft 26.2
- Paper: `75c0b485bf038c175d6f3e6efc67519cd5cd524d`
- Revalidate after changing any version above

## Crash mechanism

Paper's SNBT packrat depth guard stored an `IllegalStateException` after 512 nested lists or compounds. Normal command
parsing converted that runtime failure into Brigadier syntax state, but `ItemParser.fillSuggestions` only catches
`CommandSyntaxException`. A partial item-component command therefore let `IllegalStateException: Too deep` escape the
suggestion task. Repeating `ServerboundCommandSuggestionPacket` produced expensive recursive parses and one full error
log per request; servers without the guard could instead reach `StackOverflowError`.

## Verified correction

- Client command-suggestion text is scanned before Paper's async suggestion queue. More than 64 nested list/compound
  delimiters is discarded, with delimiters inside quoted strings ignored. The server emits a rate-limited warning that
  identifies the sender and the applied limit.
- The authoritative packrat scope uses the same limit for every SNBT parser ingress. Exceeding it now records a delayed
  `CommandSyntaxException`, so suggestion implementations treat it as invalid syntax instead of leaking a runtime
  exception even when they bypass the packet preflight.

## Regression checks

1. `CommandSuggestionNestingTest.deeplyNestedItemComponentDoesNotEscapeSuggestionParser` reproduces the original
   `ItemParser.fillSuggestions` failure and verifies the exception no longer escapes.
2. The remaining tests verify the packet preflight accepts the exact limit, rejects the next level, and does not count
   braces or brackets inside quoted strings.
