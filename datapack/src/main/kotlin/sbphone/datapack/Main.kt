package sbphone.datapack

import kotlin.system.exitProcess

private const val VAULT_DATA = "C:/Users/LJR19/OneDrive/Documents/Obsidian Vault/Scholars/Rix/Data/data"

/**
 * Usage: datapack [--bible PATH] [--conc PATH] [--out PATH]
 * Defaults: the vault's bible.db and conc.db; out = app/src/main/assets/app.db.
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val bible = opts["bible"] ?: "$VAULT_DATA/bible.db"
    val conc = opts["conc"] ?: "$VAULT_DATA/conc.db"
    val out = opts["out"] ?: "app/src/main/assets/app.db"
    val started = System.currentTimeMillis()
    try {
        val report = Build(bible, conc, out).run()
        report.lines.forEach { log(it) }
        // The app reads the uncompressed size from this sidecar to show copy progress.
        java.io.File("$out.size").writeText(report.bytes.toString())
        log("wrote ${report.path}: ${report.bytes / 1_048_576} MB, sha256 ${report.sha256}")
        log("done in ${(System.currentTimeMillis() - started) / 1000} s")
    } catch (e: RightsGateFailure) {
        System.err.println(e.message); exitProcess(2)
    } catch (e: VerifyFailure) {
        System.err.println(e.message); exitProcess(3)
    }
}
