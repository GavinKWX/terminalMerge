package helpers

import iso.CurrentTxn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AsyncTask-style base for background work started from a screen. Moved to `:core` from both apps
 * (audit item 105). It has Pro's `executeAwait`/`executeAwaitResult`, and the session is captured
 * when the task **runs** in every entry point, which was MF919's choice and is now both apps'.
 */
abstract class CoroutineTask<Params, Result> {

    protected open fun onPreExecute() {}

    protected abstract fun doInBackground(vararg params: Params): Result?

    protected open fun onPostExecute(result: Result?) {}

    private val mUiScope by lazy { CoroutineScope(Dispatchers.Main) }

    private lateinit var mJob: Job

    // Ownership token, captured when the task runs: mUiScope is a fresh, unbound scope with no tie
    // to the calling screen's lifecycle, so onPostExecute can fire after the screen is gone AND a
    // newer transaction has already called TransData.reset(). Subclasses whose onPostExecute writes
    // TransData must check isSessionCurrent() first and skip those writes if stale.
    // See obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
    @Volatile
    private var txnSession = 0L

    /** True while the transaction this task ran for still owns TransData. */
    protected fun isSessionCurrent(): Boolean = CurrentTxn.currentSessionId() == txnSession

    /** The session this task ran under, for logging or an explicit check. */
    protected fun taskSession(): Long = txnSession

    // doInBackground runs via withContext (NOT async): a failed async child cancels the parent job
    // and crashes the app even when await() is inside try/catch, whereas withContext rethrows only
    // here where we can contain it. The finally guarantees onPostExecute always runs -- tasks pair a
    // progress-dialog show in onPreExecute with a hide in onPostExecute, and the dialog is
    // non-cancelable, so skipping it on an exception or cancel() would leave the terminal frozen.
    private suspend fun runTask(vararg params: Params): Result? {
        onPreExecute()
        var result: Result? = null
        try {
            result = withContext(Dispatchers.IO) { doInBackground(*params) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            onPostExecute(result)
        }
        return result
    }

    fun execute(vararg params: Params) {
        txnSession = CurrentTxn.currentSessionId()
        mJob = mUiScope.launch {
            runTask(*params)
        }
    }

    fun executeAwait(vararg params: Params): Deferred<Unit> {
        txnSession = CurrentTxn.currentSessionId()
        return mUiScope.async {
            runTask(*params)
            Unit
        }
    }

    fun executeAwaitResult(vararg params: Params): Deferred<Result?> {
        txnSession = CurrentTxn.currentSessionId()
        return mUiScope.async {
            runTask(*params)
        }
    }

    fun cancel() {
        if (::mJob.isInitialized && mJob.isActive) mJob.cancel()
    }

    fun cancelAll() {
        if (mUiScope.isActive) mUiScope.cancel()
    }
}
