# Scissors Agent Guide

## Scope

These instructions apply to the entire repository. Scissors is a patch-based fork of Paper built with paperweight. The
files produced by applying patches are development worktrees, not the repository's source of truth.

Read this file before changing code. Human setup and build instructions are in
`README.md`.

## Objective: eliminate exploits, do not soften them

Scissors exists to prevent exploit payloads from crashing clients or the server. A fix is complete only when the
malicious data is eliminated: stripped, discarded, or refused at the server boundary so it can never reach a client or
take effect. Making a failure look nicer is not a fix. Kicking the sender with a vague error, catching an exception
around a crash, or letting clients cope with bad data on their own all leave the payload alive and are incomplete.

Hold every exploit fix to these outcomes:

- Malicious data arriving from a client is discarded or sanitized where it enters, and the connection of a player who
  merely carries a bad item survives.
- Data already in world or player storage is sanitized before the server sends it, so pre-existing bad items cannot
  crash the clients that receive them.
- Strip the invalid part and keep the valid remainder when possible, rather than rejecting the whole interaction.
- The server logs what was eliminated and why, so operators can identify abuse without guessing at vague errors.

## Version-scoped learnings

Before revisiting an exploit or subsystem, read `learnings/README.md` and the directory matching the exact `mcVersion`.
Treat notes from other Minecraft versions as leads only and revalidate them against the current sources.

## Project identity and upstream boundary

Scissors owns:

- The `scissors` root project and the `scissors-api` and `scissors-server`
  modules.
- The `io.github.scissorsmc` Maven group.
- The `scissorsmc:scissors` runtime brand and the `Scissors` product name.
- Scissors-specific patches, source files, tests, and documentation.

Paper remains the upstream project. Keep `Paper`, `paper-api`, `paper-server`,
`io.papermc`, Paper repositories, Paper APIs, and Minecraft names where they describe upstream code or compatibility. Do
not perform blanket Paper-to-Scissors replacements. Preserve upstream copyright, license, and attribution text.

The Paper commit is pinned by `paperRef` in `gradle.properties`. Do not change it unless the task is specifically an
upstream update.

## Updating Paper

Treat a Paper update as a patch rebase, not a dependency bump.

1. Confirm the root repository and every generated nested worktree are free of unrelated changes. Fold or commit all
   intended work before changing the upstream reference because patch application can replace generated worktrees.
2. Set `paperRef` in `gradle.properties` to the full target Paper commit SHA.
3. If the target commit changes Minecraft versions, also copy `mcVersion` and `apiVersion` from the target Paper
   `gradle.properties`. Do not guess either value.
4. Compare the target Paper root build files, Gradle wrapper, Java/toolchain requirements, and paperweight configuration
   with this repository. Port only changes required to build the new target; retain Scissors project names, group,
   branding, repositories, and owned build behavior.
5. Run `./gradlew applyAllPatches`. Resolve failures in patch-set order:
    - Rebase rejected single-file build-script changes in the generated `scissors-*/build.gradle.kts`, then run
      `./gradlew rebuildPaperSingleFilePatches`.
    - Resolve API conflicts in `paper-api/`, Paper-server conflicts in `paper-server/`, and Minecraft conflicts in
      `scissors-server/src/minecraft/`.
    - Preserve the reason for each Scissors patch. Drop a patch when Paper now provides the same behavior; do not retain
      an empty or redundant compatibility patch.
    - Use `applyPaperSingleFilePatchesFuzzy` or `applyPaperApiFilePatchesFuzzy` only to recover changed context that is
      still semantically correct. For a Minecraft-version update,
      `applyOrMovePaperApiFilePatches` can move rejected API file patches aside for manual resolution.
6. Regenerate the tracked patch sets with the full patch-regeneration sequence below. Review every regenerated patch;
   an upstream update must not silently absorb unrelated Paper changes into a Scissors patch.
7. Run `./gradlew applyAllPatches` again from the regenerated tracked state, then run `./gradlew build`,
   `git diff --check`, and `git diff`.

Use `.\gradlew.bat` for every Gradle command on Windows PowerShell. An update commit should contain the new
`paperRef`, any required version or build-tooling changes, and all regenerated Scissors patches needed for that exact
Paper commit.

## Repository model

| Path                             | Purpose                                                                                                | Commit it? |
|----------------------------------|--------------------------------------------------------------------------------------------------------|------------|
| `scissors-api/`                  | Scissors API build patch, patches, and directly owned API source                                       | Yes        |
| `scissors-server/`               | Scissors server build patch, Paper-server patches, Minecraft patches, and directly owned server source | Yes        |
| `paper-api/`                     | Applied API worktree generated by paperweight                                                          | No         |
| `paper-server/`                  | Applied Paper-server worktree generated by paperweight                                                 | No         |
| `scissors-server/src/minecraft/` | Applied Minecraft worktree and nested Git repository                                                   | No         |
| `scissors-*/build.gradle.kts`    | Generated patched build scripts                                                                        | No         |
| `build/`, `.gradle/`, `run/`     | Build/runtime output                                                                                   | No         |

The ignored worktrees are where existing upstream files are edited. The durable result must be regenerated into tracked
files under `scissors-api/` or
`scissors-server/`.

New Scissors-owned classes that do not replace an upstream file may be added directly under the applicable module's
`src/main/java` or `src/test/java`.

## Before editing

1. Run `git status --short` and preserve unrelated user changes.
2. Read `gradle.properties`, `build.gradle.kts`, and the relevant module patch directories.
3. Apply patches:

   ```shell
   ./gradlew applyAllPatches
   ```

   On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.
