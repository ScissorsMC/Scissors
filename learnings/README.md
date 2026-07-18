# Version-scoped learnings

This directory preserves implementation lessons from completed Scissors work. It supplements the source, tracked
patches, and `AGENTS.md`; it does not override them.

## Freshness contract

- Learnings live under `minecraft-<version>/` and apply only to that exact `mcVersion`.
- Read the matching version directory before changing the same subsystem or revisiting the same exploit.
- For another Minecraft version, treat a learning as a lead, not a fact. Revalidate every class, call path, dependency
  behavior, limit, and Gradle task against the current applied sources.
- Create a new dated note when behavior changes. Do not silently rewrite an older note to imply it was always true.
- Prefer notes that record a concrete failure, its cause, the verified correction, and a repeatable regression check.

File names use `YYYY-MM-DD-topic.md` so future agents can distinguish newer observations from older ones within a
Minecraft version.
