# Instant-death potion amplifier overflow

- Recorded: 2026-08-29
- Applies to: Minecraft 26.2
- Paper: `37dc5450d9b7acb2e367e8f1fbe0f07bb01334d9`
- Revalidate after changing any version above

## Crash mechanism

The reported splash potion carries custom Instant Health amplifier `125`. `HealOrHarmMobEffect` computed healing with
`4 << amplification`; Java masks an `int` shift distance to five bits, so `4 << 125` becomes `-2147483648`. The splash
path passed the negative result to `LivingEntity.heal`, allowing a healing potion to kill players, including creative
players, without ordinary damage handling.

The supplied item used legacy `CustomPotionEffects` with numeric effect ID `6` plus ViaVersion backup tags. After data
conversion, the relevant 26.2 representation is the same custom effect in `minecraft:potion_contents`. The
ViaVersion bookkeeping is not part of the arithmetic trigger.

## Verified correction

- Custom Instant Health and Instant Damage potion effects are capped at amplifier `1`, the strongest level created by
  vanilla potions in 26.2. The effect remains and its duration/display flags and all safe sibling effects are kept.
- `ItemStack.sanitizeUnsafeData` applies that rule at storage decode, creative ingress, plugin event replacement,
  nested-item recursion, and final client-bound encoding. Creative comparison sees the changed potion component, logs
  the player, and explicitly overwrites the client's saved-toolbar copy.
- `PotionContents` sanitizes before exposing or applying effects, and stored/programmatically assigned area-effect
  clouds and tipped arrows sanitize their contents. This covers direct construction that has not yet crossed item
  serialization.
- `HealOrHarmMobEffect` uses saturating, non-negative arithmetic for both tick and splash application. This final sink
  protects raw NMS callers that bypass potion carriers while preserving the exact vanilla Instant Health I/II,
  Instant Damage I/II, and splash-scale amounts.
- Sanitation warnings identify the effect and old/new amplifiers and are rate-limited to prevent log spam.

The amplifier cap deliberately restricts command/plugin-created custom potions above level II. Other mob-effect types
retain their configured amplifiers.

## Regression checks

1. `InstantHealthOverflowTest` proves `4 << 125` reproduces the negative overflow while the replacement calculation
   saturates to a non-negative amount.
2. The same test verifies vanilla amounts and splash scaling are unchanged, the supplied custom potion is copied with
   amplifier `1`, its original is untouched, Instant Damage follows the same rule, and an unrelated high-amplifier
   effect survives.
3. Rock page `14-instant-death-potions.md` and saved-toolbar group 2 slot 7 carry the version-scoped amplifier-125
   fixture for creative-ingress and explicit-slot-correction testing.
