package com.fiilda.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    companion object {
        private const val LauncherWidgetHostId = 0xF11D
        private const val BindWidgetRequestCode = 0xF11
        private const val ConfigureWidgetRequestCode = 0xF12
        private const val PendingWidgetIdKey = "pending_widget_id"
        private const val PendingWidgetIdPreferenceKey = "pending_widget_id"
        private const val PendingWidgetResultReadyKey = "pending_widget_result_ready"
        private const val PendingWidgetResultReadyPreferenceKey = "pending_widget_result_ready"
        private const val PendingWidgetHomePageKey = "pending_widget_home_page"
        private const val PendingWidgetHomePagePreferenceKey = "pending_widget_home_page"
    }

    internal val appWidgetManager: AppWidgetManager by lazy {
        AppWidgetManager.getInstance(this)
    }
    internal val appWidgetHost: AppWidgetHost by lazy {
        LauncherAppWidgetHost(this, LauncherWidgetHostId)
    }
    private var isWidgetHostListening = false
    private var pendingWidgetId: Int? = null
    private var pendingWidgetResultReady = false
    private var pendingWidgetHomePage = 0
    private var widgetResultListener: WidgetResultListener? = null
    /** Incremented when Android routes the system HOME action back to this launcher instance. */
    private var homeIntentRequest by mutableIntStateOf(0)
    // Snapshot state is observed by Compose so a provider uninstall/settings round-trip can
    // refresh descriptors while this Activity instance remains alive.
    private val externalWidgetLifecycleRefresh = mutableStateOf(0)
    // Advanced only by package/catalog changes, never by an ordinary resume. The full launcher
    // app query (icons, labels, metadata for every app) is keyed on this token.
    private val appCatalogRefresh = mutableIntStateOf(0)
    private val launcherAppsCallbackHandler = Handler(Looper.getMainLooper())
    private var isLauncherAppsCallbackRegistered = false
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            launchableAppIconColorCache.invalidatePackage(packageName)
            requestLauncherAppsRefresh(catalogChanged = true)
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            launchableAppIconColorCache.invalidatePackage(packageName)
            requestLauncherAppsRefresh(catalogChanged = true)
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            launchableAppIconColorCache.invalidatePackage(packageName)
            requestLauncherAppsRefresh(catalogChanged = true)
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach(launchableAppIconColorCache::invalidatePackage)
            requestLauncherAppsRefresh(catalogChanged = true)
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            suspended: Boolean,
        ) {
            packageNames.forEach(launchableAppIconColorCache::invalidatePackage)
            requestLauncherAppsRefresh(catalogChanged = true)
        }

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle,
        ) {
            // Shortcut publications can change without a package change. They refresh the
            // shortcut queries only; the app catalog itself is unchanged.
            requestLauncherAppsRefresh(catalogChanged = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isSystemHomeIntent(intent)) {
            homeIntentRequest = 1
        }
        val restoredPendingId = savedInstanceState
            ?.getInt(PendingWidgetIdKey, -1)
            ?.takeIf { it > 0 }
        val restoredResultReady = savedInstanceState
            ?.getBoolean(PendingWidgetResultReadyKey, false)
            ?: false
        val preferences = getPreferences(MODE_PRIVATE)
        val persistedPendingId = preferences
            .getInt(PendingWidgetIdPreferenceKey, -1)
            .takeIf { it > 0 }
        val persistedResultReady = preferences
            .getBoolean(PendingWidgetResultReadyPreferenceKey, false)
        val savedPendingHomePage = savedInstanceState
            ?.getInt(PendingWidgetHomePageKey, 0)
            ?: 0
        val persistedPendingHomePage = preferences
            .getInt(PendingWidgetHomePagePreferenceKey, 0)
        val persistedDescriptorIds = readWidgetDescriptors(this)
            .mapTo(mutableSetOf()) { it.appWidgetId }
        val pendingRestore = resolvePendingWidgetRestore(
            savedAppWidgetId = restoredPendingId,
            savedResultReady = restoredResultReady,
            persistedAppWidgetId = persistedPendingId,
            persistedResultReady = persistedResultReady,
            savedHomePage = savedPendingHomePage,
            persistedHomePage = persistedPendingHomePage,
        )
        pendingWidgetId = pendingRestore.appWidgetId
        pendingWidgetResultReady = pendingRestore.resultReady
        // The committed preference marker and its page are authoritative. A saved-instance
        // bundle can belong to an older Activity instance and must not move a completed result
        // back to the wrong page after recreation.
        pendingWidgetHomePage = pendingRestore.targetHomePage

        // A completed result and its captured target page must survive until Compose acknowledges
        // both durable writes. An in-flight marker is retained across Activity/configuration
        // recreation when the saved bundle can still deliver the platform result; a
        // resultReady=false marker left after a cold process restart is intentionally reclaimed
        // because no result can be delivered. If the bundle is stale, this decision still follows
        // the committed preference marker rather than the bundle's ID.
        val keepPendingMarker = persistedPendingId != null &&
            (persistedResultReady ||
                (savedInstanceState != null && persistedPendingId !in persistedDescriptorIds))
        if (!keepPendingMarker) {
            // A descriptor can win if a stale pre-picker marker survived after the descriptor was
            // already committed; never delete a durable widget merely because its marker was not
            // cleared. A bundle-only ID is intentionally left for onStart reconciliation.
            if (persistedPendingId != null && persistedPendingId !in persistedDescriptorIds) {
                deleteAppWidgetIdSafely(persistedPendingId)
            }
            pendingWidgetId = null
            pendingWidgetResultReady = false
            pendingWidgetHomePage = 0
            clearPersistedPendingWidgetId()
        }
        // Keep package callbacks for the whole Activity lifetime. A change made while the
        // launcher is stopped (install/uninstall from another app) must still refresh the
        // catalog, which lets an ordinary return to home skip the full app query.
        registerLauncherAppsCallback()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            FiiLDATheme {
                OnboardingHost {
                FiiLDALauncher(
                    appWidgetHost = appWidgetHost,
                    appWidgetManager = appWidgetManager,
                    onPickExternalWidget = { provider, targetHomePage ->
                        pickExternalWidget(provider, targetHomePage)
                    },
                    onWidgetResultListener = ::setWidgetResultListener,
                    onAppLaunchFailure = { requestLauncherAppsRefresh(catalogChanged = true) },
                    lifecycleRefreshToken = externalWidgetLifecycleRefresh.value,
                    appCatalogRefreshToken = appCatalogRefresh.intValue,
                    homeIntentRequest = homeIntentRequest,
                )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isSystemHomeIntent(intent)) {
            homeIntentRequest++
        }
    }

    private fun isSystemHomeIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)

    private fun registerLauncherAppsCallback() {
        if (isLauncherAppsCallbackRegistered) return
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        runCatching {
            launcherApps.registerCallback(launcherAppsCallback, launcherAppsCallbackHandler)
        }.onSuccess {
            isLauncherAppsCallbackRegistered = true
        }
    }

    private fun unregisterLauncherAppsCallback() {
        if (!isLauncherAppsCallbackRegistered) return
        // Mark it inactive before unregistering so a callback already queued on another thread
        // cannot update Compose state after the Activity is destroyed.
        isLauncherAppsCallbackRegistered = false
        getSystemService(LauncherApps::class.java)?.let { launcherApps ->
            runCatching { launcherApps.unregisterCallback(launcherAppsCallback) }
        }
    }

    private fun requestLauncherAppsRefresh(catalogChanged: Boolean) {
        if (!isLauncherAppsCallbackRegistered) return
        val bump = {
            if (catalogChanged) appCatalogRefresh.intValue++
            externalWidgetLifecycleRefresh.value++
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bump()
        } else {
            launcherAppsCallbackHandler.post {
                if (isLauncherAppsCallbackRegistered) bump()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isWidgetHostListening) {
            runCatching { appWidgetHost.startListening() }
                .onSuccess { isWidgetHostListening = true }
        }
        reconcileWidgetHostIds()
        deliverPendingWidgetResultIfReady()
    }

    override fun onResume() {
        super.onResume()
        externalWidgetLifecycleRefresh.value++
    }

    override fun onStop() {
        if (isWidgetHostListening) {
            runCatching { appWidgetHost.stopListening() }
            isWidgetHostListening = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(PendingWidgetIdKey, pendingWidgetId ?: -1)
        outState.putBoolean(PendingWidgetResultReadyKey, pendingWidgetResultReady)
        outState.putInt(PendingWidgetHomePageKey, pendingWidgetHomePage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        unregisterLauncherAppsCallback()
        // A picker/configuration activity that disappears because the launcher is destroyed must
        // not leave an allocated ID behind forever. Keep it during a configuration/process-state
        // recreation so the platform picker result can be delivered to the restored Activity.
        // A ready result may already have been bound/configured while Compose is still writing
        // its descriptor and home order. Preserve that durable marker until the acknowledgement
        // callback clears it; only an in-flight allocation is reclaimed on an actual finish.
        if (isFinishing && !pendingWidgetResultReady) {
            pendingWidgetId?.let(::deleteAppWidgetIdSafely)
            clearPersistedPendingWidgetId()
        }
        pendingWidgetId = null
        widgetResultListener = null
        super.onDestroy()
    }

    /**
     * Starts the platform binding flow only after the user selected a concrete provider from the
     * launcher-owned picker. Preview rendering never reaches this method and therefore never
     * allocates an AppWidgetId.
     */
    private fun pickExternalWidget(selectedProvider: WidgetPickerProvider, targetHomePage: Int) {
        if (pendingWidgetId != null) {
            Toast.makeText(this, tr("ウィジェットの選択を完了してください", "Finish choosing the widget"), Toast.LENGTH_SHORT).show()
            return
        }
        val providerInfo = selectedProvider.info
        val appWidgetId = runCatching { appWidgetHost.allocateAppWidgetId() }.getOrNull()
        if (appWidgetId == null || appWidgetId <= 0) {
            Toast.makeText(this, tr("ウィジェット用の領域を確保できませんでした", "Couldn't reserve space for the widget"), Toast.LENGTH_SHORT).show()
            return
        }
        pendingWidgetId = appWidgetId
        pendingWidgetHomePage = targetHomePage.coerceAtLeast(0)
        if (!persistPendingWidgetState(appWidgetId, resultReady = false)) {
            deleteAppWidgetIdSafely(appWidgetId)
            clearPendingWidgetId()
            Toast.makeText(this, tr("ウィジェットの状態を保存できませんでした", "Couldn't save the widget state"), Toast.LENGTH_SHORT).show()
            return
        }
        val provider = providerInfo.provider
        if (provider == null) {
            deleteAppWidgetIdSafely(appWidgetId)
            clearPendingWidgetId()
            Toast.makeText(this, tr("利用できるウィジェットがありません", "No widgets available"), Toast.LENGTH_SHORT).show()
            return
        }
        val options = appWidgetOptionsForProvider(this, providerInfo)
        val profile = selectedProvider.profile
        val allowed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                appWidgetManager.bindAppWidgetIdIfAllowed(
                    appWidgetId,
                    profile,
                    provider,
                    options,
                )
            } else {
                @Suppress("DEPRECATION")
                appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider, options)
            }
        }.getOrDefault(false)
        if (allowed) {
            continueAfterWidgetBound(appWidgetId)
            return
        }

        val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile)
            }
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
        }
        try {
            startActivityForResult(bindIntent, BindWidgetRequestCode)
        } catch (_: Exception) {
            deleteAppWidgetIdSafely(appWidgetId)
            clearPendingWidgetId()
            Toast.makeText(this, tr("ウィジェットを追加できませんでした", "Couldn't add the widget"), Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Android activity result API is still needed for the platform widget picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != BindWidgetRequestCode && requestCode != ConfigureWidgetRequestCode) return

        val allocatedId = pendingWidgetId
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val appWidgetId = when {
            returnedId > 0 -> returnedId
            allocatedId != null -> allocatedId
            else -> -1
        }
        val canceledConfigureInfo = if (
            requestCode == ConfigureWidgetRequestCode &&
            resultCode == WidgetActivityResultCanceled &&
            appWidgetId > 0 &&
            allocatedId != null &&
            appWidgetId == allocatedId
        ) {
            runCatching { appWidgetManager.getAppWidgetInfo(appWidgetId) }.getOrNull()
        } else {
            null
        }
        val accepted = if (requestCode == BindWidgetRequestCode) {
            // Binding cancellation is always a failed selection; the optional-config exception
            // applies only after a provider has already been bound successfully.
            resultCode == RESULT_OK
        } else {
            shouldAcceptWidgetConfigureResult(
                resultCode = resultCode,
                returnedAppWidgetId = returnedId,
                allocatedAppWidgetId = allocatedId,
                providerBound = canceledConfigureInfo?.provider != null,
                configurationOptional = canceledConfigureInfo?.let(::isOptionalWidgetConfiguration) == true,
            )
        }
        if (!accepted || appWidgetId <= 0 || allocatedId == null || appWidgetId != allocatedId) {
            widgetActivityResultCleanupIds(
                allocatedAppWidgetId = allocatedId,
                returnedAppWidgetId = returnedId,
            ).forEach(::deleteAppWidgetIdSafely)
            clearPendingWidgetId()
            return
        }

        if (requestCode == BindWidgetRequestCode) {
            continueAfterWidgetBound(appWidgetId)
        } else {
            finishExternalWidgetSelection(appWidgetId)
        }
    }

    private fun continueAfterWidgetBound(appWidgetId: Int) {
        val providerInfo = runCatching { appWidgetManager.getAppWidgetInfo(appWidgetId) }.getOrNull()
        if (providerInfo?.provider == null) {
            deleteAppWidgetIdSafely(appWidgetId)
            clearPendingWidgetId()
            Toast.makeText(this, tr("利用できるウィジェットがありません", "No widgets available"), Toast.LENGTH_SHORT).show()
            return
        }
        if (providerInfo.configure != null) {
            try {
                appWidgetHost.startAppWidgetConfigureActivityForResult(
                    this,
                    appWidgetId,
                    0,
                    ConfigureWidgetRequestCode,
                    null,
                )
            } catch (_: Exception) {
                deleteAppWidgetIdSafely(appWidgetId)
                clearPendingWidgetId()
                Toast.makeText(this, tr("ウィジェットを設定できませんでした", "Couldn't set up the widget"), Toast.LENGTH_SHORT).show()
            }
        } else {
            finishExternalWidgetSelection(appWidgetId)
        }
    }

    private fun finishExternalWidgetSelection(appWidgetId: Int) {
        val info = runCatching { appWidgetManager.getAppWidgetInfo(appWidgetId) }.getOrNull()
        if (info?.provider == null) {
            deleteAppWidgetIdSafely(appWidgetId)
            clearPendingWidgetId()
            Toast.makeText(this, tr("利用できるウィジェットがありません", "No widgets available"), Toast.LENGTH_SHORT).show()
            return
        }
        pendingWidgetResultReady = true
        if (!persistPendingWidgetState(appWidgetId, resultReady = true)) {
            deleteAppWidgetIdSafely(appWidgetId)
            clearPendingWidgetId()
            Toast.makeText(this, tr("ウィジェットの状態を保存できませんでした", "Couldn't save the widget state"), Toast.LENGTH_SHORT).show()
            return
        }
        deliverPendingWidgetResultIfReady()
    }

    internal fun deleteAppWidgetIdSafely(appWidgetId: Int) {
        if (appWidgetId <= 0) return
        runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
    }

    private fun setWidgetResultListener(listener: WidgetResultListener?) {
        widgetResultListener = listener
        deliverPendingWidgetResultIfReady()
    }

    private fun deliverPendingWidgetResultIfReady() {
        val appWidgetId = pendingWidgetId
        val listener = widgetResultListener
        if (!pendingWidgetResultReady || appWidgetId == null || listener == null) return
        listener(appWidgetId, pendingWidgetHomePage) {
            acknowledgeWidgetPersistence(appWidgetId)
        }
    }

    /** Called by Compose only after both descriptor and home-order writes have completed. */
    private fun acknowledgeWidgetPersistence(appWidgetId: Int) {
        if (pendingWidgetId == appWidgetId && pendingWidgetResultReady) {
            clearPendingWidgetId()
        }
    }

    /**
     * Keep host IDs that are represented by persisted descriptors or by the active pending
     * picker result. Everything else is an allocation left behind by a failed/canceled flow.
     */
    private fun reconcileWidgetHostIds() {
        val persistedIds = readWidgetDescriptors(this).mapTo(mutableSetOf()) { it.appWidgetId }
        val pendingId = pendingWidgetId
        runCatching { appWidgetHost.appWidgetIds.toList() }
            .getOrDefault(emptyList())
            .filter { it != pendingId && it !in persistedIds }
            .forEach(::deleteAppWidgetIdSafely)
    }

    private fun persistPendingWidgetState(appWidgetId: Int, resultReady: Boolean): Boolean =
        getPreferences(MODE_PRIVATE)
            .edit()
            .putInt(PendingWidgetIdPreferenceKey, appWidgetId)
            .putBoolean(PendingWidgetResultReadyPreferenceKey, resultReady)
            .putInt(PendingWidgetHomePagePreferenceKey, pendingWidgetHomePage)
            .commit()

    private fun clearPersistedPendingWidgetId() {
        getPreferences(MODE_PRIVATE)
            .edit()
            .remove(PendingWidgetIdPreferenceKey)
            .remove(PendingWidgetResultReadyPreferenceKey)
            .remove(PendingWidgetHomePagePreferenceKey)
            .commit()
    }

    private fun clearPendingWidgetId() {
        pendingWidgetId = null
        pendingWidgetResultReady = false
        pendingWidgetHomePage = 0
        clearPersistedPendingWidgetId()
    }
}
