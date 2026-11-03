# SB Phone: Design (clean sheet, 2026-11-01)

A free, offline Bible-study app for Android phones (first target: Samsung Galaxy S21). It is a new design and a new codebase. `D:\prod\simple_bible` is used only as a model of features and benefits; no code, schema or class design is carried over.

Working title "SB Phone" until a real name is chosen.

## 1. Benefits it must keep (from the simple_bible model)

- **Every fact shows where it came from.** Each lexicon entry, word tag and cross-reference carries its source and license on screen. The app shows facts. It does not compose them.
- **Nothing is hidden behind a fence.** No ads, store, accounts, sign-in, telemetry or paywall.
- **No network at all.** The app declares no INTERNET permission, so Android itself guarantees it cannot send anything anywhere.
- **No AI on the phone.** Everything shown was built ahead of time from sourced data.
- **Your notes stay yours.** Notes, highlights and bookmarks live only on the phone, with export and import to a file you choose.
- **Public data only.** Only texts whose licenses allow redistribution ship, and every one is credited.

## 2. Release 1 features

| Feature | What the user gets |
|---|---|
| Reader | Any shipped version, chapter by chapter; swipe between chapters; book and chapter picker; text size; dark and light |
| Parallel | Up to three versions verse by verse, stacked for a phone screen |
| Verse sheet | Tap a verse to see its original words, cross-references and every version of that verse; highlight, bookmark, note, copy or share it with its credit |
| Original words (interlinear) | Hebrew (OT) and Greek (NT) word by word: the original, a transliteration, an English gloss, the parsing, and the dictionary form |
| Word study | Tap an original word for its lexicon page: headword, pronunciation, Strong's number, part of speech, how often it occurs, how the KJV renders it, the lexicon senses grouped by source, and every place it occurs |
| Search | Words, "exact phrases", prefixes (`love*`), OR, and -exclusion, within one version or all; Hebrew and Greek match with or without points and accents; `H7225` or `G3056` opens that word |
| Cross-references | OpenBible.info references ranked by votes, each with a preview of the verse |
| Library | Bookmarks, highlights (four colors), notes, reading history |
| Credits | Every shipped text and dataset with its license and attribution |

Later (not Release 1): reading plans, memory cards, prayer list, morphology search, a timeline, KJV word-tap to the original, user-imported private texts.

## 3. Data: what ships

Source data comes from the vault databases, read only: `bible.db` and `conc.db` in `Scholars/Rix/Data/data/`.

**Ships (license permits redistribution, credited):**

