package com.fiilda.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.compositionLocalOf
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * A user-defined home tile that opens a web page. The URL, label, and optional icon are chosen by
 * the user; no request is made to the site itself, so adding a link never contacts it.
 */
internal data class WebLinkTile(
    val homeId: String,
    val url: String,
    val label: String,
    /** File name inside [webLinkIconDirectory], or null for the initial-letter fallback. */
    val iconFile: String? = null,
)

internal const val WebLinkHomeIdPrefix = "widget:weblink:"
private const val WebLinkRecordsKey = "web_link_tiles"
private const val WebLinkStoragePrefix = "v1;"
private const val WebLinkIconDirectoryName = "web_link_icons"
private const val WebLinkIconMaxPx = 256

internal fun isWebLinkHomeId(id: String): Boolean =
    id.startsWith(WebLinkHomeIdPrefix) &&
        id.length > WebLinkHomeIdPrefix.length &&
        id.removePrefix(WebLinkHomeIdPrefix).all { it.isLetterOrDigit() }

internal fun newWebLinkHomeId(): String =
    WebLinkHomeIdPrefix + UUID.randomUUID().toString().replace("-", "")

/**
 * Normalizes user input to an http(s) URL. A bare host such as `example.com/path` gains
 * `https://`; any other scheme, or a URL without a host, is rejected.
 */
internal fun normalizeWebLinkUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val schemeEnd = trimmed.indexOf("://")
    val candidate = when {
        schemeEnd < 0 -> "https://$trimmed"
        else -> trimmed
    }
    val scheme = candidate.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = candidate.substringAfter("://").substringBefore('/').substringBefore('?')
        .substringBefore('#').substringAfterLast('@').substringBefore(':')
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.')) return null
    // A single word such as "www" is almost always unfinished typing, not a reachable site.
    if ('.' !in host && host != "localhost") return null
    return "$scheme://" + candidate.substringAfter("://")
}

/** The label shown when the user leaves the name empty: the host without a leading `www.`. */
internal fun defaultWebLinkLabel(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').removePrefix("www.")

/** The character drawn when a link has no icon image. */
internal fun webLinkInitial(link: WebLinkTile): String =
    (link.label.ifBlank { defaultWebLinkLabel(link.url) })
        .trim()
        .firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()
        ?.toString()
        ?: "?"

private fun encodeField(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun decodeField(value: String): String? =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()

internal fun serializeWebLinks(links: List<WebLinkTile>): String = links
    .filter { isWebLinkHomeId(it.homeId) && normalizeWebLinkUrl(it.url) != null }
    .distinctBy { it.homeId }
    .joinToString("\n") { link ->
        WebLinkStoragePrefix + listOf(
            link.homeId,
            link.url,
            link.label,
            link.iconFile.orEmpty(),
        ).joinToString(";") { encodeField(it) }
    }

internal fun parseWebLinks(raw: String?): List<WebLinkTile> {
    if (raw.isNullOrBlank()) return emptyList()
    val seen = mutableSetOf<String>()
    return raw.lineSequence().mapNotNull { line ->
        if (!line.startsWith(WebLinkStoragePrefix)) return@mapNotNull null
        val fields = line.removePrefix(WebLinkStoragePrefix).split(';')
        if (fields.size != 4) return@mapNotNull null
        val decoded = fields.map { decodeField(it) ?: return@mapNotNull null }
        val homeId = decoded[0]
        val url = normalizeWebLinkUrl(decoded[1]) ?: return@mapNotNull null
        val iconFile = decoded[3].takeIf { it.isNotEmpty() && isSafeIconFileName(it) }
        if (!isWebLinkHomeId(homeId) || !seen.add(homeId)) return@mapNotNull null
        WebLinkTile(homeId = homeId, url = url, label = decoded[2], iconFile = iconFile)
    }.toList()
}

private fun isSafeIconFileName(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '.' || it == '_' } &&
        !name.startsWith('.')

internal fun readWebLinks(context: Context): List<WebLinkTile> = runCatching {
    parseWebLinks(
        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .getString(WebLinkRecordsKey, null),
    )
}.getOrDefault(emptyList())

internal fun saveWebLinks(context: Context, links: List<WebLinkTile>): Boolean = runCatching {
    context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(WebLinkRecordsKey, serializeWebLinks(links))
        .commit()
}.getOrDefault(false)

internal fun webLinkIconDirectory(context: Context): File =
    File(context.filesDir, WebLinkIconDirectoryName)

internal fun webLinkIconFile(context: Context, link: WebLinkTile): File? =
    link.iconFile?.let { File(webLinkIconDirectory(context), it) }?.takeIf { it.isFile }

/**
 * Copies a picked image into app storage, downscaled to at most [WebLinkIconMaxPx]. The picker's
 * temporary grant is therefore not needed after this call. Returns the new file name.
 */
internal fun importWebLinkIcon(context: Context, uri: Uri): String? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
        if (longest > WebLinkIconMaxPx) {
            val scale = WebLinkIconMaxPx.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
    val directory = webLinkIconDirectory(context).apply { mkdirs() }
    val name = "icon_${UUID.randomUUID().toString().replace("-", "")}.png"
    File(directory, name).outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    name
}.getOrNull()

/** Removes icon files that no saved link references, such as replaced or removed icons. */
internal fun deleteUnusedWebLinkIcons(context: Context, links: List<WebLinkTile>) {
    runCatching {
        val used = links.mapNotNull { it.iconFile }.toSet()
        webLinkIconDirectory(context).listFiles()?.forEach { file ->
            if (file.name !in used) file.delete()
        }
    }
}

internal fun openWebLink(context: Context, link: WebLinkTile): Boolean {
    val url = normalizeWebLinkUrl(link.url)
    val opened = url != null && try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    if (!opened) {
        Toast.makeText(context, tr("リンクを開けませんでした", "Couldn't open the link"), Toast.LENGTH_SHORT).show()
    }
    return opened
}

/** Saved web links, provided at the launcher root so every home surface can resolve their IDs. */
internal val LocalWebLinkTiles = compositionLocalOf { emptyList<WebLinkTile>() }

/** An open add/edit sheet: [existing] is null when adding a new link to [targetHomePage]. */
internal data class WebLinkEditorRequest(
    val existing: WebLinkTile?,
    val targetHomePage: Int,
)
