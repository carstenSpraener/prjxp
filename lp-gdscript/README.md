# lp-gdscript — GDScript / Godot 4 Language Pack

Language pack plugin for **GDScript** (Godot 4) for the prjxp retrieval stack.
GDScript is chunked line/indentation-based (no ANTLR, no parser).

## Standalone project

This is a **standalone Gradle project** — it is *not* part of the prjxp root build.
It consumes the host API `de.spraener.prjxp:prjxp-common` from the **local Maven
repository** (`mavenLocal`). Before building, publish it from the prjxp root:

```bash
# from the prjxp root
./gradlew :prjxp-common:publishToMavenLocal
```

## Layout

```
lp-gdscript/
├── build.gradle
├── settings.gradle
├── gradlew, gradlew.bat, gradle/wrapper/   # Gradle wrapper (copied from lp-csharp)
└── src/
    ├── main/java/de/spraener/prjxp/lpgdscript/   # the language pack (Phase 02+)
    └── test/java/de/spraener/prjxp/lpgdscript/   # tests
```

## Build

```bash
cd lp-gdscript
./gradlew build
```

The deliverable is the plugin fat-JAR:

```
build/libs/lp-gdscript-plugin.jar
```

Copy it into the host's `./language-packs` directory to install it.

## Golden rules

- **Never bundle `de.spraener.prjxp.**` or `org.slf4j.**` into the plugin JAR.**
  The host provides the API and logging with parent-first class delegation; a
  bundled copy would break class identity and the plugin is rejected at load.
  `prjxp-common` is therefore a `compileOnly` dependency, and `shadowJar`
  excludes `de/spraener/prjxp/common/**` and `org/slf4j/**`.
- The deliverable is `build/libs/lp-gdscript-plugin.jar` for `./language-packs`.
