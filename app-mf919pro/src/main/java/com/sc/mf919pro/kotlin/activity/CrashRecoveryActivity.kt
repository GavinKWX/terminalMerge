package com.sc.mf919pro.kotlin.activity
import helpers.CrashHandler

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Process
import android.util.Log
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.sc.mf919pro.R

/**
 * The screen that stands in for the app between a crash and the restart.
 *
 * Runs in its own process (`:recovery`, see the manifest). That is the whole trick: the crashing
 * process cannot restart itself once it is dead, and from Android 10 onward nothing may start an
 * activity from the background, so a post-mortem alarm or broadcast is silently ignored. What IS
 * still allowed is a foreground start, and at the instant of the crash the app still owns a
 * visible window -- so CrashHandler launches this activity while it is still alive, then dies.
 * This process survives, holds the screen, and relaunches MainActivity as a foreground start from
 * its own visible activity, which is legal on Android 7, 10 and 13 alike.
 *
 * It also means the operator never sees the ROM launcher. The terminal looks like it is recovering,
 * because it is.
 *
 * Everything here must work with NO app initialisation: MF919 deliberately skips its setup in this
 * process (no device service, no HTTP server, no WorkManager, and critically no file logging --
 * two processes appending to the same log file would interleave). Logcat only, and nothing that
 * reaches into the rest of the app.
 */
class CrashRecoveryActivity : AppCompatActivity() {

    private var timer: CountDownTimer? = null
    private var relaunched = false

    private lateinit var countdownView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_recovery)

        // There is nothing to go back to -- the app that owned the back stack is gone.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val crashId = intent?.getStringExtra(EXTRA_CRASH_ID) ?: "unknown"
        val delayMs = intent?.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS) ?: DEFAULT_DELAY_MS
        val attempt = intent?.getIntExtra(EXTRA_ATTEMPT, 1) ?: 1
        val deadPid = intent?.getIntExtra(EXTRA_MAIN_PID, -1) ?: -1

        Log.w(TAG, "Recovering: crashId=$crashId attempt=$attempt delayMs=$delayMs deadPid=$deadPid")

        countdownView = findViewById(R.id.crashRecoveryCountdown)
        findViewById<TextView>(R.id.crashRecoveryReference).text =
            getString(R.string.crash_recovery_reference, crashId)

        // Backstop only: CrashHandler's watchdog should already have killed it. If that somehow
        // did not happen, relaunching MainActivity into a half-dead process would be worse than
        // the crash, so make sure it is gone. Same UID, so this is permitted.
        killIfStillAlive(deadPid)

        // Waiting is the operator's cue that something is happening; tapping skips it.
        findViewById<android.view.View>(R.id.crashRecoveryRoot).setOnClickListener { relaunchNow() }

        startCountdown(delayMs)
    }

    private fun killIfStillAlive(pid: Int) {
        if (pid <= 0 || pid == Process.myPid()) return
        try {
            Process.killProcess(pid)
        } catch (t: Throwable) {
            // Already dead, or the OS refused. Either way the relaunch below is what matters.
            Log.w(TAG, "Could not kill pid $pid: $t")
        }
    }

    private fun startCountdown(delayMs: Long) {
        if (delayMs <= 0L) {
            relaunchNow()
            return
        }

        timer = object : CountDownTimer(delayMs, TICK_MS) {
            override fun onTick(msLeft: Long) {
                // Round up, so a 2000ms wait reads "2 s" rather than flashing "1 s" immediately.
                val secondsLeft = ((msLeft + 999) / 1000).toInt()
                countdownView.text = getString(R.string.crash_recovery_countdown, secondsLeft)
            }

            override fun onFinish() = relaunchNow()
        }.start()
    }

    /**
     * Relaunch is idempotent: the countdown finishing and an operator tap can race, and starting
     * MainActivity twice would leave a stray task behind.
     */
    private fun relaunchNow() {
        if (relaunched) return
        relaunched = true

        timer?.cancel()
        timer = null
        countdownView.setText(R.string.crash_recovery_starting)

        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                // CLEAR_TASK, not CLEAR_TOP: MainActivity finishes itself after routing to the
                // home screen, so it is usually not on the stack for CLEAR_TOP to clear back to,
                // and the crashed screen would survive underneath.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
            Log.w(TAG, "Relaunched MainActivity")
        } catch (t: Throwable) {
            // Nothing left to fall back to from here. Log it and get off the screen so the ROM
            // launcher is at least usable; the sticky AppServices restart is the last line.
            Log.e(TAG, "Relaunch failed", t)
        }

        finish()
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CrashRecovery"
        private const val TICK_MS = 250L
        private const val DEFAULT_DELAY_MS = 2_000L

        const val EXTRA_CRASH_ID = "crash_id"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_ATTEMPT = "attempt"
        const val EXTRA_MAIN_PID = "main_pid"
    }
}
