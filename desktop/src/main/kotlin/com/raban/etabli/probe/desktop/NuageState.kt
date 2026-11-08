// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raban.etabli.probe.engine.BackgroundChecker
import com.raban.etabli.probe.engine.CheckResult
import com.raban.etabli.probe.engine.CsvLoader
import com.raban.etabli.probe.engine.CsvPreview
import com.raban.etabli.probe.engine.MAX_CSV_BYTES
import com.raban.etabli.probe.engine.ResultsCsv
import com.raban.etabli.probe.engine.UrlEntry
import com.raban.etabli.probe.engine.WebDavCreds
import com.raban.etabli.probe.engine.entries
import com.raban.etabli.probe.engine.readBounded
import com.raban.etabli.probe.engine.runSequentialChecks
import com.raban.etabli.probe.engine.suggestedUrlColumn
import com.raban.etabli.probe.net.WebDavClient
import com.raban.etabli.probe.net.WebDavError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class Screen { Source, Run, Results, Settings }
enum class Tone { Info, Accent, Danger }
data class Notice(val text: String, val tone: Tone = Tone.Info)
enum class ResultFilter { All, Pass, Fail }

/** A Nextcloud action waiting for this session's app password. */
sealed interface PendingDav {
    data class Fetch(val path: String) : PendingDav
    data class Upload(val fileName: String) : PendingDav
    data object Test : PendingDav
}

/**
 * Window-wide state and actions. Compose reads the snapshot state; long work runs in [scope]
 * with file and network I/O on [io]. Checks run strictly one after another.
 */
