package com.fiilda.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassWallpaperTest {
    private lateinit var context: Context
    private val temporaryDirectories = mutableListOf<File>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(GlassThemePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        GlassWallpaperImageProvider.lastProvider?.sourceFile = null
        GlassWallpaperImageProvider.lastProvider?.failOpen = false
        temporaryDirectories.forEach { it.deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun reduceTransparencyUsesItsOwnPreferenceAndLeavesLauncherPreferencesUntouched() {
        val launcherPreferences = context.getSharedPreferences(
            LauncherThemePreferencesName,
            Context.MODE_PRIVATE,
        )
        launcherPreferences.edit().putString(
            LauncherThemePreferenceKey,
            LauncherTheme.CLASSIC.token,
        ).commit()

        val preferences = GlassThemePreferences(context)
        assertFalse(preferences.readReduceTransparency())
        assertTrue(preferences.saveReduceTransparency(true))
        assertTrue(GlassThemePreferences(context).readReduceTransparency())
        assertEquals(
            LauncherTheme.CLASSIC.token,
            launcherPreferences.getString(LauncherThemePreferenceKey, null),
        )
    }

    @Test
    fun appearancePreferencesRoundTripNormalizeInvalidValuesAndLeaveOtherGlassSettings() {
        val preferences = GlassThemePreferences(context)
        assertEquals(GlassAppearance(), preferences.readAppearance())

        assertTrue(
            preferences.saveAppearance(
                GlassAppearance(
                    blurDp = 18f,
                    refractionDp = 24f,
                    tintStrength = 0.42f,
                ),
            ),
        )
        assertEquals(
            GlassAppearance(18f, 24f, 0.42f),
            GlassThemePreferences(context).readAppearance(),
        )

        assertTrue(preferences.saveReduceTransparency(true))
        context.getSharedPreferences(GlassThemePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(GlassAppearanceBlurPreferenceKey, "corrupt")
            .putFloat(GlassAppearanceRefractionPreferenceKey, 90f)
            .putFloat(GlassAppearanceTintPreferenceKey, Float.NaN)
            .commit()

        assertEquals(
            GlassAppearance(4f, 32f, 0f),
            GlassThemePreferences(context).readAppearance(),
        )
        assertTrue(GlassThemePreferences(context).readReduceTransparency())
    }

    @Test
    fun failedAppearanceSaveKeepsControllerStateAndWallpaper() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previous = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        val rawPreferences = context.getSharedPreferences(
            GlassThemePreferencesName,
            Context.MODE_PRIVATE,
        )
        rawPreferences.edit()
            .putString(GlassAppearanceBlurPreferenceKey, "old-type-corruption")
            .putFloat(GlassAppearanceRefractionPreferenceKey, 19f)
            .putFloat(GlassAppearanceTintPreferenceKey, 0.25f)
            .putBoolean(GlassReduceTransparencyPreferenceKey, true)
            .putString("unrelated_setting", "keep")
            .commit()
        val controller = GlassWallpaperController(
            store = FakeGlassWallpaperStore(
                loadResult = GlassWallpaperLoadResult(bitmap = previous),
            ),
            preferences = GlassThemePreferences(context) { editor ->
                // SharedPreferences commit-to-memory succeeds before this injected failure.
                editor.commit()
                false
            },
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        controller.start()
        advanceUntilIdle()
        val before = controller.state.appearance

        assertFalse(controller.setAppearance(GlassAppearance(20f, 16f, 0.6f)))

        assertEquals(before, controller.state.appearance)
        assertEquals(
            "old-type-corruption",
            rawPreferences.getString(GlassAppearanceBlurPreferenceKey, null),
        )
        assertEquals(
            19f,
            rawPreferences.getFloat(GlassAppearanceRefractionPreferenceKey, -1f),
            0f,
        )
        assertEquals(
            0.25f,
            rawPreferences.getFloat(GlassAppearanceTintPreferenceKey, -1f),
            0f,
        )
        assertEquals(
            GlassAppearance(4f, 19f, 0.25f),
            GlassThemePreferences(context).readAppearance(),
        )
        val recreatedController = GlassWallpaperController(
            store = FakeGlassWallpaperStore(loadResult = GlassWallpaperLoadResult()),
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        assertEquals(before, recreatedController.state.appearance)
        assertTrue(rawPreferences.getBoolean(GlassReduceTransparencyPreferenceKey, false))
        assertEquals("keep", rawPreferences.getString("unrelated_setting", null))
        assertSame(previous, controller.state.bitmap)
        assertTrue(controller.state.hasWallpaper)
        assertEquals("ガラスの調整を保存できませんでした", controller.state.errorMessage)
    }

    @Test
    fun resettingAppearanceDoesNotChangeWallpaperOrReduceTransparency() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previous = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val preferences = GlassThemePreferences(context)
        assertTrue(preferences.saveAppearance(GlassAppearance(22f, 18f, 0.75f)))
        assertTrue(preferences.saveReduceTransparency(true))
        val controller = GlassWallpaperController(
            store = FakeGlassWallpaperStore(
                loadResult = GlassWallpaperLoadResult(bitmap = previous),
            ),
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        controller.start()
        advanceUntilIdle()
        assertEquals(GlassAppearance(22f, 18f, 0.75f), controller.state.appearance)

        assertTrue(controller.setAppearance(GlassAppearance()))

        assertEquals(GlassAppearance(), controller.state.appearance)
        assertSame(previous, controller.state.bitmap)
        assertTrue(controller.state.hasWallpaper)
        assertTrue(controller.state.reduceTransparency)
        assertTrue(GlassThemePreferences(context).readReduceTransparency())
    }

    @Test
    fun failedImportAndPickerCancellationRetainExistingWallpaper() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previous = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(bitmap = previous),
            importResult = GlassWallpaperImportResult(errorMessage = "読み込み失敗"),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )

        controller.start()
        advanceUntilIdle()
        controller.onPickerResult(null)
        assertSame(previous, controller.state.bitmap)

        controller.importWallpaper(Uri.parse("content://test/broken"))
        advanceUntilIdle()

        assertSame(previous, controller.state.bitmap)
        assertTrue(controller.state.hasWallpaper)
        assertEquals("読み込み失敗", controller.state.errorMessage)
    }

    @Test
    fun successfulResetReturnsToSafeDefault() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previous = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(bitmap = previous),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )

        controller.start()
        advanceUntilIdle()
        controller.resetWallpaper()
        advanceUntilIdle()

        assertFalse(controller.state.hasWallpaper)
        assertEquals(null, controller.state.bitmap)
        assertFalse(controller.state.isBusy)
        assertEquals(listOf("reset"), store.operations)
    }

    @Test
    fun staleImportAndResetCannotOverwriteNewestImport() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(),
            importResults = listOf(
                GlassWallpaperImportResult(bitmap = first),
                GlassWallpaperImportResult(bitmap = second),
            ),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        controller.start()
        advanceUntilIdle()

        // Requests are admitted before the test dispatcher runs. The path queue keeps their
        // file/state order identical instead of allowing a stale operation to commit out of order.
        controller.importWallpaper(Uri.parse("content://test/first"))
        controller.resetWallpaper()
        controller.importWallpaper(Uri.parse("content://test/second"))
        advanceUntilIdle()

        assertSame(second, controller.state.bitmap)
        assertTrue(controller.state.hasWallpaper)
        assertEquals(
            listOf(
                "import:content://test/first",
                "reset",
                "import:content://test/second",
            ),
            store.operations,
        )
        assertTrue(first !== controller.state.bitmap)
    }

    @Test
    fun laterFailedImportLeavesUiAndPrivateFileOnPreviousSuccessfulWallpaper() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previous = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(bitmap = previous),
            importResults = listOf(
                GlassWallpaperImportResult(bitmap = previous),
                GlassWallpaperImportResult(errorMessage = "選択した画像を読み込めませんでした"),
            ),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        controller.start()
        advanceUntilIdle()

        controller.importWallpaper(Uri.parse("content://test/valid"))
        controller.importWallpaper(Uri.parse("content://test/broken"))
        advanceUntilIdle()

        assertSame(previous, controller.state.bitmap)
        assertSame(previous, store.privateBitmap)
        assertEquals("選択した画像を読み込めませんでした", controller.state.errorMessage)
    }

    @Test
    fun inFlightImportThenFailureKeepsUiAndCanonicalResultInAgreement() {
        val store = BlockingImportStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = scope,
            ioDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Unconfined,
        )
        try {
            controller.start()
            assertTrue(store.loadFinished.await(2, TimeUnit.SECONDS))

            controller.importWallpaper(Uri.parse("content://test/in-flight"))
            assertTrue(store.firstImportStarted.await(2, TimeUnit.SECONDS))
            // This request is admitted while B is still inside the synchronous store call. The
            // shared path queue must wait for B's commit before observing C's failure.
            controller.importWallpaper(Uri.parse("content://test/failure"))
            store.releaseFirstImport.countDown()

            assertTrue(store.secondImportFinished.await(2, TimeUnit.SECONDS))
            assertTrue(awaitCondition { !controller.state.isBusy })
            assertSame(store.firstBitmap, controller.state.bitmap)
            assertSame(store.firstBitmap, store.privateBitmap)
            assertEquals("選択した画像を読み込めませんでした", controller.state.errorMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recreatedControllerLoadsAfterOlderControllerCommit() {
        val store = BlockingImportStore()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldController = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = oldScope,
            ioDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Unconfined,
        )
        val newController = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = newScope,
            ioDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Unconfined,
        )
        try {
            oldController.start()
            assertTrue(store.loadFinished.await(2, TimeUnit.SECONDS))
            oldController.importWallpaper(Uri.parse("content://test/recreated"))
            assertTrue(store.firstImportStarted.await(2, TimeUnit.SECONDS))

            // Disposal cancels the old controller, but its synchronous import is allowed to finish
            // safely. The recreated controller's load is queued behind that canonical commit.
            oldController.invalidatePendingWork()
            newController.start()
            store.releaseFirstImport.countDown()

            assertTrue(store.recreatedLoadFinished.await(2, TimeUnit.SECONDS))
            // The latch is released from the store call before the controller resumes and
            // publishes its result on the configured main dispatcher.
            assertTrue(awaitCondition { newController.state.bitmap === store.firstBitmap })
            assertSame(store.firstBitmap, newController.state.bitmap)
            assertTrue(newController.state.hasWallpaper)
        } finally {
            oldScope.cancel()
            newScope.cancel()
        }
    }

    @Test
    fun admittedRequestsStayFifoWhenIoTasksAreStartedInReverseOrder() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(),
            importResults = listOf(
                GlassWallpaperImportResult(bitmap = first),
                GlassWallpaperImportResult(bitmap = second),
            ),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = scope,
            ioDispatcher = dispatcher,
            mainDispatcher = Dispatchers.Unconfined,
        )
        try {
            controller.start()
            dispatcher.runNext()

            controller.importWallpaper(Uri.parse("content://test/first"))
            controller.importWallpaper(Uri.parse("content://test/second"))
            val firstTask = dispatcher.take()
            val secondTask = dispatcher.take()

            // Let the later launch reach the queue first. It must suspend behind the earlier
            // admission instead of changing the canonical file order.
            secondTask.run()
            firstTask.run()
            dispatcher.runUntilIdle()

            assertEquals(
                listOf(
                    "import:content://test/first",
                    "import:content://test/second",
                ),
                store.operations,
            )
            assertSame(second, controller.state.bitmap)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nextImportCannotPassAnEarlierPendingUiPublication() {
        val mainDispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(),
            importResults = listOf(
                GlassWallpaperImportResult(bitmap = first),
                GlassWallpaperImportResult(bitmap = second),
            ),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = mainDispatcher,
        )
        try {
            controller.start()
            mainDispatcher.runNext()

            controller.importWallpaper(Uri.parse("content://test/first-ui"))
            controller.importWallpaper(Uri.parse("content://test/second-ui"))

            // The first import has already committed its store result, but its UI publication is
            // deliberately delayed. The second store call must remain behind that publication.
            assertEquals(listOf("import:content://test/first-ui"), store.operations)

            // Reverse pending UI work when possible. With the fixed reservation lifetime only A
            // is pending here; completing it admits B, which then queues its own publication.
            mainDispatcher.runLast()
            mainDispatcher.runNext()

            assertEquals(
                listOf("import:content://test/first-ui", "import:content://test/second-ui"),
                store.operations,
            )
            assertSame(second, controller.state.bitmap)
            assertFalse(controller.state.isBusy)
        } finally {
            scope.cancel()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun androidStoreNormalizesExifBoundsAndRemovesOrphanedTemporaryFiles() {
        val provider = Robolectric.setupContentProvider(
            GlassWallpaperImageProvider::class.java,
            GlassWallpaperImageProvider.AUTHORITY,
        )
        val directory = newTemporaryDirectory()
        val source = File(directory, "source.jpg")
        writeJpeg(source, width = 2, height = 3)
        ExifInterface(source.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        provider.sourceFile = source
        File(directory, "glass-source-orphan.image").writeText("orphan")
        File(directory, "glass-wallpaper-orphan.tmp").writeText("orphan")

        val store = AndroidGlassWallpaperStore(
            context = context,
            filesDirectory = directory,
            contentResolver = context.contentResolver,
        )
        val imported = store.importWallpaper(
            Uri.parse("content://${GlassWallpaperImageProvider.AUTHORITY}/image"),
        )

        assertTrue(imported.succeeded)
        assertEquals(3, imported.bitmap!!.width)
        assertEquals(2, imported.bitmap!!.height)
        assertFalse(File(directory, "glass-source-orphan.image").exists())
        assertFalse(File(directory, "glass-wallpaper-orphan.tmp").exists())
        assertTrue(store.wallpaperFile.isFile)
        assertTrue(maxOf(imported.bitmap!!.width, imported.bitmap!!.height) <= GlassWallpaperMaxLongEdge)

        val persisted = store.load().bitmap
        assertNotNull(persisted)
        assertEquals(3, persisted!!.width)
        assertEquals(2, persisted.height)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun androidStoreDownsamplesLongEdgeAndFailureRetainsPreviousCanonicalFile() {
        val provider = Robolectric.setupContentProvider(
            GlassWallpaperImageProvider::class.java,
            GlassWallpaperImageProvider.AUTHORITY,
        )
        val directory = newTemporaryDirectory()
        val firstSource = File(directory, "first.jpg")
        writeJpeg(firstSource, width = 5000, height = 2)
        provider.sourceFile = firstSource
        val store = AndroidGlassWallpaperStore(
            context = context,
            filesDirectory = directory,
            contentResolver = context.contentResolver,
        )
        val first = store.importWallpaper(
            Uri.parse("content://${GlassWallpaperImageProvider.AUTHORITY}/first"),
        )
        assertTrue(first.succeeded)
        assertTrue(maxOf(first.bitmap!!.width, first.bitmap!!.height) <= GlassWallpaperMaxLongEdge)
        val canonicalBytes = store.wallpaperFile.readBytes()

        val broken = File(directory, "broken.jpg").apply { writeText("not an image") }
        provider.sourceFile = broken
        val failed = store.importWallpaper(
            Uri.parse("content://${GlassWallpaperImageProvider.AUTHORITY}/broken"),
        )

        assertFalse(failed.succeeded)
        assertArrayEquals(canonicalBytes, store.wallpaperFile.readBytes())
        val persisted = store.load().bitmap
        assertNotNull(persisted)
        assertTrue(maxOf(persisted!!.width, persisted.height) <= GlassWallpaperMaxLongEdge)
        assertTrue(
            directory.listFiles().orEmpty().none {
                it.name.startsWith("glass-source-") || it.name.startsWith("glass-wallpaper-")
            },
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun androidStoreCorruptCanonicalAssetFallsBackSafely() {
        val directory = newTemporaryDirectory()
        val store = AndroidGlassWallpaperStore(
            context = context,
            filesDirectory = directory,
            contentResolver = context.contentResolver,
        )
        store.wallpaperFile.writeText("corrupt")

        val loaded = store.load()

        assertNull(loaded.bitmap)
        assertNotNull(loaded.errorMessage)
    }

    @Test
    fun lifecycleInvalidationStopsPendingLoad() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val store = FakeGlassWallpaperStore(
            loadResult = GlassWallpaperLoadResult(bitmap = loaded),
        )
        val controller = GlassWallpaperController(
            store = store,
            preferences = GlassThemePreferences(context),
            scope = this,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )

        controller.start()
        controller.invalidatePendingWork()
        advanceUntilIdle()

        assertTrue(controller.state.isLoading)
        assertEquals(null, controller.state.bitmap)
    }

    private fun newTemporaryDirectory(): File = File.createTempFile(
        "glass-wallpaper-test-",
        "",
    ).also { file ->
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        temporaryDirectories += file
    }

    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xFF34566A.toInt())
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitCondition(
        timeoutMillis: Long = 2_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5L)
        }
        return condition()
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun take(): Runnable = tasks.poll(2, TimeUnit.SECONDS)
            ?: error("Timed out waiting for a wallpaper operation")

        fun runNext() = take().run()

        fun runLast() = (tasks.pollLast() ?: error("Timed out waiting for a wallpaper operation"))
            .run()

        fun runUntilIdle() {
            while (true) {
                val task = tasks.poll() ?: return
                task.run()
            }
        }
    }

    private class BlockingImportStore : GlassWallpaperStore {
        val firstBitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val loadFinished = CountDownLatch(1)
        val recreatedLoadFinished = CountDownLatch(1)
        val firstImportStarted = CountDownLatch(1)
        val releaseFirstImport = CountDownLatch(1)
        val secondImportFinished = CountDownLatch(1)
        private val loadCount = AtomicInteger()
        private val importCount = AtomicInteger()

        @Volatile
        var privateBitmap: Bitmap? = null

        override fun load(): GlassWallpaperLoadResult {
            val result = GlassWallpaperLoadResult(bitmap = privateBitmap)
            if (loadCount.incrementAndGet() == 1) {
                loadFinished.countDown()
            } else {
                recreatedLoadFinished.countDown()
            }
            return result
        }

        override fun importWallpaper(uri: Uri): GlassWallpaperImportResult {
            return if (importCount.getAndIncrement() == 0) {
                firstImportStarted.countDown()
                try {
                    releaseFirstImport.await(2, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return GlassWallpaperImportResult(errorMessage = "中断")
                }
                privateBitmap = firstBitmap
                GlassWallpaperImportResult(bitmap = firstBitmap)
            } else {
                secondImportFinished.countDown()
                GlassWallpaperImportResult(errorMessage = "選択した画像を読み込めませんでした")
            }
        }

        override fun reset(): Boolean {
            privateBitmap = null
            return true
        }
    }

    /** A provider-backed stream keeps AndroidGlassWallpaperStore tests on the real resolver path. */
    class GlassWallpaperImageProvider : ContentProvider() {
        var sourceFile: File? = null
        var failOpen: Boolean = false

        override fun onCreate(): Boolean {
            lastProvider = this
            return true
        }

        @Throws(FileNotFoundException::class)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw FileNotFoundException("test provider failure")
            return ParcelFileDescriptor.open(
                sourceFile ?: throw FileNotFoundException("missing test source"),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "fiilda.glass.wallpaper.test"
            var lastProvider: GlassWallpaperImageProvider? = null
        }
    }

    private class FakeGlassWallpaperStore(
        private val loadResult: GlassWallpaperLoadResult,
        var importResult: GlassWallpaperImportResult = GlassWallpaperImportResult(
            errorMessage = "未設定",
        ),
        var resetResult: Boolean = true,
        private val importResults: List<GlassWallpaperImportResult> = emptyList(),
    ) : GlassWallpaperStore {
        val operations = mutableListOf<String>()
        var privateBitmap: Bitmap? = loadResult.bitmap
        private var importIndex = 0

        override fun load(): GlassWallpaperLoadResult = loadResult

        override fun importWallpaper(uri: Uri): GlassWallpaperImportResult {
            operations += "import:$uri"
            val result = importResults.getOrNull(importIndex++) ?: importResult
            if (result.succeeded) privateBitmap = result.bitmap
            return result
        }

        override fun reset(): Boolean {
            operations += "reset"
            if (resetResult) privateBitmap = null
            return resetResult
        }
    }
}
