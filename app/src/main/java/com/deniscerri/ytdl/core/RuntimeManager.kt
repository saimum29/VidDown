package com.deniscerri.ytdl.core

import android.content.Context
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.core.models.ExecuteResponse
import com.deniscerri.ytdl.core.models.YTDLRequest
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the youtubedl-android library (io.github.junkfood02.youtubedl-android),
 * which bundles its own python interpreter + yt-dlp + ffmpeg internally (no separate native
 * binaries need to be packaged by this app, unlike the previous bespoke jniLibs-based engine
 * this file used to drive).
 *
 * The public surface (init/execute/destroyProcessById/assertInit/version/versionName/updateYTDL/
 * UpdateChannel/UpdateStatus/BASENAME/getInstance) is kept intentionally identical to before, so
 * every existing call site (YTDLPUtil, DownloadWorker, TerminalDownloadWorker, PackagesFragment,
 * the various cancel/pause receivers, etc.) keeps working unchanged.
 */
object RuntimeManager {

    @Volatile
    var initialized = false
        private set

    private var initLatch = CountDownLatch(1)
    private val initLock = Any()
    private lateinit var appContext: Context

    const val BASENAME = "viddown"
    const val ytdlpDirName = "yt-dlp"
    const val ytdlpBin = "yt-dlp"

    // Process IDs that were deliberately killed via destroyProcessById, so execute() can tell
    // a user-initiated cancellation apart from a genuine failure and surface CanceledException.
    private val cancelledIds = Collections.synchronizedSet(HashSet<String>())

    fun init(appContext: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            this.appContext = appContext.applicationContext
            try {
                YoutubeDL.getInstance().init(this.appContext)
                FFmpeg.getInstance().init(this.appContext)
                try {
                    Aria2c.getInstance().init(this.appContext)
                } catch (e: Exception) {
                    // aria2c is an optional external downloader; everything else still works without it
                    e.printStackTrace()
                }
                initialized = true
                initLatch.countDown()
            } catch (e: Exception) {
                e.printStackTrace()
                // leave initialized = false; assertInit() will surface this to callers
            }
        }
    }

    fun reInit(context: Context) {
        synchronized(initLock) {
            initialized = false
            initLatch = CountDownLatch(1)
            init(context)
        }
    }

    fun assertInit() {
        val success = initLatch.await(30, TimeUnit.SECONDS)
        if (!success || !initialized) {
            throw IllegalStateException("Instance not initialized")
        }
    }

    /**
     * No-op kept only so YTDLUpdater's error-recovery path (which still calls this on a rare
     * exception branch) has nothing to crash on. yt-dlp itself is bundled inside the
     * youtubedl-android library now, there's no separate raw-resource binary to (re)copy.
     */
    @Suppress("UNUSED_PARAMETER")
    fun initYTDLP(appContext: Context, ytdlpDir: File) {
        // intentionally empty
    }

    class CanceledException : Exception()

    fun destroyProcessById(id: String): Boolean {
        return try {
            cancelledIds.add(id)
            YoutubeDL.getInstance().destroyProcessById(id)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wraps a single command-line token in double quotes if it contains whitespace or shell
     * metacharacters, escaping any embedded backslashes/quotes. Used so that values such as
     * file paths or header strings survive being written into - and re-parsed from - a yt-dlp
     * config file (yt-dlp parses config files the same shlex/shell-style way it parses argv).
     */
    private fun shellQuote(token: String): String {
        if (token.isEmpty()) return "\"\""
        val needsQuoting = token.any { it.isWhitespace() || it in "\"'\\$`#;&|<>(){}[]*?!~" }
        if (!needsQuoting) return token
        val escaped = token.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun formatProgressLine(progress: Float, etaInSeconds: Long): String {
        return if (etaInSeconds > 0) "${progress}% (ETA ${etaInSeconds}s)" else "${progress}%"
    }

    fun execute(
        request: YTDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        usingCacheDir: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {
        assertInit()
        if (processId != null) cancelledIds.remove(processId)

        val urls = request.getUrls()
        val fullCommand = request.buildCommand()
        val optionsAndCommands = (if (urls.isNotEmpty()) fullCommand.dropLast(urls.size) else fullCommand).toMutableList()
        if (!usingCacheDir && !optionsAndCommands.contains("--no-cache-dir")) {
            optionsAndCommands.add("--no-cache-dir")
        }

        // Every option (and any raw custom command tokens) is relayed through a yt-dlp config
        // file rather than re-built one-by-one against the library's request object: this lets
        // us faithfully forward arbitrarily complex/multi-value flags (e.g. --print-to-file with
        // two positional arguments) exactly as this codebase already constructs them elsewhere,
        // without needing to guess at value boundaries.
        val configFile = File(appContext.cacheDir, "ytdl_cfg_${System.nanoTime()}.conf")
        try {
            configFile.writeText(optionsAndCommands.joinToString(" ") { shellQuote(it) })
        } catch (e: Exception) {
            throw ExecuteException("failed to prepare yt-dlp config", e)
        }

        val libRequest = YoutubeDLRequest(if (urls.isNotEmpty()) urls.first() else "")
        libRequest.addOption("--config-locations", configFile.absolutePath)

        val startTime = System.currentTimeMillis()
        try {
            val response = YoutubeDL.getInstance().execute(libRequest, processId, redirectErrorStream) { progress, etaInSeconds, line ->
                callback?.invoke(progress, etaInSeconds, line)
            }

            return ExecuteResponse(
                fullCommand,
                0,
                System.currentTimeMillis() - startTime,
                response.out,
                response.err
            )
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (processId != null && cancelledIds.remove(processId)) {
                throw CanceledException()
            }
            throw ExecuteException(e.message ?: "execution failed", e)
        } finally {
            if (processId != null) cancelledIds.remove(processId)
            configFile.delete()
        }
    }

    @Synchronized
    @Throws(ExecuteException::class)
    fun updateYTDL(
        appContext: Context,
        updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()
        return try {
            YTDLUpdater.update(appContext, updateChannel)
        } catch (e: java.io.IOException) {
            throw ExecuteException("failed to update youtube-dl", e)
        }
    }

    fun version(appContext: Context?): String? {
        return YTDLUpdater.version(appContext)
    }

    fun versionName(appContext: Context?): String? {
        return YTDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
        object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }

    @JvmStatic
    fun getInstance() = this
}
