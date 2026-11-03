package sbphone.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.Normalizer
import java.util.Locale
import sbphone.fold.Pointing
import sbphone.fold.PointingLevel

/** The language a stretch of text is read in. Hebrew and Greek script are found by the reader itself; this says how to read Latin letters. */
enum class Lang(val label: String, val codes: Set<String>, val country: String, val fallback: Set<String> = emptySet()) {
    En("English", setOf("en"), "US"),
    He("Hebrew", setOf("he", "iw"), "IL"),
    El("Greek", setOf("el"), "GR"),
    /** Latin: a Latin voice if the phone has one, otherwise Italian (Church Latin sounds close to it). */
    La("Latin", setOf("la"), "", fallback = setOf("it")),
}

/** One thing to read: a verse, a heading, a dictionary sense. [tag] lets a screen follow along. */
data class Speech(val text: String, val lang: Lang = Lang.En, val tag: Any? = null, val label: String = "")

/** A batch to read, and how to get the batch after it (the next chapter), if any. Built off the main thread. */
class Batch(val title: String, val items: List<Speech>, val next: (() -> Batch?)? = null)

/**
 * Reads text aloud with the phone's own text-to-speech voices. Only voices installed on the phone
 * are used (never one that needs the network), so reading aloud sends nothing anywhere.
 *
 * Everything here runs on the main thread; the engines' callbacks are posted back to it.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ---- what the player bar shows (Compose state) ----
    var active by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var source by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var nowLabel by mutableStateOf(""); private set
    var nowTag by mutableStateOf<Any?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    /** Which voice each language uses on this phone, once the engines have been asked. */
    var voiceReport by mutableStateOf<String?>(null); private set

    /** Speaking rate: 1.0 is normal. */
    var rate = 1f

    private class Chunk(val item: Int, val text: String, val lang: Lang)
    private class Engine(val name: String, val label: String, val tts: TextToSpeech) {
        var ok = false
        var voices: List<Voice> = emptyList()
        var lastVoice: Voice? = null
    }
    private class Target(val engine: Engine, val voice: Voice?, val locale: Locale)

    private var items: List<Speech> = emptyList()
    private var chunks: List<Chunk> = emptyList()
    private var next: (() -> Batch?)? = null
    private var pos = 0
    private var gen = 0
    private var speakingOn: Engine? = null
    private val missing = linkedSetOf<Lang>()

    // ------------------------------------------------------------------ public controls

    /** Starts reading what [load] builds (it runs off the main thread). Stops anything already being read. */
    fun read(sourceId: String, load: () -> Batch?) {
        stopEngines()
        val g = ++gen
        source = sourceId
        active = true
        playing = true
        title = "Getting ready…"
        nowLabel = ""
        nowTag = null
        notice = null
        missing.clear()
        withEngines {
            Thread {
                val batch = runCatching { load() }.getOrNull()
                main.post { if (g == gen) start(batch) }
            }.start()
        }
    }

    fun pause() {
        if (!playing) return
        gen++
        stopEngines()
        playing = false
    }

    fun resume() {
        if (!active || playing) return
        playing = true
        requestFocus()
        gen++
        speakCurrent()
    }

    fun stop() {
        gen++
        stopEngines()
        finish()
    }

    /** Skips to the next item (verse, paragraph, sense). */
    fun skipNext() {
        if (!active) return
        val item = chunks.getOrNull(pos)?.item ?: return
        val to = chunks.indexOfFirst { it.item > item }
        gen++
        stopEngines()
        pos = if (to < 0) chunks.size else to
        if (playing) speakCurrent()
    }

    /** Back to the start of this item, or to the item before when already at its start. */
    fun skipPrevious() {
        if (!active) return
        val item = chunks.getOrNull(pos)?.item ?: return
        val first = chunks.indexOfFirst { it.item == item }
        val to = if (pos > first) first else chunks.indexOfFirst { it.item == item - 1 }.takeIf { it >= 0 } ?: first
        gen++
        stopEngines()
        pos = to
        if (playing) speakCurrent()
    }

    /** Asks the engines which voices they have and fills [voiceReport]. */
    fun checkVoices() = withEngines { }

    // ------------------------------------------------------------------ reading

    private fun start(batch: Batch?) {
        if (batch == null || batch.items.isEmpty()) { finish(); return }
        title = batch.title
        items = batch.items
        chunks = items.flatMapIndexed { i, s -> chunksOf(s).map { (text, lang) -> Chunk(i, text, lang) } }
        next = batch.next
        pos = 0
        requestFocus()
        speakCurrent()
    }

    private fun speakCurrent() {
        if (!playing) return
        while (pos < chunks.size) {
            val c = chunks[pos]
            val t = target(c.lang)
            if (t == null) {
                if (missing.add(c.lang)) {
                    notice = missing.joinToString(" and ") { it.label } + " skipped: this phone has no " +
                        missing.joinToString(" or ") { it.label } + " voice installed (see Settings → Read aloud)."
                }
                pos++
                continue
            }
            val item = items[c.item]
            nowTag = item.tag
            nowLabel = item.label
            val tts = t.engine.tts
            if (t.voice != null) {
                if (t.engine.lastVoice != t.voice) { tts.voice = t.voice; t.engine.lastVoice = t.voice }
            } else {
                tts.language = t.locale; t.engine.lastVoice = null
            }
            tts.setSpeechRate(rate)
            speakingOn = t.engine
            tts.speak(c.text, TextToSpeech.QUEUE_FLUSH, Bundle(), "$gen:$pos")
            return
        }
        // This batch is done: fetch the next one (the next chapter), or finish.
        val more = next ?: run { finish(); return }
        val g = gen
        Thread {
            val batch = runCatching { more() }.getOrNull()
            main.post { if (g == gen) start(batch) }
        }.start()
    }

    private fun onUtteranceEnd(id: String?) {
        main.post {
            if (id == "$gen:$pos" && playing) { pos++; speakCurrent() }
        }
    }

    private fun finish() {
        active = false
        playing = false
        source = null
        nowTag = null
        nowLabel = ""
        items = emptyList(); chunks = emptyList(); next = null; pos = 0
        abandonFocus()
        notice?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
        notice = null
    }

    private fun stopEngines() {
        for (e in engines) runCatching { e.tts.stop() }
        speakingOn = null
    }

    // ------------------------------------------------------------------ engines and voices

    private val engines = mutableListOf<Engine>()
    private var enginesState = 0 // 0 not started, 1 starting, 2 ready
    private val waiting = mutableListOf<() -> Unit>()
    private val targets = HashMap<Lang, Target?>()

    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** Runs [then] once every installed speech engine has started (the default engine first). */
    private fun withEngines(then: () -> Unit) {
        // Ready: look again at the voices (one may have been installed since), and go on.
        if (enginesState == 2 && engines.isNotEmpty()) { refreshVoices(); then(); return }
        // None worked last time: ask again, the phone's settings may have changed.
        if (enginesState == 2) { engines.forEach { runCatching { it.tts.shutdown() } }; engines.clear(); enginesState = 0 }
        waiting += then
        if (enginesState == 1) return
        enginesState = 1
        var first: TextToSpeech? = null
        // An engine may answer inside its own constructor, so each answer is handled a moment later, once it exists.
        first = TextToSpeech(app) { status -> main.post { started(first!!, status) } }
    }

    private fun started(tts: TextToSpeech, status: Int) {
        run {
            // Even when the default engine will not start, the other installed engines are tried.
            val default = runCatching { tts.defaultEngine }.getOrNull().orEmpty()
            val infos = runCatching { tts.engines }.getOrNull().orEmpty()
            engines += Engine(default, infos.firstOrNull { it.name == default }?.label ?: default, tts).also { it.ok = status == TextToSpeech.SUCCESS }
            val others = infos.filter { it.name != default }
            if (others.isEmpty()) { ready(); return }
            var pending = others.size
            for (info in others) {
                var other: TextToSpeech? = null
                other = TextToSpeech(app, { st ->
                    main.post {
                        val e = Engine(info.name, info.label, other!!)
                        e.ok = st == TextToSpeech.SUCCESS
                        engines += e
                        if (--pending == 0) ready()
                    }
                }, info.name)
            }
        }
    }

    private fun ready() {
        engines.filter { !it.ok }.forEach { runCatching { it.tts.shutdown() } }
        engines.retainAll { it.ok }
        for (e in engines) {
            e.tts.setAudioAttributes(attributes)
            e.tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = onUtteranceEnd(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onUtteranceEnd(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceEnd(utteranceId)
            })
        }
        refreshVoices()
        enginesState = 2
        val run = waiting.toList(); waiting.clear()
        run.forEach { it() }
    }

    private fun refreshVoices() {
        for (e in engines) {
            // Installed voices only: a voice that needs the network, or is listed but not downloaded, is never used.
            e.voices = runCatching { e.tts.voices }.getOrNull().orEmpty().filter {
                !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
        }
        targets.clear()
        voiceReport = if (engines.isEmpty()) "This phone has no text-to-speech engine turned on."
        else Lang.entries.joinToString("\n") { lang ->
            val t = target(lang)
            "${lang.label}: " + if (t == null) "no voice installed" else "${t.engine.label}, ${(t.voice?.locale ?: t.locale).displayName}"
        }
    }

    private fun target(lang: Lang): Target? = targets.getOrPut(lang) { findTarget(lang.codes, lang) ?: lang.fallback.takeIf { it.isNotEmpty() }?.let { findTarget(it, lang) } }

    private fun findTarget(codes: Set<String>, lang: Lang): Target? {
        val device = Locale.getDefault()
        val country = if (lang == Lang.En && device.language == "en") device.country else lang.country
        for (e in engines) {
            val voice = e.voices.filter { it.locale.language in codes }
                .sortedWith(compareByDescending<Voice> { it.locale.country == country }.thenByDescending { it.quality }.thenBy { it.latency })
                .firstOrNull()
            if (voice != null) return Target(e, voice, voice.locale)
            // An engine that lists no voices: ask it for the language itself.
            if (e.voices.isEmpty()) {
                val locale = Locale(codes.first(), country)
                val r = runCatching { e.tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (r >= TextToSpeech.LANG_AVAILABLE) return Target(e, null, locale)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ audio focus (a phone call or other audio pauses the reading)

    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) main.post { pause() }
        }
        .build()

    private fun requestFocus() { runCatching { audio.requestAudioFocus(focus) } }
    private fun abandonFocus() { runCatching { audio.abandonAudioFocusRequest(focus) } }

    companion object {
        /** Splits a text into stretches by script, so Hebrew goes to a Hebrew voice and Greek to a Greek one, and long stretches into sentences. */
        fun chunksOf(s: Speech): List<Pair<String, Lang>> {
            val runs = mutableListOf<Pair<StringBuilder, Lang?>>()
            for (ch in s.text) {
                val script = when {
                    ch in '֐'..'׿' || ch in 'יִ'..'ﭏ' -> Lang.He
                    ch in 'Ͱ'..'Ͽ' || ch in 'ἀ'..'῿' -> Lang.El
                    Character.isLetterOrDigit(ch) -> s.lang
                    else -> null // spaces, punctuation and combining marks stay with the stretch they are in
                }
                val last = runs.lastOrNull()
                when {
                    last == null -> runs += StringBuilder().append(ch) to script
                    script == null || last.second == script -> last.first.append(ch)
                    last.second == null -> runs[runs.lastIndex] = last.first.append(ch) to script
                    else -> runs += StringBuilder().append(ch) to script
                }
            }
            return runs.flatMap { (sb, lang) ->
                val l = lang ?: s.lang
                val text = clean(sb.toString(), l)
                if (text.none { it.isLetterOrDigit() }) emptyList() else sentences(text).map { it to l }
            }
        }

        private fun clean(text: String, lang: Lang): String = when (lang) {
            // Accents (cantillation) off, maqaf as a space, sof pasuq as a full stop.
            Lang.He -> Pointing.apply(text, PointingLevel.Vowels).replace('־', ' ').replace('׃', '.').replace("׀", "").replace("׆", "")
            Lang.El -> monotonic(text)
            else -> spokenEnglish(text)
        }.replace(Regex("\\s+"), " ").trim()

        /** Polytonic Greek to the one-accent spelling modern Greek voices read: breathings and iota subscripts off, every accent as an acute. */
        private fun monotonic(text: String): String {
            val d = Normalizer.normalize(text, Normalizer.Form.NFD)
            val sb = StringBuilder()
            for (ch in d) when (ch) {
                '̓', '̔', 'ͅ', '̆', '̄' -> {}
                '̀', '͂' -> sb.append('́')
                else -> sb.append(ch)
            }
            return Normalizer.normalize(sb, Normalizer.Form.NFC)
        }

        /** Symbols the screen uses that a voice would read badly, and verse references in running text. */
        private fun spokenEnglish(text: String): String = text
            .replace(Regex("""(\d+):(\d+)\s?[-–]\s?(\d+)"""), "$1, verses $2 to $3")
            .replace(Regex("""(\d+):(\d+)"""), "$1, verse $2")
            .replace("×", " times").replace("&", " and ").replace("·", ",")
            .replace(Regex("[†★☆✎→←▾▴▸•#*_|\\[\\]{}<>]"), " ")

        private fun sentences(text: String): List<String> {
            if (text.length <= 500) return listOf(text)
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (part in text.split(Regex("""(?<=[.;:!?])\s+"""))) {
                if (sb.isNotEmpty() && sb.length + part.length > 500) { out += sb.toString(); sb.clear() }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
                while (sb.length > 3000) { out += sb.substring(0, 3000); sb.delete(0, 3000) }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}