class NuageState(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newChecker: (timeoutMs: Long, installId: String) -> (suspend (UrlEntry) -> CheckResult) =
        { timeoutMs, installId -> BackgroundChecker(timeoutMs, installId)::probe },
    private val newWebDav: (WebDavCreds) -> WebDavClient = { WebDavClient(it) },
) {
    var screen by mutableStateOf(Screen.Source)

    // ---- settings (non-secret, persisted) ----
    var settingsNotice by mutableStateOf<Notice?>(null)
    var settings by mutableStateOf(
        try { store.load() } catch (e: Exception) {
            settingsNotice = Notice("Settings could not be read (${describe(e)}); defaults apply for this session.", Tone.Danger)
            AppSettings(installId = UUID.randomUUID().toString())
        }
    )
        private set

    private fun update(change: (AppSettings) -> AppSettings) {
        val next = change(settings)
        settings = next
        try { store.save(next) } catch (e: Exception) {
            settingsNotice = Notice("Settings could not be saved: ${describe(e)}", Tone.Danger)
        }
    }

    fun setDelayMs(value: Long) = update { it.copy(delayMs = value.coerceIn(AppSettings.DELAY_RANGE)) }
    fun setTimeoutMs(value: Long) = update { it.copy(timeoutMs = value.coerceIn(AppSettings.TIMEOUT_RANGE)) }
    fun setTheme(value: ThemePref) = update { it.copy(theme = value) }

    // ---- source ----
    var sourceName by mutableStateOf<String?>(null)
        private set
    var preview by mutableStateOf<CsvPreview?>(null)
        private set
    var selectedColumn by mutableStateOf<Int?>(null)
        private set
    var sourceNotice by mutableStateOf<Notice?>(null)
    var loading by mutableStateOf(false)
        private set

    fun openFile(path: Path): Job =
        load(path.fileName?.toString() ?: path.toString()) { Files.newInputStream(path).use { it.readBounded(MAX_CSV_BYTES) } }

    private fun load(name: String, read: suspend () -> ByteArray): Job = scope.launch {
        loading = true
        sourceNotice = null
        try {
            val parsed = withContext(io) { CsvLoader.parse(read()) }
            preview = parsed
            sourceName = name
            selectedColumn = parsed.suggestedUrlColumn()
            sourceNotice = if (parsed.headers.isEmpty()) Notice("$name contains no records.", Tone.Danger)
            else Notice("Opened $name: ${parsed.rows.size} records, ${parsed.headers.size} columns. The file itself is not changed.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            forgetRejectedPassword(e)
            sourceNotice = Notice("Could not open $name: ${describe(e)}", Tone.Danger)
        } finally { loading = false }
    }

    fun setHeader(enabled: Boolean) {
        val next = preview?.withHeader(enabled) ?: return
        preview = next
        selectedColumn = next.suggestedUrlColumn()
    }

    fun selectColumn(index: Int) {
        if (preview?.headers?.indices?.contains(index) == true) selectedColumn = index
    }

    // ---- prepared list ----
    var prepared by mutableStateOf<List<UrlEntry>>(emptyList())
        private set
    var preparedLabel by mutableStateOf<String?>(null)
        private set

    fun prepare(): Boolean {
        val p = preview ?: return false
        val column = selectedColumn ?: return false
        if (running) {
            sourceNotice = Notice("Cancel the current run before preparing another list.", Tone.Danger)
            return false
        }
        val entries = p.entries(column)
        if (entries.none { it.valid }) {
            sourceNotice = Notice("Column \"${p.headers[column]}\" contains no valid URLs.", Tone.Danger)
            return false
        }
        prepared = entries
        preparedLabel = "${sourceName.orEmpty()} · ${p.headers[column]}"
        runNotice = null
        screen = Screen.Run
        return true
    }

    // ---- run ----
    var running by mutableStateOf(false)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var passCount by mutableIntStateOf(0)
        private set
    var failCount by mutableIntStateOf(0)
        private set
    var current by mutableStateOf<String?>(null)
        private set
    var runNotice by mutableStateOf<Notice?>(null)
    val results = mutableStateListOf<CheckResult>()
    private var savedCount by mutableIntStateOf(0)
    val unsavedCount: Int get() = results.size - savedCount
    private var runStartedAt: LocalDateTime? = null
    private var runJob: Job? = null
    var confirmDiscard by mutableStateOf(false)

    /** Starts a run, or asks first when the previous results were not exported or uploaded. */
    fun requestStart() {
        if (unsavedCount > 0 && !running) confirmDiscard = true else startRun()
    }

    fun startRun(): Job? {
        confirmDiscard = false
        if (running || prepared.none { it.valid }) return null
        val entries = prepared
        val snapshot = settings
        results.clear()
        savedCount = 0
        done = 0; total = entries.size; passCount = 0; failCount = 0
        runNotice = null; resultsNotice = null
        runStartedAt = LocalDateTime.now()
        running = true
        val probe = newChecker(snapshot.timeoutMs, snapshot.installId)
        return scope.launch {
            try {
                runSequentialChecks(
                    entries, snapshot.delayMs, probe,
                    onStart = { _, entry -> current = entry.normalized ?: entry.raw },
                    onResult = { index, result ->
                        results += result
                        done = index + 1
                        if (result.pass) passCount++ else failCount++
                    },
                )
                runNotice = Notice("Finished: $done checked, $passCount passed, $failCount failed.", Tone.Accent)
            } catch (e: CancellationException) {
                runNotice = Notice("Cancelled after $done of $total. Completed rows are kept; export them from Results.")
                throw e
            } catch (e: Exception) {
                runNotice = Notice("Run stopped: ${describe(e)}. Completed rows are kept.", Tone.Danger)
            } finally {
                running = false
                current = null
            }
        }.also { runJob = it }
    }

    fun cancelRun() { runJob?.cancel() }

    // ---- results ----
    var filter by mutableStateOf(ResultFilter.All)
    var resultsNotice by mutableStateOf<Notice?>(null)

    fun suggestedFileName(): String =
        "nuage-results-" + (runStartedAt ?: LocalDateTime.now()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv"

    fun exportTo(path: Path): Job {
        val snapshot = results.toList()
        return scope.launch {
            try {
                withContext(io) { ResultsCsv.write(snapshot, path) }
                savedCount = maxOf(savedCount, snapshot.size)
                resultsNotice = Notice("Saved ${snapshot.size} rows to $path", Tone.Accent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { resultsNotice = Notice("Export failed: ${describe(e)}", Tone.Danger) }
        }
    }

    // ---- Nextcloud (optional) ----
    /** App password for this session only. It is never written to disk. */
    var sessionPassword by mutableStateOf("")
        private set
    var passwordPrompt by mutableStateOf<PendingDav?>(null)
        private set
    var accountNotice by mutableStateOf<Notice?>(null)
    var davBusy by mutableStateOf(false)
        private set

    val hasAccount: Boolean get() = settings.serverUrl.isNotBlank() && settings.username.isNotBlank()

    /** Saves the non-secret account fields. A different server or user never reuses the password. */
    fun saveAccount(serverUrl: String, username: String, uploadDir: String) {
        val server = serverUrl.trim()
        val user = username.trim()
        if (server.trimEnd('/') != settings.serverUrl.trimEnd('/') || user != settings.username) sessionPassword = ""
        update { it.copy(serverUrl = server, username = user, uploadDir = uploadDir.trim().ifEmpty { "EtabliProbe" }) }
        accountNotice = Notice("Saved. The app password is asked once per session and kept in memory only.")
    }

    fun forgetAccount() {
        sessionPassword = ""
        update { it.copy(serverUrl = "", username = "") }
        accountNotice = Notice("Server and username removed; the session password was discarded.")
    }

    fun forgetSessionPassword() {
        sessionPassword = ""
        accountNotice = Notice("Session password discarded.")
    }

    /** Each returns the started job, or null when it waits for the password or cannot start. */
    fun testConnection(): Job? = withPassword(PendingDav.Test)
    fun fetchFromNextcloud(path: String): Job? = withPassword(PendingDav.Fetch(path.trim()))
    fun uploadResults(fileName: String): Job? = withPassword(PendingDav.Upload(fileName.trim()))

    fun submitPassword(password: String): Job? {
        val pending = passwordPrompt ?: return null
        passwordPrompt = null
        if (password.isEmpty()) return null
        sessionPassword = password
        return perform(pending)
    }

    fun dismissPasswordPrompt() { passwordPrompt = null }

    private fun withPassword(action: PendingDav): Job? {
        if (!hasAccount) {
            val notice = Notice("Enter the Nextcloud server and username in Settings first.", Tone.Danger)
            when (action) {
                is PendingDav.Fetch -> sourceNotice = notice
                is PendingDav.Upload -> resultsNotice = notice
                PendingDav.Test -> accountNotice = notice
            }
            return null
        }
        if (sessionPassword.isEmpty()) {
            passwordPrompt = action
            return null
        }
        return perform(action)
    }

    private fun perform(action: PendingDav): Job? {
        val creds = WebDavCreds(settings.serverUrl, settings.username, sessionPassword)
        return when (action) {
            is PendingDav.Fetch -> load(action.path.substringAfterLast('/').ifEmpty { action.path }) {
                newWebDav(creds).get(action.path)
            }
            is PendingDav.Upload -> upload(creds, action.fileName)
            PendingDav.Test -> scope.launch {
                davBusy = true
                try {
                    val count = newWebDav(creds).testConnection()
                    accountNotice = Notice("Connected: $count items in your Nextcloud files root.", Tone.Accent)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    forgetRejectedPassword(e)
                    accountNotice = Notice("Connection failed: ${describe(e)}", Tone.Danger)
                } finally { davBusy = false }
            }
        }
    }

    private fun upload(creds: WebDavCreds, fileName: String): Job? {
        val snapshot = results.toList()
        if (snapshot.isEmpty()) return null
        val dir = settings.uploadDir
        return scope.launch {
            davBusy = true
            try {
                val bytes = ResultsCsv.encode(snapshot).toByteArray(Charsets.UTF_8)
                newWebDav(creds).put(dir, fileName, bytes, "text/csv; charset=utf-8")
                savedCount = maxOf(savedCount, snapshot.size)
                resultsNotice = Notice("Uploaded ${snapshot.size} rows to ${dir.trim('/')}/$fileName", Tone.Accent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                forgetRejectedPassword(e)
                resultsNotice = Notice("Upload failed: ${describe(e)}", Tone.Danger)
            } finally { davBusy = false }
        }
    }

    private fun forgetRejectedPassword(e: Exception) {
        if (e is WebDavError.Http && e.status == 401) sessionPassword = ""
    }

    // ---- window ----
    var confirmQuit by mutableStateOf(false)

    /** True when the window may close now; otherwise asks first. */
    fun requestQuit(): Boolean {
        if (running || unsavedCount > 0) { confirmQuit = true; return false }
        return true
    }
}

internal fun describe(e: Throwable): String = when (e) {
    is NoSuchFileException -> "file not found"
    is AccessDeniedException -> "permission denied"
    else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
