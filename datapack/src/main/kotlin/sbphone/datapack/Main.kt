package sbphone.datapack

import java.io.File
import kotlin.system.exitProcess

private val VAULT = System.getProperty("user.home").replace('\\', '/') + "/OneDrive/Documents/Obsidian Vault/Scholars"

/**
 * Usage:
 *   datapack                      build the public app.db (rights gate, fingerprint and private-name scans)
 *   datapack --pack               build the PRIVATE pack of the author's own materials (never into the repo)
 * Options: --bible --conc --out --timeline --cliopatria --naturalearth --names --vault --pack-out
 */
fun main(args: Array<String>) {
    val flags = args.filter { it == "--pack" }.toSet()
    val opts = args.filter { it !in flags }.chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    opts["names"]?.let { PrivateNames.listFile = File(it) }
    val started = System.currentTimeMillis()
    try {
        if ("--pack" in flags) {
            val out = opts["pack-out"] ?: "D:/android/packs/rix-materials.db"
            val repo = File(".").canonicalPath.replace('\\', '/').lowercase()
            require(!File(out).canonicalPath.replace('\\', '/').lowercase().startsWith(repo)) {
                "The private pack must be written outside the repository (got $out)."
            }
            val report = PrivatePack(File(opts["vault"] ?: VAULT), opts["bible"] ?: "$VAULT/Rix/Data/data/bible.db", out).run()
            report.forEach { log(it) }
        } else {
            val history = History(
                File(opts["timeline"] ?: "$VAULT/Rix/History/_Timeline Build/02 - Seed"),
                File(opts["cliopatria"] ?: "${System.getProperty("user.home")}/AppData/Local/simple_bible/sources/cliopatria"),
                File(opts["naturalearth"] ?: "D:/android/_dl/naturalearth"),
            )
            val out = opts["out"] ?: "app/src/main/assets/app.db"
            val report = Build(opts["bible"] ?: "$VAULT/Rix/Data/data/bible.db", opts["conc"] ?: "$VAULT/Rix/Data/data/conc.db", out, history).run()
            report.lines.forEach { log(it) }
            // The app reads the uncompressed size from this sidecar to show copy progress.
            File("$out.size").writeText(report.bytes.toString())
            log("wrote ${report.path}: ${report.bytes / 1_048_576} MB, sha256 ${report.sha256}")
        }
        log("done in ${(System.currentTimeMillis() - started) / 1000} s")
    } catch (e: RightsGateFailure) {
        System.err.println(e.message); exitProcess(2)
    } catch (e: VerifyFailure) {
        System.err.println(e.message); exitProcess(3)
    }
}
