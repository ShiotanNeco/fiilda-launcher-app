package com.fiilda.launcher

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.glasslab.glass.GlassBackdrop
import dev.glasslab.glass.GlassSurface
import dev.glasslab.glass.rememberGlassBackdrop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** The largest decoded wallpaper edge accepted by the launcher. */
internal const val GlassWallpaperMaxLongEdge = 3840

internal const val GlassThemePreferencesName = "fiilda_glass_preferences"
internal const val GlassReduceTransparencyPreferenceKey = "reduce_transparency"
internal const val GlassAppearanceBlurPreferenceKey = "appearance_blur_dp"
internal const val GlassAppearanceRefractionPreferenceKey = "appearance_refraction_dp"
internal const val GlassAppearanceTintPreferenceKey = "appearance_tint_strength"
internal const val GlassWallpaperFileName = "glass_wallpaper.png"

/** State shared by the Glass background and the settings preview. */
internal data class GlassWallpaperState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val hasWallpaper: Boolean = false,
    val errorMessage: String? = null,
    val reduceTransparency: Boolean = false,
    val appearance: GlassAppearance = GlassAppearance(),
)

internal data class GlassWallpaperLoadResult(
    val bitmap: Bitmap? = null,
    val errorMessage: String? = null,
)

internal data class GlassWallpaperImportResult(
    val bitmap: Bitmap? = null,
    val errorMessage: String? = null,
) {
    val succeeded: Boolean
        get() = bitmap != null && errorMessage == null
}

/** File/content operations are isolated so lifecycle and race behavior can be tested without UI. */
internal interface GlassWallpaperStore {
    /** Controllers sharing this key are serialized across composition/activity instances. */
    val operationKey: Any
        get() = this

    fun load(): GlassWallpaperLoadResult
    fun importWallpaper(uri: Uri): GlassWallpaperImportResult
    fun reset(): Boolean
}

/**
 * Admission is synchronous, while the actual file work remains on the caller's IO dispatcher.
 * A per-path queue is process-wide so an old composition cannot commit a file after a recreated
 * controller has already loaded the previous canonical asset. Reservations are assigned before a
 * coroutine is launched; cancellation releases the reservation even if the coroutine never starts.
 */
private object GlassWallpaperOperationCoordinator {
    private val queues = ConcurrentHashMap<Any, GlassWallpaperPathQueue>()

    fun reserve(key: Any): GlassWallpaperOperationReservation =
        queues.computeIfAbsent(key) { GlassWallpaperPathQueue() }.reserve()
}

private class GlassWallpaperPathQueue {
    private val monitor = Any()
    private var nextTicket = 0L
    private var servingTicket = 0L
    private val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val completed = mutableSetOf<Long>()

    fun reserve(): GlassWallpaperOperationReservation = synchronized(monitor) {
        val ticket = nextTicket++
        val gate = CompletableDeferred<Unit>()
        gates[ticket] = gate
        if (ticket == servingTicket) gate.complete(Unit)
        GlassWallpaperOperationReservation(this, ticket, gate)
    }

    fun complete(ticket: Long) = synchronized(monitor) {
        if (!completed.add(ticket)) return@synchronized
        // A canceled queued ticket is marked complete and skipped when the current ticket
        // finishes. This prevents a canceled old controller from blocking a new one forever.
        while (completed.remove(servingTicket)) {
            gates.remove(servingTicket)
            servingTicket++
        }
        gates[servingTicket]?.complete(Unit)
    }
}

private class GlassWallpaperOperationReservation internal constructor(
    private val queue: GlassWallpaperPathQueue,
    private val ticket: Long,
    private val gate: CompletableDeferred<Unit>,
) {
    private val finished = AtomicBoolean(false)

    suspend fun <T> runIfActive(isActive: () -> Boolean, block: () -> T): T? {
        gate.await()
        if (!isActive()) return null
        currentCoroutineContext().ensureActive()
        // Keep the reservation until launchReserved completes. Callers publish the result after
        // this method returns; releasing here would let the next store operation commit and
        // publish first, after which this older result could overwrite the UI state.
        return block()
    }

    /** Releases a reservation if its coroutine was canceled before entering its body. */
    fun cancel() = finish()

    private fun finish() {
        if (finished.compareAndSet(false, true)) queue.complete(ticket)
    }
}

