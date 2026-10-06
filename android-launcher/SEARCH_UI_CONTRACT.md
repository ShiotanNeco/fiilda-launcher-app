# Drawer search UI contract

The drawer owns the query text and presents the search results supplied by
`rememberDrawerSearchController(query, active, refreshToken)`. Search target management is
available from the full-screen Settings opened from a long-press action menu, while the drawer
only presents results. The controller owns all search metadata, permission rechecks, source
preferences, document targets, and result opening. Query text and history are never persisted.

## Controller

`DrawerSearchController.state` is Compose observable and contains:

- `contacts` and `files`: ranked `SearchResult` lists. A result has an opaque `id`,
  display `label`, optional `subtitle`, optional content `uri`, optional `mimeType`,
  and its `source`.
- `sources`: one `DrawerSearchSourceState` for contacts, visual media, and audio.
  Each entry includes `enabled`, `status`, `statusMessage`, and an optional
  `actionLabel`.
- `documents`: persisted `SearchDocumentTarget` values with `id`, `label`, `uri`,
  `isTree`, and `requiresReselection`.
- `documentsStatus`, `canContinueDocuments`, and `pendingPicker`.

`SearchSourceStatus` distinguishes `DISABLED`, `LOADING`, `READY`, `NO_RESULTS`,
`DENIED`, `PARTIAL`, and `ERROR`. `PARTIAL` is used for Android 14 selected-media
access and for a document scan that has more work; it does not hide already found
results.

The controller actions are:

```kotlin
toggleSource(source, enabled)
manageAccess(source)
addFolder()
addDocuments()
reselectDocumentTarget(id)
removeDocumentTarget(id)
refresh()
continueDocuments()
openResult(result)
openExternal(target)
onFolderPicked(uri: Uri?)
onDocumentsPicked(uris: List<Uri>)
```

Picker methods are cancellation safe: a null folder result or empty document list leaves
the previous target list unchanged. `pendingPicker` is consumed by the UI after launching
the matching Activity Result contract. The UI should call the callback methods only after
the platform result returns.

`reselectDocumentTarget(id)` records the target identity separately from the one-shot picker
launch event. Its successful result acquires all new grants, commits the replacement and then
releases the old URI only when no photo/search owner still needs it. Cancelled or failed results
keep the old target. The Compose factory saves this in-flight kind/target identity with
`rememberSaveable`, so a restored typed callback can complete the replacement after recreation.

`ExternalSearchTarget` is an enum with Google, Maps, and YouTube. `openExternal` uses
encoded URLs and `ACTION_VIEW`; missing handlers are reported with a short Toast and do
not crash the launcher.

## Lifecycle and permissions

The factory registers `RequestMultiplePermissions` with `rememberLauncherForActivityResult`.
The controller never calls `Activity.requestPermissions` directly; its result callback invalidates
the affected metadata cache (or all caches when the restored source is ambiguous) and starts a
fresh permission-checked generation. The factory rechecks permissions when `active` or
`refreshToken` changes. A source is
queried only when the drawer is active, its normalized query is nonblank, it is enabled,
and the required runtime permission is currently available. A 200 ms debounce and
per-source cancellation/generation guards prevent stale results from publishing.

Source preferences and persisted document targets live in a dedicated search preference
file. Runtime grants are not persisted by the controller. Documents retain a persisted
read grant before their target is committed; revoked targets remain visible with
`requiresReselection` so the user can reselect or remove them.
