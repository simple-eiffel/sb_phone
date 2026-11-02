package sbphone.datapack

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.util.zip.ZipFile

/**
 * The Timeline (the author's own drafts, published labeled as draft) and the Empires atlas
 * (Cliopatria borders, CC BY 4.0, simplified) over a Natural Earth base map (public domain).
 */
class History(private val timelineDir: File, private val clioDir: File, private val naturalEarthDir: File) {

    private val mapper = ObjectMapper()

    /** The map covers the Bible's world: the Atlantic to India, the Sahel to the steppe. */
    private val west = -20.0; private val east = 90.0; private val south = -5.0; private val north = 60.0

    /** Places may only cite sources under these licenses (the timeline's own claims are the author's). */
    private val placeLicenses = setOf("CC BY 4.0", "CC0 1.0")

    fun sources(): List<Source> {
        val clioLicense = ZipFile(File(clioDir, "cliopatria.zip")).use { z ->
            z.entries().asSequence().firstOrNull { it.name.endsWith("/LICENSE.md") }?.let { z.getInputStream(it).readBytes().decodeToString() }
        } ?: throw RightsGateFailure("Cliopatria: no LICENSE.md in cliopatria.zip; cannot confirm its license")
        if ("Attribution 4.0 International" !in clioLicense) throw RightsGateFailure("Cliopatria: LICENSE.md is not CC BY 4.0")
        val neLicense = File(naturalEarthDir, "LICENSE.md").takeIf { it.exists() }?.readText()
            ?: throw RightsGateFailure("Natural Earth: LICENSE.md missing beside the data")
        if ("public domain" !in neLicense.lowercase()) throw RightsGateFailure("Natural Earth: LICENSE.md does not say public domain")
        return listOf(
            Source("timeline", "Timeline of the Bible's world (draft)", "Timeline", "the author's own work, free to share",
                "Timeline by Larry Rix (draft; every event is marked draft or to confirm).", "", false),
            Source("openbible-geo", "OpenBible.info Bible Geocoding Data", "Places", "CC BY 4.0",
                "Place locations from OpenBible.info Bible Geocoding Data, CC BY 4.0.", "https://github.com/openbibleinfo/Bible-Geocoding-Data", false),
            Source("wikidata", "Wikidata coordinates", "Places", "CC0 1.0", "Some place locations from Wikidata (P625), CC0.", "https://www.wikidata.org", false),
            Source("cliopatria", "Cliopatria polity borders (Seshat Global History Databank)", "Map", "CC BY 4.0",
                "Empire borders from Cliopatria, Seshat Global History Databank, CC BY 4.0. Changed: outlines simplified and limited to 3400 BC – AD 400.",
                "https://github.com/Seshat-Global-History-Databank/cliopatria", false),
            Source("naturalearth", "Natural Earth", "Map", "public domain", "Made with Natural Earth (coastlines and lakes), public domain.", "https://www.naturalearthdata.com", false),
        )
    }

    fun write(db: Connection) {
        writeTimeline(db)
        writeLand(db)
        writeEmpires(db)
    }

    // ---- timeline ----

