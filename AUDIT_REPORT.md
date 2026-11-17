# Ax Gallery: audit and repair report (main @ ec3e89e)

Builds/tests: GitHub Actions (check.yml: testDebugUnitTest + assembleRelease) passed on each merged PR. Nothing was run on a device or emulator, and no local Gradle run was possible (no network in the work environment).

| # | Issue | Status |
|---|---|---|
| 1 | Vault: originals deleted before index saved; index failure swallowed | FIXED, logic not testable without Android (helpers tested) |
| 2 | Vault: copy only checked length>0, partial copy left behind | FIXED AND TESTED (VaultFilesTest) |
| 3 | Vault: restore overwrote existing files; null output stream left empty row and deleted the vault copy | FIXED (null/failed stream tested; MediaStore path not testable) |
| 4 | Vault: partial original removal then rollback deleted vault copies of already-removed originals | FIXED, not unit-testable (needs ContentResolver) |
| 5 | Vault: AtomicFile backup ignored; unreadable index crashed/overwrote | FIXED, not unit-testable (org.json) |
| 6 | Vault: interrupted-transfer .part files | FIXED AND TESTED |
| 7 | Trash: unverified copies, index-before-delete, duplicate ids, overwrite on restore | FIXED AND TESTED (TrashFilesTest) earlier PR #6 |
| 8 | Restored photo jumped to top (new MediaStore DATE_ADDED) | FIXED AND TESTED codec (RestoredDatesCodecTest); on-device effect unverified. User's own case was a settings choice |
| 9 | Thumbnails: stale picture after an external edit | FIXED, not unit-testable (Bitmap) |
| 10 | AVIF fallback: whole file on heap twice plus unbounded full decode | FIXED (mapped file, 100 MB cap, release); not unit-testable |
| 11 | Media ids from different storage volumes can collide (id is the identity everywhere) | NOT FIXED: id is used by selection, favourites, trash, vault (negative ids), cache keys and grid keys. Changing it needs a composite key across ~100 call sites and cannot be verified without a device and an SD card. URIs are already volume-correct (VOLUME_NAME). Plan: add a volume-aware key field, migrate favourites/trash references, then switch grid and cache keys |
| 12 | Vault is not encrypted (plain files in app-private storage, .nomedia, app lock only) | NOT FIXED: Keystore AES-GCM migration touching existing vault files is too risky without device testing. Plan: write encrypted copies next to the originals, verify by decrypt-and-compare, switch the index entry, delete the plain file last, per file |
| 13 | Media discovery delay | NOT A CODE BUG found: ContentObserver, 120 ms debounce, light refresh and a latency log already exist. Needs the in-app latency log from the phone |
| 14 | Manifest/R8/backup | CHECKED: allowBackup=false, only launcher and widget exported, release shrinking on. No change |
| 15 | Main-thread I/O, Compose recomposition, full audit of every screen | NOT COMPLETED: only the files named above were read in depth. Editor, video, folder browser, app lock and widget were not audited |

Tests added: VaultFilesTest (11), RestoredDatesCodecTest (5), TrashFilesTest (7).

## Second pass (what was checked, honestly)
- Checked, no confirmed bug: app-lock PIN (PBKDF2 + salt, legacy hash upgraded, constant-time compare), MediaMetadataRetriever cleanup (try/finally), ExoPlayer release, no GlobalScope/Thread.sleep/registerReceiver leaks, manifest exports, R8 on for release.
- Changed: bulk trash/vault de-duplication now keys on id+uri. MediaRepository.distinctBy{id} is KEPT on purpose: grid and selection keys are the plain id, and two equal keys crash LazyGrid, so removing it before the identity model is changed is unsafe.
- NOT reviewed line by line: EditorScreen.kt, EditorCanvasView.kt, VideoEngine/VideoPage/VideoControls, FolderBrowserScreen, AlbumsScreen, SettingsScreen logic, DuplicateDetector, widget. Lint and a local Gradle run were not possible (no network). The original project is preserved: all work is on branches and merged by PR, and a copy of the starting tree was kept.
