# Compose and Material 3 in Tuna Mail

Upstream Thunderbird is moving its legacy XML screens to Jetpack Compose with its Material 3 design system
(Bolt, `core/ui/compose/designsystem`), behind feature flags in
[`thunderbird_mobile_featureflag.catalog.json`](../../config/featureflag/thunderbird_mobile_featureflag.catalog.json).
Tuna Mail does not rewrite those screens itself: a fork-only rewrite would conflict with every daily upstream
merge. It turns upstream's Compose paths on, one flag at a time, in
`app-tunamail/src/main/kotlin/org/tunaos/mail/featureflag/TunaMailFeatureFlagProvider.kt`, once a path does
everything the XML screen did for every account type, Tuta and Proton included.

The provider's pins win over both catalogs in release and debug builds; in debug builds the runtime debug
overrides (the feature-flag section of the debug settings) still win over the pins, so any of these can be
flipped for testing without a rebuild.

## The flags

Surveyed against upstream `main` at `467787fcc4` (2026-11-07).

|                 Flag                 |      Upstream default      |         Status          |    Tuna Mail    |
|--------------------------------------|----------------------------|-------------------------|-----------------|
| `use_compose_for_message_list_items` | off                        | usable with gaps        | **on**          |
| `use_new_message_reader_css_styles`  | on (promote by 2026-12-31) | ready                   | **on** (pinned) |
| `enable_message_list_new_state`      | off                        | stub (work in progress) | off (pinned)    |
| `use_compose_for_message_reader`     | off                        | stub (nothing reads it) | off (pinned)    |

### `use_compose_for_message_list_items`: usable with gaps

Swaps the rows of the existing message list for Compose rows; the screen around them (toolbar, pull to
refresh, swipe, selection, search, threads) stays `LegacyMessageListFragment`, so all of it keeps working.

- `MessageListAdapter` creates a `ComposableMessageViewHolder` per row
  (`legacy/ui/legacy/src/main/java/com/fsck/k9/ui/messagelist/item/`), rendering `ReadMessageItem` /
  `UnreadMessageItem` from `feature/mail/message/list/api`. Click, long click (selection), the avatar and
  the star are wired to the same listener as the XML rows.
- Swipe is still the `RecyclerView`'s `ItemTouchHelper`; `MessageListSwipeCallback` resolves both holder
  types (line 391).
- Account types: the row only renders a `MessageListItem`; there is no server-type logic on this path, so
  IMAP, POP3, Tuta and Proton accounts behave the same.

Gaps:

- The sender line shows the first address, not the sender's name: `MessageItemContent.kt` maps
  `senders.displayName = displayAddress?.address` (line 131) and passes `showCorrespondentNames = false`
  (line 65). Contact name colouring is ignored for the same reason.
- `MessageItemContent` is `@Deprecated("Don't use. Will be removed soon.")`: upstream treats this path as a
  bridge to the new message list, not the destination.
- Hardware-keyboard actions on the focused row (delete, toggle selection, star, read) do nothing:
  `LegacyMessageListFragment.selectedMessageListItem` casts the focused holder `as? MessageViewHolder`
  (line 1928) and gets `null` for a Compose row.
- Upstream enables it in no build, not even its debug and daily ones, so it has seen little testing.

### `use_new_message_reader_css_styles`: ready

Namespaced CSS classes and generated global styles for the reader's HTML: read by `HtmlProcessorFactory`
(`legacy/core`), `DefaultCssClassNameProvider` (`app-common`) and `FeatureMessageReaderModule`
(`feature/mail/message/reader/impl`). Upstream already defaults it on and plans to remove the flag by
2026-12-31. Tuna Mail pins it on so a remote catalog cannot turn it off. No account-type logic.

### `enable_message_list_new_state`: stub, left off

`KoinModule.kt` (`legacy/ui/legacy/.../ui/`) picks `MessageListFragment` (the MVI list rendered by
`MessageListScreenRenderer` from `feature/mail/message/list/internal`) over `LegacyMessageListFragment`.
It is a work in progress:

- **Swipe does nothing.** `MessageListSwipeableItem.kt` dispatches `MessageItemEvent.OnSwipeMessage`
  (line 60), and no state-machine transition or side-effect handler accepts it.
- **The star does nothing.** `MessageList.kt` dispatches `MessageItemEvent.ToggleFavourite` (line 84);
  nothing handles it either.
- **Mark read/unread and star on a selection do nothing.** `MessageListFragment.setFlagForSelected`
  (line 1366) has its loop commented out under `TODO(#10775)`; the action-mode items (lines 2263–2278)
  call it.
- **Starring a thread does nothing** (`setFlag`, line 1356, a bare `// TODO`), and the keyboard's
  toggle-read shortcut flips the read flag using the *star* state (line 1751).
- **It assumes numeric server UIDs.** `setFlag` calls `messagingController.setFlag` with
  `messageItemUi.id.toLong()` (line 1361), but `MessageItemUi.id` is the server UID
  (`MessageItemContent.kt`, `id = messageUid`), not the database id the controller wants. On IMAP that
  flags whichever local message has that row id; Tuta and Proton UIDs (and local `K9LOCAL:` UIDs) are not
  numbers, so it throws `NumberFormatException`.
