# Native Tuta and Proton accounts

Tuna Mail adds Tuta and Proton accounts next to IMAP and POP3. Each provider is a backend
(`backend/tuta`, `backend/proton`) and a setup module (`feature/tunamail/tuta`, `feature/tunamail/proton`)
on top of shared plumbing.

## Libraries

| Library | Version | Licence | Source |
|---|---|---|---|
| `org.tunaos.tuta:tuta-sdk-android` | 0.2.1 | GPL-3.0-or-later | https://github.com/tuna-os/tuta-unofficial-sdk/releases/tag/v0.2.1 |
| `org.tunaos.proton:proton-sdk-android` | 0.1.1 | GPL-3.0-or-later | https://github.com/tuna-os/proton-unofficial-sdk/releases/tag/v0.1.1 |

They are not committed. `tools/tunamail/tunamail.settings.gradle.kts` (applied from `settings.gradle.kts`)
downloads each release's `-maven.zip` on the first build, checks its pinned SHA-256, unzips it into
`third_party/maven/` (git-ignored) and adds that directory as a Maven repository for the `org.tunaos.tuta` and
`org.tunaos.proton` groups. To move to a new release, change the version, URL and digest there and the
dependency in the module that uses it.

Linking them makes every Tuna Mail build GPL-3.0-or-later as a whole (see NOTICE and the licence section of
[MAINTAINING.md](MAINTAINING.md)).

## Shared plumbing

- **`:backend:tunasession`** — `TunaSessionStore`: each account's provider session (a credential) by account
  UUID, one AES-GCM file per account in the no-backup directory, the key generated in and never leaving the
  Android Keystore, the UUID bound in as associated data. Never in the account settings or a log.
- **`:feature:tunamail:provider`** — `TunaMailProvider`, bound once per provider with a qualifier
  (`single<TunaMailProvider>(named("tuta"))`), and the provider-neutral work:
  - `TunaProviderAutoDiscovery` recognizes a provider's address domains and returns `DirectServerSettings`
    of the provider's server type; it runs ahead of the generic lookups.
  - `TunaAccountSetupExtension` (an `AccountSetupExtension`) routes such an account from the setup's
    password step to the provider's sign-in screens, then on to the account options.
  - `TunaSignInHandoff` keeps a signed-in session in memory until the account exists and removes the
    password from the setup state; `TunaAccountSessions.sessionFor` adopts it for the new account (and turns
    off uploading sent mail, which the provider files itself), then reads it from the store.
  - `TunaAccountCleaner`, started by `TunaMailApp`, revokes and deletes the sessions of removed accounts.
- **Wiring** — `app-tunamail`: `accounts/TunaMailProvidersModule.kt` includes each provider's Koin module;
  `developmentModuleAdditions` (debug and release) adds `tunaBackendFactories()` to the backends and
  `tunaAutoDiscoveries()` to the address discovery.
- **Upstream seams** — listed with their reasons in `tools/tunamail/touchpoints.txt`.

## Tuta

Server type `tuta`; addresses at `tuta.com`, `tuta.io`, `tutamail.com`, `keemail.me` and `tutanota.*`.
An address on a custom domain is not recognized and goes through the generic setup.

- **Setup** (`TutaSignInViewModel`): signs in with the password from the setup's password step; if the account
  has two-factor sign-in, asks for the TOTP code and sends it on the sign-in Tuta keeps open. That pending
  sign-in lives in memory only and is dropped when the password changes, when the user steps back, and
  whenever Tuta says the sign-in must start over. `rate_limited`, `pending_session_expired`, a refused code,
  security keys (unsupported) and network failures each get their own message.
- **Folders** mirror Tuta's tree; a folder's server id is its Tuta id, its name its path. `TutaBackend` is
  its own `RemoteFolderCreator`, so the app's folder creation (today, "create an archive folder") creates a
  Tuta folder at the path it names, under an existing parent.
- **Mail** — a message's server id is its Tuta mail id. A sync lists the folder's newest mails (as many as
  it shows), drops what the folder no longer holds and updates read state. A new mail without attachments is
  downloaded whole; one with attachments is stored as an envelope and downloaded when opened. Tuta hands out a
  decrypted mail's parts rather than its MIME source, so it is rebuilt as MIME (HTML body, inline images,
  attachments, threading headers from the original header block) and parsed by the app's MIME parser.
- **Flags** — read state is Tuta's. Tuta stores no star, so "flagged" is a Tuta label: the one the user chose
  for flags, or else `Flagged`, created the first time a mail is flagged. If the plan allows no more labels
  and none was chosen, flags stay on the device and a notification says so, once per run; tapping it opens
  `TutaFlagLabelActivity` to pick one of the account's labels for flags (`TutaFlagLabelChoice`, stored as a
  backend extra). The choice takes effect from the next flag change or sync.
- **Move and delete** use Tuta folder ids. Deleting deletes for good in Trash and Spam, discards drafts, and
  moves mail anywhere else to Trash.
- **Sending** takes the composed message apart (addresses, subject, HTML body, attachments, In-Reply-To and
  References) and sends it as an ordinary send: mail to Tuta recipients is end-to-end encrypted by Tuta
  anyway; password-protected (confidential) delivery to others is not offered. `send_uncertain` is a
  permanent failure, so a mail that may have gone out is never sent twice. **Drafts** saved to the Drafts
  folder become Tuta drafts.
