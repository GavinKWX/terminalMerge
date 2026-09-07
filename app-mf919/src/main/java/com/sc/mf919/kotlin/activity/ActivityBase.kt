package com.sc.mf919.kotlin.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.text.Html
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
import androidx.lifecycle.Lifecycle
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.morefun.yapi.device.printer.OnPrintListener
import com.morefun.yapi.device.printer.PrinterConfig
import com.sc.mf919.R
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.java.device.DeviceHelper
import com.sc.mf919.kotlin.helper_common.CoroutineTask
import com.sc.mf919.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919.kotlin.helper_common.TmsHelper.checkTerminalPIN
import enums.EnumLogFileName
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import org.apache.commons.lang3.StringUtils
import java.lang.ref.WeakReference
import java.util.*

open class ActivityBase : AppCompatActivity() {
	protected var alertDialog: AlertDialog? = null
	protected var alertDialog_1: AlertDialog? = null
	private var mListener: onAlertDialogListener? = null
	protected var pDTitle: String? = null
	protected var pDMsg: String? = null
	protected var terminalPIN: String? = null

	fun passwordAlertDialog(id: Int, expectedResult: String, _mListener: onAlertDialogListener?) {
		logPinGate("Dialog opened :: [PASSWORD LOCK] id=$id")
		mListener = _mListener
		val keyListener = DialogInterface.OnKeyListener { dialog, keyCode, KEvent -> keyCode == KeyEvent.KEYCODE_HOME }
		val alertDialogBuilder = AlertDialog.Builder(this)
		alertDialogBuilder.setOnKeyListener(keyListener)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.actvivty_passwordlock, null
		)
		val edTx = dialogView.findViewById<EditText>(R.id.passwordString)
		edTx.setOnEditorActionListener(
			OnEditorActionListener { textView, actionId, keyEvent ->
				if (actionId == EditorInfo.IME_ACTION_SEND) {
					mListener!!.onResult(id, true, expectedResult == edTx.text.toString())
					edTx.setText("")
					return@OnEditorActionListener true
				}
				false
			})
		val position = dialogView.findViewById<TextView>(R.id.positionBtn)
		position.setOnClickListener {
			mListener!!.onResult(id, true, expectedResult == edTx.text.toString())
			edTx.setText("")
		}
		val negative = dialogView.findViewById<TextView>(R.id.negativeBtn)
		negative.setOnClickListener {
			logPinGate("User Cancel :: dismissed [PASSWORD LOCK] id=$id")
			mListener!!.onResult(id, false, expectedResult == edTx.text.toString())
			alertDialog?.dismiss()
		}
		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(true)
		alertDialog = alertDialogBuilder.create()
		val lp = WindowManager.LayoutParams()
		lp.copyFrom(Objects.requireNonNull(alertDialog?.window)!!.attributes)
		lp.width = 600
		alertDialog?.window!!.attributes = lp
		alertDialog?.show()
	}

	// Always POSTED (never inline) so showNow's commitNow can never run inside
	// an executing FragmentManager transaction. One FIFO queue keeps
	// show/update/hide ordering identical for all callers.
	private val progressHandler = Handler(Looper.getMainLooper())

	fun showProgress(title: String, msg: String) {
		progressHandler.post {
			// A manually-constructed helper instance (never attached, stays INITIALIZED)
			// has no FragmentManager host — showNow on it throws IllegalStateException
			if (lifecycle.currentState == Lifecycle.State.INITIALIZED) return@post
			if (isFinishing || isDestroyed) return@post
			val fm = supportFragmentManager
			if (fm.isStateSaved || fm.isDestroyed) return@post
			// isRemoving: a just-dismissed dialog stays findable by tag until its async
			// removal commits — updating it would show nothing, so treat it as absent
			val existing = fm.findFragmentByTag(ProgressDialogFragment.TAG) as? ProgressDialogFragment
			if (existing != null && !existing.isRemoving) {
				existing.update(title = title, message = msg)
				return@post
			}
			// showNow commits synchronously so a second showProgress/updateProgress
			// call can find the dialog by tag immediately (no stacked dialogs)
			ProgressDialogFragment.newInstance(title, msg)
				.showNow(fm, ProgressDialogFragment.TAG)
		}
	}

	fun updateProgress(title: String? = null, msg: String? = null) {
		progressHandler.post {
			if (isDestroyed) return@post
			(supportFragmentManager.findFragmentByTag(ProgressDialogFragment.TAG) as? ProgressDialogFragment)
				?.update(title, msg)
		}
	}

	fun hideProgress() {
		progressHandler.post {
			if (isDestroyed) return@post
			(supportFragmentManager.findFragmentByTag(ProgressDialogFragment.TAG) as? ProgressDialogFragment)
				?.dismissAllowingStateLoss()
		}
	}

	// Legacy progress API: the AlertDialog implementation was replaced by
	// ProgressDialogFragment (survives config changes, never stacks, ordered
	// show/update/hide). Signatures kept so existing call sites need no change.
	//
	// mContext routing: some flows construct an ActivityBase subclass manually and
	// use it as a helper object (e.g. BnplScanActivity().closeProgressDialog()).
	// Such an instance has no attached FragmentManager, so the dialog must be hosted
	// by the real activity passed as mContext — exactly like the old implementation,
	// which built its AlertDialog on mContext rather than `this`. close/update on the
	// helper instance follow the same remembered host.
	private var progressHostRef: WeakReference<ActivityBase>? = null

	private fun progressHost(): ActivityBase = progressHostRef?.get() ?: this

	fun startProgressDialog(mContext: Context?, title: String, msg: String) {
		val host = (mContext as? ActivityBase)?.takeIf { it !== this }
		progressHostRef = host?.let { WeakReference(it) }
		(host ?: this).showProgress(title, msg)
	}

	fun closeProgressDialog() {
		progressHost().hideProgress()
	}

	protected var changeMessage = Runnable {
		pDMsg?.let { progressHost().updateProgress(msg = it) }
	}
	protected var changeTitle = Runnable {
		pDTitle?.let { progressHost().updateProgress(title = it) }
	}

	protected fun print(list: List<MulPrintStrEntity?>?) {
		try {
			//int fontSize = FontFamily.MIDDLE;
			val config = Bundle()
			//config.putString(PrinterConfig.COMMON_TYPEFACE_PATH, fontPath);
			config.putInt(PrinterConfig.COMMON_GRAYLEVEL, 30)
			DeviceHelper.getPrinter().printStr(list, object : OnPrintListener.Stub() {
				@Throws(RemoteException::class)
				override fun onPrintResult(result: Int) {
					/*this.runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            //button.setEnabled(true);
                        }
                    });*/
					//showResult(textView, result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
					//this.sysPrint(result == ServiceResult.Success ? getString(R.string.msg_succ) : getString(R.string.msg_fail));
				}
			}, config)
		} catch (e: RemoteException) {
			e.printStackTrace()
		}
	}

	protected fun ToastMake(mContext: Context?, msg: String?, duration: Int) {
		runOnUiThread { Toast.makeText(mContext, msg, duration).show() }
	}

	/**
	 * One-off log line for a PIN gate event. Built per call rather than held in a field:
	 * some flows construct an ActivityBase subclass manually and use it as a plain helper
	 * object, so no lateinit field of this class can be relied on here.
	 * Records the OUTCOME only -- never the entered PIN.
	 */
	private fun logPinGate(msg: String, fileName: EnumLogFileName = EnumLogFileName.TerminaLog) {
		val className = this.javaClass.simpleName
		val log = HelperLog(
			getSession(),
			checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			"Terminal PIN Verification",
			className,
			className
		)
		log.appendLine(className, msg)
		log.logToFile(fileName)
	}

	fun PINDialog(type: String?, activityBack: Boolean, onPinConfirmed: ((pin: String, type: String?) -> Unit)? = null, onPinCancel: (() -> Unit)? = null) {
		val keyListener = DialogInterface.OnKeyListener { dialog, keyCode, KEvent -> keyCode == KeyEvent.KEYCODE_HOME }
		val alertDialogBuilder = AlertDialog.Builder(this)
		alertDialogBuilder.setOnKeyListener(keyListener)
		val inflater = this.layoutInflater

		@SuppressLint("InflateParams")
		val dialogView = inflater.inflate(R.layout.actvivty_passwordlock, null)
		val edTx = dialogView.findViewById<EditText>(R.id.passwordString)
		edTx.setOnEditorActionListener(
			OnEditorActionListener { textView, actionId, keyEvent ->
				if (actionId == EditorInfo.IME_ACTION_SEND) {

					if (edTx.text.length != 6 || edTx.text.isEmpty()) {
						logPinGate("REJECT :: PIN must be 6 digits [TERMINAL PIN]")
						edTx.setText("")
						Toast.makeText(applicationContext, "Invalid PIN", Toast.LENGTH_SHORT).show()
					} else {
						terminalPIN = edTx.text.toString()
						VoidSaleTask(onPinConfirmed).execute(terminalPIN, type)
						edTx.setText("")
					}
					return@OnEditorActionListener true
				}
				false
			})

		val position = dialogView.findViewById<TextView>(R.id.positionBtn)
		position.setOnClickListener {
			if (edTx.text.length != 6 || edTx.text.isEmpty()) {
				logPinGate("REJECT :: PIN must be 6 digits [TERMINAL PIN]")
				edTx.setText("")
				Toast.makeText(applicationContext, "Invalid PIN", Toast.LENGTH_SHORT).show()
			} else {
				terminalPIN = edTx.text.toString()
				VoidSaleTask(onPinConfirmed).execute(terminalPIN, type)
				edTx.setText("")
			}
		}

		val negative = dialogView.findViewById<TextView>(R.id.negativeBtn)
		negative.setOnClickListener {
			logPinGate("User Cancel :: dismissed [TERMINAL PIN]")
			edTx.setText("")
			if(activityBack) {
				onBackPressedDispatcher.onBackPressed()
			} else {
				try {
					alertDialog_1?.dismiss()
					alertDialog?.dismiss()
				} catch (e: Exception) {
					e.printStackTrace()
				}
			}
			onPinCancel?.invoke()
		}

		alertDialogBuilder.setView(dialogView)
		alertDialogBuilder.setCancelable(false)
		alertDialog_1 = alertDialogBuilder.create()
		val lp = WindowManager.LayoutParams()
		lp.copyFrom(Objects.requireNonNull(alertDialog_1!!.window)!!.attributes)
		lp.width = 600
		alertDialog_1!!.window!!.attributes = lp
		alertDialog_1!!.show()
	}


	@SuppressLint("StaticFieldLeak")
	private inner class VoidSaleTask( private val onPinConfirmed: ((String, String?) -> Unit)? = null): CoroutineTask<String?, Boolean>() {
		override fun onPreExecute() {
			startProgressDialog(this@ActivityBase, "Verifying PIN", "Loading...")
			super.onPreExecute()
		}
		var terminalPin = ""
		var type = ""

		@RequiresApi(api = Build.VERSION_CODES.O)
		override fun doInBackground(vararg params: String?): Boolean? {
			try {
				return params[0]?.let {
					params[1]?.let { it1 ->
						val log = HelperLog(
							getSession(),
							checkIsConnectedWifi(applicationContext),
							Utils.getIPAddress(),
							"Setting Download Configuration",
							this@ActivityBase.javaClass.simpleName,
							this@ActivityBase.javaClass.name
						)

						terminalPin = it
						type = it1
						val pinResult = checkTerminalPIN(log, this@ActivityBase, it, it1)
						log.logToFile(EnumLogFileName.TerminaLog)
						pinResult
					}
				}
			} catch (e: Exception) {
				logPinGate("PIN verification error :: ${e.message}", EnumLogFileName.TerminaLogException)
				e.printStackTrace()
			}
			return false
		}

		override fun onPostExecute(result: Boolean?) {
			super.onPostExecute(result)
			closeProgressDialog()
			// PIN gate outcome only -- the entered PIN itself is never logged.
			if (result == true) {
				logPinGate("PIN check :: pass [TERMINAL PIN] type=$type")
				alertDialog_1?.dismiss()
                onPinConfirmed?.invoke(terminalPin, type)
			} else {
				logPinGate("REJECT :: PIN check failed [TERMINAL PIN] type=$type")
				Toast.makeText(this@ActivityBase, "Incorrect PIN", Toast.LENGTH_SHORT).show()
			}
		}
	}

	fun parseEppDetailsReceipt(eppDe63: String?, isCZ: Boolean): Array<String?> {
		val eppDetails = arrayOfNulls<String>(4)
		val strEppDetails = StringUtils.trim(eppDe63)
		if (strEppDetails == null) {
			eppDetails[0] = ""
			eppDetails[1] = "0.00"
			eppDetails[2] = "0.00"
			eppDetails[3] = "0.00"
		} else {
			eppDetails[0] = "EPP " + String.format("%02d", strEppDetails.substring(0, 3).toInt()) + " Month" //Tenure
			if (isCZ){
				eppDetails[1] = Utils.getActualAmount(strEppDetails.substring(4, 15)) //First Amt
				eppDetails[2] = Utils.getActualAmount(strEppDetails.substring(16, 27)) //Monthly Amt
				eppDetails[3] = Utils.getActualAmount(strEppDetails.substring(40)) //Total Amt
			} else {
				eppDetails[1] = Utils.getActualAmount(strEppDetails.substring(48)) //Total Amt
				eppDetails[2] = Utils.getActualAmount(strEppDetails.substring(22, 35)) //Monthly Amt
				eppDetails[3] = Utils.getActualAmount(strEppDetails.substring(9, 22)) //Final Amt
			}

		}
		Utils.debugLogPrint("TAG", "eppDetails = " + eppDetails.contentToString())
		return eppDetails
	}

}
