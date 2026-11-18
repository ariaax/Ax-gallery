# AX Gallery – Trash Workflow Removal

## Behavior change
- Normal gallery deletion now uses permanent deletion instead of moving items into the in-app trash.
- Legacy persisted `delete_mode=TRASH` and `use_system_trash=true` preferences are overridden at load time, so existing installations also switch to direct deletion.
- Legacy delete call sites are redirected to the explicit delete-confirmation dialog.
- The multi-select delete action always opens the confirmation dialog before permanent deletion.
- The Android system-trash option is disabled and removed from Settings.
- The prominent home quick-action for Trash is removed.

## Legacy recovery safety
The old Trash screen and repository are retained only so users can recover files that were already moved there by earlier builds. New deletions do not enter this system. Do not delete the app's `filesDir/trash` directory or `trash_index.json` during migration; those may contain the only remaining copies of older trashed files.

## Verification limitation
Source changes and ZIP integrity were checked. Gradle unit tests/build could not run in this environment because Gradle 9.4.1 could not be downloaded (`UnknownHostException: services.gradle.org`). Device-level deletion behavior still requires testing on the target phone. Permanent deletion is irreversible after the confirmation step.
