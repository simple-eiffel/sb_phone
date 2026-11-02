package sbphone.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import sbphone.app.data.Area
import sbphone.app.data.TlEvent

/** An astronomical year as people write it: 1300 BC, AD 14. */
fun yearLabel(y: Int): String = when {
    y <= 0 -> "${1 - y} BC"
    y < 1000 -> "AD $y"
    else -> "$y"
}

// ------------------------------------------------------------------------------------ timeline

@Composable
fun TimelineScreen(state: AppState) {
    var lane by remember { mutableStateOf<String?>(null) }
    val lanes = loadOnIo(Unit) { state.bible.lanes() }
    val events = loadOnIo(lane) { state.bible.events(lane) }
    OverlayScaffold(title = "Timeline", onBack = { state.back() }, actions = { TextButton(onClick = { state.open(Overlay.Atlas(-1000, null)) }) { Text("Map") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                FilterChip(selected = lane == null, onClick = { lane = null }, label = { Text("All") }, modifier = Modifier.padding(end = 6.dp))
                for (l in lanes.orEmpty()) FilterChip(selected = lane == l.id, onClick = { lane = l.id }, label = { Text(l.title) }, modifier = Modifier.padding(end = 6.dp))
            }
            Text("A draft by Larry Rix: every event below is marked draft or “to confirm”.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (events == null) { Loading(); return@Column }
            val laneNames = lanes.orEmpty().associate { it.id to it.title }
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { e -> EventRow(state, e, laneNames[e.lane].orEmpty()) }
                item { state.bible.source("timeline")?.let { SourceLine(it.credit) } }
            }
        }
    }
}

