# Scissors

Scissors is a fork of [Paper](https://github.com/PaperMC/Paper) focused on
patching exploits and security vulnerabilities that are unpatched upstream.

## Building

Building requires Git, an internet connection for initial setup, and Java 25.
Gradle can provision the Java 25 toolchain when run with Java 21 or newer.

```shell
git clone https://github.com/ScissorsMC/Scissors.git
cd Scissors
./gradlew applyAllPatches
./gradlew :scissors-server:createPaperclipJar
```

On Windows, enable Git long-path support before applying patches:

```powershell
git config --global core.longpaths true
```

Then use:

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat :scissors-server:createPaperclipJar
```

The runnable server is written to
`scissors-server/build/libs/scissors-paperclip-<version>.jar`. The
`scissors-server-<version>.jar` in the same directory is a thin development
JAR and does not include the runtime dependencies required to start the
server.

Contributors can run `./gradlew build` (or `.\gradlew.bat build` on Windows)
to compile all modules and run the test suite. This verification task does not
create the runnable Paperclip JAR.

## Development

Scissors uses
[paperweight](https://github.com/PaperMC/paperweight) to store changes as Git
patches over a pinned Paper commit. Applied Paper and Minecraft worktrees are
generated and ignored; edits to existing upstream code must be committed or
folded into the appropriate nested patch repository, then rebuilt into tracked
patch files.

Read [AGENTS.md](AGENTS.md) before contributing. It defines the patch workflow,
Git boundaries, Scissors/Paper naming boundary, and required verification. The
layout is based on the
[paperweight fork example](https://github.com/PaperMC/paperweight-examples/tree/v2-fork)
and follows conventions used by [Folia](https://github.com/PaperMC/Folia).

## Version branches and publishing

Use `master` for the latest Minecraft version.
Keep older versions on `ver/<mcVersion>` branches, such as `ver/26.2`.
Before you update `master` to a new Minecraft version, create the older
version branch from the last commit for that version. Include the version-branch
workflow changes in both branches.

Pushes to `master` and `ver/*` in `ScissorsMC/Scissors` run the full publishing
pipeline: allocate a build number, apply patches, run the build and tests,
create the Paperclip JAR, and publish to Fill. Other branches and fork pull
requests run build checks without publishing.

On a version branch, keep `mcVersion` in `gradle.properties` equal to the
version in the branch name. The workflow checks this before allocating a build
number. Build numbers use the `scissors-<mcVersion>` track. Fill versions also
use `mcVersion`, so each Minecraft version has separate builds. Keep the
matching `apiVersion`, Paper pin, and build requirements on each branch.

The GitHub `fill` environment must allow the `master` and `ver/*` branch
patterns. It holds the publishing secrets. Keep feature branches and tags out
of this environment. Review the live environment rules before you change them.

## License

Scissors is distributed under the GNU General Public License version 3 only.
Individual authors may additionally offer their own contributions under the
MIT License. See [LICENSING.md](LICENSING.md) for the scope of those grants and
the upstream licensing notices.