internal val LocalGlassWallpaperController = staticCompositionLocalOf<GlassWallpaperController?> {
    null
}

/** Only the Glass settings use this preference file; photo/search URI grants remain untouched. */
internal class GlassThemePreferences(
    context: Context,
    private val commitEditor: (android.content.SharedPreferences.Editor) -> Boolean =
        { editor -> editor.commit() },
) {
    private data class StoredPreference(
        val present: Boolean,
        val value: Any?,
    )

    private val appearancePreferenceKeys = listOf(
        GlassAppearanceBlurPreferenceKey,
        GlassAppearanceRefractionPreferenceKey,
        GlassAppearanceTintPreferenceKey,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        GlassThemePreferencesName,
        Context.MODE_PRIVATE,
    )

    fun readReduceTransparency(): Boolean = preferences.getBoolean(
        GlassReduceTransparencyPreferenceKey,
        false,
    )

    fun saveReduceTransparency(enabled: Boolean): Boolean = runCatching {
        commitEditor(
            preferences.edit()
            .putBoolean(GlassReduceTransparencyPreferenceKey, enabled)
        )
    }.getOrDefault(false)

    fun readAppearance(): GlassAppearance {
        val defaults = GlassAppearance()
        return GlassAppearance(
            blurDp = readFloatOrDefault(GlassAppearanceBlurPreferenceKey, defaults.blurDp),
            refractionDp = readFloatOrDefault(
                GlassAppearanceRefractionPreferenceKey,
                defaults.refractionDp,
            ),
            tintStrength = readFloatOrDefault(
                GlassAppearanceTintPreferenceKey,
                defaults.tintStrength,
            ),
        ).normalized()
    }

    fun saveAppearance(appearance: GlassAppearance): Boolean {
        val normalized = appearance.normalized()
        val previous = snapshotAppearancePreferences()
        return try {
            val committed = commitEditor(
                preferences.edit()
                    .putFloat(GlassAppearanceBlurPreferenceKey, normalized.blurDp)
                    .putFloat(GlassAppearanceRefractionPreferenceKey, normalized.refractionDp)
                    .putFloat(GlassAppearanceTintPreferenceKey, normalized.tintStrength),
            )
            if (!committed) restoreAppearancePreferences(previous)
            committed
        } catch (_: Exception) {
            // A custom/test editor or a platform failure may mutate SharedPreferences memory
            // before throwing. Restore the old values even when the write reports an exception.
            restoreAppearancePreferences(previous)
            false
        }
    }

    private fun snapshotAppearancePreferences(): Map<String, StoredPreference> =
        appearancePreferenceKeys.associateWith { key ->
            StoredPreference(
                present = preferences.contains(key),
                value = preferences.all[key],
            )
        }

    private fun restoreAppearancePreferences(previous: Map<String, StoredPreference>) {
        runCatching {
            val editor = preferences.edit()
            previous.forEach { (key, stored) ->
                if (!stored.present) {
                    editor.remove(key)
                } else {
                    when (val value = stored.value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(
                            key,
                            value.filterIsInstance<String>().toSet(),
                        )
                        else -> editor.remove(key)
                    }
                }
            }
            // Use the platform editor directly: rollback must repair commit-to-memory even when
            // the injected commitEditor deliberately returns false after committing.
            editor.commit()
        }
    }

    private fun readFloatOrDefault(key: String, default: Float): Float = runCatching {
        preferences.getFloat(key, default)
    }.getOrDefault(default)
}

