package com.sc.mf919pro.kotlin.activity

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
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.morefun.yapi.device.printer.OnPrintListener
import com.morefun.yapi.device.printer.PrinterConfig
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.device.DeviceHelper
import com.sc.mf919pro.kotlin.helper_common.CoroutineTask
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.checkTerminalPIN
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import org.apache.commons.lang3.StringUtils
import java.util.*

open class ActivityBase : AppCompatActivity() {
	var alertDialog: AlertDialog? = null
	protected var alertDialog_1: AlertDialog? = null
	private var mListener: onAlertDialogListener? = null
	protected var terminalPIN: String? = null

	fun passwordAlertDialog(id: Int, expectedResult: String, _mListener: onAlertDialogListener?) {
		mListener = _mListener
		val keyListener = DialogInterface.OnKeyListener { dialog, keyCode, KEvent -> keyCode == KeyEvent.KEYCODE_HOME }
		val alertDialogBuilder = AlertDialog.Builder(this)
		alertDialogBuilder.setOnKeyListener(keyListener)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_passwordlock, null
		)
		val edTx = dialogView.findViewById<EditText>(R.id.passwordString)
		edTx.setOnEditorActionListener(
			OnEditorActionListener { textView, actionId, keyEvent ->
				if (actionId == EditorInfo.IME_ACTION_SEND) {
					val isOK = expectedResult == edTx.text.toString()
					mListener!!.onResult(id, true, isOK)
					edTx.setText("")
					if (isOK) alertDialog?.dismiss()
					return@OnEditorActionListener true
				}
				false
			})
		val position = dialogView.findViewById<TextView>(R.id.positionBtn)
		position.setOnClickListener {
			val isOK = expectedResult == edTx.text.toString()
			mListener!!.onResult(id, true, isOK)
			edTx.setText("")
			if (isOK) alertDialog?.dismiss()
		}
		val negative = dialogView.findViewById<TextView>(R.id.negativeBtn)
		negative.setOnClickListener {
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
	// an executing FragmentManager transaction. See BaseFragment.progressHandler.
	private val progressHandler = Handler(Looper.getMainLooper())

	fun showProgress(title: String, msg: String) {
		progressHandler.post {
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

	protected fun print(list: List<MulPrintStrEntity?>?) {
		try {
			//int fontSize = FontFamily.MIDDLE;
			val config = Bundle()
			//config.putString(PrinterConfig.COMMON_TYPEFACE_PATH, fontPath);
			config.putInt(PrinterConfig.COMMON_GRAYLEVEL, 30)
			DeviceHelper.getMultipleAppPrinter().printStr(list, object : OnPrintListener.Stub() {
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

	fun ToastMake(msg: String?, duration: Int) {
		runOnUiThread { Toast.makeText(this@ActivityBase, msg, duration).show() }
	}

	fun PINDialog(type: String?, activityBack: Boolean) {
		val keyListener = DialogInterface.OnKeyListener { dialog, keyCode, KEvent -> keyCode == KeyEvent.KEYCODE_HOME }
		val alertDialogBuilder = AlertDialog.Builder(this)
		alertDialogBuilder.setOnKeyListener(keyListener)
		val inflater = this.layoutInflater
		@SuppressLint("InflateParams") val dialogView = inflater.inflate(
			R.layout.activity_passwordlock, null
		)
		val edTx = dialogView.findViewById<EditText>(R.id.passwordString)
		edTx.setOnEditorActionListener(
			OnEditorActionListener { textView, actionId, keyEvent ->
				if (actionId == EditorInfo.IME_ACTION_SEND) {
					if (edTx.text.length != 6 || edTx.text.isEmpty()) {
						edTx.setText("")
						Toast.makeText(
							applicationContext,
							"Invalid PIN",
							Toast.LENGTH_SHORT
						).show()
					} else {
						terminalPIN = edTx.text.toString()
						VoidSaleTask().execute(terminalPIN, type)
						edTx.setText("")
					}
					return@OnEditorActionListener true
				}
				false
			})
		val position = dialogView.findViewById<TextView>(R.id.positionBtn)
		position.setOnClickListener {
			if (edTx.text.length != 6 || edTx.text.isEmpty()) {
				edTx.setText("")
				Toast.makeText(
					applicationContext,
					"Invalid PIN",
					Toast.LENGTH_SHORT
				).show()
			} else {
				terminalPIN = edTx.text.toString()
				VoidSaleTask().execute(terminalPIN, type)
				edTx.setText("")
			}
		}
		val negative = dialogView.findViewById<TextView>(R.id.negativeBtn)
		negative.setOnClickListener {
			edTx.setText("")
			if(activityBack){
				onBackPressedDispatcher.onBackPressed()
			} else {
				try {
					alertDialog_1?.dismiss()
					alertDialog?.dismiss()
				} catch (e: Exception) {
					e.printStackTrace()
				}
			}
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
	private inner class VoidSaleTask : CoroutineTask<String?, Boolean>() {
		override fun onPreExecute() {
			showProgress("Verifying PIN", "Loading...")
			super.onPreExecute()
		}

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

						checkTerminalPIN(log, this@ActivityBase, it, it1)
					}
				}
			} catch (e: Exception) {
				e.printStackTrace()
			}
			return false
		}

		override fun onPostExecute(result: Boolean?) {
			super.onPostExecute(result)
			hideProgress()
			if (result == true) {
				alertDialog_1!!.dismiss()
			} else {
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