@Composable
private fun EventRow(state: AppState, e: TlEvent, lane: String) {
    Row(Modifier.fillMaxWidth().clickable { state.open(Overlay.TimelineEvent(e.id)) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(e.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.38f).padding(end = 8.dp))
        Column(Modifier.weight(0.62f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, style = if (e.importance >= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (e.importance >= 2) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp))
                StatusChip(e.status)
            }
            Text(lane, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
fun TimelineEventScreen(state: AppState, id: String) {
    val data = loadOnIo(id) { Triple(state.bible.events().firstOrNull { it.id == id }, state.bible.claims(id), state.bible.places(id)) }
    OverlayScaffold(title = data?.first?.title ?: "", onBack = { state.back() }) { padding ->
        val (e, claims, places) = data ?: run { Loading(Modifier.padding(padding)); return@OverlayScaffold }
        if (e == null) { Empty("Not found.", Modifier.padding(padding)); return@OverlayScaffold }
        val link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(e.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    StatusChip(e.status)
                }
                Text("Draft: not yet confirmed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(linkRefs(e.summary, link) { state.goTo(it) }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
                if (places.isNotEmpty()) TextButton(onClick = { state.open(Overlay.Atlas((e.earliest + e.latest) / 2, places.first().id)) }) {
                    Text("Show ${places.joinToString(", ") { it.name }} on the map  →")
                }
                Text("The dating", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
            }
            items(claims) { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(c.label + if (c.lean) "  (preferred)" else "", style = MaterialTheme.typography.titleSmall)
                    val who = listOf(c.chronology, c.heldBy).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(listOf("Basis: ${c.basis}", who).filter { it.isNotBlank() && it != "Basis: " }.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodyMedium)
                    if (c.citation.isNotBlank()) Text(c.citation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            item { state.bible.source("timeline")?.let { SourceLine(it.credit) } }
        }
    }
}

// ------------------------------------------------------------------------------------ atlas

/** Map units: x = longitude × cos(32°), y = −latitude, so the Levant keeps its shape. */
private const val COS0 = 0.848f
private fun mx(lon: Float) = lon * COS0
private fun my(lat: Float) = -lat

private fun ringPath(ring: FloatArray): Path = Path().apply {
    var i = 0
    while (i + 1 < ring.size) {
        if (i == 0) moveTo(mx(ring[0]), my(ring[1])) else lineTo(mx(ring[i]), my(ring[i + 1]))
        i += 2
    }
    close()
}

private fun inside(ring: FloatArray, lon: Float, lat: Float): Boolean {
    var c = false
    var j = ring.size - 2
    var i = 0
    while (i + 1 < ring.size) {
        val xi = ring[i]; val yi = ring[i + 1]; val xj = ring[j]; val yj = ring[j + 1]
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
        j = i; i += 2
    }
    return c
}

private fun colorFor(name: String): Color = Color.hsv(((name.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.85f)

@Composable
fun AtlasScreen(state: AppState, startYear: Int, focusPlace: String?) {
    var year by remember { mutableIntStateOf(startYear.coerceIn(-3399, 400)) }
    val land = loadOnIo(Unit) { state.bible.land.map { (k, r) -> k to ringPath(r) } }
    val places = loadOnIo(Unit) { state.bible.places() }
    val areas = loadOnIo(year) { state.bible.areas(year).map { it to ringPath(it.ring) } }
    var scale by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var picked by remember { mutableStateOf<List<Area>>(emptyList()) }
    val measurer = rememberTextMeasurer()
    val dark = isDark(state.theme)
    val landColor = if (dark) Color(0xFF2E323C) else Color(0xFFE9E4D6)
    val waterColor = if (dark) Color(0xFF101C2B) else Color(0xFFC9DCEB)
    val labelColor = MaterialTheme.colorScheme.onBackground
    val dotColor = MaterialTheme.colorScheme.primary
    OverlayScaffold(title = "Empires · ${yearLabel(year)}", onBack = { state.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(waterColor)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val s = (scale * zoom).coerceIn(4f, 400f)
                                val z = s / scale
                                offset = centroid - (centroid - offset) * z + pan
                                scale = s
                            }
                        }
                        .pointerInput(areas) {
                            detectTapGestures { p ->
                                val lon = (p.x - offset.x) / scale / COS0
                                val lat = -(p.y - offset.y) / scale
                                picked = areas.orEmpty().filter { (a, _) -> inside(a.ring, lon, lat) }.map { it.first }.distinctBy { it.name }
                            }
                        },
                ) {
                    // Set the first view once the places are known, so a focused place can be centred.
                    if (scale == 0f && (focusPlace == null || places != null)) {
                        // First view: the Bible lands, from the Aegean to the Persian Gulf.
                        val focus = places?.firstOrNull { it.id == focusPlace }
                        if (focus != null) {
                            scale = size.width / (14f * COS0)
                            offset = Offset(size.width / 2 - mx(focus.lon) * scale, size.height / 2 - my(focus.lat) * scale)
                        } else {
                            scale = size.width / (40f * COS0)
                            offset = Offset(-mx(20f) * scale, -my(41f) * scale)
                        }
                    }
                    if (scale == 0f) return@Canvas
                    withTransform({ translate(offset.x, offset.y); scale(scale, scale, Offset.Zero) }) {
                        land?.forEach { (kind, path) -> drawPath(path, if (kind == "lake") waterColor else landColor) }
                        areas?.forEach { (a, path) ->
                            val c = colorFor(a.name)
                            drawPath(path, c.copy(alpha = 0.30f))
                            drawPath(path, c.copy(alpha = 0.85f), style = Stroke(width = 1.2f / scale))
                        }
                    }
                    // Names never overlap: a label that would cover one already drawn is skipped.
                    val taken = mutableListOf<androidx.compose.ui.geometry.Rect>()
                    fun free(r: androidx.compose.ui.geometry.Rect): Boolean = taken.none { it.overlaps(r) }.also { if (it) taken += r }
                    // Places first (the focused one before all others), then empires large enough on screen.
                    val zoom = scale / (size.width / (40f * COS0))
                    places?.sortedBy { if (it.id == focusPlace) 0 else it.minZoom }?.forEach { p ->
                        if (p.minZoom > 1 && zoom < 2.5f && p.id != focusPlace) return@forEach
                        val sx = mx(p.lon) * scale + offset.x; val sy = my(p.lat) * scale + offset.y
                        if (sx < -20 || sy < -20 || sx > size.width + 20 || sy > size.height + 20) return@forEach
                        drawCircle(dotColor, radius = if (p.id == focusPlace) 7f else 4f, center = Offset(sx, sy))
                        if (zoom >= 1.6f || p.id == focusPlace) {
                            val layout = measurer.measure(p.name, TextStyle(fontSize = if (p.id == focusPlace) 12.sp else 10.sp, color = labelColor,
                                fontWeight = if (p.id == focusPlace) FontWeight.Bold else FontWeight.Normal))
                            val r = androidx.compose.ui.geometry.Rect(Offset(sx + 6, sy - layout.size.height / 2f), androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                            if (free(r)) drawText(layout, topLeft = r.topLeft)
                        }
                    }
                    areas?.distinctBy { it.first.name }?.forEach { (a, _) ->
                        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                        var i = 0
                        while (i + 1 < a.ring.size) { val x = mx(a.ring[i]); val y = my(a.ring[i + 1]); if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; i += 2 }
                        val w = (maxX - minX) * scale
                        if (w > 90f) {
                            val cx = (minX + maxX) / 2 * scale + offset.x; val cy = (minY + maxY) / 2 * scale + offset.y
                            val layout = measurer.measure(a.name, TextStyle(fontSize = 11.sp, color = labelColor.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold))
                            val r = androidx.compose.ui.geometry.Rect(Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f), androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                            if (free(r)) drawText(layout, topLeft = r.topLeft)
                        }
                    }
                }
                if (picked.isNotEmpty()) {
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                        .clickable { picked = emptyList() }.padding(12.dp)) {
                        for (a in picked) Text("${a.name}  ·  ${yearLabel(a.yearFrom)} – ${yearLabel(a.yearTo)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year = (year - 50).coerceAtLeast(-3399) }) { Text("−50") }
                Slider(value = year.toFloat(), onValueChange = { year = it.toInt() }, valueRange = -3399f..400f, modifier = Modifier.weight(1f))
                TextButton(onClick = { year = (year + 50).coerceAtMost(400) }) { Text("+50") }
            }
            Text("Tap the map to name what was there. ${areas?.size ?: 0} outlines in ${yearLabel(year)}.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            state.bible.source("cliopatria")?.let { SourceLine(it.credit + " " + (state.bible.source("naturalearth")?.credit ?: "")) }
        }
    }
}
