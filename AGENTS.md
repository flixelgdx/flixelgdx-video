# FlixelGDX Video (project instructions for AI assistants)

---

## Project context

FlixelGDX Video is the optional video playback extension for [FlixelGDX](https://github.com/flixelgdx/flixelgdx).
It is a **platform-split extension**: a shared core API plus one backend per target platform, so a game only
ships the decoder and natives it actually needs.

This repository has three published Java modules and five packaging-only natives modules:

- **`flixelgdx-video-core`**: The platform-neutral API (`FlixelVideo`, `FlixelVideos`, `FlixelVideoFactory`,
  `FlixelVideoQuality`). Owns the shared upload path (one texture, rewritten pixels per frame). Depends only
  on `flixelgdx-core`.
- **`flixelgdx-video-desktop`**: Desktop backend powered by [libvlc](https://www.videolan.org/vlc/libvlc.html),
  bridged through JNA. Decodes frames via libvlc video callbacks and hands them to core.
- **`flixelgdx-video-html5`**: Web backend built on a hidden HTML video element the browser decodes. Each frame
  is read back and handed to core.
- **`flixelgdx-video-vlc-natives-*`**: Packaging-only modules. They download, strip, and bundle the libvlc
  natives for Windows and Linux (x86-64 and ARM64) and macOS (universal). Only built when the
  `packageVlcNatives` Gradle property is set.

Runtime verification happens in a **separate game project** that consumes the extension via
`publishToMavenLocal`, a composite build, or JitPack.

---

## Collaboration before implementation

Treat the interaction as teamwork, not robotic task execution. Prefer brainstorming when the user's direction
is ambiguous.

- Before implementing anything (planning or coding):

  1. Ask yourself whether the requested change actually belongs here. A contribution belongs in this repository
     if it affects video decoding, the backend API contract, the natives packaging, or the shared upload path.
     New framework behavior that is not video-specific belongs in the
     [base framework](https://github.com/flixelgdx/flixelgdx) instead.
  2. If the change hurts the extension, breaks backend invariants, or there is clearly a better path, **stop
     before editing files or running commands**. Explain why, suggest alternatives, and ask whether the user
     still wants to proceed.
  3. If they confirm after that discussion, proceed as requested.

- If you are unsure about something, whether that would be for a library, the libvlc API, a specific aspect of
  the codebase, or the base framework's API, do **not** assume anything. Verify first. If you are still unsure,
  ignore it and bring it up when you are done with the task.

---

## Explaining things for beginners and contributors

The project welcomes new contributors learning open source.

When explaining code or introducing patterns:

- Explain **why** before **how** (motivation before mechanics).
- Use analogies for complex systems; if the user gave no analogy topic, ask for one they like.
- End **complex** explanations with a short check-in question so you can verify understanding.
- Stay encouraging and professional. Assume intelligence but not deep familiarity with JNA, libvlc, or
  FlixelGDX internals.

---

## Code quality (non-negotiables)

### Performance, Memory, and allocations

- **Do not allocate objects inside loops or in methods invoked every frame.** The video upload path
  (`FlixelVideo.updateFrame(...)`) is called per frame. Do not allocate there. The same rule applies to any
  code a backend calls on its decode thread.
- **Always put fields in the correct order for each class**. Follow the order below:

  1. `long`s and `double`s
  2. `int`s, `float`s, and object references
  3. `short`s and `char`s
  4. `boolean`s and `byte`s

- **Standard Java collections are completely banned**. Use FlixelGDX collection types (`FlixelArray`,
  `FlixelMap`, `FlixelSet`, and so on) instead. The only exception is build-time code (Gradle plugins and
  the natives-packaging tasks), which does not run at game runtime.
- **Reflection is banned**. It breaks platforms that require ahead-of-time compilation. If reflection appears
  to be necessary, do not use it; stop and bring it up with the user.
- **Do not use deprecated APIs**. If one is already in use in the file you are editing, replace it with the
  recommended alternative.

### Coding style

**Always put fields, modifiers, types, and methods in the correct order**. Follow the orders below:

#### Modifiers

1. `public`
2. `protected`
3. default
4. `private`
5. `static`
6. `final`

#### Fields, Methods/Functions and Types

1. Fields (following the alignment padding rule!)
2. Constructors, with smallest to largest parameters top to bottom
3. Methods (if there are overloads, order them smallest to largest parameters top to bottom)
4. Simple Getter/Setter methods (below every other method)
5. Inner classes
6. Inner interfaces
7. Inner enums

### Language and style

- Target **Java 17**. Prefer modern features (records, lambdas, modern `switch`) over legacy patterns.
- **Import** every type you use. Do **not** use star imports (`*`).
- Do **not** use fully qualified class names inline when a normal import would read cleanly.
- Prefer **short** field and method names. If shortening a name hides its meaning, use a concise name plus
  Javadoc instead of a long identifier.

---

## Documentation, comments, and Javadoc

Documentation should read like a **beginner-friendly handbook**, not an expert-only manual.

- Use correct grammar and punctuation everywhere.
- Stick to **ASCII** in prose; avoid decorative punctuation like en dash, em dash, fancy arrows, or emojis.
  Use a plain hyphen only for compound adjectives.
- Every doc comment should start with a single sentence, with detailed paragraphs following.
- Include the right Javadoc tags (`@param`, `@return`, `@throws`) wherever they apply.
- Use nullability annotations (`@Nullable`, `@NotNull`) where they help tooling.
- Skip Javadoc on trivial, self-explanatory methods unless there is subtle behavior.
- All source files should carry the project's standard copyright header (exceptions: `package-info.java` and
  build scripts).
- Use **American English** in docs.
- After code changes that affect public behavior or APIs, **update relevant Markdown docs** in the repo.
- Don't use section comments (like `// ---`).

---

## Architecture and scope

- `flixelgdx-video-core` is the only module a game's shared code should depend on. Keep backend quirks (libvlc
  callbacks, JNA bindings, browser canvas reads) out of core; abstract with the `FlixelVideoFactory` service
  contract.
- Desktop and HTML5 backends are **strictly separate**. Do not let desktop JNA types leak into core or the
  HTML5 module.
- The natives packaging modules (`flixelgdx-video-vlc-natives-*`) contain no Java source. Keep packaging logic
  in `DownloadVlcNativesTask`; do not add game-runtime code there.
- Keep changes minimal: avoid touching unrelated files unless strictly necessary for the stated task.
- The convention plugin in `build-logic/` applies to all modules. Changes there affect the whole project.

---

## Finishing work

Before considering a coding task finished:

1. Apply formatting: `./gradlew spotlessApply`
2. Verify formatting: `./gradlew spotlessCheck`
3. Verify Javadoc: `./gradlew javadocAll`
4. Build all modules: `./gradlew assemble`

Fix any failures before considering the task complete.

If you are currently on a branch for a pull request, always update the PR description to reflect your
changes after completing a task.

Summarize edits in plain language: what changed, why, and how it fits the extension.

---

## Working with Git and Pull Requests

- Prefer **small, focused commits** as you finish logical slices of work so history stays readable.
- Use **one branch and one pull request** unless the user explicitly asks for more.
- If the user renames a pull request, **do not rename it back**; respect their title.
- Commit titles must be **short and descriptive** (72 characters maximum), **present tense**, and must
  not start with type keywords like `fix:`, `feat:`, or `refactor:`. Examples:
    - "Switch version source from gradle.properties to git tags"
    - "Add AGENTS.md and missing markdown docs"
    - "Fix per-frame allocation in desktop video callback"
- Always pull the latest changes before editing code if on a branch outside of `master`.
- If the current branch is `master`, **create a new branch** off the latest `master` before making changes.
- When you are done with a task (and you have not yet made one), **create a pull request** following the
  [PR template](.github/PULL_REQUEST_TEMPLATE.md) exactly.
- Pull request titles should read as **past tense**, written as if announcing a new update. Examples:
    - "Switched the version system to read from git tags instead of gradle.properties"
    - "Added missing project markdown docs and AI instructions"
- All pull requests must target the **`master`** branch.
- If the user has changes present on the current branch, **do not undo, modify, or touch them**.
