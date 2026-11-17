# AX Gallery — Repair Report

**Build status: NOT VERIFIED.** This delivery contains real source changes and tests, but it is not being marked as a fully completed repair because the Android Gradle build and Android integration tests could not run in this environment, and two high-impact areas remain unresolved (volume-aware preference migration and app-level Vault encryption).

## 1. Input and preservation

- Source archive: `ax-gallery-updated (1).zip`.
- The source archive passed ZIP integrity inspection before extraction.
- The original project was preserved. All edits were made in a separate working copy and compared against a fresh extraction of the original archive.
- Original project files: **164**.
- Working project files before this report: **167**.
- Files changed: **25**.
- Files added: **3**.
- Files removed: **0**.
- No UI redesign or feature removal was intentionally made.

## 2. Critical / High findings

### C-01 — Vault restore could accept an unpublished MediaStore row

**Status: FIXED.** `VaultRepository.restoreFileToStorage()` now checks the return count of `ContentResolver.update(IS_PENDING=0)`. If publication fails, the newly inserted row is deleted and the Vault backup is retained. The copied bytes are read back and SHA-256 checked before publication. An identical row left by a prior restore whose index commit failed is reused instead of inserting a duplicate.

### C-02 — Trash ContentResolver fallback could accept a partial backup

**Status: FIXED.** Trash copy paths now use a `.part` file, byte-count and expected-size checks, `fd.sync()`, and SHA-256 comparison of the streamed source and staged file. The ContentResolver fallback reopens the source and compares it against the completed backup before the original can be deleted.

### C-03 — Trash restore could delete its only backup after failed publication/index commit

**Status: FIXED.** Trash restore now verifies copied bytes, checks the `IS_PENDING=0` update count, removes failed pending rows, and only deletes the Trash backup after the index write succeeds. An identical MediaStore row from an interrupted prior restore is detected by content, not merely by name/size.

### C-04 — Partial bulk-delete rollback could permanently lose earlier items

**Status: FIXED.** Bulk deletion now evaluates each original independently instead of short-circuiting on the first failure. Rollback only discards a backup when the original path still exists and has identical SHA-256 content. If the original is missing or uncertain, its backup remains in Trash. This intentionally favors retaining a recoverable Trash copy over pretending that a cancelled operation fully rolled back.

### H-01 — MediaStore IDs were treated as globally unique

**Status: PARTIALLY FIXED; preference migration remains.** A shared `MediaIdentity.key(volumeName, path, mediaType, id)` now defines row identity. It is used by the media cache, snapshots, tombstones, EXIF cache, thumbnail/HDR/depth caches, motion/depth-photo extractors, duplicate filtering, grid keys, drag selection, viewer selection, and hero transitions. Snapshot format v4 stores volume and migrates v3 records using their saved paths. Vault/Trash indexes now retain the original MediaStore ID and volume so restore can re-establish the original identity.

**Still unresolved:** `LibraryPreferences` and its consumers still store favorites, locked-media entries, custom titles, and album-cover media references by numeric `Long` ID. `IrisPhotoWidget` also reads the legacy ID-only locked set. A safe migration needs a per-device reconciliation step that maps each legacy ID only when it is unambiguous; silently assigning an old ID to multiple volumes would be incorrect. These preferences are therefore not claimed as repaired.

### H-02 — Volume-blind MediaStore URI construction/deletion

**Status: MOSTLY FIXED.** Vault, Trash, Album, Editor, MainActivity, MediaRepository rename, widget queries, and MediaDocumentsProvider path resolution now use exact volume collections where available. Synthetic `VOLUME_EXTERNAL` remains only in query/discovery paths; no insert is made into the synthetic volume. MediaDocumentsProvider's ID-only document IDs are resolved across exact volumes and return no guessed path if multiple volumes match.

### H-03 — Vault data is not application-encrypted

