# FiiLDA launcher source ownership

`MainActivity.kt` is the Android entry point. It owns window setup, lifecycle registration,
platform widget binding/configuration results, pending widget markers, and the launcher callback
bridge. Compose state and interaction callbacks remain owned by `FiiLDALauncher` in
`LauncherRoot.kt`, so the refactor does not introduce a second source of truth.

MainActivity publishes two refresh tokens. `lifecycleRefreshToken` advances on every resume and
on package/shortcut callbacks; it drives cheap rechecks (notification/media access, search
permissions, shortcut queries). `appCatalogRefreshToken` advances only on package changes and app
launch failures, profile broadcasts, and a resume-time check that the profile set changed; it drives the full app catalog query and widget descriptor revalidation. The
LauncherApps callback stays registered for the Activity lifetime so changes made while the
launcher is stopped still advance the catalog token.

The app catalog includes associated profiles exposed by `LauncherApps`. Personal app IDs remain
`package/activity`; other profiles use `package@userSerial/activity`, where UserManager's serial
survives restarts and is not reused when a profile is recreated. Home items, folders, shortcuts,
and drawer keys share this identity. Cross-profile icons use the system badge, and app launches,
shortcut queries/launches, and app info target the entry's UserHandle. An unavailable or locked
profile does not discard stored favorites; a deleted profile (its serial no longer resolves) does. Uninstall actions for other profiles go through their
app info screen so a package-delete intent cannot remove the personal copy.

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Activity lifecycle, edge-to-edge setup, LauncherApps/AppWidgetHost integration |
| `LauncherModels.kt` | Shared posture, home item, grid size, built-in widget, and home projection models |
| `LauncherRoot.kt` | Compose state coordinator and navigation projection |
| `HomeSurface.kt` | Home pages, canvas, board layout, and drag/reorder gestures |
| `HomeWidgets.kt` | Widget tile dispatch plus photo, media, pinned shortcut, and external widget tiles |
| `AppDrawer.kt` | App grid/tile rendering, shortcut projections, and drawer search |
| `LauncherDialogs.kt` | Home action sheets and built-in/provider widget picker dialogs |
| `LauncherSharedUi.kt` | Header, context bar, tile primitives, icon mapping, and display helpers |
| `LauncherThemeSurface.kt` | Palette accessors and the root theme boundary |
| `LauncherGlass.kt` | Stable glass host, wallpaper backdrop, surface/contributor adapters, and accessibility appearance |
| `GlassWallpaper.kt` | App-private wallpaper import/store/controller, stable photo-picker registration, and settings preview |
| `LauncherPersistence.kt` | SharedPreferences keys, migrations, ordering, serialization, and atomic writes |
| `FavoriteRetention.kt` | Startup favorite reconciliation: missing apps are kept unless their package is confirmed uninstalled; unambiguous activity renames are followed |
| `LauncherWidgetSupport.kt` | Provider metadata, picker models, previews, descriptor validation, and widget helpers |
| `BuiltInWidgetTiles.kt` | Built-in widget rendering (all except media/photo) and the per-theme widget design language |
| `BatterySupport.kt` | Live battery status from the sticky broadcast and its labels |
| `ClockSupport.kt` | Next alarm lookup and clock/calendar label helpers |
| `StarterApps.kt` | Fresh-install Home apps chosen from the device's default apps |
| `OnboardingTutorial.kt` | First-run swipe tutorial, its fresh-install decision, and the replay hook for Settings |
| `UiLanguage.kt` | Japanese/English selection (`tr`) and localized date and weekday labels |
| `WeatherSupport.kt` | Open-Meteo forecast fetch/parse, coarse location, process-wide weather repository and cache |
| `AgendaSupport.kt` | Today's calendar instances query, agenda filtering, and calendar intents |
| `GlancePermission.kt` | Runtime permission state/request for built-in weather and agenda tiles |
| `LauncherWidgetHost.kt` | AppWidgetHostView touch negotiation and provider drag handoff |

The coordinator boundary is explicit: the root coordinator projects state into the
home, drawer, and dialog surfaces; those surfaces report events through callbacks. Persistence and
platform support remain pure/shared helpers where possible. Existing Compose `remember`,
`rememberSaveable`, `LaunchedEffect`, `DisposableEffect`, and callback keys stay in their original
coordinator or surface bodies.

The `:glass` library owns GPU backdrop recording, clipped optical regions, API-specific effects,
and the pre-glass foreground scene. It does not own launcher navigation, input, or persistence.
`LauncherGlassHost` retains one content call site across theme changes. Tiles sample the fixed
wallpaper; the floating navigation and fixed drawer search surfaces additionally sample registered sharp foreground
layers, without capturing other glass effects or duplicating interactive Composables. Foreground
contributors are registered only while a foreground-sampling control is visible. Wide Home and
hidden surfaces keep their optical wallpaper sampling without allocating foreground capture
layers or observing the scene's scroll/transition signal. Wide glass navigation retains the
centered fade/scale reveal without a second, full-screen blur of the already filtered tiles;
opaque themes keep their existing reveal blur. Native View/SurfaceView content can register a
fallback color instead of claiming a captured image.

Wallpaper settings belong to a controller remembered at the `FiiLDATheme` boundary, where the
photo picker remains registered even while Settings is closed. Its private files and preferences
are independent of photo widgets and search-document URI ownership.

`artifacts/MainActivity.before-organization-20260906.kt` is the pre-edit source snapshot used for
declaration and body comparison. The snapshot SHA-256 is
`1117468c98c865673a0fce33c8c162322269ef5a185c9b8af499b86ad92cba48`.

Home page count belongs to the canonical `HomeLayout` record (v5). It starts at two, may
grow, and retains at least one page. The v4/v3 records migrate with two pages. `HomePages`
is the derived narrow projection, including empty pages; its v3 codec also reads the old
v2 projection during migration. Page deletion reassigns items to the previous neighbor
(or the next for the first page), renumbers later owners, and retains both global orders,
widget records, folders, sizes, and URI grants. Page controls report events to `LauncherRoot`;
state changes only after an atomic layout save succeeds. The narrow Home tab cycles through
the current page count; returning from the drawer preserves the selected page.
