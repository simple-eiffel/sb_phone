# SB Phone

A free, offline Bible-study app for Android. Kotlin and Jetpack Compose; first target device: Samsung Galaxy S21.

**Status:** 0.1.0, first test build. Public repository: https://github.com/simple-eiffel/sb_phone

## What it does

- **Reader:** 11 public texts (KJV, BSB, ASV, YLT, WLC and MAM Hebrew, Westcott-Hort Greek, Targum Jonathan, Tyndale, Wycliffe, Clementine Vulgate), chapter by chapter, swipe to turn, up to three parallel versions.
- **Tap a verse:** its Hebrew or Greek words (transliteration, gloss, parsing), OpenBible cross-references ranked by votes, every version side by side; highlight, bookmark, note, copy or share with its credit.
- **Tap a word:** heading and pronunciation, Strong's number, how often it occurs, how the KJV renders it, seven lexicons each in its own words (BDB, UBS Hebrew, TBESH, Abbott-Smith, UBS Greek, Middle Liddell, LSJ), and every place it occurs.
- **Search:** words, "phrases", prefixes, OR, -exclusion; Hebrew and Greek with or without points and accents; original words by sound (`bara`, `logos`); Strong's numbers; references (`jn 3 16`).
- **Library:** bookmarks, highlights, notes (every saved version kept), history; export and import to a file.

## Trust rules

- The app declares **no Android permissions**, so it cannot use the network.
- No ads, accounts, tracking or AI. Every fact names its source and license.
- Only texts whose licenses allow redistribution ship. See the rights gate below.

## Layout

```
fold/      shared Kotlin library: search folding, reference parsing, query parsing (tests)
datapack/  builder: vault bible.db + conc.db -> app/src/main/assets/app.db, with the rights gate (tests)
app/       the Android app
DESIGN.md  the clean-sheet design
```

## Building

Toolchain (user space, no admin): JDK 21 at `D:\android\jdk`, Android SDK at `D:\android\sdk`.

```powershell
$env:JAVA_HOME = 'D:\android\jdk'
.\gradlew.bat :fold:test :datapack:test   # unit tests
.\gradlew.bat :datapack:run               # build app.db (rights gate + fingerprint scan)
.\gradlew.bat :app:assembleRelease        # signed APK -> app/build/outputs/apk/release/
```

`app.db` is a build artifact and is never committed.

## The rights gate

`datapack` refuses to write `app.db` unless:

1. every shipped item is on the allowlist in `RightsGate.kt`;
2. its vault register (`bible.db` `source_provenance.ship_class`, `conc.db` `corpus` / `meaning_source`) still marks it distributable;
3. a full fingerprint scan finds none of the distinctive verses of any text that does not ship (Septuagint, TR, Byzantine, Samaritan Pentateuch, Targums Onkelos/Neofiti/W, Peshitta, NWT).

The credits screen is generated from the same records.

## License

Code: MIT. Texts and datasets carry their own licenses, listed in the app's Credits screen. The Hebrew font Ezra SIL is under the SIL Open Font License 1.1.
