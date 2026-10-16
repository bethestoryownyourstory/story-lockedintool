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
| 11 | 2026-10-15 | Send pictures, videos, documents and audio in chats, groups, Your Community and group posts (end-to-end locked in private chats); automatic warnings to accounts that get reported; reports never show who reported; Delete account moved to Settings > Privacy | 582fd20 |
| 12 | 2026-10-15 | Delete account in steps: why you're leaving, you'll lose everything, your phone's fingerprint / face / PIN, then Lockedintool messages you a link + code; enter the code on that page, Confirm, then Delete account | bda53f1 |
| 13 | 2026-10-15 | Deleting your account removes everything that comes with Lockedintool on the phone too (Pages, notes, money and wallet history, cards, trading logins, settings) and Lockedintool starts fresh; every delete screen says nothing outside Lockedintool is touched - like deleting an app | d3e8bb3 |
| 14 | 2026-10-15 | Accounts: Lockedintool opens on Create your account / Log in; passwords; Log out in Settings > Privacy (your Pages stay); chats backed up locked with your password so logging in on any phone brings them back; deleting an account goes back to sign up / log in | 3c7875a |
| 15 | 2026-10-15 | Tap Your status (or its +) in Updates to make a status straight away; Station 3 goes back above the keyboard / every screen as soon as Lockedintool system actions is on, and a notification says if it's switched off | d5f00be |
| 16 | 2026-10-15 | Setup screen shows where Station 3 is right now (on top of everything / not on top / hidden by Quick Access) | 3c0bb4f |
| 17 | 2026-10-16 | Your official profile (@officiallockedintoolprofile) can keep the name Lockedintool - Protect your account no longer says only Lockedintool's own profile can use it | 1598181, c811619 |
| 18 | 2026-10-16 | Forgot password? on Log in: a reset link by email / text message -> new password, confirm, Apply change (your chats come back); Save login info switch (off = log in every time you close Lockedintool); private email / phone number in Edit profile (never shown to anyone) | 36f3888 |
| 19 | 2026-10-16 | Lockedintool as your system (a section on the Subscription page): your phone only uses Lockedintool - no other app opens, not even the home screen or recent apps - you always land on the Dashboard, your home; calls and emergency still work; turns off only when you log out or delete your account | 9b76117, 42e20a9 |
| 20 | 2026-10-16 | Subscription: two option buttons at the top - Second system | Only system (cursor over one shows what it is; on a phone tap it, read the details, press ✓); then choose your plan and it's bought under that option and plan - Only system turns on once the plan is paid | 2be03d8, cb54857 |
| 21 | 2026-10-16 | Only system: the Dashboard's APPS button and everything involving your apps (Apps screen, app pages, Open <platform>, sharing to apps) and the bottom Apps | Pages switch line aren't there at all | db90460, 5dc2da8 |
| 22 | 2026-10-16 | Subscription options: no background and nothing chosen at first (the chosen one gets an outline + ✓); you can't buy a plan without choosing Second system or Only system | 55fc063 |
| 23 | 2026-10-16 | Widgets: every page widget (Dashboard, Money, Home / Wallet, Focus, Notes, Community) now fills the whole widget - no gaps at the sides | 0342b5c |
