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

Scissors removes non-finite item attribute amounts and restricts item camera overlays to the vanilla pumpkin overlay
in the head slot. Custom resource-pack camera overlays are removed. Players cannot equip items in the animal body or
saddle slots. These limits also apply to nested items and item templates.

Scissors repairs affected player equipment automatically on load and before equipment updates. It removes the hidden
equipment effects and returns the sanitized items to normal inventory. If the inventory is full, Scissors saves the
recovered items with the player and returns them when space is available. No repair command is required. Check the
server log for the player and the equipment slot that Scissors repaired.

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

## License

Scissors is distributed under the GNU General Public License version 3 only.
Individual authors may additionally offer their own contributions under the
MIT License. See [LICENSING.md](LICENSING.md) for the scope of those grants and
the upstream licensing notices.