**Status: NOT FIXED; explicitly unresolved.** Vault files remain in app-private `filesDir`, which is protected by Android's app sandbox and device file-based encryption, but there is no separate AES-GCM encryption layer backed by Android Keystore. I did not introduce a format/key migration without an Android runtime to validate key availability, key invalidation, rollback, interrupted migration, and legacy-file recovery. Doing so without those tests could lock users out of existing Vault contents.

### H-04 — AVIF paths could allocate unbounded/full-size bitmaps

**Status: FIXED WITH BOUNDS.** Coil AVIF input is capped at 16 MiB and 8 MP. Thumbnail AVIF decoding is capped by input size and decoded pixel count. Editor AVIF fallback reads are bounded and reject oversized payloads before allocating a bitmap. Editor ImageDecoder and pre-28 BitmapFactory paths now downsample to a bounded target dimension; API <28 video frame decoding and the VideoPage scrub preview reject oversized full-frame decodes.

### H-05 — Snapshot did not preserve volume identity

**Status: FIXED.** Snapshot v4 writes volume name and path. The reader accepts v3 and derives a best-effort volume from the saved path.

### H-06 — Same-size different-content files could be treated as identical

**Status: FIXED.** Vault and Trash identity/rollback checks now compare SHA-256 content in addition to size and, where relevant, modification time. Copy helpers also compare the bytes streamed into the staged file with the staged file itself.

### H-07 — AtomicFile backup recovery could be bypassed by checking base-file existence first

**Status: FIXED.** Trash index reading now calls `AtomicFile.openRead()` directly, allowing its backup recovery path to run. Corrupt/unreadable indexes are not silently treated as empty; writes are guarded so an unreadable index is not overwritten as an empty index. Snapshot and EXIF cache access are serialized around their AtomicFile operations.

### H-08 — Restore/save-copy inserted into the synthetic external volume

**Status: FIXED.** Vault and Trash inserts use the selected concrete volume. Editor save-copy targets `external_primary`. A source scan found no remaining insert into `MediaStore.VOLUME_EXTERNAL` or `EXTERNAL_CONTENT_URI` on API 29+.

## 3. Medium findings and additional repairs

| Finding | Status | Repair / verification |
|---|---|---|
| M-01 — SD-card restore fell back to primary storage | Fixed | Vault, Trash, Album, Editor and MainActivity derive/use the original concrete volume; relative-path fallback uses `/storage/<volume>/` for removable storage. |
| M-02 / M-03 — labels in the earlier conversational audit | Not independently traceable | The available `AUDIT_REPORT.txt` does not contain these numbered labels, and the accessible prior audit summary did not preserve their descriptions. I have not invented a mapping or claimed them fixed. |
| M-04 — API 26 video frame decode could allocate a full large frame | Fixed | Full-frame fallback checks metadata dimensions and rejects frames over 12 MP. API 27+ uses scaled frame extraction. |
| M-05 — copy durability/error handling | Fixed for local staging | Local staged copies are synced with `fd.sync()` and verified. ContentResolver outputs are closed and read back before publication. Actual storage-device power-loss behavior still requires device testing. |
| M-06 — synchronous snapshot/Vault/Trash I/O on Main | Fixed | Snapshot loading and Vault/Trash loading are dispatched to IO before UI state is published. |
| M-07 — scan cancellation and snapshot write races | Fixed | Media cursor loops and storage walks check coroutine cancellation; snapshot read/write share a lock. |
| M-08 — restored-date override forgotten before copy/delete completed | Already corrected in supplied working source | `RestoredDates.forget()` is called only after the original deletion reports success. Failed copies/deletes keep the date override. |
| M-09 — restored-date cache too small for bulk restore | Fixed | Capacity raised to 5,000; batch records receive monotonically ordered record timestamps so the newest entries survive pruning. Vault restore also records original `dateAdded`. |
| M-10 — interrupted Trash copies could leave partial files | Fixed | Trash initialization removes `.part` files; verified copy helpers remove their own partial files. |
| M-11 — Trash restore ignored index commit result | Fixed | Backup deletion and date recording occur only after `writeIndex()` succeeds. |
| M-12 — thumbnail freshness stamps could grow without bound | Fixed | Freshness stamps and HDR/depth metadata caches are bounded LRU caches; memory thumbnail cache reduced to 12.5% of max heap and thumbnail worker parallelism reduced. |
| M-13 — EXIF enrichment skipped new lists while an earlier job was active | Fixed | A pending latest list is queued and processed by the active job before it exits. |
| M-14 — EXIF date cache replacement was not atomic | Fixed | EXIF date cache uses `AtomicFile`, `fd.sync()`, and fail-write recovery. Legacy ID-only entries are ignored rather than applied to multiple volumes. |
| M-15 — storage rescan did not cooperate with cancellation | Fixed | Folder walks and cursor loops check cancellation; cancellation is rethrown rather than swallowed. |
| M-16 — Editor save/overwrite could leave pending or damaged output | Fixed with recovery marker | Save-copy verifies the pending row and deletes it if publication fails. In-place overwrite stages and verifies the final payload, keeps a private recovery backup and marker, restores partial writes on startup, and preserves a fully published write by comparing its hash. |
| P-01 — AlbumRepository unknown-size ContentResolver copy | Fixed | Source size is queried from the asset descriptor when possible; streamed output is verified against a second source read before source deletion. |

