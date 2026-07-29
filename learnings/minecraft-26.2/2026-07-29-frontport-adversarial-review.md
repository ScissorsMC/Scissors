# Frontported exploit patches: adversarial review

- Recorded: 2026-07-29
- Applies to: Minecraft 26.2
- Paper: `a95ae8d00f4434acb823ba345d249ea084cec785`
- Revalidate after changing any version above

## Review rule

Forward-porting the apparent 1.20.4 behavior was not sufficient. Each port needed a second pass that tried to bypass
the chosen limit through adjacent fields, predicates, persistent data, programmatic construction, and final network
encoding. Limits must count work performed rather than only accepted results, and sanitation must occur before an
attacker-controlled value can mutate authoritative state or reach a client.

## NBT rendering must distinguish durable serialization from bounded diagnostics

Making the default `StringTagVisitor` and `SnbtPrinterTagVisitor` lossy corrupted legitimate persistent-data,
item-meta, and configuration round trips. Their default constructors are part of serialization paths and must remain
lossless. Bounded rendering is explicit at attacker-facing diagnostic, macro, command-output, component-text, and
decode-error sinks.

The bounded visitors share one 1024-element budget across the complete recursive render, including primitive arrays
and sibling containers. They cap nesting at 64 while emitting parseable SNBT. The render-side depth check must stop one
level before the parser's maximum; allowing the renderer to open the 64th nested container produced output the parser
itself rejected. Truncation logging is globally rate-limited rather than emitted once per visited container.

## Privileged packets have more than a target coordinate

Loaded-chunk and 32-block target validation does not bound all work in editor packets. Test-instance dimensions can
drive large block/chunk iteration and jigsaw generation depth controls recursive structure work. Validate these fields
before calling the block entity:

- every test-instance size axis is `0..StructureBlockEntity.MAX_SIZE_PER_AXIS`;
- jigsaw depth is `JigsawStructure.MIN_DEPTH..JigsawStructure.MAX_DEPTH`;
- client-supplied test status and error text are discarded because they are authoritative server state.

`TestInstanceBlockEntity.set` also clamps dimensions so stored data and programmatic callers cannot bypass the packet
handler.

## Entity-query limits must count scanned candidates

Limiting a world query whose collection predicate already contained the caller's collision predicate only bounded
matching entities. A dense set of nonmatching entities still caused an unbounded scan and predicate evaluation. The
vehicle query now collects at most `limit + 1` raw entities (excluding only the vehicle itself), then evaluates the
caller's predicate over at most `limit` candidates. A configured limit of zero or less preserves Paper's disabled
collision behavior; positive values are capped at 16. Overflow logging is static and time-based so creating many
vehicles cannot multiply warnings.

## Map decoration names require structural validation at egress

The persistent component constructor caps item decorations, but plugins and saved-map state can still produce outgoing
collections independently. The client packet is therefore the authoritative final cap at 256 entries.

Visible text length alone is not a sufficient name limit. Empty siblings, deeply nested translatable arguments, hover
or click events, insertions, and unsupported component-content types can carry work without increasing visible text.
Outgoing names are stripped unless they stay within 32 visible characters and 64 component nodes, use only the allowed
plain/keybind/translatable contents, and contain no interactive style payloads. Keep the decoration and remove only its
unsafe optional name when possible.

## Selector coordinates can overflow without special-number syntax

Brigadier does not need to accept the literal `Infinity` for `readDouble` to return a non-finite value: a sufficiently
long decimal overflows to infinity. Check `Double.isFinite` for selector `x`, `y`, and `z` after parsing, while allowing
very large values that remain finite. Selectors constructed outside the command parser require the same finite-position
check immediately before querying entities. Distance and volume limits remain independently capped at 1024 blocks.

## Regression checks

- `TagVisitorLimitTest`: lossless default round trips, bounded primitive arrays and sibling containers, and parseable
  depth truncation.
- `PrivilegedPacketValidationTest`: exact accepted and rejected test-instance dimensions and jigsaw depths.
- `VehicleCollisionLimitTest`: configuration boundaries and a hard bound on caller-predicate evaluation.
- `MapDecorationsTest`: count limits, 32/33-character name boundary, and structurally excessive empty siblings.
- `EntitySelectorRangeTest`: decimal overflow for all three axes and acceptance of a very large finite decimal.