- Effects for move, archive, delete, back navigation and the undo snackbars (`MessageListEffect`) fall
  through `else -> Unit` (line 2465), and the active and selected messages are not restored after a
  configuration change (`TODO(#10775)`, line 1851).
- Debug `println` calls are left in the effect handler (lines 2424, 2438).
- Upstream's recent message-list features (empty state, pull to refresh only for remote folders,
  `fb8f48f151`, `314388ba22`) landed only in `LegacyMessageListFragment`.

Pinned off so a remote catalog cannot turn it on before it handles Tuta and Proton accounts.

### `use_compose_for_message_reader`: stub, left off

Declared in the catalog and in the feature-flag docs, but no code reads
`GeneratedFeatureFlagKey.USE_COMPOSE_FOR_MESSAGE_READER`; turning it on changes nothing.
`feature/mail/message/reader` holds three `main` files in `impl` (`MessageReaderViewModel`, the reply-action
bottom sheet, the CSS providers) and the `api` contracts. The reader itself is still the XML
`MessageViewFragment` / `MessageTopView` / `MessageContainerView`, which already embed two Compose
pieces unconditionally: the attachment cards (`AttachmentCard`, `MessageContainerView.renderAttachments`)
and the reply/attachment bottom sheets (`MessageViewFragment.initializeMessageTopView`). Headers, the HTML
`WebView`, remote-image loading, and the PGP/crypto states (`message_content_crypto_*` layouts) are XML.

Pinned off so a remote catalog cannot turn on a reader nobody has reviewed for this fork.

## The composer

There is no Compose composer upstream. `feature/mail/message/composer` (9 Kotlin files) holds the HTML
settings provider, the signature sanitizer and preview `WebView` settings, and one Compose dialog
(`SentFolderNotFoundConfirmationDialog`). Composing a message is still `MessageCompose.java`
(`legacy/ui/legacy/src/main/java/com/fsck/k9/activity/`), with its XML recipient, identity and PGP views.

## What is Compose now

- Already Compose upstream, no flag: onboarding, account setup and server settings, account settings and
  avatar, the navigation drawer, the migration QR code, settings import, the debug settings, the funding and
  changelog screens, the Tuta and Proton sign-in screens, the message-list widget (Glance), in-app
  notifications, and the reader's attachment cards and bottom sheets.
- Turned on by Tuna Mail: the message-list rows.

## What is still XML

57 source files under `legacy/ui` reference `R.layout.*` (68 distinct layouts; count with
`grep -rlE 'R\.layout\.' legacy/ui --include=*.kt --include=*.java`):

- **Message list screen:** `LegacyMessageListFragment` (and `MessageListFragment`, which only hosts a
  `ComposeView`), the footer row, the swipe backgrounds; `MessageHomeActivity` around it.
- **Reader:** `MessageViewFragment`, `MessageViewContainerFragment`, `MessageTopView`
  (container and four crypto-state layouts), `RecipientNamesView`, `PlaceholderFragment`; message details
  (`MessageDetailsFragment` and six item types); message source and headers.
- **Composer:** `MessageCompose`, `ChooseIdentity`, `EditIdentity`, the recipient and identity adapters,
  `RecipientSelectView`, and the PGP dialogs (`PgpEnabledErrorDialog`, `PgpEncryptDescriptionDialog`,
  `PgpInlineDialog`, `PgpSignOnlyDialog`).
- **Settings:** `SettingsActivity` and its list items, `GeneralSettingsActivity`,
  `AccountSettingsActivity` and its dialogs (OpenPGP app, Autocrypt, vibration, account spinner),
  notification actions.
- **Folders:** `ChooseFolderActivity`, `ManageFoldersActivity` / `ManageFoldersFragment`.
- **Other:** `AccountList`, `UpgradeDatabaseActivity`, `AutocryptKeyTransferActivity`, push info, the
  progress dialog, `StatusIndicator`.

## What upstream would need to finish

1. **Message list (`enable_message_list_new_state`):** handle `OnSwipeMessage` and `ToggleFavourite`;
   restore `setFlagForSelected` and thread starring against `MessageItemUi`; pass the database id (not the
   server UID) to `MessagingController`; act on the move/archive/delete/navigate effects; restore selection
   and the active message; port the empty state and remote-folder pull to refresh; drop the `println`s;
   then move the fragment into `feature/mail/message/list` (`TODO(#10322)`) and delete
   `LegacyMessageListFragment`, `MessageListAdapter` and the deprecated `MessageItemContent`.
2. **Message list rows (`use_compose_for_message_list_items`)**, if it stays until then: show the sender's
   name and honour correspondent names, and resolve the focused Compose row for keyboard actions.
3. **Reader (`use_compose_for_message_reader`):** a Compose screen to read the flag at all — headers and
   recipients, the HTML `WebView` with remote-image consent, the crypto states, message details and source.
4. **Composer:** a Compose composer does not exist yet; `MessageCompose` would need a full rewrite
   (recipients with autocomplete, identities, the HTML editor, attachments, PGP/Autocrypt).

When one of the flags left off becomes usable, check its path for IMAP-only assumptions (numeric UIDs,
`ImapStore` casts, server-type checks) against `backend/tuta` and `backend/proton` before pinning it on.
