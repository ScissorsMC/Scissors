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

## Keep the three repositories in sync

- Keep the same `learnings/` files and contents in `Scissors`, `Scissors-Folia`, and `Scissors-ASP`.
- When you add or update a note, copy the result to both sibling repositories.
- Compare existing copies before you replace them. Merge fork-specific findings; do not discard them.
- Name the fork and exact upstream pin for each finding or verification result. Recorded pins are historical, not
  the current configuration. Sharing a note does not mean its behavior was verified on every fork.
- Keep different build procedures in separate, named sections. Do not substitute one fork's commands for another's.
- Preserve superseded notes and their replacement links. Do not revive an abandoned policy during a port.
- Check that the relative file paths and file contents match across all three repositories before you finish.
- If a sibling repository is unavailable or has conflicting work, report what remains out of sync.
