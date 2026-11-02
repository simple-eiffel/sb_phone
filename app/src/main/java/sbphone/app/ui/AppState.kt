package sbphone.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sbphone.app.data.AppDb
import sbphone.app.data.PackDb
import sbphone.app.data.UserDb
import sbphone.fold.Pointing
import sbphone.fold.PointingLevel
import sbphone.fold.Ref

enum class Tab { Read, Search, Library, More }

/** Screens that open over the tabs; the system Back button closes them in order. */
sealed interface Overlay {
    data object Picker : Overlay
    data class Word(val lemma: Int) : Overlay
    data class English(val word: String) : Overlay
    data class NoteEdit(val key: Int) : Overlay
    data class Journal(val id: Long) : Overlay
    data class TagVerses(val tag: Long, val name: String) : Overlay
    data object Memory : Overlay
    data class PackHome(val file: String) : Overlay
    data object Timeline : Overlay
    data class TimelineEvent(val id: String) : Overlay
    data class Atlas(val year: Int, val place: String?) : Overlay
    data class PackDocument(val file: String, val id: Long) : Overlay
    data object Prayers : Overlay
    data object Credits : Overlay
    data object Settings : Overlay
    data object About : Overlay
}

class AppState(val bible: AppDb, val user: UserDb, initialPacks: List<PackDb> = emptyList()) {

    /** Private packs loaded on this phone from files the user chose (never shipped in the app). */
    val packs = mutableStateListOf<PackDb>().apply { addAll(initialPacks) }

    var tab by mutableStateOf(Tab.Read)
    val overlays = mutableStateListOf<Overlay>()

    var page by mutableIntStateOf(bible.pageOf(
        user.setting("book")?.toIntOrNull() ?: 43,
        user.setting("chapter")?.toIntOrNull() ?: 1,
    ))
        private set

    /** A verse to scroll to and flash after a jump (English key), or 0. */
    var focusKey by mutableIntStateOf(0)

    /** The verse whose sheet is open in the reader (English key), or 0. */
    var sheetKey by mutableIntStateOf(0)

    var version by mutableStateOf(user.setting("version")?.takeIf { v -> bible.versions.any { it.code == v } } ?: "KJV")
        private set
    var parallel by mutableStateOf(user.setting("parallel")?.split(',')?.filter { v -> bible.versions.any { it.code == v } }.orEmpty())
        private set
    var parallelOn by mutableStateOf(user.setting("parallelOn") == "1")
        private set
    var textScale by mutableStateOf(user.setting("textScale")?.toFloatOrNull() ?: 1f)
        private set
    var theme by mutableStateOf(user.setting("theme")?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.Dark)
        private set

    /** How much Hebrew pointing to show: full, vowels only, or consonants only. */
    var pointing by mutableStateOf(user.setting("pointing")?.let { runCatching { PointingLevel.valueOf(it) }.getOrNull() } ?: PointingLevel.Full)
        private set

    fun choosePointing(level: PointingLevel) {
        pointing = level
        user.setSetting("pointing", level.name)
    }

    /** Hebrew or Aramaic text at the chosen pointing level. */
    fun hebrew(text: String): String = Pointing.apply(text, pointing)

    /** Focus reading: the text alone, without bars. */
    var focus by mutableStateOf(false)

    // ---- Back / Forward over the places jumped to (chapter turns by swiping are not recorded) ----
    private val backStack = mutableStateListOf<Ref>()
    private val forwardStack = mutableStateListOf<Ref>()
    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    private fun here(): Ref = bible.chapters[page].let { (b, c) -> Ref(b, c) }

    fun goBack() {
        val to = backStack.removeLastOrNull() ?: return
        forwardStack += here()
        jump(to)
    }

    fun goForward() {
        val to = forwardStack.removeLastOrNull() ?: return
        backStack += here()
        jump(to)
    }

    private fun jump(ref: Ref) {
        turnTo(bible.pageOf(ref.book, ref.chapter))
        focusKey = if (ref.verse > 0) ref.key else 0
        sheetKey = 0
        overlays.clear()
        tab = Tab.Read
    }

    /** Bumped whenever the user's own data changes, so lists re-read it. */
    var userRevision by mutableIntStateOf(0)
        private set

    /** The search box text survives switching tabs. */
    var searchText by mutableStateOf("")
    var searchAllVersions by mutableStateOf(false)

    val chapter: Pair<Int, Int> get() = bible.chapters[page]

    fun turnTo(index: Int) {
        page = index.coerceIn(0, bible.chapters.lastIndex)
        val (b, c) = bible.chapters[page]
        user.setSetting("book", b.toString())
        user.setSetting("chapter", c.toString())
    }

    fun goTo(ref: Ref) {
        val from = here()
        if (from.book != ref.book || from.chapter != ref.chapter) {
            backStack += from
            if (backStack.size > 100) backStack.removeAt(0)
            forwardStack.clear()
        }
        turnTo(bible.pageOf(ref.book, ref.chapter))
        focusKey = if (ref.verse > 0) ref.key else 0
        if (ref.verse > 0) user.recordVisit(ref.key, version)
        sheetKey = 0
        overlays.clear()
        tab = Tab.Read
        touched()
    }

    fun chooseVersion(code: String) {
        version = code
        user.setSetting("version", code)
    }

    fun chooseParallel(codes: List<String>) {
        parallel = codes.take(3)
        user.setSetting("parallel", parallel.joinToString(","))
    }

    fun toggleParallel() {
        parallelOn = !parallelOn
        if (parallelOn && parallel.isEmpty()) chooseParallel(listOf("WLC", "WH").filter { it != version })
        user.setSetting("parallelOn", if (parallelOn) "1" else "0")
    }

    fun chooseTextScale(scale: Float) {
        textScale = scale
        user.setSetting("textScale", scale.toString())
    }

    fun chooseTheme(choice: ThemeChoice) {
        theme = choice
        user.setSetting("theme", choice.name)
    }

    fun open(overlay: Overlay) { overlays += overlay }
    fun back(): Boolean = if (overlays.isNotEmpty()) { overlays.removeAt(overlays.lastIndex); true } else false

    fun touched() { userRevision++ }
}