    private fun writeTimeline(db: Connection) {
        val lanes = mapper.readTree(File(timelineDir, "lanes.timeline.json"))["lanes"]
        db.prepareStatement("INSERT INTO tl_lane(id, title, grp, sort) VALUES (?,?,?,?)").use { st ->
            for (l in lanes) { st.setString(1, l.text("id")); st.setString(2, l.text("title")); st.setString(3, l.text("group")); st.setInt(4, l["sort"]?.asInt() ?: 0); st.addBatch() }
            st.executeBatch()
        }
        var events = 0; var claims = 0; var unreadable = 0
        val citations = LinkedHashMap<String, String>()
        val ev = db.prepareStatement("INSERT INTO tl_event(id, lane, title, kind, importance, summary, status, earliest, latest, label) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val cl = db.prepareStatement("INSERT INTO tl_claim(event, ordinal, edtf, label, chronology, held_by, basis, lean, citation, note) VALUES (?,?,?,?,?,?,?,?,?,?)")
        for (f in timelineDir.listFiles { f -> f.name.matches(Regex("""\d\d-.*\.timeline\.json""")) }.orEmpty().sortedBy { it.name }) {
            val doc = mapper.readTree(f)
            doc["citations"]?.forEach { c -> citations[c.text("id")] = c.text("text") }
            for (e in doc["events"]) {
                val spans = e["claims"].mapNotNull { Edtf.parse(it.text("edtf")) }
                if (spans.isEmpty()) { unreadable++; continue }
                // The first claim is the event's date as shown; every claim is kept with who holds it.
                val first = spans.first()
                ev.setString(1, e.text("id")); ev.setString(2, e.text("lane")); ev.setString(3, e.text("title")); ev.setString(4, e.text("kind"))
                ev.setInt(5, e["importance"]?.asInt() ?: 0); ev.setString(6, e.text("summary")); ev.setString(7, e.text("status").ifBlank { "draft" })
                ev.setInt(8, spans.minOf { it.earliest }); ev.setInt(9, spans.maxOf { it.latest }); ev.setString(10, first.label)
                ev.addBatch(); events++
                e["claims"].forEachIndexed { i, c ->
                    val span = Edtf.parse(c.text("edtf")) ?: return@forEachIndexed
                    cl.setString(1, e.text("id")); cl.setInt(2, i); cl.setString(3, c.text("edtf")); cl.setString(4, span.label)
                    cl.setString(5, c.text("chronology")); cl.setString(6, c.text("held_by")); cl.setString(7, c.text("basis"))
                    cl.setInt(8, if (c["lean"]?.asBoolean() == true) 1 else 0); cl.setString(9, c.text("citation")); cl.setString(10, c.text("note"))
                    cl.addBatch(); claims++
                }
            }
        }
        ev.executeBatch(); cl.executeBatch(); ev.close(); cl.close()

        val places = mapper.readTree(File(timelineDir, "places.timeline.json"))
        val placeLicense = HashMap<String, String>()
        places["citations"]?.forEach { c -> citations[c.text("id")] = c.text("text"); placeLicense[c.text("id")] = c.text("license") }
        var placeCount = 0; var refused = 0
        val kept = HashSet<String>()
        db.prepareStatement("INSERT INTO tl_place(id, name, kind, min_zoom, lon, lat, citation) VALUES (?,?,?,?,?,?,?)").use { st ->
            for (p in places["places"]) {
                // Fail closed: a location is kept only if a claim cites a source under an allowed license.
                val claim = p["claims"].firstOrNull { placeLicense[it.text("citation")] in placeLicenses }
                if (claim == null) { refused++; continue }
                st.setString(1, p.text("id")); st.setString(2, p.text("name")); st.setString(3, p.text("kind")); st.setInt(4, p["min_zoom"]?.asInt() ?: 1)
                st.setDouble(5, claim["lon"].asDouble()); st.setDouble(6, claim["lat"].asDouble()); st.setString(7, claim.text("citation"))
                st.addBatch(); placeCount++; kept += p.text("id")
            }
            st.executeBatch()
        }
        db.prepareStatement("INSERT INTO tl_event_place(event, place) VALUES (?,?)").use { st ->
            for (ep in places["event_places"]) if (ep.text("place") in kept) { st.setString(1, ep.text("event")); st.setString(2, ep.text("place")); st.addBatch() }
            st.executeBatch()
        }
        db.prepareStatement("INSERT INTO tl_citation(id, text) VALUES (?,?)").use { st ->
            for ((id, text) in citations) { st.setString(1, id); st.setString(2, text); st.addBatch() }
            st.executeBatch()
        }
        log("timeline: $events events, $claims claims, ${citations.size} citations, $placeCount places ($refused refused: no allowed license), $unreadable events with unreadable dates skipped")
    }

    // ---- maps ----

    private fun writeLand(db: Connection) {
        var rings = 0
        db.prepareStatement("INSERT INTO atlas_land(kind, ring) VALUES (?,?)").use { st ->
            for ((kind, file) in listOf("land" to "ne_50m_land.geojson", "lake" to "ne_50m_lakes.geojson")) {
                val root = mapper.readTree(File(naturalEarthDir, file))
                for (feature in root["features"]) for (ring in outerRings(feature["geometry"])) {
                    if (!intersects(ring)) continue
                    val simple = simplify(ring, 0.02)
                    if (simple.size < 3) continue
                    st.setString(1, kind); st.setBytes(2, encode(simple)); st.addBatch(); rings++
                }
            }
            st.executeBatch()
        }
        log("base map: $rings land and lake outlines")
    }

    private fun writeEmpires(db: Connection) {
        var areas = 0
        val names = HashSet<String>()
        db.prepareStatement("INSERT INTO atlas_area(name, year_from, year_to, west, east, south, north, ring) VALUES (?,?,?,?,?,?,?,?)").use { st ->
            mapper.factory.createParser(File(clioDir, "cliopatria.geojson")).use { p ->
                // Stream the features one at a time; the file is far too large to read whole.
                while (p.nextToken() != null) {
                    if (p.currentToken() == JsonToken.FIELD_NAME && p.currentName() == "features") {
                        p.nextToken()
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            val f: JsonNode = mapper.readTree(p)
                            val props = f["properties"] ?: continue
                            if (props.text("Type") != "POLITY") continue
                            val from = props["FromYear"]?.asInt() ?: continue
                            val to = props["ToYear"]?.asInt() ?: continue
                            if (from > 400) continue
                            for (ring in outerRings(f["geometry"])) {
                                if (!intersects(ring)) continue
                                val simple = simplify(ring, 0.02)
                                if (simple.size < 3) continue
                                // Cliopatria years are historical (no year 0): -3400 = 3400 BC. Store astronomical years.
                                st.setString(1, props.text("Name")); st.setInt(2, if (from < 0) from + 1 else from); st.setInt(3, if (to < 0) to + 1 else to)
                                st.setDouble(4, simple.minOf { it.first }); st.setDouble(5, simple.maxOf { it.first })
                                st.setDouble(6, simple.minOf { it.second }); st.setDouble(7, simple.maxOf { it.second })
                                st.setBytes(8, encode(simple)); st.addBatch(); areas++
                                names += props.text("Name")
                                if (areas % 2000 == 0) st.executeBatch()
                            }
                        }
                        break
                    }
                }
            }
            st.executeBatch()
        }
        log("empires: $areas outlines of ${names.size} polities (to AD 400)")
    }

