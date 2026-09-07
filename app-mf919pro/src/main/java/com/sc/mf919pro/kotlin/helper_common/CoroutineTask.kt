package com.sc.mf919pro.kotlin.helper_common

import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import kotlinx.coroutines.*

abstract class CoroutineTask<Params, Result> {

    /**
     * Reusable ownership guard for subclasses.
     *
     * `execute()` runs on `CoroutineScope(Dispatchers.Main)` with **no Activity/Fragment lifecycle
     * tie**, so a task outlives the screen that started it: `onPostExecute` can fire after a newer
     * transaction has already called `TransData.reset()`. Any subclass writing `TransData` from
     * `onPostExecute` (or reading it back to persist a record) must gate on the session it was
     * started with:
     *
     * ```
     * private val session = TransData.sessionId      // captured at construction
     * override fun onPostExecute(result: X?) {
     *     if (!isSessionCurrent()) return            // a newer txn owns TransData now
     *     ...
     * }
     * ```
     *
     * Provided on the base class rather than copied per subclass so every current and future task
     * has it to hand — the same reason MF919 added it there.
     */
    private val startedSession: Long = TransData.sessionId

    /** True while the transaction this task was created for still owns [TransData]. */
    protected fun isSessionCurrent(): Boolean = TransData.isCurrentSession(startedSession)

    /** The session this task was constructed under, for logging or an explicit check. */
    protected fun taskSession(): Long = startedSession

    protected open fun onPreExecute() {}

    protected abstract fun doInBackground(vararg params: Params): Result?

    protected open fun onPostExecute(result: Result?) {}


    private val mUiScope by lazy { CoroutineScope(Dispatchers.Main) }

    private lateinit var mJob: Job

    // doInBackground runs via withContext (NOT async): a failed async child cancels the
    // parent job and crashes the app even when await() is inside try/catch, whereas
    // withContext rethrows only here where we can contain it. The finally guarantees
    // onPostExecute always runs — tasks pair showProgress in onPreExecute with
    // hideProgress in onPostExecute, and the dialog is non-cancelable, so skipping it
    // on an exception or cancel() would leave the terminal frozen behind the dialog.
    private suspend fun runTask(vararg params: Params): Result? {
        // run on UI/Main thread
        onPreExecute()

        var result: Result? = null
        try {
            // execute on worker thread
            result = withContext(Dispatchers.IO) { doInBackground(*params) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // return result on UI/Main thread
            onPostExecute(result)
        }
        return result
    }

    fun execute(vararg params: Params) {
        mJob = mUiScope.launch {
            runTask(*params)
        }
    }

    fun executeAwait(vararg params: Params): Deferred<Unit> {
        return mUiScope.async {
            runTask(*params)
            Unit
        }
    }

    fun executeAwaitResult(vararg params: Params): Deferred<Result?> {
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