## 4. New findings found and repaired

1. **In-place Editor overwrite was not transactional.** Writing directly to an existing file/MediaStore URI could truncate the original before the new payload was fully written. Added `EditorOverwriteRecovery`, a durable app-private backup plus URI/hash marker, staged file replacement, read-back verification, and startup recovery. If a write is partial, the old bytes are restored; if the full expected payload was published before process death, it is preserved.
2. **Viewer/selection/cache identity still used numeric IDs in several UI and decoder paths.** Migrated viewer selection, grid selection/drag ranges, hero transition keys, thumbnail lookup, motion-photo and depth-photo caches to `stableKey`.
3. **MediaDocumentsProvider document IDs did not include a volume.** The resolver now checks exact volume collections and refuses to guess when the same ID exists on multiple volumes.
4. **Bulk state invalidation could remove another volume's same-ID row.** ViewModel deletion/tombstones now use `MediaImage.stableKey` plus path; Trash/Vault indexes retain original MediaStore IDs for restore.

## 5. Files changed

### Existing files modified (25)

- `app/src/main/java/com/iris/gallery/IrisPhotoWidget.kt`
- `app/src/main/java/com/iris/gallery/MainActivity.kt`
- `app/src/main/java/com/iris/gallery/data/AlbumRepository.kt`
- `app/src/main/java/com/iris/gallery/data/DuplicateDetector.kt`
- `app/src/main/java/com/iris/gallery/data/ExifDateStore.kt`
- `app/src/main/java/com/iris/gallery/data/IrisApplication.kt`
- `app/src/main/java/com/iris/gallery/data/MediaImage.kt`
- `app/src/main/java/com/iris/gallery/data/MediaRepository.kt`
- `app/src/main/java/com/iris/gallery/data/RestoredDates.kt`
- `app/src/main/java/com/iris/gallery/data/TrashRepository.kt`
- `app/src/main/java/com/iris/gallery/data/VaultRepository.kt`
- `app/src/main/java/com/iris/gallery/decoder/AvifCoilDecoder.kt`
- `app/src/main/java/com/iris/gallery/decoder/DepthPhotoExtractor.kt`
- `app/src/main/java/com/iris/gallery/decoder/MotionPhotoExtractor.kt`
- `app/src/main/java/com/iris/gallery/ui/AxMotion.kt`
- `app/src/main/java/com/iris/gallery/ui/EditorScreen.kt`
- `app/src/main/java/com/iris/gallery/ui/ExifEditorSheet.kt`
- `app/src/main/java/com/iris/gallery/ui/FolderBrowserScreen.kt`
- `app/src/main/java/com/iris/gallery/ui/GalleryViewModel.kt`
- `app/src/main/java/com/iris/gallery/ui/MediaThumbnail.kt`
- `app/src/main/java/com/iris/gallery/ui/SmartScreens.kt`
- `app/src/main/java/com/iris/gallery/ui/video/VideoPage.kt`
- `app/src/test/java/com/iris/gallery/data/RestoredDatesCodecTest.kt`
- `app/src/test/java/com/iris/gallery/data/TrashFilesTest.kt`
- `app/src/test/java/com/iris/gallery/data/VaultFilesTest.kt`

