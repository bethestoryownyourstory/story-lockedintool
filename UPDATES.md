# Lockedintool - live updates

Every update goes live for everyone at once (fully live). Each one gets a number here, and the owner is told
"Update #N is live: ..." when it's out. "Delete update #N" = take out exactly that update (git revert of its
commits, pushed as a new update) - nothing else changes.

| # | Date | What changed | Commits |
|---|------|--------------|---------|
| 1 | 2026-10-15 | Paywall off until the owner switches it on (SUB_ENFORCE); the owner's setup-screen button is now "Undo last update"; every update goes live for everyone (fully live); no "I agree to updates" screen | cddf3b5, e6355ed, 23ab451 |
| 2 | 2026-10-15 | Updates are seamless: no "Updating..." notification, app updates install only while the screen is off (never during a call), Android's installer never pops up by itself | 83c1aa2 |
| 3 | 2026-10-15 | Everything fast: as soon as an update is live, the server tells every phone and they get it at once (no waiting for their next check) | 34c1cd8 |
| 4 | 2026-10-15 | Download page for new users at /get/: logo, Download for Android (the live app) and the install steps | 723486e |
| 5 | 2026-10-15 | Profile privacy (Profile's ⋮ menu): Public / Private - private profiles can't be found in search and only show to people you follow, message or share a group with. Also: nothing goes live until the owner taps Publish live update; the download page waits for launch | 2fe2de7, ecaaefe |
| 6 | 2026-10-15 | Community: Your Community (your own end-to-end encrypted team chat - you name it and add people) and Publics community (Community as before); public accounts choose (or always open theirs), private accounts only have their own | 1ef8789 |
| 7 | 2026-10-15 | Community: the switch under the two choices says "Always start tab in your community" (not your community's name) | 34c8af5 |
| 8 | 2026-10-15 | Block, report and delete account: block anyone (Settings > Privacy > Blocked people to undo), report profiles, chats, statuses, posts, comments and groups; the official profile reviews reports (Profile ⋮ > Reports); Profile ⋮ > Delete account; Privacy Policy + Terms updated | 55613f3 |
| 9 | 2026-10-15 | Block / Report sheets show no name as a title (just the actions); Block asks "Block this account?" | 44a46a8 |
| 10 | 2026-10-15 | Updates: Create post box (title, text, Pictures / Videos - up to 20) and a feed of everything posted on Lockedintool (heart, comments, share to a chat, tap name -> profile); Profiles list after the first few posts | 399b027 |
