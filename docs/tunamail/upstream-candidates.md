# Upstream candidates

Changes the fork carries that belong in
[thunderbird/thunderbird-android](https://github.com/thunderbird/thunderbird-android), written up as pull
requests ready to open. They are drafts: nothing here has been sent upstream yet. Each one lives on its own
branch cut from `upstream/main` (see "Sending a change upstream" in [MAINTAINING.md](MAINTAINING.md)) and was
merged, not cherry-picked, into our `main`, so the upstream merge resolves cleanly once upstream takes it.

When upstream merges one, delete its section here, remove the files it touched from
`tools/tunamail/touchpoints.txt` if nothing else of ours remains in them, and delete the fork-side branch.

## fix(message-list): show sender names and keyboard actions on Compose rows

- **Branch:** `fix/compose-row-sender-keyboard`, from upstream `467787fcc4`.
- **Fork issue:** tuna-os/tuna-mail#11.
- **Related upstream issues:** #10780 (Compose message list UI fixes), #10784 (behaviour parity between the
  Compose and legacy message list).
- **Touchpoints:** `MessageItemContent.kt`, `MessageListAdapter.kt`, `LegacyMessageListFragment.kt`,
  `MessageListAdapterTest.kt`.

> **Title:** fix(message-list): show sender names and keyboard actions on Compose rows
>
> With `use_compose_for_message_list_items` on, two things on a Compose row differ from the XML row:
>
> 1. **The sender line shows the email address, not the name.** `MessageListItem.toMessageItemUi()` maps
>    `senders.displayName` to `displayAddress?.address`, while the XML row shows `MessageListItem.displayName`,
>    which `MessageListItemMapper` already resolves through `MessageHelper` (the "Show correspondent names"
>    and "Show contact names" settings, "To: …" for sent folders). `buildSenderStyles()` was already computing
>    the bold/regular ranges against `displayName`, so they were applied to the wrong string. The row now
>    shows `displayName`. Contact name colouring is still not carried over: `ComposedAddressStyle` has no
>    colour style, and that belongs with the new list's sender work (#10555).
> 2. **Keyboard actions on the focused row do nothing.** `LegacyMessageListFragment.selectedMessageListItem`
>    casts the focused holder `as? MessageViewHolder`, which is `null` for a `ComposableMessageViewHolder`,
>    so delete, move, archive, star, toggle read and toggle selection from a hardware keyboard are no-ops.
>    `MessageListAdapter.getItem(viewHolder)` now resolves both row types, the same way
>    `MessageListSwipeCallback` already does.
>
> **Tests:** `MessageItemContentTest` (sender name and its styles), `MessageListAdapterTest` (`getItem` for an
> XML row, a Compose row and the footer). `:legacy:ui:legacy:testDebugUnitTest`, `detekt` and `spotlessCheck`
> pass.
>
> **Not verified:** keyboard navigation on a device; the focused Compose row is found through
> `RecyclerView.focusedChild`, the same as the XML row.

## fix(message-list): flag messages by database id in the new list

- **Branch:** `fix/message-list-flag-database-id`, from upstream `467787fcc4`.
- **Fork issue:** tuna-os/tuna-mail#12.
- **Related upstream issues:** #10783 (selection mode and favourite toggle through the legacy action mode),
  `TODO(#10775)` in `MessageListFragment.setFlagForSelected` (#10775 is closed, the TODO is not).
- **Touchpoints:** `MessageListFragment.kt`. New files: `MessageListFlagTargets.kt` and its test.

> **Title:** fix(message-list): flag messages by database id in the new list
>
> With `enable_message_list_new_state` on, `MessageListFragment.setFlag` calls
> `messagingController.setFlag(account.id, listOf(messageItemUi.id.toLong()), …)`. `MessageItemUi.id` is the
> message's server UID (`toMessageItemUi` sets `id = messageUid`), not the database id `setFlag` expects:
>
> - on IMAP it flags whichever local message happens to have that row id;
> - for a UID that is not a number it throws `NumberFormatException` and crashes the app — local messages
>   (`K9LOCAL:…`), and any backend whose server ids are not numeric.
>
> `MessageItemUi` does not carry the database id or the thread root, so the fragment now looks up the
> `MessageListItem`s the list was built from (the legacy bridge's `MessageListInfo`) by
> `MessageItemUi.messageReference`, and flags their `databaseId`, or `threadRoot` for a collapsed thread in a
> threaded list. With that in place:
>
> - `setFlagForSelected` works again (its loop was commented out under `TODO(#10775)`), so mark read/unread and
>   star/unstar from the action mode do something;
> - starring a collapsed thread uses `setFlagForThreads` instead of the bare `// TODO`;
> - the keyboard's toggle-read shortcut flips the read state; it used `!starred`.
>
> The grouping is the loop `LegacyMessageListFragment.setFlagForSelected` uses, moved into
> `toFlagTargets()` so it can be tested without a fragment.
>
> **Tests:** `MessageListFlagTargetsTest` (lookup by message reference with non-numeric UIDs, database ids
> rather than UIDs, thread roots only in a threaded list, grouping by account).
> `:legacy:ui:legacy:testDebugUnitTest`, `detekt` and `spotlessCheck` pass.
>
> **Still missing in the new list (not in this PR):** swipe (`OnSwipeMessage`) and the star button
> (`ToggleFavourite`) have no handler; the move/archive/delete/back effects fall into `else -> Unit`; the
> active and selected messages are not restored after a configuration change; the empty state and
> remote-folder pull to refresh exist only in `LegacyMessageListFragment`.

## feat(composer): let an account type add send options to the composer

- **Branch:** `feat/compose-extension`, from upstream `467787fcc4`.
- **Fork issue:** tuna-os/tuna-mail#5 (Tuta's password-protected mail is the fork's use of it).
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

## Let a backend create its own folders

- **Touchpoints:** `app-common/.../feature/mail/RemoteFolderCreatorResolver.kt`, `FeatureMailModule.kt`.
- **Title:** `feat(backend): let backends implement RemoteFolderCreator themselves`

> `RemoteFolderCreatorResolver` returns a real creator only for IMAP and `NoOpRemoteFolderCreator` for
> every other server type, so a backend that can create folders (JMAP, or any future backend) has no way
> to offer it. This adds an optional `backendFolderCreator: (AccountId) -> RemoteFolderCreator?` to the
> resolver; `FeatureMailModule` passes one that returns the account's `Backend` when it implements
> `RemoteFolderCreator`. IMAP keeps its factory, POP3 still gets the no-op creator, and nothing changes
> for existing backends. Tests: `BackendRemoteFolderCreatorResolverTest`.

Before sending, check whether upstream has grown folder creation outside the archive-folder dialog; if
it has, the seam may belong next to that instead.

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

## Let a server search run in steps from a cursor

**Touchpoints:** `legacy/message/.../MessagingListener.java`, `legacy/core/.../MessagingController.java`,
`MessagingControllerWrapper.kt`, `MessagingControllerTest.java`.

**Draft PR title:** `feat(backend): let backends implement SteppedSearch for server search in steps`

**Draft PR body:**

> `Backend.search` returns every match in the folder at once, which holds the message list while a large folder
> is scanned. Backends that scan the folder on the server and return a cursor to resume from can now implement
> `SteppedSearch` (`net.thunderbird.backend.api.search`), whose `searchStep` searches part of the folder (up to
> the account's remote search limit) and returns the cursor to continue from. `MessagingController` runs one
> step at a time and notifies `MessagingListener.remoteSearchStepFinished`, so the message list can show what
> was found and let the user search further. Non-stepped backends keep calling `Backend.search` as before.
