# Upstream candidates

Provider-neutral changes the fork carries as touchpoints, drafted here as pull requests to
thunderbird/thunderbird-android. Nothing here has been sent; see "Sending a change upstream" in
[MAINTAINING.md](MAINTAINING.md).

## Let a backend create its own folders

**Touchpoints:** `app-common/.../feature/mail/RemoteFolderCreatorResolver.kt`, `FeatureMailModule.kt`.

**Draft PR title:** `feat(backend): let backends implement RemoteFolderCreator themselves`

**Draft PR body:**

> `RemoteFolderCreatorResolver` returns a real creator only for IMAP and `NoOpRemoteFolderCreator` for
> every other server type, so a backend that can create folders (JMAP, or any future backend) has no way
> to offer it. This adds an optional `backendFolderCreator: (AccountId) -> RemoteFolderCreator?` to the
> resolver; `FeatureMailModule` passes one that returns the account's `Backend` when it implements
> `RemoteFolderCreator`. IMAP keeps its factory, POP3 still gets the no-op creator, and nothing changes
> for existing backends. Tests: `BackendRemoteFolderCreatorResolverTest`.

Before sending, check whether upstream has grown folder creation outside the archive-folder dialog; if
it has, the seam may belong next to that instead.
