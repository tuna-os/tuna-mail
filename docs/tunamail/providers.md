# Native Tuta and Proton accounts

Tuna Mail adds Tuta and Proton accounts next to IMAP and POP3. Each provider is a backend
(`backend/tuta`, `backend/proton`) and a setup module (`feature/tunamail/tuta`, `feature/tunamail/proton`)
on top of shared plumbing.

## Libraries

|                Library                 | Version |     Licence      |                                Source                                |
|----------------------------------------|---------|------------------|----------------------------------------------------------------------|
| `org.tunaos.tuta:tuta-sdk-android`     | 0.3.0   | GPL-3.0-or-later | https://github.com/tuna-os/tuta-unofficial-sdk/releases/tag/v0.3.0   |
| `org.tunaos.proton:proton-sdk-android` | 0.1.2   | GPL-3.0-or-later | https://github.com/tuna-os/proton-unofficial-sdk/releases/tag/v0.1.2 |

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
  Android Keystore, the UUID bound in as associated data. Never in the account settings or a log. `replace`
  swaps an account's session for a new one and returns the old one.
- **`:feature:tunamail:provider`** — `TunaMailProvider`, bound once per provider with a qualifier
  (`single<TunaMailProvider>(named("tuta"))`), and the provider-neutral work:
  - `TunaProviderAutoDiscovery` recognizes a provider's address domains, and otherwise looks up the domain's
    MX record (`TunaMxLookup`, through MiniDNS like upstream's MX-based lookup): a custom domain whose mail
    server is the provider's is the provider's. It returns `DirectServerSettings` of the provider's server
    type and runs ahead of the generic lookups. An MX answer without DNSSEC is an untrusted result, which the
    user confirms on the setup's password step.
  - `TunaAccountSetupExtension` (an `AccountSetupExtension`) routes such an account from the setup's
    password step to the provider's sign-in screens, then on to the account options. When the user sets an
    address up by hand (no configuration found, or "Edit configuration"), it first asks which kind of account
    it is (`TunaAccountTypeScreen`): a provider, whose sign-in follows, or another provider (IMAP or POP3),
    which continues with upstream's server settings as before.
  - A provider's sign-in screens sign in a `TunaSignInTarget`: `SetupSignInTarget`, the account being set up,
    or `AccountSignInTarget`, an existing account signing in again.
  - `TunaSignInHandoff` keeps a signed-in session in memory until the account exists and removes the
    password from the setup state; `TunaAccountSessions.sessionFor` adopts it for the new account (and turns
    off uploading sent mail, which the provider files itself), then reads it from the store.
  - `TunaAccountEditExtension` (an `AccountEditExtension`) shows a provider account's sign-in where upstream
    shows its server settings: from the account settings and from the "authentication failed" notification.
    Signing in again replaces the session (`TunaAccountSessions.replace`), drops the account's open backend
    (`TunaBackendReset`, bound to `BackendManager.removeBackend`, which also restarts its push) and revokes the
    old session. The account and its mail stay.
  - `TunaAccountCleaner`, started by `TunaMailApp`, revokes and deletes the sessions of removed accounts.
- **Wiring** — `app-tunamail`: `accounts/TunaMailProvidersModule.kt` includes each provider's Koin module and
  binds `TunaBackendReset`; `developmentModuleAdditions` (debug and release) adds `tunaBackendFactories()` to
  the backends and `tunaAutoDiscoveries()` to the address discovery.
- **Settings export** — a provider account is exported like any other, with its server type (`TUTA`,
  `PROTON`) and no password; its session stays in the session store and never leaves the device. Importing it
  asks for no password: the imported account has no session, so its first sync reports a sign-in problem and
  the notification opens the provider's sign-in.
- **Upstream seams** — listed with their reasons in `tools/tunamail/touchpoints.txt`.

## Tuta

Server type `tuta`; addresses at `tuta.com`, `tuta.io`, `tutamail.com`, `keemail.me` and `tutanota.*`, and
custom domains whose MX record names `mail.tutanota.de`.

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
  References) for the SDK's send. `send_uncertain` is a permanent failure, so a mail that may have gone out is
  never sent twice. **Drafts** saved to the Drafts folder become Tuta drafts.
- **Confidential mail** — the composer of a Tuta account has a *Confidential* menu toggle (off by default),
  added through upstream's composer seam (`ComposeExtension`, a touchpoint in `MessageCompose`) by
  `TutaComposeExtension`. Off, the message goes as an ordinary send: end-to-end encrypted to Tuta recipients by
  Tuta anyway, ordinary mail to everyone else. On, it goes end-to-end encrypted to everyone: on Send the
  composer asks the account (`contact_passwords`) which recipients are outside Tuta and asks a password for each
  of them, prefilled from their Tuta contact, with Tuta's own strength hint (`TutaPasswordStrength`, a port of
  Tuta's `getPasswordStrength`); a weak password needs a second tap, a missing one holds the send. The choice
  travels in an `X-Tuta-Confidential: yes|no` header, so drafts (local and Tuta's) keep it; the passwords never
  do. They go from the composer to the backend in memory only, keyed by Message-ID (`TutaRecipientPasswords`),
  never into a draft, the Outbox, the instance state or a log. A message still queued after the app restarted
  reads them from the recipients' Tuta contacts, where Tuta keeps every password a send used; a recipient with
  none fails the send with `password_required` rather than going unencrypted.
- **Push** follows Tuta's event bus; a reconnect syncs every watched folder.
- **Copy** is not offered (`supportsCopy` is false, which hides the action). Tuta has no server-side copy,
  and rebuilding the mail with the SDK's import is refused below the Legend and Unlimited plans, which a
  backend's static capability cannot express, and would make a second, separate mail without the
  original's labels that counts twice against the account's storage.
- **Server search** — see "Server search" below.
- **Limits** — renaming and deleting folders is in the SDK but the app has no screen for it, for any account
  type.

## Proton

Server type `proton`; addresses at `proton.me`, `protonmail.com`, `protonmail.ch` and `pm.me`, and custom
domains whose MX record names `mail.protonmail.ch` or `mailsec.protonmail.ch`.

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
- **Server search** — see "Server search" below.
- **Limits** — renaming and deleting folders is in the SDK but the app has no screen for it, for any account
  type.

## Tests

JVM tests run against fakes: `:backend:tunasession` (encryption, binding to the account, replacing a session),
`:backend:tuta` (`FakeTutaApi`: folders and folder creation, sync, MIME, flags and the flag label, moves, deletes,
send, confidential send, drafts, push), `:feature:tunamail:provider` (discovery by domain and by MX record, setup
routing, the account type choice, handoff, signing in again, cleanup) and `:feature:tunamail:tuta` (the sign-in
state machine, for setup and for signing in again, the composer's confidential state, the password strength
against Tuta's own scores). `:backend:proton` (`FakeProtonApi`: folders and folder creation, sync and event
replay, MIME, flags, moves, labels, deletes, send, drafts, the poll schedule, session handling) and
`:feature:tunamail:proton` (the sign-in state machine, the CAPTCHA page's messages, the sign-in screen's view
model). `:legacy:core`'s `OwnBackendAccountImportExportTest` covers exporting and importing an account with a
backend of its own. There are no live tests.
