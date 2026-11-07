# Upstream candidates

Changes the fork carries that belong in
[thunderbird/thunderbird-android](https://github.com/thunderbird/thunderbird-android), written up as pull
requests ready to open. They are drafts: nothing here has been sent upstream yet. Each one lives on its own
branch cut from `upstream/main` (see "Sending a change upstream" in [MAINTAINING.md](MAINTAINING.md)) and was
merged, not cherry-picked, into our `main`, so the upstream merge resolves cleanly once upstream takes it.

When upstream merges one, delete its section here, remove the files it touched from
`tools/tunamail/touchpoints.txt` if nothing else of ours remains in them, and delete the fork-side branch.


## feat(composer): let an account type add send options to the composer

- **Branch:** `feat/compose-extension`, from upstream `467787fcc4`.
- **Fork issue:** tuna-os/thunderbird-android#5 (Tuta's password-protected mail is the fork's use of it).
- **Related upstream issues:** #8837 (Refactor: Message Composer), which asks for "better and more consistent
  ways to hook into the sending process"; no issue asks for this seam itself.
- **Touchpoints:** `MessageCompose.java`. New files: `ComposeExtension.kt` (in
  `feature/mail/message/composer/api`), `ComposeExtensionHost.kt` and its test.

> **Title:** feat(composer): let an account type add send options to the composer
>
> Some servers send mail in ways a MIME message cannot ask for by itself: a server that encrypts per recipient
> needs to know, at send time, how each recipient is to get the message, and sometimes a secret per recipient
> that must never be stored with the draft. Today the only way to add such an option to the composer is to edit
> `MessageCompose`.
>
> This adds a small seam:
>
> - `ComposeExtension` (in `:feature:mail:message:composer:api`): every extension bound in Koin is asked, for
>   the account the composer sends from, for its `ComposeOptions`, or null.
> - `ComposeOptions`: add or update menu items when the menu is prepared, handle their clicks, hold a send until
>   it may go (`beforeSend`, which calls back once ready), add headers to the built draft or message, and read a
>   reopened draft's headers back.
> - `ComposeExtensionHost` (beside `MessageCompose`): resolves the options again whenever the composer switches
>   account, removes the previous account's menu items, and is a no-op when nothing is bound — so K-9 Mail and
>   Thunderbird behave exactly as before.
>
> `MessageCompose` gains a host field and a call into it in each of `onPrepareOptionsMenu`, `onOptionsItemSelected`,
> `performSendAfterChecks` (so the attachment-loading and sent-folder continuations go through it too),
> `onMessageBuildSuccess` and `processDraftMessage`.
>
> **Tests:** `ComposeExtensionHostTest` (no extension bound, headers on drafts and sends, accounts without
> options, a reopened draft's headers, menu items following the account). `:legacy:ui:legacy:testDebugUnitTest`
> (that class), `detekt` and `spotlessCheck` pass.
>
> **Not verified:** on a device with K-9 Mail or Thunderbird, where nothing is bound and every call returns at
> once.

## Server settings screens for account types with their own sign-in

**Files:** `feature/account/edit/.../navigation/AccountEditExtension.kt` (new), `DefaultAccountEditNavigation.kt`.

**Draft PR text:** The server settings screens validate IMAP, POP3 and SMTP servers. An account whose backend signs
in by itself (the demo account today, or any future account type with a backend of its own) has nothing those screens
can edit, yet the account settings and the "authentication failed" notification both open them. This adds an
`AccountEditExtension` interface: the edit navigation asks the extensions bound in Koin whether one handles the
account, and if so shows its screens in place of the server settings. Without a bound extension nothing changes.

## Exporting and importing accounts of other server types

**Files:** `legacy/core/.../preferences/ServerTypeConverter.kt`, `SettingsImporter.kt`.

**Draft PR text:** Exporting settings fails with `AssertionError("Unsupported type: demo")` when an account's server
type is not IMAP, POP3 or SMTP, which already happens with the demo account. The exporter now writes any other type
upper case, as it does the three it knows, and the importer reads it back as before. Such an account signs in through
its own backend rather than with a server password, so the import no longer asks for one. Covered by
`OwnBackendAccountImportExportTest`.

## Account types offered during a manual setup

**Files:** `feature/account/setup/.../navigation/AccountSetupExtension.kt` (fork-added), `AccountSetupNavHost.kt`.

**Draft PR text:** Only together with the existing `AccountSetupExtension` seam, which upstream does not have yet:
`accountTypeRoute` lets an extension show a screen before the manual server settings, so an address the automatic
configuration does not recognize can still be set up as one of the extension's account types.
