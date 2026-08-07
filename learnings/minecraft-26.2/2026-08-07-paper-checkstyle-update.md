# Paper API Checkstyle project update

## Version

- Minecraft: `26.2`
- Paper: `f5706462f8833a858fee5fe1bccc05b60c248922`
- Upstream change: Paper `1cd6d5799c9b6548be562ca6963d8d1367e9bf7f`

## What changed upstream

Paper added a `paper-checkstyle` Gradle project, root `.checkstyle` configuration, API-specific `.checkstyle` files,
and Checkstyle wiring in `paper-api/build.gradle.kts`. Updating only `paperRef` first failed while configuring the
generated Scissors API build because `:paper-checkstyle` did not exist. Including the project alone allowed patches to
apply, but `build` then failed because the root base config and Scissors API override config paths did not exist.

## Scissors integration

- Include the upstream-owned project as `paper-checkstyle`; do not rename it to Scissors.
- Materialize both `paper-checkstyle/` and root `.checkstyle/` through paperweight `patchDir` entries with empty
  Scissors patch sets. Ignore the generated output directories.
- Keep Paper's API Checkstyle plugin, dependency, custom Javadoc tags, and tests when rebasing the generated API build
  patch.
- The applied Paper API sources live under `paper-api/`, but the Gradle project using them is `scissors-api/`.
  Therefore point `directoriesToSkipFile` and `MergeCheckstyleConfigs.overrideConfigFile` at
  `paper-api/.checkstyle/...`; the plugin default of `scissors-api/.checkstyle/...` is wrong for this fork layout.
- Do not copy Checkstyle XML into Scissors-owned source directories. Materializing and referencing Paper's pinned files
  keeps the configuration synchronized with `paperRef`.

## Verification

`applyAllPatches` must show the Paper Checkstyle project and config patch sets applying. A full `build` must execute and
pass `paper-checkstyle` tests plus `scissors-api:checkstyleMain` and `scissors-api:checkstyleTest`.