### New files added (3)

- `app/src/main/java/com/iris/gallery/data/EditorOverwriteRecovery.kt`
- `app/src/main/java/com/iris/gallery/data/MediaIdentity.kt`
- `app/src/test/java/com/iris/gallery/data/MediaIdentityTest.kt`

## 6. Tests and build — actual results only

### Test source

There are **33 `@Test` methods** in `app/src/test/java` after the additions. New tests cover volume/media-type identity, same-size different-content files, interrupted stream copies, independent bulk-delete behavior, backup rollback guards, and large restored-date batches.

### Executed successfully in this environment

- A Kotlin/JVM harness compiled the actual `TrashFiles`, `VaultFiles`, `MediaIdentity`, and `RestoredDatesCodec` implementations extracted from the working source. It passed checks for SHA-256 copy identity, same-size/different-content rejection, wrong-size and interrupted-copy cleanup, independent bulk deletion, rollback backup guard, volume/media-type key separation, and restored-date cap/newest-entry retention.
- A second Kotlin/JVM harness compiled the actual `EditorOverwriteRecovery`, `TrashFiles`, and `VaultFiles` implementations against minimal Android API stubs. It passed two crash-injection cases: partial overwrite restores the original, and a fully published payload is retained after simulated process death.
- Source archive integrity and final archive integrity checks are reported separately below.

These harnesses are **not** the Android Gradle/JUnit test task and do not validate real MediaStore or ContentResolver behavior.

### Not executed / blocked

- `./gradlew --offline testDebugUnitTest assembleDebug` was attempted and failed before Gradle could start because Gradle 9.4.1 was not cached and the environment could not resolve `services.gradle.org` (`UnknownHostException`).
- `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset; no Android SDK/Build Tools are available in this environment.
- No emulator was run, as explicitly requested.
- No real-device tests were possible for `IS_PENDING`, MediaStore volume collisions, SD-card restore, permission revocation, full-storage behavior, or actual process-kill recovery.

**Build result: NOT RUN / NOT VERIFIED.** No build or Android JUnit test is being reported as successful.

## 7. Remaining risks / next required work

1. Complete `LibraryPreferences` migration from numeric IDs to `(volume, media type, ID)` for favorites, locked items, custom titles, and album covers. Legacy values must be mapped only when unambiguous; ambiguous entries should be surfaced for user resolution, not applied to every volume.
2. Design and validate an Android Keystore AES-GCM Vault migration with a two-phase copy/verify/commit protocol, key-loss handling, and recovery of legacy plaintext files. No encryption migration was applied in this delivery.
3. Run Gradle unit tests and Android build on a machine with Gradle 9.4.1 and Android SDK/Build Tools installed.
4. Run device tests for ContentResolver update counts, pending-row cleanup, multiple external volumes with colliding IDs, SD-card read/write permissions, storage-full injection, and app-process death during Vault/Trash/Editor transactions.
5. Reconcile the missing M-02 and M-03 descriptions from the earlier audit if they refer to separate findings.

**Conclusion:** This is a materially repaired working copy, not a verified-complete release. The ZIP is delivered for review and continued validation; the unresolved items above are intentionally disclosed.

## 8. Final package integrity

- Final archive: `AX-Gallery-repaired.zip`.
- ZIP entries: **168 files** (including this report, the Gradle wrapper, Android manifest, and complete project tree).
- `unzip -t` / `ZipFile.testzip()` completed with **no corrupt entries**.
- The archive contains `ax-gallery/REPAIR_REPORT.md`, `gradle/wrapper/gradle-wrapper.jar`, and `app/src/main/AndroidManifest.xml`.
- No source files from the original project were removed.
