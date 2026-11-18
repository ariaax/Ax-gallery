# AX Gallery date rail update

- Added a compact, year-based navigation rail using the gallery's existing grouped timeline model.
- Year capsules are generated from the first media item in each year and jump to that year's first timeline group.
- The active year receives the accent-container treatment; other years use subdued surface capsules.
- The drag-date bubble now pairs the exact date with a separate year capsule.
- The vertical scrubber touch lane and thumb were refined while retaining the existing drag-to-scrub behavior.

Build validation: not completed in this environment because the Gradle 9.4.1 distribution could not be downloaded (network/DNS unavailable). Run the project's normal Gradle build and test on a machine with Android SDK and Gradle access before installing.
