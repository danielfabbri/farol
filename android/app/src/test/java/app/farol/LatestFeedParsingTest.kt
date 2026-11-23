package app.farol

import app.farol.data.model.FarolJson
import app.farol.data.model.LatestFeed
import app.farol.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garante que o app lê o contrato real gerado pelo backend (python -m backend.main --dev). */
class LatestFeedParsingTest {
    private fun sample(): String =
        javaClass.classLoader!!.getResource("sample_latest.json")!!.readText()

    @Test
    fun parsesBackendLatestJson() {
        val feed = FarolJson.decodeFromString(LatestFeed.serializer(), sample())
        assertTrue("dados de exemplo devem estar marcados", feed.isSample)
        assertEquals(1, feed.schemaVersion)
        assertTrue(feed.articles.isNotEmpty())
        assertTrue(feed.highlights.isNotEmpty())
        val events = feed.events.associateBy { it.id }
        feed.highlights.forEach { assertNotNull("destaque sem evento: $it", events[it]) }
        val multi = feed.events.first { it.sourceCount == 2 }
        assertEquals(2, multi.sources.size)
        assertTrue(feed.articles.all { it.displayTitle.isNotBlank() && it.sourceUrl.startsWith("https://") })
        assertNotNull(Fmt.instant(feed.generatedAt))
        assertTrue(feed.filters.any { it.id == "agents" })
        // Seções e resumos de seção
        assertEquals(listOf("brasil", "politica", "economia", "mundo", "ciencia_saude", "technology_ai"), feed.sections.map { it.id })
        val tech = feed.sections.first { it.id == "technology_ai" }
        assertTrue(tech.filters.isNotEmpty() && tech.brief != null)
        tech.brief!!.points.forEach { p -> p.eventIds.forEach { assertNotNull(events[it]) } }
    }

    @Test
    fun toleratesUnknownAndMissingFields() {
        val json = """{"generated_at":"2026-11-23T08:30:00Z","edition":{"id":"x","label":"Edição","generated_at":"2026-11-23T08:30:00Z"},"campo_novo":1}"""
        val feed = FarolJson.decodeFromString(LatestFeed.serializer(), json)
        assertTrue(feed.articles.isEmpty())
        assertEquals("Edição", feed.edition.label)
    }

    @Test
    fun parsesOffsetTimestamps() {
        assertNotNull(Fmt.instant("2026-11-23T08:30:00Z"))
        assertNotNull(Fmt.instant("2026-11-23T08:30:00+00:00"))
    }
}
