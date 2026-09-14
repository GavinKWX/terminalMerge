package helpers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import enums.EnumLogFileName
import iso.CurrentTxn
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Last-resort handler: get the reason onto disk, then make sure the process actually dies.
 *
 * The ordering here is the design. The previous version built a HelperLog first, which called
 * CurrentCrashHost.ipAddress() -- and that threw on Android 10+, where getConnectionInfo() is nullable.
 * The exception escaped the handler, so killProcess() was never reached. For a main-thread crash
 * that leaves the main thread dead and the process alive: the screen keeps showing whatever was on
 * it (typically the transaction Result screen), nothing repaints, nothing responds, and on an
 * unattended terminal no input ever arrives to trigger an ANR. The terminal is bricked until
 * someone power-cycles it.
 *
 * The kill is now guaranteed three ways over, strongest first:
 *  1. A watchdog thread armed BEFORE any other work, which kills unconditionally after
 *     [HARD_KILL_AFTER_MS]. This is the one that holds when capture *blocks* rather than throws --
 *     full disk, stalled I/O, a drain that overruns.
 *  2. A finally block, which holds when capture throws.
 *  3. The normal path.
 *
 * Everything on the capture path is therefore allowed to fail, and nothing on it may block
 * unboundedly. Values needing a Context or the network are read once at startup by [cacheContext]
 * and only formatted here.
 */
class CrashHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Two threads can crash at once. The first owns the shutdown; the second must not race it
        // into the log writers. It still dies -- the winner's watchdog kills the whole process.
        if (!handling.compareAndSet(false, true)) return

        armHardKill()

        var crashId = "unknown"
        var signature = throwable.javaClass.simpleName
        var attempt = 1

        try {
            // Step 1: identity only. All in-memory except CrashState, which appends a handful of
            // bytes -- orders of magnitude cheaper than the report in step 3, and it already
            // falls back to 1 on any failure.
            crashId = UUID.randomUUID().toString().take(8)
            signature = signatureOf(throwable)
            attempt = CrashState.recordCrash(signature)
        } catch (t: Throwable) {
            // Defaults above are good enough to recover on.
        }

        try {
            // Step 1b: tell a waiting ECR caller, before the socket dies with us.
            notifyEcrCaller(crashId)
        } catch (t: Throwable) {
            // The caller falls back to its own timeout, which is where it was before this existed.
        }

        try {
            // Step 2: schedule the restart BEFORE writing anything.
            //
            // Ordering here was learned the hard way. With the write first, a stalled capture --
            // a failing or full disk -- burns the whole HARD_KILL_AFTER_MS budget, the watchdog
            // fires, and the handoff never runs: verified on device, the terminal was left sitting
            // on the ROM launcher. That is the exact outcome this class exists to prevent.
            //
            // A binder call is far less likely to block than a disk write, so doing it first makes
            // recovery independent of the filesystem. The crash reason is still written before the
            // process dies (step 3, immediately below); only the SCHEDULING of the restart moves
            // earlier, and the recovery process waits out its backoff before relaunching anyway.
            handOffRecovery(crashId, attempt)
        } catch (t: Throwable) {
            // Nothing more to try. The sticky AppServices restart is the remaining safety net.
        }

        try {
            // Step 3: the durable record. writeSync, so it is on disk when this returns.
            writeCrashRecord(thread, throwable, crashId, signature, attempt)
        } catch (t: Throwable) {
            // The report failed, but the restart is already scheduled and will happen regardless.
        }

        try {
            // Step 4: best-effort. The TerminaLog lines leading up to the crash are still queued
            // on the writer's daemon thread and explain what led here, so drain if there is time.
            // Last because it is the most expensive and the least important.
            AsyncLogWriter.shutdownAndDrain()
        } catch (t: Throwable) {
            // Losing the tail of the log is survivable; everything above already happened.
        } finally {
            terminate()
        }
    }

    /**
     * Start the recovery process, then let ourselves die.
     *
     * This must happen while this process is still foreground: that is the only window in which
     * an activity start is permitted on Android 10+. Once we are dead nothing of ours can start
     * an activity until the next boot, which is why the ordering here is not negotiable.
     */
    private fun handOffRecovery(crashId: String, attempt: Int) {
        val appContext = cachedAppContext ?: return

        // A crash that repeats past the top of the backoff ladder is not going to be fixed by
        // another restart. Bounce the terminal instead -- auto-start brings it back clean, so a
        // reboot is now a recovery action rather than an outage.
        if (attempt >= REBOOT_AFTER_ATTEMPTS && requestReboot()) return

        // The recovery Activity stays per app (layout + manifest entry), so the app builds
        // and starts the Intent from the values computed here.
        CurrentCrashHost.launchRecovery(
            appContext, crashId, attempt, CrashState.backoffMsFor(attempt), Process.myPid()
        )

        // Give the ActivityManager a moment to receive the start before this process disappears.
        // Bounded and well inside the watchdog; the recovery process is separate, so once the
        // request has landed our death cannot cancel it.
        try {
            Thread.sleep(HANDOFF_GRACE_MS)
        } catch (ignored: InterruptedException) {
            // proceed to the kill
        }
    }

    /**
     * Answer a waiting ECR / App2App caller instead of leaving it on its own timeout.
     *
     * Only the HTTP path can be answered from here. It is a local socket already held open by the
     * nanohttpd worker, so writing the response is an in-process handoff that can complete before
     * the kill -- and if it does not, the caller sees the connection drop when this process dies,
     * which it detects far sooner than a timeout anyway.
     *
     * App2App deliberately gets nothing here. Replying to it means starting the CALLER's activity,
     * and the one foreground-start this process has left is already committed to
     * CrashRecoveryActivity. Racing the two would risk losing the restart to save a timeout, which
     * is the wrong trade -- the terminal coming back matters more.
     */
    private fun notifyEcrCaller(crashId: String) {

        val payload = JSONObject().apply {
            // SHC000/System Busy is the existing contract for "terminal cannot take this now", so
            // integrators already handle it. A new code would need coordination with every caller.
            put("ResponseCode", "SHC000")
            put("ResponseDescription", "Terminal Error - restarting")
            put("CrashId", crashId)
        }.toString()

        CurrentCrashHost.notifyCallerBusy(payload)
    }

    /** True if the ROM accepted the reboot request, in which case there is nothing left to do. */
    /** Delegated: the device SDK handle is per app. */
    private fun requestReboot(): Boolean = CurrentCrashHost.requestReboot()

    /**
     * Kill on a timer, started before anything that could hang.
     *
     * A bare daemon thread with a plain sleep: no executor, no coroutine, no allocation beyond the
     * thread itself, so it still works under the conditions that break everything else -- OOM, a
     * wedged executor, a full disk.
     */
    private fun armHardKill() {
        try {
            Thread {
                try {
                    Thread.sleep(HARD_KILL_AFTER_MS)
                } catch (ignored: InterruptedException) {
                    // fall through and kill anyway
                }
                terminate()
            }.apply {
                name = "CrashHardKill"
                isDaemon = true
            }.start()
        } catch (t: Throwable) {
            // Could not even start the watchdog -- do not gamble on capture() returning.
            terminate()
        }
    }

    /** What the recovery handoff needs to know, returned so it survives a partial failure. */
    /**
     * Write the crash report. Identity is passed in rather than derived here, because it is
     * computed before the recovery handoff -- see the ordering note in [uncaughtException].
     */
    private fun writeCrashRecord(
        thread: Thread,
        throwable: Throwable,
        crashId: String,
        signature: String,
        consecutive: Int
    ) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = scrub(sw.toString())
        val message = scrub(throwable.message ?: "(no message)")

        val helperLog = HelperLog(
            newSessionId(),
            cachedIsWifi,
            cachedIp,
            CLASS_NAME,
            CLASS_NAME,
            "CrashHandler Triggered"
        )

        helperLog.appendLine(CLASS_NAME, "Crash id", crashId)
        helperLog.appendLine(CLASS_NAME, "Signature", signature)
        helperLog.appendLine(CLASS_NAME, "Thread", "${thread.name} (main=${isMainThread(thread)})")
        helperLog.appendLine(CLASS_NAME, "Foreground screen", foregroundActivity)
        helperLog.appendLine(CLASS_NAME, "Consecutive pre-home crashes", consecutive.toString())
        helperLog.appendLine(CLASS_NAME, "Runtime", describeRuntime())
        helperLog.appendLine(CLASS_NAME, "Transaction", describeTransaction())
        helperLog.appendLine(CLASS_NAME, message)
        helperLog.appendLine(CLASS_NAME, stackTrace)
        helperLog.logToFile(EnumLogFileName.TerminaLogException)

        // Marks the session as a crash rather than an external kill, so the next startup reports
        // it correctly. A tiny synchronous write, unlike the drain that follows in step 4.
        LogSessionMarker.closeSession("CRASH: $signature (id $crashId)")
    }

    /** Stable identity for a crash: exception class + the first frame inside our own code. */
    /** A short random id for the crash log header. Was HelperCommon.getSession(), which is
     *  the same three lines and carries no app state. */
    private fun newSessionId(): String = java.util.UUID.randomUUID().toString().substring(0, 7)

    private fun signatureOf(throwable: Throwable): String = try {
        val topAppFrame = throwable.stackTrace
            .firstOrNull { it.className.startsWith(APP_PACKAGE) }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "no-app-frame"
        "${throwable.javaClass.simpleName}@$topAppFrame"
    } catch (t: Throwable) {
        throwable.javaClass.simpleName
    }

    private fun isMainThread(thread: Thread): Boolean = try {
        thread.id == Looper.getMainLooper().thread.id
    } catch (t: Throwable) {
        false
    }

    private fun describeRuntime(): String = try {
        val rt = Runtime.getRuntime()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        "freeHeap=${(rt.maxMemory() - usedHeap) / 1048576}MB maxHeap=${rt.maxMemory() / 1048576}MB " +
            "uptime=${SystemClock.elapsedRealtime() / 1000}s " +
            "build=${CurrentCrashHost.buildLabel()} " +
            "os=${Build.VERSION.RELEASE}(API${Build.VERSION.SDK_INT})"
    } catch (t: Throwable) {
        "unavailable"
    }

    /**
     * Transaction context, identifiers only. No PAN, no track data, no PIN block -- this file is
     * uploaded to TMS.
     */
    private fun describeTransaction(): String = try {
        val ecr = CurrentCrashHost.callerChannel()
        "type=${CurrentTxn.txnTypeLabel} stan=${CurrentTxn.stan} invoice=${CurrentTxn.invoiceNo} " +
            "batch=${CurrentTxn.batchNo} tid=${CurrentTxn.tid} ecr=$ecr"
    } catch (t: Throwable) {
        "unavailable"
    }

    private fun terminate() {
        Process.killProcess(Process.myPid())
        exitProcess(2)
    }

    companion object {
        private const val CLASS_NAME = "CrashHandler"
        private const val APP_PACKAGE = "com.sc.mf919"

        /**
         * Generous enough for the synchronous write plus AsyncLogWriter's 2s drain timeout, short
         * enough that a wedged terminal is never left sitting there.
         */
        private const val HARD_KILL_AFTER_MS = 3_000L

        /**
         * Long enough for the ActivityManager to receive the recovery start, short enough to stay
         * well inside [HARD_KILL_AFTER_MS] alongside the log write and drain.
         */
        private const val HANDOFF_GRACE_MS = 400L

        /**
         * Consecutive pre-home-screen crashes before a reboot is preferred over another restart.
         * The backoff ladder tops out at 60s from attempt 6, so this is "the ceiling has been hit
         * a few times and restarting is clearly not working".
         */
        private const val REBOOT_AFTER_ATTEMPTS = 8

        private val handling = AtomicBoolean(false)

        /**
         * Application context, cached so the crash path never has to reach for one. Held
         * statically on purpose -- it is the Application instance, so there is nothing to leak.
         */
        @Volatile
        private var cachedAppContext: Context? = null

        @Volatile
        private var cachedIp: String = CurrentCrashHost.ipAddress()

        @Volatile
        private var cachedIsWifi: Boolean = false

        /** Last resumed activity -- answers "what was on screen when it died". */
        @JvmStatic
        @Volatile
        var foregroundActivity: String = "unknown"

        /**
         * Read the Context-dependent values once, off the crash path.
         *
         * These are precisely the calls that used to break the handler: getIPAddress()
         * dereferences a nullable WifiInfo, checkIsConnectedWifi() casts a system service. Doing
         * them here, where a throw is survivable, leaves the crash path formatting strings only.
         */
        @JvmStatic
        fun cacheContext(context: Context) {
            cachedAppContext = context.applicationContext
            cachedIp = try {
                CurrentCrashHost.ipAddress()
            } catch (t: Throwable) {
                CurrentCrashHost.ipAddress()
            }
            cachedIsWifi = try {
                CurrentCrashHost.isWifi(context)
            } catch (t: Throwable) {
                false
            }
        }

        private val TRACK2 = Regex("""\d{12,19}[=dD]\d{4,}""")

        /**
         * Any digit run long enough to be a card number. Deliberately NOT `\b\d{13,19}\b`: word
         * boundaries require a non-digit on both sides, so a PAN sitting next to other digits --
         * which is exactly what a raw ISO field or TLV dump looks like -- slipped through
         * completely unmasked. Verified: "41111111111111112345" was passing through intact.
         */
        private val DIGIT_RUN = Regex("""\d{13,}""")

        /**
         * Strip cardholder data before anything reaches the log file.
         *
         * A stack trace can carry a PAN in an exception message, a toString(), or a raw ISO field,
         * and TerminaLogException ships to TMS. Track 2 is matched first because it *contains* a
         * PAN: masking PANs first would break the track pattern and leave the rest behind.
         *
         * Over-masking is the intended bias. A 14-digit timestamp gets masked too; losing that
         * from a crash report costs far less than leaking a card number into a TMS upload.
         */
        @JvmStatic
        fun scrub(text: String): String = try {
            text.replace(TRACK2, "[TRACK2-REDACTED]").replace(DIGIT_RUN) { maskDigits(it.value) }
        } catch (t: Throwable) {
            "[SCRUB-FAILED]"
        }

        /**
         * Runs of 13-19 digits keep the standard first-6/last-4. Anything longer cannot be split
         * into "PAN plus other digits" reliably, so it is masked whole rather than guessing where
         * the card number starts and exposing part of it.
         */
        private fun maskDigits(run: String): String =
            if (run.length > 19) "*".repeat(run.length)
            else run.take(6) + "*".repeat(run.length - 10) + run.takeLast(4)
    }
}
