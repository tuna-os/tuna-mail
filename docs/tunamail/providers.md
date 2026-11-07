# Native Tuta and Proton accounts

Tuna Mail adds Tuta and Proton accounts next to IMAP and POP3. Each provider is a backend
(`backend/tuta`, `backend/proton`) and a setup module (`feature/tunamail/tuta`, `feature/tunamail/proton`)
on top of shared plumbing.

## Libraries

| Library | Version | Licence | Source |
|---|---|---|---|
| `org.tunaos.tuta:tuta-sdk-android` | 0.2.1 | GPL-3.0-or-later | https://github.com/tuna-os/tuta-unofficial-sdk/releases/tag/v0.2.1 |
| `org.tunaos.proton:proton-sdk-android` | 0.1.0 | GPL-3.0-or-later | https://github.com/tuna-os/proton-unofficial-sdk/releases/tag/v0.1.0 |

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
- **Folders** mirror Tuta's tree; a folder's server id is its Tuta id, its name its path.
- **Mail** — a message's server id is its Tuta mail id. A sync lists the folder's newest mails (as many as
  it shows), drops what the folder no longer holds and updates read state. A new mail without attachments is
  downloaded whole; one with attachments is stored as an envelope and downloaded when opened. Tuta hands out a
  decrypted mail's parts rather than its MIME source, so it is rebuilt as MIME (HTML body, inline images,
  attachments, threading headers from the original header block) and parsed by the app's MIME parser.
- **Flags** — read state is Tuta's. Tuta stores no star, so "flagged" is a Tuta label named `Flagged`,
  created the first time a mail is flagged; if the plan allows no more labels, flags stay on the device.
- **Move and delete** use Tuta folder ids. Deleting deletes for good in Trash and Spam, discards drafts, and
  moves mail anywhere else to Trash.
- **Sending** takes the composed message apart (addresses, subject, HTML body, attachments, In-Reply-To and
  References) and sends it as an ordinary send: mail to Tuta recipients is end-to-end encrypted by Tuta
  anyway; password-protected (confidential) delivery to others is not offered. `send_uncertain` is a
  permanent failure, so a mail that may have gone out is never sent twice. **Drafts** saved to the Drafts
  folder become Tuta drafts.
- **Push** follows Tuta's event bus; a reconnect syncs every watched folder.
- **Limits** — no server search; no copy; a revoked session means removing and re-adding the account
  (the account's server settings screens are IMAP's and do not apply).

## Tests

JVM tests run against fakes: `:backend:tunasession` (encryption, binding to the account),
`:backend:tuta` (`FakeTutaApi`: sync, MIME, flags, moves, deletes, send, drafts, push),
`:feature:tunamail:provider` (discovery, setup routing, handoff, cleanup) and `:feature:tunamail:tuta`
(the sign-in state machine). There are no live tests.
