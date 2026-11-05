# AutoPoll development status

This branch adds the first implementation. A successful local build does not establish on-device reliability.

The fork identity used by CI is `io.github.hren4073.autopoll`, name `AutoPoll`. Normal Telegram X login and chat functionality remain in the source. Open Telegram X Settings > AutoPoll Settings after login. Select a target while monitoring is stopped. Dry Run defaults to ON. Enabling Live Voting requires an explicit confirmation. Never use a production target before testing on a disposable poll. The separate package uses the repository's experimental build mode because the upstream `google-services.json` does not contain this package; push notifications need a Firebase configuration owned by this fork.

## Implemented

- Kotlin parser with strict five-option structure, ordered 08/09/10 shifts and fail-closed suffix handling. Options 4 and 5 are always excluded.
- Shuffle Bag proposals, next-free priority, RANDOM and FIXED modes.
- TDLib message/content/poll listeners, bounded history recovery and foreground service.
- SQLite ledger keyed by Telegram user/chat/message/poll identity; FULL synchronous writes before send; bag commit and VERIFIED state in the same database transaction.
- Refresh and option text/identity validation before SetPollAnswer. UNKNOWN/PENDING/SUBMITTING never trigger automatic resend for the same poll; new poll identities are processed independently.
- Event-driven handling has no cooldown. A bounded recovery scan runs every 90-120 seconds (90 by default) and on reconnection; scanning never delays event handling.
- Settings, main/archive chat selection and title search, journal diagnostics, best-effort boot/update/process recovery, session reference and wake lock.
- Actions workflow for independent JVM tests and signed arm64 debug APK artifacts retained 14 days.

## Verification and blockers

Run independent core tests with `./gradlew -p autopoll-core test`. Fifteen JVM test methods cover parser forms and suffixes, the requested selection examples, shuffle cycles, modes, revalidation, confirmation, independent poll admission, composite poll identities, bag reservations, out-of-order confirmations and scan interval bounds. These tests do **not** validate the Android service or SQLite crash recovery.

Full build: `./gradlew :app:validateGitSetup :app:testLatestDebugUnitTest :app:assembleLatestDebug -Pabi=arm64 --max-workers=1`. The repeated-poll changes have not yet produced a new APK or GitHub Actions run.

The AutoPoll worktree can be hydrated offline from the verified P0 checkout: all 58 recursive submodules match pinned SHA values, and the TDLib LFS cache passes `git lfs fsck --objects`. Git worktrees contain a `.git` file rather than a directory; `validateGitSetup` accepts either. Never replace pinned submodules, disable TLS/checksums, or substitute placeholder native binaries.

No on-device checks or latency measurements have been performed. GetMessage revalidation uses TDLib's current message state; a server-side change can still race submission. Catch-up is bounded to the newest 100 messages from the last 24 hours. Unknown results can block subsequent polls indefinitely if confirmation cannot be retrieved. Foreground/wake-lock behavior depends on Android and OEM restrictions; force-stop prevents automatic recovery. Monitoring consumes battery. These limitations need device evaluation.

## CI credentials

Configure repository Actions secrets securely (never in chat or tracked files):

- `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` for the fork's Telegram application.
- `AUTOPOLL_KEYSTORE_BASE64`, `AUTOPOLL_STORE_PASSWORD`, `AUTOPOLL_KEY_ALIAS`, `AUTOPOLL_KEY_PASSWORD` for a single persistent signing key. Back up that key; keep it unchanged for updates.

The APK job fails clearly if a secret is missing and does not silently generate a new signing identity. Run `AutoPoll APK` manually or push to `feature/autopoll`. A successful JVM job alone is not an APK build. A built APK alone is not evidence of working background voting.

Remaining device checks: login, main/archive target selection, Dry Run examples, live confirmed vote, duplicate TDLib events, options edited before submission, network loss at send, process death after SUBMITTING, database/bag persistence, reboot/update recovery, screen-off behavior and real latency.