| Kind | Items | License |
|---|---|---|
| English Bibles | KJV, BSB, ASV, YLT, Tyndale (NT, Pentateuch, Jonah) | Public domain / CC0 |
| Early English and Latin | Wycliffe NT, Clementine Vulgate NT | CC BY-SA 4.0 / Public domain |
| Hebrew | WLC (Westminster Leningrad Codex), MAM (Miqra according to the Masorah) | CC BY 4.0 |
| Greek | Westcott-Hort 1881 | PD text, accents CC BY-SA 3.0 |
| Aramaic | Targum Jonathan | Public domain |
| Word data | MACULA Hebrew, MACULA Greek (glosses, parsing, lemmas) | CC BY 4.0 |
| Lexicons | BDB 1906 (Open Scriptures), TBESH, Abbott-Smith 1922, Middle Liddell 1889, LSJ (STEPBible), UBS Dictionary of Biblical Hebrew, UBS Dictionary of NT Greek | CC BY 4.0 / CC BY-SA 4.0 |
| Renderings | How the KJV translates each word (eBible kjv2006 Strong's tags) | Public domain |
| Cross-references | OpenBible.info | CC BY |

**Never ships:** the Rahlfs Septuagint, TR, Byzantine, Samaritan Pentateuch, Targums Onkelos, Neofiti and W, the Peshitta, both NWT editions, the STEPBible TTESV material, the BSB word tables (license unverified), the vault `strongs` and `word_strongs` tables (lineage untraced), the ANE glosses, and anything from rix.db, scholars.db, transcripts.db or the commentary packs.

### The rights gate (enforced, not declared)

The data builder refuses to finish unless all three checks pass:

1. **Allowlist.** Only the items above, named in code. Nothing ships by default.
2. **Register cross-check.** Every allowlisted item must be marked distributable in its source register (`bible.db` `source_provenance.ship_class` in open / attribution / share_alike, or `conc.db` `meaning_source.ship_class = 'open'` / `corpus.ship_class`). If the vault later downgrades an item, the build fails.
3. **Fingerprint scan.** The finished database is scanned for sample verses of every restricted text (for example LXX Gen 1:1, NWT John 1:1, TR Matt 1:1, TgO Gen 1:1). Any match fails the build.

The credits screen is generated from the same records, so the credits cannot drift from what shipped.

## 4. Architecture

```
sb_phone/
  fold/      Kotlin library shared by the builder and the app: text folding, reference parsing, book table
  datapack/  Kotlin command-line builder: vault DBs -> app.db, with the rights gate and verifier
  app/       Android app: Kotlin + Jetpack Compose (Material 3)
```

One folding routine (`fold`) is used both when the search index is built and when the user types a query, so the two can never disagree. Folding: Unicode NFD, drop combining marks (Hebrew points and cantillation, Greek accents and breathings), lowercase, Greek final sigma to sigma, everything that is not a letter or digit becomes a space.

**app.db** (built once, shipped inside the APK, copied to the phone's private storage on first launch):

- `source`: code, name, kind, license, credit, url
- `version`: code, name, language, direction, source code, ordering
- `book`: id, name, abbreviation, testament, chapter count
- `verse`: version, book, chapter, verse, text (rowid ordered by version, then canon order)
- `verse_fts`: FTS4 over the folded text (Android's built-in SQLite, no native library)
- `word`: book, chapter, verse, position, surface, after, lemma, parsing code, parsing in English, gloss, transliteration (MACULA, original versification)
- `lemma`: id, language, headword, transliteration, pronunciation, Strong's, part of speech, count
- `sense`: lemma, source, order, depth, label, gloss, text
- `rendering`: lemma, English word, count (KJV)
- `xref`: from verse, to verse range, votes
- `vmap`: English-versification verse to Hebrew-versification verse, where they differ (derived from the KJV-to-original word links, labeled as derived)

**Search:** each term or phrase runs as its own FTS query, and AND, OR and NOT are combined in Kotlin. This avoids depending on which FTS query syntax a given phone's SQLite was compiled with.

**user.db** (on the phone only): bookmarks, highlights, notes (every edit kept), history, settings. Export and import are a JSON file through Android's file picker.

**Fonts:** open-licensed (SIL OFL) fonts are bundled for Hebrew with cantillation and for polytonic Greek, so rendering does not depend on the phone.

## 5. Delivery

- A signed release APK, sideloaded onto the S21 (Settings, then allow installs from this source). The signing key lives outside the repo.
- Target size: under 150 MB installed.
- Google Play publishing is a later decision.

## Read aloud (0.6.0, 2026-11-03)

Every screen carries a speaker button that reads what that screen shows; the reader reads the chapter verse by verse (marking and following the verse) and goes on into the next chapter. A bar at the bottom gives back, pause, next and stop. It uses the phone's own text-to-speech engines and only voices installed on the phone (never one that needs the network), so the no-network promise holds; the manifest gains a `<queries>` entry for the TTS service, which is not a permission. Hebrew and Greek script are found in the text and sent to a Hebrew or Greek voice (modern pronunciation, said so in Settings); the Vulgate uses a Latin voice, or Italian. A phone call or other audio pauses it. Tested on the emulator with the eSpeak NG engine (installed on the emulator only).
