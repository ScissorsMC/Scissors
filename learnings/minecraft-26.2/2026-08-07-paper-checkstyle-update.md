# Paper API Checkstyle project update

## Version

- Minecraft: `26.2`
- Paper: `f5706462f8833a858fee5fe1bccc05b60c248922`
- Upstream change: Paper `1cd6d5799c9b6548be562ca6963d8d1367e9bf7f`
- Folia: `24c5c95dc45e02caff98a97ed6ffee7565523464`
- Revalidate after changing these versions. The sections below preserve the separate Paper and Folia procedures.

## What changed upstream

Paper added a `paper-checkstyle` Gradle project, root `.checkstyle` configuration, API-specific `.checkstyle` files,
and Checkstyle wiring in `paper-api/build.gradle.kts`. Updating only `paperRef` first failed while configuring the
generated Scissors API build because `:paper-checkstyle` did not exist. Including the project alone allowed patches to
apply, but `build` then failed because the root base config and Scissors API override config paths did not exist.

## Scissors integration on Paper

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

## Verification on Paper

`applyAllPatches` must show the Paper Checkstyle project and config patch sets applying. A full `build` must execute and
pass `paper-checkstyle` tests plus `scissors-api:checkstyleMain` and `scissors-api:checkstyleTest`.

## Scissors Folia integration

Folia layers its own `folia-checkstyle/build.gradle.kts` over the Paper-owned source project.
A downstream fork must materialize both layers. The generated API project alone is not sufficient.

- Include the upstream-owned build project as `folia-checkstyle`; do not rename it to Scissors.
- Apply Folia's `folia-checkstyle/build.gradle.kts` as a single-file patch target.
- Materialize Paper's `paper-checkstyle/` sources and root `.checkstyle/` configuration through paperweight `patchRepo`
  entries with empty Scissors patch sets.
- Keep `folia-checkstyle` build output outside the generated upstream worktree so patch regeneration never scans class
  files or test results as source changes.
- Keep Folia's Paper Checkstyle plugin, dependency, custom Javadoc tags, and tests when rebasing the generated API
  build patch.
- The applied Paper API sources live under `paper-api/`, but the Gradle project using them is `scissors-api/`.
  Therefore point `directoriesToSkipFile` and `MergeCheckstyleConfigs.overrideConfigFile` at
  `paper-api/.checkstyle/...`.
- Folia's Checkstyle build script must reference the materialized `paper-checkstyle/` sources and configuration rather
  than expecting them inside the generated `folia-checkstyle/` directory.

## Verification on Folia

Run `applyAllPatches` to materialize the Folia Checkstyle build script plus the Paper Checkstyle source and configuration
patch sets. Run a full `build` and check that `folia-checkstyle` tests, `scissors-api:checkstyleMain`, and
`scissors-api:checkstyleTest` pass.
