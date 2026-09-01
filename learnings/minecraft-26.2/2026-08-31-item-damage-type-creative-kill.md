# Item damage types can bypass creative invulnerability

- Recorded: 2026-08-31
- Applies to: Minecraft 26.2
- Paper: `a2a42c5b12249aaba42a347327fd930a1f94af06`
- Revalidate after changing any version above

## Crash/exploit mechanism

The reported old-client splash potion was not another instant-effect amplifier overflow. Its ViaBackwards backup field
`VB|Protocol1_21_11To1_21_9|backup.damage_type_id` was `32`. In the exact 26.2 damage-type registry, raw ID 32 is
`minecraft:out_of_world`, which belongs to `minecraft:bypasses_invulnerability`. ViaBackwards restores that backup as
the current `minecraft:damage_type` item component when the old-client item travels back to the 26.2 server.

`ItemStack.getDamageSource` used an item's `minecraft:damage_type` component without restricting it. The potion's
50-point attack-damage modifier supplied lethal melee damage, while `out_of_world` made that damage pass the creative
player check in `Player.hurtServer`. The visible legacy `AttributeModifiers` data alone cannot bypass creative
invulnerability; the restored damage type is the missing part of the exploit.

## Verified correction

- `ItemStack.sanitizeUnsafeData` removes any item damage type in `minecraft:bypasses_invulnerability`, not only the
  reported `out_of_world` instance. This also covers `generic_kill` and custom/data-pack types added to the tag.
- If an unsafe override replaced a safe vanilla default (for example a spear's `spear` damage type), sanitation restores
  the default instead of deleting legitimate behavior. Other components, including the reported attack modifier and
  unrelated custom data, remain intact.
- The existing storage-decode, creative-ingress, creative-event, nested-item, and final client-bound encode boundaries
  all call `sanitizeUnsafeData`; creative sanitation explicitly corrects the client's slot and identifies the player.
- `ItemStack.getDamageSource` independently refuses a bypass-invulnerability component. This is the final sink defense
  for raw NMS/plugin construction that has not crossed an item sanitation boundary.
- Removal logs identify the item and damage type and are rate-limited.

This deliberately prevents item attacks from using damage types that bypass invulnerability. Safe custom damage types
and the vanilla spear damage type remain supported. Commands and non-item damage sources are unchanged.

## Regression checks

`UnsafeItemDamageTypeTest` verifies the exact raw-ID mapping, the supplied splash-potion shape, both vanilla bypass
types, the final damage-source sink, safe custom and vanilla damage types, restoration of spear defaults, storage
decode, and nested-item sanitation.