internal class GlassWallpaperController(
    private val store: GlassWallpaperStore,
    private val preferences: GlassThemePreferences,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val stateHolder = mutableStateOf(
        GlassWallpaperState(
            reduceTransparency = preferences.readReduceTransparency(),
            appearance = preferences.readAppearance(),
        ),
    )
    private var started = false
    @Volatile
    private var acceptingRequests = true
    private var pickerLauncher: (() -> Unit)? = null
    private var loadJob: Job? = null
    private var pendingOperations = 0
    private val operationJobs = Collections.synchronizedSet(mutableSetOf<Job>())

    internal val state: GlassWallpaperState
        get() = stateHolder.value

    internal fun start() {
        if (started || !acceptingRequests) return
        started = true
        val reservation = GlassWallpaperOperationCoordinator.reserve(store.operationKey)
        loadJob = launchReserved(reservation) {
            val result = reservation.runIfActive({ acceptingRequests }) {
                try {
                    store.load()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    GlassWallpaperLoadResult(errorMessage = tr("壁紙を読み込めませんでした", "Couldn't load the wallpaper"))
                }
            } ?: return@launchReserved
            if (!acceptingRequests) return@launchReserved
            withContext(mainDispatcher) {
                if (!acceptingRequests) return@withContext
                stateHolder.value = stateHolder.value.copy(
                    bitmap = result.bitmap,
                    isLoading = false,
                    hasWallpaper = result.bitmap != null,
                    errorMessage = result.errorMessage,
                )
            }
        }
    }

    internal fun setPickerLauncher(launch: () -> Unit) {
        pickerLauncher = launch
    }

    internal fun openWallpaperPicker() {
        pickerLauncher?.invoke()
    }

    internal fun onPickerResult(uri: Uri?) {
        // Photo Picker cancellation is intentionally a no-op: the old bitmap and file remain.
        if (uri == null) return
        importWallpaper(uri)
    }

    internal fun importWallpaper(uri: Uri) {
        if (!acceptingRequests) return
        val reservation = GlassWallpaperOperationCoordinator.reserve(store.operationKey)
        pendingOperations++
        stateHolder.value = stateHolder.value.copy(isBusy = true, errorMessage = null)
        launchReserved(reservation) {
            val result = reservation.runIfActive({ acceptingRequests }) {
                try {
                    store.importWallpaper(uri)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    GlassWallpaperImportResult(errorMessage = tr("壁紙を保存できませんでした", "Couldn't save the wallpaper"))
                }
            } ?: return@launchReserved
            if (!acceptingRequests) return@launchReserved
            withContext(mainDispatcher) {
                if (!acceptingRequests) return@withContext
                pendingOperations = (pendingOperations - 1).coerceAtLeast(0)
                if (result.succeeded) {
                    stateHolder.value = stateHolder.value.copy(
                        bitmap = result.bitmap,
                        isLoading = false,
                        isBusy = pendingOperations > 0,
                        hasWallpaper = true,
                        errorMessage = null,
                    )
                } else {
                    // The path queue commits earlier operations before this result is observed.
                    // A failed later import therefore leaves both UI and canonical file unchanged.
                    stateHolder.value = stateHolder.value.copy(
                        isLoading = false,
                        isBusy = pendingOperations > 0,
                        errorMessage = result.errorMessage ?: tr("壁紙を保存できませんでした", "Couldn't save the wallpaper"),
                    )
                }
            }
        }
    }

    internal fun resetWallpaper() {
        if (!acceptingRequests) return
        val reservation = GlassWallpaperOperationCoordinator.reserve(store.operationKey)
        pendingOperations++
        stateHolder.value = stateHolder.value.copy(isBusy = true, errorMessage = null)
        launchReserved(reservation) {
            val succeeded = reservation.runIfActive({ acceptingRequests }) {
                try {
                    store.reset()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
            } ?: return@launchReserved
            if (!acceptingRequests) return@launchReserved
            withContext(mainDispatcher) {
                if (!acceptingRequests) return@withContext
                pendingOperations = (pendingOperations - 1).coerceAtLeast(0)
                if (succeeded) {
                    stateHolder.value = stateHolder.value.copy(
                        bitmap = null,
                        isLoading = false,
                        isBusy = pendingOperations > 0,
                        hasWallpaper = false,
                        errorMessage = null,
                    )
                } else {
                    stateHolder.value = stateHolder.value.copy(
                        isLoading = false,
                        isBusy = pendingOperations > 0,
                        errorMessage = tr("壁紙を削除できませんでした", "Couldn't remove the wallpaper"),
                    )
                }
            }
        }
    }

    internal fun setReduceTransparency(enabled: Boolean): Boolean {
        if (enabled == stateHolder.value.reduceTransparency) return true
        if (!preferences.saveReduceTransparency(enabled)) {
            stateHolder.value = stateHolder.value.copy(
                errorMessage = tr("透明効果の設定を保存できませんでした", "Couldn't save the transparency setting"),
            )
            return false
        }
        stateHolder.value = stateHolder.value.copy(
            reduceTransparency = enabled,
            errorMessage = null,
        )
        return true
    }

    internal fun setAppearance(request: GlassAppearance): Boolean {
        val normalized = request.normalized()
        if (!preferences.saveAppearance(normalized)) {
            stateHolder.value = stateHolder.value.copy(
                errorMessage = tr("ガラスの調整を保存できませんでした", "Couldn't save the glass settings"),
            )
            return false
        }
        stateHolder.value = stateHolder.value.copy(
            appearance = normalized,
            errorMessage = null,
        )
        return true
    }

    /** Invalidates in-flight work when the composition owner is disposed. */
    internal fun invalidatePendingWork() {
        acceptingRequests = false
        loadJob?.cancel()
        pickerLauncher = null
        val jobs = synchronized(operationJobs) { operationJobs.toList() }
        jobs.forEach { it.cancel() }
    }

    private fun launchReserved(
        reservation: GlassWallpaperOperationReservation,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(ioDispatcher) { block() }
        operationJobs += job
        // A canceled parent may prevent the coroutine body from starting, so release the ticket
        // from completion as well as from the reservation's normal finally block.
        job.invokeOnCompletion {
            reservation.cancel()
            operationJobs.remove(job)
        }
        return job
    }
}

internal class AndroidGlassWallpaperStore(
    context: Context,
    private val filesDirectory: File = context.applicationContext.filesDir,
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver,
) : GlassWallpaperStore {
    override val operationKey: Any by lazy {
        runCatching { wallpaperFile.canonicalPath }
            .getOrDefault(wallpaperFile.absolutePath)
    }

    internal val wallpaperFile: File
        get() = File(filesDirectory, GlassWallpaperFileName)

    override fun load(): GlassWallpaperLoadResult {
        val file = wallpaperFile
        cleanupOrphanedTempFiles(file.parentFile)
        if (!file.isFile || file.length() <= 0L) return GlassWallpaperLoadResult()
        return runCatching {
            decodeNormalizedBitmap(file, strictExif = false)?.let { GlassWallpaperLoadResult(bitmap = it) }
                ?: GlassWallpaperLoadResult(errorMessage = tr("保存済みの壁紙を読み込めませんでした", "Couldn't load the saved wallpaper"))
        }.getOrElse {
            GlassWallpaperLoadResult(errorMessage = tr("保存済みの壁紙を読み込めませんでした", "Couldn't load the saved wallpaper"))
        }
    }

    override fun importWallpaper(uri: Uri): GlassWallpaperImportResult {
        val parent = filesDirectory
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            return GlassWallpaperImportResult(errorMessage = tr("壁紙の保存先を用意できませんでした", "Couldn't prepare storage for the wallpaper"))
        }
        cleanupOrphanedTempFiles(parent)
        val source = runCatching {
            File.createTempFile("glass-source-", ".image", parent)
        }.getOrElse {
            return GlassWallpaperImportResult(errorMessage = tr("壁紙を読み込めませんでした", "Couldn't load the wallpaper"))
        }
        var normalizedFile: File? = null
        return try {
            val copied = runCatching {
                val input = contentResolver.openInputStream(uri) ?: return@runCatching false
                input.use { sourceInput ->
                    FileOutputStream(source).use { output ->
                        sourceInput.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)
            if (!copied || source.length() <= 0L) {
                GlassWallpaperImportResult(errorMessage = tr("選択した画像を読み込めませんでした", "Couldn't load the selected image"))
            } else {
                val normalized = decodeNormalizedBitmap(source, strictExif = true)
                    ?: return GlassWallpaperImportResult(errorMessage = tr("選択した画像を読み込めませんでした", "Couldn't load the selected image"))
                normalizedFile = File.createTempFile("glass-wallpaper-", ".tmp", parent)
                writeBitmap(normalized, normalizedFile!!)
                atomicReplace(normalizedFile!!, wallpaperFile)
                // The same normalized bitmap is already orientation-corrected and bounded; there
                // is no second decode on the UI thread after the atomic swap.
                GlassWallpaperImportResult(bitmap = normalized)
            }
        } catch (_: OutOfMemoryError) {
            GlassWallpaperImportResult(errorMessage = tr("画像が大きすぎて壁紙にできませんでした", "The image is too large to use as a wallpaper"))
        } catch (_: Exception) {
            GlassWallpaperImportResult(errorMessage = tr("壁紙を保存できませんでした", "Couldn't save the wallpaper"))
        } finally {
            source.delete()
            normalizedFile?.takeIf { it.exists() }?.delete()
        }
    }

    override fun reset(): Boolean {
        val file = wallpaperFile
        cleanupOrphanedTempFiles(file.parentFile)
        return !file.exists() || file.delete()
    }

    private fun writeBitmap(bitmap: Bitmap, destination: File) {
        FileOutputStream(destination).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("bitmap compression failed")
            }
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Android's app-internal filesystems normally support rename replacement. This
            // fallback still moves only a fully flushed temp file and never writes the canonical
            // path incrementally.
            if (!source.renameTo(destination)) {
                throw IOException("atomic wallpaper rename failed")
            }
        } catch (_: UnsupportedOperationException) {
            if (!source.renameTo(destination)) {
                throw IOException("atomic wallpaper rename failed")
            }
        } catch (_: FileAlreadyExistsException) {
            // A provider may reject REPLACE_EXISTING together with ATOMIC_MOVE even though it
            // supports an atomic same-directory rename. Retry the replacement only after the
            // fully written temporary file is ready.
            if (!source.renameTo(destination)) {
                throw IOException("atomic wallpaper rename failed")
            }
        }
    }

    private fun cleanupOrphanedTempFiles(parent: File?) {
        parent ?: return
        parent.listFiles { _, name ->
            name.startsWith("glass-source-") || name.startsWith("glass-wallpaper-")
        }?.forEach { candidate ->
            runCatching { candidate.delete() }
        }
    }
}

