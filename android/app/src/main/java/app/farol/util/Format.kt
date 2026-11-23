package app.farol.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val ptBR: Locale = Locale.forLanguageTag("pt-BR")
    private val brasilia: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", ptBR)
    private val dateFmt = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ptBR)
    private val shortDateFmt = DateTimeFormatter.ofPattern("d MMM", ptBR)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", ptBR)
    private val timeFmt = DateTimeFormatter.ofPattern("HH'h'mm", ptBR)

    /** Horários das edições (Brasília), espelhando o agendamento do GitHub Actions. */
    private val editionTimes = listOf(LocalTime.of(5, 30), LocalTime.of(11, 30), LocalTime.of(17, 30), LocalTime.of(20, 30))

    fun instant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private fun local(value: String?) = instant(value)?.atZone(ZoneId.systemDefault())

    fun dateTime(value: String?): String = local(value)?.format(dateTimeFmt) ?: "—"
    fun date(value: String?): String = local(value)?.format(dateFmt) ?: "—"
    fun shortDate(value: String?): String = local(value)?.format(shortDateFmt) ?: "—"
    fun dateTime(millis: Long): String =
        if (millis <= 0) "nunca" else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFmt)

    fun relative(value: String?, now: Instant = Instant.now()): String {
        val t = instant(value) ?: return ""
        val d = Duration.between(t, now)
        return when {
            d.isNegative || d.toMinutes() < 1 -> "agora"
            d.toMinutes() < 60 -> "há ${d.toMinutes()} min"
            d.toHours() < 24 -> "há ${d.toHours()} h"
            d.toDays() < 7 -> if (d.toDays() == 1L) "há 1 dia" else "há ${d.toDays()} dias"
            else -> shortDate(value)
        }
    }

    fun dayLabel(isoDay: String): String =
        runCatching { LocalDate.parse(isoDay).format(dayFmt).replaceFirstChar { it.titlecase(ptBR) } }.getOrDefault(isoDay)

    fun nextEdition(now: ZonedDateTime = ZonedDateTime.now(brasilia)): String {
        val today = now.toLocalTime()
        val next = editionTimes.firstOrNull { it.isAfter(today) }
        return if (next != null) "hoje às ${next.format(timeFmt)} (Brasília)"
        else "amanhã às ${editionTimes.first().format(timeFmt)} (Brasília)"
    }

    fun potential(value: String?): String = when (value) {
        "high" -> "Alto"
        "medium" -> "Médio"
        "low" -> "Baixo"
        else -> "—"
    }

    fun sourceType(value: String): String = when (value) {
        "primary" -> "Fonte primária"
        "research" -> "Pesquisa"
        "aggregator" -> "Agregador"
        else -> "Imprensa"
    }

    fun trendStatus(value: String): String = when (value) {
        "emerging" -> "Emergente"
        "rising" -> "Em alta"
        else -> "Recorrente"
    }

    fun trendKind(value: String): String = if (value == "technology") "Tecnologia" else "Empresa / organização"
}