4. Choose the correct layer before editing:
    - Public API: `paper-api/`
    - Paper/CraftBukkit implementation: `paper-server/`
    - Mojang/Minecraft source: `scissors-server/src/minecraft/`
    - Scissors-owned new source: `scissors-api/src/...` or
      `scissors-server/src/...`
    - Build wiring: the generated module build script, then rebuild its single-file patch

Do not start `runServer`, `runDevServer`, `runPaperclip`, or any other server or watch task. Use terminating builds and
tests only.

## Patch workflow

Applied patch worktrees are nested Git repositories. Root-repository Git commands do not commit edits inside them.

### Small per-file changes

Use a file patch for a focused change to an existing upstream file.

1. Edit the file in the applied worktree.
2. Inspect the nested repository's diff.
3. Fold the tracked change into that patch set's `file` commit with its
   `fixup...FilePatches` Gradle task.
4. Rebuild the applicable patch set.
5. Review the generated tracked `.patch` file from the repository root.

Common task pairs are:

| Layer        | Fix up file patches            | Rebuild patches             |
|--------------|--------------------------------|-----------------------------|
| API          | `fixupPaperApiFilePatches`     | `rebuildPaperPatches`       |
| Paper server | `fixupPaperServerFilePatches`  | `rebuildPaperServerPatches` |
| Minecraft    | `fixupMinecraftSourcePatches`  | `rebuildMinecraftPatches`   |

If a task name changes with paperweight, use
`./gradlew tasks --group patching` and select the task for the named patch set. The server-side fixup tasks live in the
server subproject and are not listed by the root patching group; find them with
`./gradlew :scissors-server:tasks --all`.

### Feature patches

Use a feature patch when a coherent change spans multiple upstream files, benefits from its own commit message, or
should be independently reorderable or droppable during an upstream update.

1. Edit the applied worktree.
2. In that nested worktree, stage only the intended files.
3. Commit with a concise subject explaining the behavior and a body explaining the reason or important maintenance
   constraints.
4. Rebuild the applicable patch set.
5. Confirm the new mail-formatted patch appears under that patch set's
   `features/` directory.

Example:

```shell
git -C paper-server status --short
git -C paper-server add src/main/java/example/ChangedFile.java
git -C paper-server commit
./gradlew rebuildPaperServerPatches
```

Do not use a feature patch for unrelated edits. Do not hand-edit generated patches as the normal workflow; edit the
applied source and regenerate them.

### Amending an existing patch

Extend an existing patch instead of creating a new one when the change belongs to the same logical fix.

File patches need no special amend step: edit the applied file again and rerun that layer's fixup task. Every file
patch in a layer lives in the same `file` commit, so the fixup folds the new change in and the rebuild regenerates the
per-file `.patch` content. This is also the way to extend an existing fix to an additional upstream file in the same
layer.

To amend an existing feature patch, rewrite its commit in the nested worktree:

1. Edit the applied worktree and stage only the intended files.
2. If the feature commit is the tip of the nested worktree, run `git commit --amend --no-edit`, or drop `--no-edit`
   when the patch message must change. Otherwise, autosquash a fixup commit into it non-interactively:

   ```shell
   git -C paper-server commit --fixup <feature-commit-sha>
   git -C paper-server -c sequence.editor=: rebase -i --autosquash <feature-commit-sha>~1
   ```

3. Rebuild the applicable patch set and review the regenerated patch under `features/` from the repository root; the
   diff should show only the intended amendment.

### Full patch regeneration

When a change crosses layers, or before final verification, use the same complete sequence established by Paper forks
such as Folia:

```shell
./gradlew rebuildPaperPatches
./gradlew rebuildPaperServerPatches
./gradlew rebuildServerPatches
./gradlew rebuildMinecraftPatches
```

Run the commands as separate invocations and stop on the first failure. On Windows, substitute `.\gradlew.bat`.

Before reapplying patches, ensure all intended nested-worktree changes have either been folded into file patches or
committed as feature patches. Reapplying can replace generated worktrees.

## Patch quality

- Keep one logical change per feature patch.
- Explain why a patch exists, not merely what the diff does.
- Match the style of the upstream file. Avoid unrelated formatting.
- In Minecraft source, mark non-obvious Scissors changes with concise
  `// Scissors - ...` comments. Use start/end markers only when they improve patch readability.
- Prefer fully qualified names for occasional references added to Minecraft source; import churn causes avoidable
  upstream conflicts.
- Add or update API and implementation together when behavior is public.
- Add tests in the layer whose behavior changed when practical. Do not add a new test framework.
- Never edit generated sources just to silence a build unless the resulting tracked patch is intentional.

## Verification

Use the narrowest relevant check first, then verify the complete patch state:

```shell
./gradlew applyAllPatches
./gradlew build
```

For documentation-only changes, patch application is sufficient when the build configuration did not change. For build,
API, server, or Minecraft changes, run the build.

The `build` task verifies the modules but does not create a standalone server. When a task changes distribution packaging
or requires a runnable artifact, also run:

```shell
./gradlew :scissors-server:createPaperclipJar
```

The deployable artifact is `scissors-server/build/libs/scissors-paperclip-<version>.jar`.
`scissors-server-<version>.jar` is a thin development JAR and must not be used as a standalone server.

After verification:

1. Run `git status --short`.
2. Review every tracked patch with `git diff --check` and `git diff`.
3. Confirm generated worktrees and build outputs are not staged.
4. State exactly which commands passed. If a check could not run, report the command and error.

## Safety

- Do not discard, reset, or overwrite unrelated work in the root or nested Git repositories.
- Do not update Paper, Gradle, paperweight, Java, mappings, or dependencies unless requested.
- Do not publish artifacts, push branches, create releases, or modify external services unless requested.
- Do not run a Minecraft server as verification. Hand the run command to the user when interactive runtime testing is
  required.