private fun decodeNormalizedBitmap(file: File, strictExif: Boolean): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        maxOf(bounds.outWidth, bounds.outHeight).toDouble() / sampleSize >
            GlassWallpaperMaxLongEdge
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    val orientation = if (strictExif) {
        runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrElse {
            decoded.recycle()
            return null
        }
    } else {
        runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }
    val transform = photoFrameExifTransform(orientation)
    if (transform.rotationDegrees != 0 || transform.mirrorHorizontal) {
        val matrix = Matrix().apply {
            setRotate(transform.rotationDegrees.toFloat())
            if (transform.mirrorHorizontal) postScale(-1f, 1f)
        }
        val transformed = runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrNull()
        if (transformed != null && transformed !== decoded) {
            decoded.recycle()
            decoded = transformed
        }
    }
    val longEdge = maxOf(decoded.width, decoded.height)
    if (longEdge > GlassWallpaperMaxLongEdge) {
        val scale = GlassWallpaperMaxLongEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) {
            decoded.recycle()
            decoded = scaled
        }
    }
    return decoded
}

private val GlassWallpaperDefaultBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1E3144),
        Color(0xFF3E6177),
        Color(0xFF182838),
    ),
)

/** Draws the selected wallpaper with center/crop; the default is a broad blue-gray color field. */
@Composable
internal fun GlassWallpaper(modifier: Modifier = Modifier) {
    val bitmap = LocalGlassWallpaperController.current?.state?.bitmap
    Box(
        modifier = modifier
            .clipToBounds()
            .background(GlassWallpaperDefaultBrush),
    ) {
        if (bitmap == null) {
            Canvas(Modifier.fillMaxSize()) {
                val longestEdge = maxOf(size.width, size.height)
                // Broad, static fields give the renderer a visible edge to refract while keeping
                // the fallback quiet enough for white launcher labels. User images bypass these
                // fields completely.
                drawCircle(
                    color = Color(0xFF6E9BB1).copy(alpha = 0.46f),
                    radius = longestEdge * 0.62f,
                    center = Offset(size.width * 0.08f, size.height * 0.22f),
                )
                drawOval(
                    color = Color(0xFF102235).copy(alpha = 0.72f),
                    topLeft = Offset(-size.width * 0.22f, size.height * 0.46f),
                    size = androidx.compose.ui.geometry.Size(
                        width = size.width * 1.46f,
                        height = size.height * 0.48f,
                    ),
                )
                rotate(degrees = -15f, pivot = Offset(size.width * 0.68f, size.height * 0.62f)) {
                    drawRect(
                        color = Color(0xFF91B7C5).copy(alpha = 0.24f),
                        topLeft = Offset(size.width * 0.24f, size.height * 0.18f),
                        size = androidx.compose.ui.geometry.Size(
                            width = size.width * 0.98f,
                            height = size.height * 0.22f,
                        ),
                    )
                }
            }
        }
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
        }
    }
}

