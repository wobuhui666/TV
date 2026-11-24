# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Android/Gradle project. `app/` is the main application, with shared logic in `app/src/main/`, TV UI in `app/src/leanback/`, mobile UI in `app/src/mobile/`, and local unit tests in `app/src/test/`. Included library modules are `catvod/`, `chaquo/`, and `quickjs/`. Configuration references live in `docs/`, bundled AARs in `app/libs/`, and release artifacts are copied to `Release/apk/`.

## Build, Test, and Development Commands

- `chmod +x "./gradlew"`: ensure the Gradle wrapper is executable on Unix systems.
- `./gradlew :app:assembleLeanbackArm64_v8aDebug`: build a debug TV APK for arm64.
- `./gradlew :app:assembleMobileArm64_v8aDebug`: build a debug mobile APK for arm64.
- `./gradlew assembleLeanbackArm64_v8aRelease assembleLeanbackArmeabi_v7aRelease --no-daemon --build-cache --max-workers=2`: reproduce the CI release build.
- `./gradlew :app:testLeanbackArm64_v8aDebugUnitTest`: run the focused JUnit test variant.
- `./gradlew :app:lintLeanbackArm64_v8aDebug`: run Android lint for the TV arm64 debug variant.

Release builds require JDK 21, Python 3.10, Android SDK 37, signing values in `local.properties`, and `MEDIA3_SOURCE_DIR` when using the local Media3 composite build. See `LOCAL_BUILD_ENV.md`.

## Coding Style & Naming Conventions

Follow the existing Android Studio style: 4-space indentation for Java/Kotlin/XML, Java package names under `com.fongmi.android.tv`, and descriptive class names such as `VodPlaybackController` or `LiveNavigationPolicy`. Keep shared behavior in `app/src/main/`; place UI-specific code in the matching `leanback` or `mobile` source set. Prefer small, single-purpose classes and avoid adding new abstractions unless they remove real duplication.

Prefer Kotlin for new Android code. Keep changes to existing Java files in Java unless migration provides a clear benefit, and migrate incrementally as needed.

Avoid unnecessary secondary labels and descriptions. Let the layout do the explaining.

## Documentation Guidelines

- Prefer updating existing documentation. Keep one primary entry per topic and avoid duplicate task summaries, plans, or explanations.
- Keep project READMEs, build guides, and agent instructions at the repository root. Place feature, interface, and implementation documentation in `docs/`.
- Use descriptive topic names for new documents. When adding, moving, or removing documents, update `docs/README.md` and check affected relative links.
- Store test reports in `docs/testing/reports/YYYY-MM-DD-topic/`. Record the tested commit, environment, results, and coverage limits.
- Move completed plans and outdated material to `docs/archive/`, clearly mark their historical status, and keep current documentation accurate.
- Update relevant documentation when behavior, interfaces, or configuration changes; keep Chinese and English READMEs in sync when affected. Keep writing concise and focused on usage and necessary constraints.
- Keep `docs/` under normal Git version control without ignoring the directory. Commit public documentation, verification summaries, and necessary images; keep generated artifacts, credentials, and raw private logs outside the documentation tree.

## Testing Guidelines

Tests use JUnit4 under `app/src/test/java`. Name test classes `*Test` and test methods with clear behavior statements, e.g. `shouldRejectImageMimeFormat`. Add or update tests when changing parsing, playback guard logic, database policies, or fallback behavior. Run the closest variant test before broader builds.

When changing TMDB queries or filters, also validate against live TMDB content using `tmdbApiKey` from ignored `local.properties` or `TMDB_API_KEY`. Record sanitized results and coverage limits; never copy the key into source code, documentation, or test logs.

## Commit & Pull Request Guidelines

Recent commits use short subjects, sometimes imperative (`Enhance...`) and sometimes concise fixes (`bugfix`, `fix ui`). Prefer a clear imperative subject under 72 characters and keep each commit scoped to one logical change. Pull requests should describe the user-visible impact, list tested Gradle commands, link related issues, and include screenshots or recordings for UI changes.

## Security & Configuration Tips

Do not commit `local.properties`, `release.jks`, API keys, or generated APKs. Keep secrets in environment variables or local Gradle properties, and document required configuration changes without exposing credentials.
