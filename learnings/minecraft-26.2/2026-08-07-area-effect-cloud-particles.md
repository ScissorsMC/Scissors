# Bound area-effect-cloud client work

- Recorded: 2026-08-07
- Applies to: Minecraft 26.2
- Paper: `a40947c0acc780cebf578c6ab0f38b6948c3f86e`
- Revalidated after updating Paper to: `f5706462f8833a858fee5fe1bccc05b60c248922`
- Revalidate after changing any version above

## Crash mechanism

The reported creative saved-toolbar entry was an elder-guardian spawn egg whose `minecraft:entity_data` changed the
spawned type to `minecraft:area_effect_cloud`. It requested radius `80`, duration `72000`, and an
`elder_guardian` custom particle. The server clamps the radius to 32, but the client still computes
`ceil(pi * radius * radius)` particle attempts per cloud tick: 3,217 attempts per tick, or 64,340 per second.

The client calls the particle provider for every attempt before applying its particle queue limits. The cloud uses the
always-visible overload, so `ParticleType#getOverrideLimiter()` is not a safety boundary. Besides `elder_guardian`,
non-override particles such as item, trail, shriek, and flash can add expensive construction, embedded data, long
lifetimes, or large visuals to every attempt.

## Why the first correction was incomplete

The first iteration removed particles whose type had `getOverrideLimiter() == true`. That stopped the reported elder
guardian visuals, so the reproduction appeared fixed, but it confused a correlated registry flag with the actual
client work boundary. The client constructs an always-visible cloud particle before its queue limits are considered,
and the sanitized egg still produced a radius-32 cloud for 72,000 ticks. Valid non-override particles and even the
fallback potion particle could therefore retain substantial work.

Per-cloud bounds alone were also insufficient. A creative spawn egg has no cloud-specific cooldown, so repeatedly
using the cross-type egg could accumulate enough individually bounded clouds to recreate the workload. Restoring the
egg's registered entity type closes that amplification path while runtime limits protect commands, storage, and APIs.

General lesson: validate the object that remains after sanitation, trace the exact sink ordering, and budget the
product of per-operation cost, operations per tick, lifetime, and attacker-controlled multiplicity. A payload-specific
visual change is evidence, not proof, that the resource-exhaustion class is closed.

## Verified correction

- `AreaEffectCloud` allows only canonical `entity_effect` and `dragon_breath` particles. It caps radius at 7 (the
  largest vanilla dragon-fireball cloud), active duration at 600 ticks, and wait time at 20 ticks. Loaded age is bounded,
  expiration arithmetic uses `long`, duration-on-use cannot extend past the cap, and direct data-watcher writes are
  rechecked. This covers commands, entity/world loading, Bukkit/NMS setters, growth, and direct metadata mutation.
- `ItemStack.sanitizeUnsafeData` restores an area-effect-cloud-overridden spawn egg to the egg's registered entity type.
  This prevents a no-cooldown egg from creating a swarm of individually bounded clouds; the supplied elder-guardian egg
  now spawns an elder guardian. Other area-effect-cloud entity data has unsafe/malformed particles removed and its
  radius, duration, age, and wait time normalized.
- Nested bundle, container, and charged-projectile items are recursively sanitized. Storage decode, creative ingress,
  creative-event replacement, and the final outgoing item encoder all apply the sanitizer. Changes are logged with the
  applicable safety policy, and creative inventory slots are explicitly corrected.

## Regression checks

1. `AreaEffectCloudParticleTest` covers the explicit particle allowlist and canonical dragon-breath power, radius,
   duration, age, and wait bounds, malformed fields, and the exact reported egg shape.
2. It covers bundle, container, charged-projectile, and multi-level nested sanitation and verifies valid canonical
   dragon-breath data remains unchanged.
3. Manual: load the supplied toolbar egg. The server must warn that it restored the mismatched egg, immediately correct
   the creative slot, and using it must spawn an elder guardian rather than an area-effect cloud.
