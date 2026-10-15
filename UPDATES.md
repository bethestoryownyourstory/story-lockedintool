# Lockedintool - live updates

Every update goes live for everyone at once (fully live). Each one gets a number here, and the owner is told
"Update #N is live: ..." when it's out. "Delete update #N" = take out exactly that update (git revert of its
commits, pushed as a new update) - nothing else changes.

| # | Date | What changed | Commits |
|---|------|--------------|---------|
| 1 | 2026-10-15 | Paywall off until the owner switches it on (SUB_ENFORCE); the owner's setup-screen button is now "Undo last update"; every update goes live for everyone (fully live); no "I agree to updates" screen | cddf3b5, e6355ed, 23ab451 |
| 2 | 2026-10-15 | Updates are seamless: no "Updating..." notification, app updates install only while the screen is off (never during a call), Android's installer never pops up by itself | 83c1aa2 |
| 3 | 2026-10-15 | Everything fast: as soon as an update is live, the server tells every phone and they get it at once (no waiting for their next check) | 34c1cd8 |
