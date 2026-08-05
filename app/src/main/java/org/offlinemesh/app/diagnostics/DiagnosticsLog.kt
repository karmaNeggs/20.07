package org.offlinemesh.app.diagnostics

import android.content.Context
import org.offlinemesh.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A small, exportable event log for diagnosing mesh behavior on a real phone, without needing a
 * cable and `adb logcat` — the app writes it itself and the user shares the file out (see
 * `MeshDiagnostics`' share action in the UI).
 *
 * **Debug builds only, by construction.** Every entry point is a no-op unless
 * [BuildConfig.DEBUG] — same boundary LeakCanary already sits behind (`debugImplementation`), and
 * for the same reason. This app's stated property is that a seized phone has no persisted trail to
 * find (README's Permissions section: positions live in RAM only, uninstall is a real wipe); a
 * durable log file is exactly the kind of artifact that would quietly break that promise. Release
 * builds additionally strip all `android.util.Log` calls via `proguard-rules.pro`.
 *
 * **What it deliberately does NOT record:** GPS coordinates, message bodies, group keys, evidence
 * content, or full peer/sender identifiers. Callers pass event *types*, counts, and reasons —
 * identifiers are expected to be truncated by the caller (see `RelayResponder`'s
 * `SENDER_ID_LOG_CHARS`). This is the difference between "diagnosable" and "a forensic record of
 * who was near whom, when".
 *
 * Bounded on disk: [MAX_FILE_BYTES] per file, one rotation ([FILE_NAME]/[FILE_NAME_PREVIOUS]), so
 * the worst case is a fixed ~1MB regardless of session length. In-memory writes are queued and
 * flushed on [flush] / rotation so a hot path (a per-frame callback) never blocks on file I/O.
 */
object DiagnosticsLog {

    private const val MAX_FILE_BYTES = 512 * 1024
    private const val FILE_NAME = "mesh-diagnostics.txt"
    private const val FILE_NAME_PREVIOUS = "mesh-diagnostics-previous.txt"
    private const val DIR_NAME = "diagnostics"
    private const val FLUSH_THRESHOLD = 20

    private val pending = ConcurrentLinkedQueue<String>()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    /** Call once from `MeshApp`/`MeshService` startup. No-op in release. */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        appContext = context.applicationContext
        event("session", "diagnostics log started")
    }

    /**
     * Records one event. [tag] is a short category ("conn", "reject", "push", "beacon", "identity");
     * [message] is a short human-readable detail — see the class doc for what must never go in it.
     */
    fun event(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        pending += "${timestampFormat.format(Date())} [$tag] $message"
        if (pending.size >= FLUSH_THRESHOLD) flush()
    }

    /** Writes anything queued to disk, rotating first if the active file is at its cap. */
    // Broad catch, deliberately and in both places below: a diagnostics logger that can throw is
    // strictly worse than no logger — it would take down the very session it exists to explain, on
    // any of a dozen unremarkable I/O failures (storage full, file locked, dir removed mid-write).
    // Nothing here is recoverable or worth reporting, hence swallowed rather than re-thrown.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    @Synchronized
    fun flush() {
        if (!BuildConfig.DEBUG) return
        val ctx = appContext ?: return
        if (pending.isEmpty()) return
        try {
            val dir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
            val active = File(dir, FILE_NAME)
            if (active.exists() && active.length() >= MAX_FILE_BYTES) {
                File(dir, FILE_NAME_PREVIOUS).delete()
                active.renameTo(File(dir, FILE_NAME_PREVIOUS))
            }
            val batch = StringBuilder()
            while (true) {
                val line = pending.poll() ?: break
                batch.append(line).append('\n')
            }
            active.appendText(batch.toString())
        } catch (e: Exception) {
            // A diagnostics log that can crash the app it's diagnosing is worse than no log at all.
            pending.clear()
        }
    }

    /** The file to share, newest content last. Null if nothing has been written yet. */
    @Synchronized
    fun exportFile(context: Context): File? {
        if (!BuildConfig.DEBUG) return null
        flush()
        val active = File(File(context.filesDir, DIR_NAME), FILE_NAME)
        return active.takeIf { it.exists() && it.length() > 0 }
    }

    /** Deletes everything on disk — the "I'm done testing" escape hatch. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // see flush()'s note
    @Synchronized
    fun clear(context: Context) {
        pending.clear()
        try {
            File(context.filesDir, DIR_NAME).deleteRecursively()
        } catch (e: Exception) {
            // Nothing useful to do; the next rotation will cap the size anyway.
        }
    }
}