- **Push** follows Tuta's event bus; a reconnect syncs every watched folder.
- **Copy** is not offered (`supportsCopy` is false, which hides the action). Tuta has no server-side copy,
  and rebuilding the mail with the SDK's import is refused below the Legend and Unlimited plans, which a
  backend's static capability cannot express, and would make a second, separate mail without the
  original's labels that counts twice against the account's storage.
- **Server search** (`TutaSearch`, the SDK's `search`): Tuta keeps no search index on the server, so the SDK
  scans the folder newest first, at most 500 mails per call, looking for the text in the subject, sender,
  recipients and body. Unread state narrows the scan on the server; flagged and the other flags are checked
  on the results. A search stops at 200 matches or 20 calls (the newest 10,000 mails).
- **Limits** — renaming and deleting folders is in the SDK but the app has no screen for it, for any account
  type; a revoked session means removing and re-adding the account (the account's server settings screens
  are IMAP's and do not apply).

## Proton

Server type `proton`; addresses at `proton.me`, `protonmail.com`, `protonmail.ch` and `pm.me`. An address on
a custom domain is not recognized and goes through the generic setup.

- **Setup** (`ProtonSignIn`, driven by `ProtonSignInViewModel`): signs in with the password from the setup's
  password step, then asks for whatever Proton wants next: the TOTP code, the mailbox password of a
  two-password account, or a human verification (CAPTCHA). Proton keeps no half-finished sign-in, so every
  attempt is a complete login with everything entered so far; changing the address or password starts over.
  A refused password, code or mailbox password, security keys (unsupported), an outdated client and network
  failures each get their own message.
- **Human verification** — `HumanVerificationView` opens Proton's verify page
  (`https://verify.proton.me/?…&embed=true`) in a WebView that cannot navigate away from verify.proton.me.
  The page's messages reach the app through a web message listener (`WebViewCompat.addWebMessageListener`)
  that only frames from verify.proton.me can see, fed by a script injected before the page's own
  (`addDocumentStartJavaScript`). A solved token retries the login with it; a token Proton rejects fetches a
  fresh challenge; closing the page returns to the form. A WebView without those two features cannot show the
  page, and the user is told to update Android System WebView.
- **Folders** — Proton's system folders, user folders and user labels, a server id each Proton's label id, a
  name its path. A label is a tag: a message can sit in a label folder and in its real folder at once.
  `ProtonBackend` is its own `RemoteFolderCreator` and creates user folders (never labels) at the path the
  app names.
- **Mail** — a message's server id is its Proton id, which survives moves. A folder's first sync lists its
  newest messages (as many as it shows) and remembers Proton's event id; later syncs replay the event log
  from there, and "load more" pages further back. Every message is downloaded whole as the decrypted MIME the
  SDK builds and parsed by the app's MIME parser; one that cannot be decrypted is skipped.
- **Flags** — read state and Proton's star.
- **Move, copy and delete** use Proton label ids. Moving into a label applies it and moving out of one removes
  it; copying works only into labels, since Proton has one real location per message. Deleting deletes for
  good in Trash and Drafts and moves mail anywhere else to Trash; Trash and Spam can be emptied.
- **Sending** takes the composed message apart (addresses, subject, HTML body, attachments, Message-ID,
  In-Reply-To and References) for the SDK's send, with ordinary delivery: end-to-end encrypted to Proton and
  PGP recipients, TLS to everyone else, as Proton's own apps do. `send_uncertain` is a permanent failure, so
  a mail that may have gone out is never sent twice. Proton files the Sent copy itself. **Drafts** saved to
  the Drafts folder become Proton drafts.
- **Push** — Proton has no push channel for other clients, so `ProtonBackendPusher` polls Proton's event log
  (`events`) and syncs the watched folders a change touches. `ProtonPollSchedule` sets the pace from
  `ProtonPollConditions` (`AndroidProtonPollConditions`: screen interactive, active network metered), sampled
  every 30 seconds: the Inbox is polled every 30 seconds (Proton's own clients' interval, and the fastest it
  ever polls), every 2 minutes on a metered network and every 5 minutes with the screen off. A change to
  another watched folder is pushed with the next poll at least 5 minutes (15 with the screen off) after the
  last such push, since each push costs a folder sync; an account without the Inbox watched polls at that
  pace. Network failures retry after a growing delay; a refused session is reported as a sign-in problem.
- **Server search** (`ProtonSearch`, the SDK's `search`) looks in what Proton can read: the subject, sender and
  recipients. Bodies and attachments are end-to-end encrypted and cannot be searched on the server, full text
  or not. Read and starred state narrow the search on the server; it stops at 200 matches or 10 pages.
- **Limits** — renaming and deleting folders is in the SDK but the app has no screen for it, for any account
  type; a revoked session means removing and re-adding the account.

## Tests

JVM tests run against fakes: `:backend:tunasession` (encryption, binding to the account), `:backend:tuta`
(`FakeTutaApi`: folders and folder creation, sync, MIME, flags and the flag label, search, moves, deletes,
send, drafts, push), `:feature:tunamail:provider` (discovery, setup routing, handoff, cleanup) and
`:feature:tunamail:tuta` (the sign-in state machine). `:backend:proton` (`FakeProtonApi`: folders and folder
creation, sync and event replay, MIME, flags, search, moves, labels, deletes, send, drafts, the poll schedule,
session handling) and `:feature:tunamail:proton` (the sign-in state machine, the CAPTCHA page's messages, the
sign-in screen's view model). There are no live tests.