/** Settings content is kept under the system Material 3 boundary and only appears for Glass. */
@Composable
internal fun GlassThemeSettings() {
    if (LocalLauncherTheme.current != LauncherTheme.GLASS) return
    val controller = LocalGlassWallpaperController.current ?: return
    val state = controller.state
    var draftAppearance by remember(state.appearance) {
        mutableStateOf(state.appearance)
    }
    val previewBackdrop = rememberGlassBackdrop()
    val previewStyle = launcherGlassStyle(
        appearance = draftAppearance,
        reduceTransparency = state.reduceTransparency,
        highContrast = rememberHighContrastTextEnabled(),
    )
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val refractionSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun commitAppearance() {
        if (!controller.setAppearance(draftAppearance)) {
            draftAppearance = controller.state.appearance
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 196.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Keep the optical sample as a sibling of its source backdrop so it cannot capture
            // itself into the preview.
            Box(modifier = Modifier.fillMaxSize()) {
                GlassBackdrop(
                    state = previewBackdrop,
                    modifier = Modifier.fillMaxSize(),
                    contentVersion = state.bitmap ?: "default-wallpaper",
                ) {
                    GlassWallpaper(Modifier.fillMaxSize())
                }
                GlassSurface(
                    backdrop = previewBackdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(128.dp)
                        .align(Alignment.Center),
                    style = previewStyle,
                    cornerRadius = 20.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Canvas(Modifier.size(30.dp)) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.92f),
                                radius = size.minDimension * 0.30f,
                                center = Offset(size.width * 0.38f, size.height * 0.40f),
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.72f),
                                radius = size.minDimension * 0.18f,
                                center = Offset(size.width * 0.68f, size.height * 0.66f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = tr("ガラス", "Glass"),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = tr("プレビュー", "Preview"),
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (state.isLoading || state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                )
            }
        }
        Text(
            text = if (state.hasWallpaper) tr("選択中の壁紙", "Selected wallpaper") else tr("標準の青灰色フィールド", "Default blue-grey field"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = controller::openWallpaperPicker,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("壁紙を選ぶ", "Choose wallpaper"))
            }
            OutlinedButton(
                onClick = controller::resetWallpaper,
                // Keep reset available for a missing/corrupt canonical file so the user can
                // remove the failed asset and return to the safe code-drawn fallback.
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("標準に戻す", "Reset to default"))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.reduceTransparency,
                    role = Role.Switch,
                    onValueChange = { enabled -> controller.setReduceTransparency(enabled) },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = tr("透明効果を抑える", "Reduce transparency")
                    stateDescription = if (state.reduceTransparency) tr("オン", "On") else tr("オフ", "Off")
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tr("透明効果を抑える", "Reduce transparency"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("壁紙への依存を抑え、読みやすさを優先します", "Relies less on the wallpaper and favors readability"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.reduceTransparency,
                onCheckedChange = null,
            )
        }
        if (state.reduceTransparency) {
            Text(
                text = tr("透明効果を抑えている間も、ガラスの調整値は保持されます。", "Your glass settings are kept while transparency is reduced."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = tr("ガラスの調整", "Glass settings"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = tr("壁紙の上に重ねるガラスの見え方を調整します", "Adjust how the glass looks over the wallpaper"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassAppearanceSlider(
            label = tr("ぼかし", "Blur"),
            value = draftAppearance.blurDp,
            valueLabel = "${draftAppearance.blurDp.roundToInt()}dp",
            valueRange = 0f..48f,
            steps = 47,
            enabled = blurSupported,
            onValueChange = { draftAppearance = draftAppearance.copy(blurDp = it) },
            onValueChangeFinished = ::commitAppearance,
        )
        if (!blurSupported) {
            Text(
                text = tr("ぼかしは Android 12 以降で適用されます。設定値は保持されます。", "Blur applies on Android 12 and later. The value is kept."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassAppearanceSlider(
            label = tr("屈折の強さ", "Refraction"),
            value = draftAppearance.refractionDp,
            valueLabel = "${draftAppearance.refractionDp.roundToInt()}dp",
            valueRange = 0f..32f,
            steps = 31,
            enabled = refractionSupported,
            onValueChange = { draftAppearance = draftAppearance.copy(refractionDp = it) },
            onValueChangeFinished = ::commitAppearance,
        )
        if (!refractionSupported) {
            Text(
                text = tr("屈折は Android 13 以降で適用されます。設定値は保持されます。", "Refraction applies on Android 13 and later. The value is kept."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassAppearanceSlider(
            label = tr("ガラスの濃さ", "Glass tint"),
            value = draftAppearance.tintStrength,
            valueLabel = "${(draftAppearance.tintStrength * 100f).roundToInt()}%",
            valueRange = 0f..1f,
            steps = 99,
            enabled = true,
            onValueChange = { draftAppearance = draftAppearance.copy(tintStrength = it) },
            onValueChangeFinished = ::commitAppearance,
        )
        OutlinedButton(
            onClick = {
                draftAppearance = GlassAppearance()
                commitAppearance()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(tr("ガラスの調整をリセット", "Reset glass settings"))
        }
        state.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun GlassAppearanceSlider(
    label: String,
    value: Float,
    valueLabel: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueLabel
            },
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
internal fun rememberGlassWallpaperController(context: Context): GlassWallpaperController {
    val scope = rememberCoroutineScope()
    val controller = remember(context.applicationContext) {
        GlassWallpaperController(
            store = AndroidGlassWallpaperStore(context.applicationContext),
            preferences = GlassThemePreferences(context.applicationContext),
            scope = scope,
        )
    }
    LaunchedEffect(controller) {
        controller.start()
    }
    DisposableEffect(controller) {
        onDispose { controller.invalidatePendingWork() }
    }
    return controller
}

@Composable
internal fun RegisterGlassWallpaperPicker(controller: GlassWallpaperController) {
    val currentController by rememberUpdatedState(controller)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        currentController.onPickerResult(uri)
    }
    SideEffect {
        controller.setPickerLauncher {
            launcher.launch(PickVisualMediaRequest(ImageOnly))
        }
    }
}
