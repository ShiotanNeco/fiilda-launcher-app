package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinkSupportTest {
    @Test
    fun urlsAreNormalizedToHttpOrHttps() {
        assertEquals("https://example.com", normalizeWebLinkUrl("example.com"))
        assertEquals("https://example.com/path?q=1", normalizeWebLinkUrl("  example.com/path?q=1 "))
        assertEquals("http://example.com", normalizeWebLinkUrl("http://example.com"))
        assertEquals("https://Example.com/A", normalizeWebLinkUrl("HTTPS://Example.com/A"))
        assertNull(normalizeWebLinkUrl(""))
        assertNull(normalizeWebLinkUrl("javascript://alert(1)"))
        assertNull(normalizeWebLinkUrl("intent://scan#Intent;end"))
        assertNull(normalizeWebLinkUrl("file:///sdcard/a.html"))
        assertNull(normalizeWebLinkUrl("https://"))
        assertNull(normalizeWebLinkUrl("two words.com"))
        assertNull(normalizeWebLinkUrl("www"))
        assertEquals("http://localhost:8080/", normalizeWebLinkUrl("http://localhost:8080/"))
    }

    @Test
    fun labelsFallBackToTheHost() {
        assertEquals("example.com", defaultWebLinkLabel("https://www.example.com/news"))
        val link = WebLinkTile(newWebLinkHomeId(), "https://www.example.com", label = "")
        assertEquals("E", webLinkInitial(link))
        assertEquals("ニ", webLinkInitial(link.copy(label = "ニュース")))
    }

    @Test
    fun recordsRoundTripAndRejectUnsafeValues() {
        val links = listOf(
            WebLinkTile(newWebLinkHomeId(), "https://example.com/a;b?c=d%20e", "名前; with\nnewline", "icon_abc.png"),
            WebLinkTile(newWebLinkHomeId(), "https://example.org", ""),
        )
        // The stored URL is normalized on read, so compare against the parsed value.
        val parsed = parseWebLinks(serializeWebLinks(links))
        assertEquals(2, parsed.size)
        assertEquals(links[0].label, parsed[0].label)
        assertEquals("icon_abc.png", parsed[0].iconFile)
        assertNull(parsed[1].iconFile)

        val unsafe = serializeWebLinks(listOf(links[1].copy(iconFile = "../../shared_prefs/x.xml")))
        assertNull(parseWebLinks(unsafe).single().iconFile)
        assertTrue(parseWebLinks("v1;garbage\nnot-a-record").isEmpty())
    }

    @Test
    fun webLinkIdsLiveInTheWidgetNamespace() {
        val id = newWebLinkHomeId()
        assertTrue(isWebLinkHomeId(id))
        assertFalse(isWebLinkHomeId(WebLinkHomeIdPrefix))
        assertFalse(isWebLinkHomeId("${WebLinkHomeIdPrefix}../x"))
        // Size overrides for a link survive the widget-size persistence filter.
        val overrides = mapOf(id to WidgetSizeChoice.of(2, 3))
        assertEquals(overrides, parseWidgetSizeOverrides(serializeWidgetSizeOverrides(overrides)))
    }

    @Test
    fun layoutKeepsLinkIdsAndHomeItemsResolveThem() {
        val link = WebLinkTile(newWebLinkHomeId(), "https://example.com", "Example")
        val layout = normalizeHomeLayout(
            storedLayout = HomeLayout(order = listOf(link.homeId), pageCount = 1),
            storedPages = null,
            legacyOrder = null,
            favoriteIds = emptyList(),
        )
        assertTrue(link.homeId in layout.allIds)
        val items = buildHomeItems(order = layout.order, apps = emptyList(), webLinks = listOf(link))
        assertEquals(listOf<HomeItem>(HomeItem.WebLink(link)), items)
        // Without its record the ID renders nothing.
        assertTrue(buildHomeItems(order = layout.order, apps = emptyList()).isEmpty())
    }
}
