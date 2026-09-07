package com.sc.mf919.kotlin.helper_common

import com.sc.mf919.kotlin.data_enum.variables.TransData
import kotlinx.coroutines.*

abstract class CoroutineTask<Params, Result> {


    protected open fun onPreExecute() {}

    protected abstract fun doInBackground(vararg params: Params): Result?

    protected open fun onPostExecute(result: Result?) {}


    private val mUiScope by lazy { CoroutineScope(Dispatchers.Main) }

    private lateinit var mJob: Job

    // Ownership token, captured the instant execute() runs: mUiScope is a fresh, unbound
    // scope with no tie to the calling Activity's lifecycle, so this task's onPostExecute
    // can still fire after the triggering Activity is destroyed AND a newer transaction has
    // already called TransData.reset(). Subclasses whose onPostExecute writes TransData
    // fields (mid/tid/acqCode/approvalCode/etc.) before navigating MUST check
    // isSessionCurrent() first and skip those writes if stale — same pattern as
    // QrScanActivity/GenerateQrActivity. See obsidian FIX-2026-08-03-TransData-Session-Clobber-Settlement-NPE.
    private var txnSession = 0L

    protected fun isSessionCurrent(): Boolean = TransData.isCurrentSession(txnSession)

    // doInBackground runs via withContext (NOT async): a failed async child cancels the
    // parent job and crashes the app even when await() is inside try/catch, whereas
    // withContext rethrows only here where we can contain it. The finally guarantees
    // onPostExecute always runs — tasks pair a progress-dialog show in onPreExecute
    // with a hide in onPostExecute, and the dialog is non-cancelable, so skipping it
    // on an exception or cancel() would leave the terminal frozen behind the dialog.
    fun execute(vararg params: Params) {
        txnSession = TransData.sessionId
        mJob = mUiScope.launch {
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
        }
    }

    fun cancel() {
        if (::mJob.isInitialized && mJob.isActive) mJob.cancel()
    }

    fun cancelAll() {
        if (mUiScope.isActive) mUiScope.cancel()
    }
}