    private fun outerRings(geometry: JsonNode?): List<List<Pair<Double, Double>>> {
        if (geometry == null) return emptyList()
        fun ring(n: JsonNode) = n.map { it[0].asDouble() to it[1].asDouble() }
        return when (geometry.text("type")) {
            "Polygon" -> listOf(ring(geometry["coordinates"][0]))
            "MultiPolygon" -> geometry["coordinates"].map { ring(it[0]) }
            else -> emptyList()
        }
    }

    private fun intersects(ring: List<Pair<Double, Double>>): Boolean =
        ring.maxOf { it.first } >= west && ring.minOf { it.first } <= east && ring.maxOf { it.second } >= south && ring.minOf { it.second } <= north

    /** Douglas–Peucker, iterative. */
    private fun simplify(points: List<Pair<Double, Double>>, epsilon: Double): List<Pair<Double, Double>> {
        if (points.size < 4) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            val (ax, ay) = points[a]; val (bx, by) = points[b]
            val dx = bx - ax; val dy = by - ay
            val len = Math.hypot(dx, dy)
            for (i in a + 1 until b) {
                val (px, py) = points[i]
                val d = if (len == 0.0) Math.hypot(px - ax, py - ay) else Math.abs(dy * px - dx * py + bx * ay - by * ax) / len
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > epsilon) { keep[idx] = true; stack.add(a to idx); stack.add(idx to b) }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** lon,lat pairs as little-endian float32. */
    private fun encode(points: List<Pair<Double, Double>>): ByteArray {
        val buf = ByteBuffer.allocate(points.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for ((x, y) in points) { buf.putFloat(x.toFloat()); buf.putFloat(y.toFloat()) }
        return buf.array()
    }

    private fun JsonNode.text(field: String): String = this[field]?.takeIf { !it.isNull }?.asText() ?: ""
}
