package com.sc.mf919pro.kotlin.fragment

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.library.terminal.Utility
import com.morefun.yapi.device.bluetoothprinter.Constants
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.activity.Utils.TextItem
import com.sc.mf919pro.kotlin.activity.ProgressDialogFragment
import com.sc.mf919pro.kotlin.activity.onAlertDialogListener
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.data_enum.variables.TransDataViewModel
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig.Companion.getSafeValue
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.CoroutineTask
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.Helper.Companion.getInstance
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.checkIsConnectedWifi
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.checkTerminalPIN
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Companion.getSession
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.lang3.StringUtils
import tms.models.AcquirerLogoReplaceObject
import java.io.File
import java.io.IOException
import java.util.Objects
import androidx.core.graphics.createBitmap

abstract class BaseFragment : Fragment() {
    // For UI stuff
    protected fun uiContext(): Context =
        context ?: requireActivity().applicationContext

    // For app-level stuff
    protected fun appContext(): Context =
        activity?.applicationContext ?: requireContext().applicationContext

    var alertDialog: AlertDialog? = null
    protected var alertDialog1: AlertDialog? = null
    private var mListener: onAlertDialogListener? = null

    protected var terminalPIN: String? = null

    protected val transDataViewModel: TransDataViewModel
        get() = ViewModelProvider(requireActivity())[TransDataViewModel::class.java]

    protected val transData: TransData
        get() = transDataViewModel.data


    override fun onResume() {
        super.onResume()
        val keyguardManager = requireContext().getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            keyguardManager.requestDismissKeyguard(requireActivity(), null)
        }
    }

    // 👇 Add common methods usable in all fragments
    protected fun showToast(message: String, duration: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, duration).show()
        }
    }

    protected fun showToast(ctx: Context, message: String, duration: Int) {
        Toast.makeText(ctx, message, duration).show()
    }


    protected fun showBottomGroup(view: View, isShow: Boolean) {
        val bottomNavWrapper = view.findViewById<View>(R.id.bottomNavWrapper)
        bottomNavWrapper.visibility = if (isShow) {View.VISIBLE} else View.GONE
    }

    protected fun showBottomNav(show: Boolean) {
        val bottomNavWrapper = requireActivity().findViewById<View>(R.id.bottomNavWrapper)
        bottomNavWrapper?.visibility = if (show) View.VISIBLE else View.GONE


    }

    protected fun navigateToHome(dbModelTerminalConfig: DbModelTerminalConfig?) {
        var homeFragment =  R.id.attendFragment
        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "DENOMINATION")) {
            homeFragment = R.id.attendDenominationFragment
        } else if (DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")) {
            homeFragment = R.id.unAttendFragment
        }

        findNavController().navigate(
            homeFragment,
            null,
            NavOptions.Builder()
                .setPopUpTo(findNavController().graph.startDestinationId, true) // clear all previous destinations
                .build()
        )
    }

    protected fun navigateSafe(@IdRes actionId: Int, args: Bundle? = null) {
        // Always use the parent fragment's NavController if nested
        val navController = if (parentFragment != null) {
            NavHostFragment.findNavController(requireParentFragment())
        } else {
            findNavController()
        }

        navController.currentDestination?.getAction(actionId)?.let {
            navController.navigate(actionId, args)
        }
    }

    protected fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    fun pinDialog(type: String?, onCancel: (canceled: Boolean) -> Unit = {}) {
        val keyListener = DialogInterface.OnKeyListener { dialog, keyCode, KEvent -> keyCode == KeyEvent.KEYCODE_HOME }

        val alertDialogBuilder = AlertDialog.Builder(requireContext())
        alertDialogBuilder.setOnKeyListener(keyListener)
        val inflater = LayoutInflater.from(requireContext())
        @SuppressLint("InflateParams") val dialogView = inflater.inflate(
            R.layout.activity_passwordlock, null
        )
        val edTx = dialogView.findViewById<EditText>(R.id.passwordString)
        edTx.setOnEditorActionListener(
            OnEditorActionListener { textView, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    if (edTx.text.length != 6 || edTx.text.isEmpty()) {
                        edTx.setText("")
                        showToast("Invalid PIN", Toast.LENGTH_SHORT)
                    } else {
                        terminalPIN = edTx.text.toString()
                        edTx.setText("")
                        CoroutineScope(Dispatchers.IO).launch {
                            val dialogResult = VoidSaleTask().executeAwaitResult(terminalPIN, type).await()
                            if(dialogResult == true){
                                alertDialog1?.dismiss()
                                onCancel(false)
                            }
                        }
                    }
                    return@OnEditorActionListener true
                }
                false
            })
        val position = dialogView.findViewById<TextView>(R.id.positionBtn)
        position.setOnClickListener {
            if (edTx.text.length != 6 || edTx.text.isEmpty()) {
                edTx.setText("")
                showToast("Invalid PIN", Toast.LENGTH_SHORT)
            } else {
                terminalPIN = edTx.text.toString()
                edTx.setText("")
                CoroutineScope(Dispatchers.IO).launch {
                    val dialogResult = VoidSaleTask().executeAwaitResult(terminalPIN, type).await()
                    if(dialogResult == true){
                        alertDialog1?.dismiss()
                        onCancel(false)
                    }
                }
            }
        }
        val negative = dialogView.findViewById<TextView>(R.id.negativeBtn)
        negative.setOnClickListener {
            edTx.setText("")
            /*if(fragmentBack){
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } else {
                try {
                    alertDialog1?.dismiss()
                    alertDialog?.dismiss()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }*/
            try {
                alertDialog1?.dismiss()
                //alertDialog?.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onCancel(true)
        }
        alertDialogBuilder.setView(dialogView)
        alertDialogBuilder.setCancelable(false)
        alertDialog1 = alertDialogBuilder.create()
        val lp = WindowManager.LayoutParams()
        lp.copyFrom(Objects.requireNonNull(alertDialog1!!.window)?.attributes)
        lp.width = 600
        alertDialog1!!.window!!.attributes = lp
        alertDialog1!!.show()
    }

    @SuppressLint("StaticFieldLeak")
    private inner class VoidSaleTask : CoroutineTask<String?, Boolean>() {
        override fun onPreExecute() {
            showProgress("Verifying PIN", "Loading...")
            super.onPreExecute()
        }

        override fun doInBackground(vararg params: String?): Boolean? {
            try {
                return params[0]?.let {
                    params[1]?.let { it1 ->
                        val log = HelperLog(
                            getSession(),
                            checkIsConnectedWifi(requireContext()),
                            Utils.getIPAddress(),
                            "Setting Download Configuration",
                            this@BaseFragment.javaClass.simpleName,
                            this@BaseFragment.javaClass.name
                        )

                        checkTerminalPIN(log, requireContext(), it, it1)
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
                alertDialog1!!.dismiss()
            } else {
                showToast("Incorrect PIN", Toast.LENGTH_SHORT)
            }
        }
    }

    fun passwordAlertDialog(id: Int, expectedResult: String, _mListener: onAlertDialogListener?) {
        mListener = _mListener
        val keyListener =
            DialogInterface.OnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_HOME }
        val alertDialogBuilder = AlertDialog.Builder(requireContext())
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

    fun getImageFromAssetsFile(context: Context, fileName: String?): Bitmap? {
        var image: Bitmap? = null
        val am = context.resources.assets
        try {
            val `is` = am.open(fileName!!)
            image = BitmapFactory.decodeStream(`is`)
            `is`.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        Utils.debugLogPrint(
            "getImageFromAssetsFile",
            "Bitmpa =$image"
        )
        return image
    }

    fun parseEppDetailsReceipt(eppDe63: String?, isCZ: Boolean): Array<String?> {
        val eppDetails = arrayOfNulls<String>(4)
        val strEppDetails = StringUtils.trim(eppDe63)
        if (strEppDetails.isNullOrEmpty()) {
            eppDetails[0] = ""
            eppDetails[1] = "0.00"
            eppDetails[2] = "0.00"
            eppDetails[3] = "0.00"
        } else {
            eppDetails[0] = "EPP " + String.format(
                "%02d",
                strEppDetails.substring(0, 3).toInt()
            ) + " Month" //Tenure
            if (isCZ) {
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

    fun formLayout(
        log: HelperLog,
        helperLogClassName: String,
        details: Array<String>,
        mainReceipt: LinearLayout,
        headerImageView: ImageView,
        specialArg: Bundle
    ) {
        val isTpa = specialArg.getBoolean("isTpa", false)
        mainReceipt.removeAllViews()
        var acqLogoEnumModel = AcquirerLogoEnumModel(
            R.mipmap.blank,
            "image/logo_footer_small.bmp",
            R.mipmap.ic_launcher,
            resources.getString(R.string.app_name_about),
            resources.getString(R.string.app_name_about),
        )

        var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig.AcqCode ?: "").data
            } catch (e: Exception) {
                e.printStackTrace()
                log.appendLine(helperLogClassName, "Exception in Card Sales Get Acquirer Enum -> ", e.toString())
                log.logToFile(EnumLogFileName.TerminaLogException)
            }
            acquirerLogoConfig = Gson().fromJson(getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
        }

        //TODO
        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
            Utils.debugLogPrint("TAG", "onCreate: image")
        }
        //TODO

        val tags = arrayOf(
            "MERCHANT ID",
            "TERMINAL ID",
            "BATCH NO",
            "",
            "APP LABEL",
            "CARD NO",
            "DATE/TIME",
            "INV NO",
            "TRACE NO",
            "ENTRY TYPE",
            "REF NO",
            "APPROVAL CODE",
            "CASHOUT AMOUNT",
            "TOTAL",
            "",
            "ARQC",
            "AID",
            "TVR",
            ""
        )

        if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpa) {
            if(powerByBmp != null) {
                headerImageView.setImageBitmap(powerByBmp)
            } else {
                headerImageView.setImageResource(R.mipmap.logo)
            }
        } else {
            headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        }

        val name = TextView(requireContext())
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(resources.getColor(R.color.black))
        name.text = getSafeValue(dbModelMerchantConfig, "MerchantName")
        val addr = TextView(requireContext())
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(resources.getColor(R.color.black))
        addr.text = getSafeValue(dbModelMerchantConfig, "MerchantAddress")
        mainReceipt.addView(name)
        mainReceipt.addView(addr)
        for (j in details.indices) {
            if (details[j].isEmpty()) {
                continue
            }

            when (j) {
                3 -> {
                    val ll = LinearLayout(requireContext())
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    params1.weight = 1f
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 14f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = details[j]
                    ll.addView(tv)
                    mainReceipt.addView(ll)
                }

                5 -> {
                    val ll = LinearLayout(requireContext())
                    ll.orientation = LinearLayout.VERTICAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 11f
                    tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = tags[j]
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params1
                    tv1.textSize = 14f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                    tv1.setTextColor(resources.getColor(R.color.black))
                    tv1.text = details[j]
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainReceipt.addView(ll)
                }

                12 -> {
                    if (details[j] != "0") {
                        val ll = LinearLayout(requireContext())
                        ll.orientation = LinearLayout.HORIZONTAL
                        ll.weightSum = 2f
                        val params1 = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        params1.setMargins(5, 0, 5, 0)
                        params1.weight = 1f
                        val tv = TextView(requireContext())
                        tv.layoutParams = params1
                        tv.textSize = 11f
                        tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        tv.setTextColor(resources.getColor(R.color.black))
                        tv.text = tags[j]
                        val tv1 = TextView(requireContext())
                        tv1.layoutParams = params1
                        tv1.textSize = 11f
                        tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                        tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                        tv1.setTextColor(resources.getColor(R.color.black))
                        tv1.text = "RM" + details[j]
                        ll.addView(tv)
                        ll.addView(tv1)
                        mainReceipt.addView(ll)
                    }
                }

                13 -> {
                    val ll = LinearLayout(requireContext())
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    ll.orientation = LinearLayout.VERTICAL
                    ll.layoutParams = params
                    val tv4 = TextView(requireContext())
                    tv4.textSize = 11f
                    tv4.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv4.setTextColor(resources.getColor(R.color.black))
                    tv4.text = "------------------------------------------------------------------"
                    val tv3 = TextView(requireContext())
                    tv3.textSize = 11f
                    tv3.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv3.setTextColor(resources.getColor(R.color.black))
                    tv3.text = "------------------------------------------------------------------"
                    val ll_1 = LinearLayout(requireContext())
                    ll_1.orientation = LinearLayout.HORIZONTAL
                    ll_1.weightSum = 2f
                    val params2 = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params2.setMargins(5, 0, 5, 0)
                    params2.weight = 1f
                    val tv2 = TextView(requireContext())
                    tv2.layoutParams = params2
                    tv2.textSize = 14f
                    tv2.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv2.setTypeface(tv2.typeface, Typeface.BOLD)
                    tv2.setTextColor(resources.getColor(R.color.black))
                    tv2.text = tags[j]
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params2
                    tv1.textSize = 14f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                    tv1.setTextColor(resources.getColor(R.color.black))
                    tv1.text = "RM ${details[j]}"
                    ll_1.addView(tv2)
                    ll_1.addView(tv1)
                    ll.addView(tv4)
                    ll.addView(ll_1)
                    ll.addView(tv3)
                    mainReceipt.addView(ll)
                }

                14 -> {
                    val isCZ = TransData.acqCode.equals("BSN_CARDZONE", true)
                    val eppDetails = parseEppDetailsReceipt(details[j], isCZ)
                    val eppTags = if (isCZ) {
                        arrayOf("TENURE", "FIRST AMT", "MTHLY AMT", "TOTAL AMT")
                    } else {
                        arrayOf("TENURE", "TOTAL DUE", "MTHLY AMT", "FINAL AMT")
                    }

                    val ll = LinearLayout(requireContext())
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    ll.orientation = LinearLayout.VERTICAL
                    ll.layoutParams = params

                    val tv3 = TextView(requireContext())
                    tv3.textSize = 11f
                    tv3.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv3.setTextColor(resources.getColor(R.color.black))
                    tv3.text = "------------------------------------------------------------------"

                    for (k in eppDetails.indices) {
                        val ll_1 = LinearLayout(requireContext())
                        ll_1.orientation = LinearLayout.HORIZONTAL
                        ll_1.weightSum = 2f
                        val params2 = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        params2.setMargins(5, 0, 5, 0)
                        params2.weight = 1f
                        val tv2 = TextView(requireContext())
                        tv2.layoutParams = params2
                        tv2.textSize = 11f
                        tv2.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        tv2.setTextColor(resources.getColor(R.color.black))
                        tv2.text = eppTags[k]
                        val tv1 = TextView(requireContext())
                        tv1.layoutParams = params2
                        tv1.textSize = 11f
                        tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                        tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                        tv1.setTextColor(resources.getColor(R.color.black))
                        if (k == 0) {
                            tv1.text = eppDetails[k]?.let { String.format(it) }
                        } else {
                            tv1.text = "RM" + eppDetails[k]
                        }
                        ll_1.addView(tv2)
                        ll_1.addView(tv1)
                        ll.addView(ll_1)
                    }

                    ll.addView(tv3)
                    mainReceipt.addView(ll)
                }

                18 -> {
                    val ll = LinearLayout(requireContext())
                    ll.orientation = LinearLayout.HORIZONTAL
                    val params1 = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    params1.weight = 1f
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 9f
                    tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = Utils.CVMAnalysis(details[j], "")
                    ll.addView(tv)
                    mainReceipt.addView(ll)
                }

                else -> {
                    val ll = LinearLayout(requireContext())
                    ll.orientation = LinearLayout.HORIZONTAL
                    ll.weightSum = 2f
                    val params1 = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params1.setMargins(5, 0, 5, 0)
                    params1.weight = 1f
                    val tv = TextView(requireContext())
                    tv.layoutParams = params1
                    tv.textSize = 11f
                    tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.text = tags[j]
                    val tv1 = TextView(requireContext())
                    tv1.layoutParams = params1
                    tv1.textSize = 11f
                    tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                    tv1.setTextColor(resources.getColor(R.color.black))
                    tv1.text = details[j]
                    ll.addView(tv)
                    ll.addView(tv1)
                    mainReceipt.addView(ll)
                }
            }
        }
        val footer = LinearLayout(requireContext())
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        footer.setPadding(0, 0, 0, 20)
        val power1 = TextView(requireContext())
        power1.textSize = 7.5f
        power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power1.setTextColor(resources.getColor(R.color.black))
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT ACCORDING TO THE CARD ISSUER AGREEMENT"
        footer.addView(power1)
        val power = TextView(requireContext())
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(resources.getColor(R.color.black))

        val im = ImageView(requireContext())
        val params1 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30))
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true


        if (isTpa) {
            power.text = "Behind Every Payment, There's A Smile."
            footer.addView(power)
        } else {
            if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    power.text = "PARTNER WITH"
                    footer.addView(power)
                    im.setImageResource(acqLogoEnumModel.HeaderLogoPng)
                    footer.addView(im)
                }
            } else {
                power.text = "POWERED BY"
                footer.addView(power)
                if(powerByBmp != null) {
                    im.setImageBitmap(powerByBmp)
                    Utils.debugLogPrint("TAG", "onCreate: image")
                } else {
                    im.setImageResource(R.mipmap.logo)
                }
                footer.addView(im)
            }
        }
        mainReceipt.addView(footer)
    }

    fun printReceipt(receipt: ScrollView?, details: Array<String>, whoseCopy: String, specialArg: Bundle) {
        val isTpa = specialArg.getBoolean("isTpa", false)
        CoroutineScope(Dispatchers.Default).launch {
            var acqLogoEnumModel = AcquirerLogoEnumModel(
                R.mipmap.blank,
                "image/logo_footer_small.bmp",
                R.mipmap.ic_launcher,
                resources.getString(R.string.app_name_about),
                resources.getString(R.string.app_name_about),
            )
            var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            if (dbModelMerchantConfig != null) {
                try {
                    acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(dbModelMerchantConfig.AcqCode!!).data
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                acquirerLogoConfig = Gson().fromJson(getSafeValue(dbModelMerchantConfig, "AcquirerLogo"),
                    AcquirerLogoReplaceObject::class.java
                )
            }
            val acquirerBmp = getImageFromAssetsFile(requireContext(), acqLogoEnumModel.HeaderLogoBmp)
            var powerByBmp = getImageFromAssetsFile(requireContext(), "image/logo_footer_small.bmp")
            val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
            }

            val list: MutableList<MulPrintStrEntity> = ArrayList()
            val fontSize = FontFamily.MIDDLE
            val tags = arrayOf(
                "MERCHANT ID",
                "TERMINAL ID",
                "BATCH NO",
                "",
                "APP LABEL",
                "CARD NO",
                "DATE/TIME",
                "INV NO",
                "TRACE NO",
                "ENTRY TYPE",
                "REF NO",
                "APPROVAL CODE",
                "CASHOUT AMOUNT",
                "TOTAL",
                "",
                "ARQC",
                "AID",
                "TVR",
                ""
            )
            var entity = MulPrintStrEntity("", fontSize)

            entity.bitmap = if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpa) powerByBmp else acquirerBmp
            entity.marginX = 50
            entity.gravity = Gravity.CENTER
            entity.isUnderline = true
            entity.yspace = 30
            list.add(entity)

            receiptMerchantDetailsModifier(list)

            list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[0]).setFont(fontSize),
                        TextItem(details[0]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[1]).setFont(fontSize),
                        TextItem(details[1]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[2]).setFont(fontSize),
                        TextItem(details[2]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(MulPrintStrEntity(details[3], FontFamily.BIG, false, Gravity.CENTER))
            if (details[4].isNotEmpty()) {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[4]).setFont(fontSize),
                            TextItem(details[4]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }
            list.add(MulPrintStrEntity(tags[5], fontSize))
            list.add(MulPrintStrEntity(details[5], FontFamily.BIG, false, Gravity.CENTER))
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[6]).setFont(fontSize),
                        TextItem(details[6]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[7]).setFont(fontSize),
                        TextItem(details[7]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[8]).setFont(fontSize),
                        TextItem(details[8]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[9]).setFont(fontSize),
                        TextItem(details[9]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[10]).setFont(fontSize),
                        TextItem(details[10]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[11]).setFont(fontSize),
                        TextItem(details[11]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            if (details[12] != "0") {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[12]).setFont(fontSize),
                            TextItem("RM" + details[12]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }
            list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[13]).setFont(FontFamily.BIG),
                        TextItem("RM" + details[13]).setFont(FontFamily.BIG).setPaddingAlign(Gravity.RIGHT)
                    ), FontFamily.BIG
                )
            )
            list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))

            if (details[3] == "Instalment Sale" || details[3] == "Void Instalment") {
                if (details[14].isNotEmpty()) {
                    val isCZ = transData.acqCode.equals("BSN_CARDZONE", true)
                    val eppDetails = parseEppDetailsReceipt(details[14], isCZ)
                    val eppTags = if (isCZ) {
                        arrayOf("TENURE", "FIRST AMT", "MTHLY AMT", "TOTAL AMT")
                    } else {
                        arrayOf("TENURE", "TOTAL DUE", "MTHLY AMT", "FINAL AMT")
                    }
                    list.add(
                        MulPrintStrEntity(
                            Utils.makeLineText(
                                TextItem(eppTags[0]).setFont(fontSize),
                                TextItem(eppDetails[0]).setFont(fontSize)
                                    .setPaddingAlign(Gravity.RIGHT)
                            ), fontSize
                        )
                    )
                    for (k in 1 until eppDetails.size) {
                        list.add(
                            MulPrintStrEntity(
                                Utils.makeLineText(
                                    TextItem(eppTags[k]).setFont(fontSize),
                                    TextItem("RM" + eppDetails[k]).setFont(fontSize)
                                        .setPaddingAlign(Gravity.RIGHT)
                                ), fontSize
                            )
                        )
                    }
                    list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
                }
            }
            if (details[15].isNotEmpty()) {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[15]).setFont(fontSize),
                            TextItem(details[15]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }
            if (details[16].isNotEmpty()) {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[16]).setFont(fontSize),
                            TextItem(details[16]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }
            if (details[17].isNotEmpty()) {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[17]).setFont(fontSize),
                            TextItem(details[17]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }
            list.add(
                MulPrintStrEntity(
                    Utils.CVMAnalysis(details[18], ""),
                    FontFamily.MIDDLE,
                    false,
                    Gravity.CENTER
                ).setIsBold(Typeface.BOLD)
            )
            list.add(
                MulPrintStrEntity(
                    "I AGREE TO PAY THE ABOVE TOTAL AMOUNT ACCORDING TO THE CARD ISSUER AGREEMENT",
                    FontFamily.SMALL,
                    false,
                    Gravity.CENTER
                )
            )

            list.add(
                MulPrintStrEntity(
                    "***** $whoseCopy COPY *****",
                    FontFamily.MIDDLE,
                    false,
                    Gravity.CENTER
                ).setIsBold(Typeface.BOLD).setYspace(10)
            )

            if (isTpa) {
                list.add(
                    MulPrintStrEntity(
                        "Behind Every Payment, There's A Smile.",
                        FontFamily.SMALL,
                        false,
                        Gravity.CENTER
                    )
                )
            } else {
                if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                    //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                    if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                        list.add(
                            MulPrintStrEntity(
                                "PARTNER WITH",
                                FontFamily.SMALL,
                                false,
                                Gravity.CENTER
                            )
                        )

                        entity = MulPrintStrEntity("", fontSize)
                        entity.bitmap = acquirerBmp
                        entity.marginX = 50
                        entity.gravity = Gravity.CENTER
                        entity.isUnderline = true
                        entity.yspace = 30
                        list.add(entity)
                    }
                } else {
                    list.add(
                        MulPrintStrEntity(
                            "POWERED BY",
                            FontFamily.SMALL,
                            false,
                            Gravity.CENTER
                        )
                    )

                    entity = MulPrintStrEntity("", fontSize)
                    entity.bitmap = powerByBmp
                    entity.marginX = 50
                    entity.gravity = Gravity.CENTER
                    entity.isUnderline = true
                    entity.yspace = 30
                    list.add(entity)
                }
            }

            entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
            entity.gravity = Gravity.CENTER
            entity.yspace = 140
            list.add(entity)
            HelperCommon.sdkPrint(list)
        }

        if(receipt != null) {
            val animSlideDown = AnimationUtils.loadAnimation(requireActivity().applicationContext, R.anim.receipt_slide_up)
            receipt.startAnimation(animSlideDown)
        }
    }


    fun formLayoutQr(
        log: HelperLog,
        helperLogClassName: String,
        details: Array<String>,
        mainReceipt: LinearLayout,
        headerImageView: ImageView,
        specialArg: Bundle
    ) {
        val acqCode = specialArg.getString("acqCode", "")
        val isTpa = specialArg.getBoolean("isTpa", false)
        val isUnionPayTxn = specialArg.getBoolean("isUnionPayTxn", false)
        mainReceipt.removeAllViews()

        var acqLogoEnumModel = AcquirerLogoEnumModel(
            R.mipmap.blank,
            "image/logo_footer_small.bmp",
            R.mipmap.ic_launcher,
            resources.getString(R.string.app_name_about),
            resources.getString(R.string.app_name_about),
        )
        var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        if (dbModelMerchantConfig != null) {
            try {
                acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(transData.acqCode).data
            } catch (e: Exception) {
                e.printStackTrace()
            }
            acquirerLogoConfig = Gson().fromJson(
                DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java
            )
        }

        var powerByBmp: Bitmap? = null
        val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
        val imgFile = File(paths)
        if (imgFile.exists()) {
            powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        }

        val tags = if (isUnionPayTxn) {
            arrayOf(
                "DATE/TIME",
                "E-WALLET",
                "TXN TYPE",
                "MID",
                "TID",
                "APPROVAL CODE",
                "HOST REF",
                "REF ID"
            )
        }  else {
            arrayOf(
                "DATE/TIME",
                "E-WALLET",
                "TXN TYPE",
                "MID",
                "TID",
                "APPROVAL CODE",
                "HOST REF",
                "AMOUNT",
                "REF ID"
            )
        }
        Utils.debugLogPrint("TAG", details.contentToString())

        if(powerByBmp != null && (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpa)) {
            headerImageView.setImageBitmap(powerByBmp)
        } else {
            headerImageView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
        }
        val name = TextView(requireActivity().applicationContext)
        name.textSize = 9f
        name.textAlignment = View.TEXT_ALIGNMENT_CENTER
        name.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
        name.text = getSafeValue(dbModelMerchantConfig, "MerchantName")
        val addr = TextView(requireActivity().applicationContext)
        addr.textSize = 9f
        addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
        addr.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
        addr.text = getSafeValue(dbModelMerchantConfig, "MerchantAddress")
        mainReceipt.addView(name)
        mainReceipt.addView(addr)
        for (j in tags.indices) {
            if (details[j] == "") {
                continue
            }
            val ll = LinearLayout(requireActivity().applicationContext)
            ll.orientation = LinearLayout.HORIZONTAL
            ll.weightSum = 2f
            val params1 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params1.setMargins(5, 0, 5, 0)
            params1.weight = 1f
            val tv = TextView(requireActivity().applicationContext)
            tv.layoutParams = params1
            tv.textSize = 11f
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
            tv.text = tags[j]
            val tv1 = TextView(requireActivity().applicationContext)
            tv1.layoutParams = params1
            tv1.textSize = 11f
            tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            tv1.setTypeface(tv1.typeface, Typeface.BOLD)
            tv1.setTextColor(ContextCompat.getColor(requireActivity().applicationContext, R.color.black))
            tv1.text = details[j]
            ll.addView(tv)
            ll.addView(tv1)
            mainReceipt.addView(ll)
        }

        /* upi txn */
        //if (TransData.isUPIQR && TransData.upiVoucherCode.isNotEmpty()) {
        if (isUnionPayTxn) {
            val upiQrDetailsTag = arrayOf(
                "VC CODE",
                "AMT (MYR)",
                "DISCOUNT AMT (MYR)",
                "TOTAL AMT (MYR)"
            )
            /*val upiQrDetails = arrayOf(
                TransData.upiVoucherCode,
                Utils.getActualAmount(TransData.amount.toString()),
                TransData.upiDiscountAmt,
                TransData.upiFinalAmount
            )*/
            val upiQrDetails = arrayOf(details[8], details[9], details[10], details[11])

            for (i in upiQrDetailsTag.indices) {
                if (upiQrDetails[i] == "") {
                    continue
                }
                val ll = LinearLayout(requireActivity().applicationContext)
                ll.orientation = LinearLayout.HORIZONTAL
                ll.weightSum = 2f
                val params1 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params1.setMargins(5, 0, 5, 0)
                params1.weight = 1f
                val tv = TextView(requireActivity().applicationContext)
                tv.layoutParams = params1
                tv.textSize = 11f
                tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tv.setTextColor(
                    ContextCompat.getColor(
                        requireActivity().applicationContext,
                        R.color.black
                    )
                )
                tv.text = upiQrDetailsTag[i]
                val tv1 = TextView(requireActivity().applicationContext)
                tv1.layoutParams = params1
                tv1.textSize = 11f
                tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                tv1.setTypeface(tv1.typeface, Typeface.BOLD)
                tv1.setTextColor(
                    ContextCompat.getColor(
                        requireActivity().applicationContext,
                        R.color.black
                    )
                )
                tv1.text = upiQrDetails[i]

                val ll2 = LinearLayout(requireActivity().applicationContext)
                val params2 = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                ll2.orientation = LinearLayout.VERTICAL
                ll2.layoutParams = params2
                val tvDashed = TextView(requireActivity().applicationContext)
                tvDashed.textSize = 11f
                tvDashed.layoutParams = params1
                tvDashed.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                tvDashed.setTextColor(
                    ContextCompat.getColor(
                        requireActivity().applicationContext,
                        R.color.black
                    )
                )
                tvDashed.setSingleLine(true)
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val dashWidth = tvDashed.paint.measureText("-")
                val dashCount = (screenWidth / dashWidth).toInt()
                tvDashed.text = "-".repeat(dashCount)

                ll.addView(tv)
                ll.addView(tv1)

                if (i == 0) {
                    ll2.addView(tvDashed)
                    mainReceipt.addView(ll2)
                }
                mainReceipt.addView(ll)
                if (i == 3) {
                    ll2.addView(tvDashed)
                    mainReceipt.addView(ll2)
                }
            }
        }
        /* upi txn */

        val footer = LinearLayout(requireActivity().applicationContext)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 5, 0, 0)
        params.gravity = Gravity.CENTER
        footer.orientation = LinearLayout.VERTICAL
        footer.layoutParams = params
        val power1 = TextView(requireActivity().applicationContext)
        power1.textSize = 7.5f
        power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power1.setTextColor(
            ContextCompat.getColor(
                requireActivity().applicationContext,
                R.color.black
            )
        )
        power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT"
        footer.addView(power1)
        val power = TextView(requireActivity().applicationContext)
        power.textSize = 9f
        power.textAlignment = View.TEXT_ALIGNMENT_CENTER
        power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
        power.setTextColor(
            ContextCompat.getColor(
                requireActivity().applicationContext,
                R.color.black
            )
        )

        val im = ImageView(requireActivity().applicationContext)
        val params1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            getInstance().getDpValue(30)
        )
        params1.gravity = Gravity.CENTER
        im.layoutParams = params1
        im.adjustViewBounds = true

        if (isTpa) {
            power.text = "Behind Every Payment, There's A Smile."
            footer.addView(power)
        } else {
            if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                    power.text = "PARTNER WITH"
                    footer.addView(power)
                    im.setImageResource(acqLogoEnumModel.HeaderLogoPng)
                    footer.addView(im)
                }
            } else {
                power.text = "POWERED BY"
                footer.addView(power)

                if(powerByBmp != null) {
                    im.setImageBitmap(powerByBmp)
                    Utils.debugLogPrint("TAG", "onCreate: image")
                } else {
                    im.setImageResource(R.mipmap.logo)
                }
                footer.addView(im)
            }
        }

        mainReceipt.addView(footer)
    }

    fun printReceiptQr(receipt: ScrollView?, details: Array<String>, whoseCopy: String, specialArg: Bundle) {
        val acqCode = specialArg.getString("acqCode", "")
        val isTpa = specialArg.getBoolean("isTpa", false)
        val isUnionPayTxn = specialArg.getBoolean("isUnionPayTxn", false)
        CoroutineScope(Dispatchers.Default).launch {
            var acqLogoEnumModel = AcquirerLogoEnumModel(
                R.mipmap.blank,
                "image/logo_footer_small.bmp",
                R.mipmap.ic_launcher,
                resources.getString(R.string.app_name_about),
                resources.getString(R.string.app_name_about),
            )
            var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
            val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
            if (dbModelMerchantConfig != null) {
                try {
                    acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(acqCode).data
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                acquirerLogoConfig = Gson().fromJson(getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
            }

            val acquirerImageAsset = getImageFromAssetsFile(requireActivity().applicationContext, acqLogoEnumModel.HeaderLogoBmp)
            var powerByImageAsset = getImageFromAssetsFile(requireActivity().applicationContext, "image/logo_footer_small.bmp")
            val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
            val imgFile = File(paths)
            if (imgFile.exists()) {
                powerByImageAsset = BitmapFactory.decodeFile(imgFile.absolutePath)
            }
            val list: MutableList<MulPrintStrEntity> = ArrayList()
            val fontSize = FontFamily.MIDDLE
            val tags = if (isUnionPayTxn) {
                arrayOf(
                    "DATE/TIME",
                    "E-WALLET",
                    "TXN TYPE",
                    "MID",
                    "TID",
                    "APPROVAL CODE",
                    "HOST REF",
                    "REF ID"
                )
            }  else {
                arrayOf(
                    "DATE/TIME",
                    "E-WALLET",
                    "TXN TYPE",
                    "MID",
                    "TID",
                    "APPROVAL CODE",
                    "HOST REF",
                    "AMOUNT",
                    "REF ID"
                )
            }

            var entity = MulPrintStrEntity("", fontSize)
            entity.bitmap = if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true || isTpa) {
                powerByImageAsset
            } else {
                acquirerImageAsset
            }
            entity.marginX = 50
            entity.gravity = Gravity.CENTER
            entity.isUnderline = true
            entity.yspace = 30
            list.add(entity)

            receiptMerchantDetailsModifier(list)

            list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[0]).setFont(fontSize),
                        TextItem(details[0]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[1]).setFont(fontSize),
                        TextItem(details[1]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[2]).setFont(fontSize),
                        TextItem(details[2]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )
            //TODO HIDE MID/TID
            /*if (details[3] != "") {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[3]).setFont(fontSize),
                            TextItem(details[3]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }*/
            /*if (details[4] != "") {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[4]).setFont(fontSize),
                            TextItem(details[4]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }*/
            /*if (details[5] != "") {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[5]).setFont(fontSize),
                            TextItem(details[5]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }*/
            //TODO HIDE MID/TID
            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[6]).setFont(fontSize),
                        TextItem(details[6]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            list.add(
                MulPrintStrEntity(
                    Utils.makeLineText(
                        TextItem(tags[7]).setFont(fontSize),
                        TextItem(details[7]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                    ), fontSize
                )
            )

            //if (isUnionPayTxn && TransData.upiVoucherCode.isNotEmpty()) {
            if (isUnionPayTxn) {
                //TODO Enhanced
                val upiQrDetailsTag = arrayOf(
                    "VC CODE",
                    "AMT (MYR)",
                    "DISCOUNT AMT (MYR)",
                    "TOTAL AMT (MYR)"
                )
                /*val upiQrDetails = arrayOf(
                    TransData.upiVoucherCode,
                    Utils.getActualAmount(TransData.amount.toString()),
                    TransData.upiDiscountAmt,
                    TransData.upiFinalAmount
                )*/
                val upiQrDetails = arrayOf(details[8], details[9], details[10], details[11])
                list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(upiQrDetailsTag[0]).setFont(fontSize),
                            TextItem(upiQrDetails[0]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(upiQrDetailsTag[1]).setFont(fontSize),
                            TextItem(upiQrDetails[1]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(upiQrDetailsTag[2]).setFont(fontSize),
                            TextItem(upiQrDetails[2]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(upiQrDetailsTag[3]).setFont(fontSize),
                            TextItem(upiQrDetails[3]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
                list.add(MulPrintStrEntity(Utility.paddingWith("", "--", 54, false), fontSize))
            } else {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags[8]).setFont(fontSize),
                            TextItem(details[8]).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
                        ), fontSize
                    )
                )
            }

            list.add(
                MulPrintStrEntity(
                    "I AGREE TO PAY THE ABOVE TOTAL AMOUNT",
                    FontFamily.SMALL,
                    false,
                    Gravity.CENTER
                )
            )

            list.add(
                MulPrintStrEntity(
                    "***** $whoseCopy COPY *****",
                    FontFamily.MIDDLE,
                    false,
                    Gravity.CENTER
                ).setIsBold(Typeface.BOLD).setYspace(10)
            )

            if (isTpa) {
                list.add(
                    MulPrintStrEntity(
                        "Behind Every Payment, There's A Smile.",
                        FontFamily.SMALL,
                        false,
                        Gravity.CENTER
                    )
                )
            } else {
                if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
                    //if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
                    if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
                        list.add(
                            MulPrintStrEntity(
                                "PARTNER WITH",
                                FontFamily.SMALL,
                                false,
                                Gravity.CENTER
                            )
                        )

                        entity = MulPrintStrEntity("", fontSize)
                        entity.bitmap = acquirerImageAsset
                        entity.marginX = 50
                        entity.gravity = Gravity.CENTER
                        entity.isUnderline = true
                        entity.yspace = 30
                        list.add(entity)
                    }
                } else {
                    list.add(
                        MulPrintStrEntity(
                            "POWERED BY",
                            FontFamily.SMALL,
                            false,
                            Gravity.CENTER
                        )
                    )

                    entity = MulPrintStrEntity("", fontSize)
                    entity.bitmap = powerByImageAsset
                    entity.marginX = 50
                    entity.gravity = Gravity.CENTER
                    entity.isUnderline = true
                    entity.yspace = 30
                    list.add(entity)
                }
            }

            entity = MulPrintStrEntity(" \n ", FontFamily.SMALL)
            entity.gravity = Gravity.CENTER
            entity.yspace = 140
            list.add(entity)
            HelperCommon.sdkPrint(list)
        }

        if (receipt != null){
            val animSlideDown = AnimationUtils.loadAnimation(requireActivity().applicationContext, R.anim.receipt_slide_up)
            receipt.startAnimation(animSlideDown)
        }
    }

    // Progress dialog calls are always POSTED (never run inline) so showNow's
    // commitNow can never land inside an executing FragmentManager transaction
    // (e.g. showProgress called synchronously from onViewCreated crashes with
    // "FragmentManager is already executing transactions" otherwise). One FIFO
    // queue keeps show/update/hide ordering identical for all callers.
    private val progressHandler = Handler(Looper.getMainLooper())

    fun showProgress(title: String, msg: String) {
        progressHandler.post {
            if (!isAdded) return@post
            val fm = parentFragmentManager
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
        // Capture the FM now: if this fragment detaches before the post runs,
        // the dialog (which lives in the PARENT fm) can still be updated.
        val fm = if (isAdded) parentFragmentManager else null
        progressHandler.post {
            val manager = fm ?: return@post
            if (manager.isDestroyed) return@post
            (manager.findFragmentByTag(ProgressDialogFragment.TAG) as? ProgressDialogFragment)
                ?.update(title, msg)
        }
    }

    fun hideProgress() {
        // Capture the FM now: dismissal must proceed even if this fragment
        // detaches between the call and the posted runnable, or the dialog
        // would be stuck on screen (it lives in the PARENT fm).
        val fm = if (isAdded) parentFragmentManager else null
        progressHandler.post {
            val manager = fm ?: return@post
            if (manager.isDestroyed) return@post
            (manager.findFragmentByTag(ProgressDialogFragment.TAG) as? ProgressDialogFragment)
                ?.dismissAllowingStateLoss()
        }
    }

    fun receiptMerchantDetailsModifier(list: MutableList<MulPrintStrEntity>){
        val dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
        val terminalConfig = ServiceHolder.getTerminalConfig()
        val receiptMerchantSize = DbModelTerminalConfig.getSafeValue(terminalConfig, "RECEIPT_MERCHANT_INFO_SIZE").toInt()

        var mulPrintStrEntity = MulPrintStrEntity(
            DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName"), receiptMerchantSize, false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
        mulPrintStrEntity = MulPrintStrEntity(
            DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantAddress"), receiptMerchantSize, false,
            Gravity.CENTER
        )
        list.add(mulPrintStrEntity)
    }

    fun receiptTxnDetailsModifier(list: MutableList<MulPrintStrEntity>, tags: String, details: String){
        val terminalConfig = ServiceHolder.getTerminalConfig()
        val receiptDetailSize = DbModelTerminalConfig.getSafeValue(terminalConfig, "RECEIPT_TXN_INFO_SIZE").toInt()

        when (receiptDetailSize){
            1 -> {
                val entity = MulPrintStrEntity("", FontFamily.MIDDLE)
                val dataBmp = addText(tags, "", details, 23, 0, 0, Constants.TEXT_STYLE_BOLD)
                entity.bitmap =  dataBmp
                entity.gravity = Gravity.CENTER
                list.add(entity)
            }
            else -> {
                list.add(
                    MulPrintStrEntity(
                        Utils.makeLineText(
                            TextItem(tags).setFont(FontFamily.MIDDLE),
                            TextItem(details).setFont(FontFamily.MIDDLE).setPaddingAlign(Gravity.RIGHT)
                        ), FontFamily.MIDDLE
                    )
                )
            }
        }
    }

    private fun addText(leftColumn: String, centerColumn: String, rightColumn: String, textSize: Int, textStyleLeft: Int, textStyleCenter: Int, textStyleRight: Int): Bitmap {
        val inflater = LayoutInflater.from(requireContext())
        val parent = FrameLayout(requireContext())
        val tableView = inflater.inflate(
            R.layout.layout_print_left_center_right,
            parent,
            false
        )

        val mTvLeft = tableView.findViewById<TextView?>(R.id.mTvLeft)
        val mTvCenter = tableView.findViewById<TextView?>(R.id.mTvCenter)
        val mTvRight = tableView.findViewById<TextView?>(R.id.mTvRight)

        tableView.setBackgroundColor(Color.WHITE)

        if (leftColumn.isNotEmpty()){
            setTV(mTvLeft!!, leftColumn, textSize, textStyleLeft)
        } else {
            mTvLeft!!.visibility = View.GONE
        }

        if (centerColumn.isNotEmpty()){
            setTV(mTvCenter!!, centerColumn, textSize, textStyleCenter)
        } else {
            mTvCenter!!.visibility = View.GONE
        }

        if (rightColumn.isNotEmpty()){
            setTV(mTvRight!!, rightColumn, textSize, textStyleRight)
        } else {
            mTvRight!!.visibility = View.GONE
        }

        println(
            ("Table view created with " +
                    (if (mTvLeft.isVisible) "left " else "") +
                    (if (mTvCenter.isVisible) "center " else "") +
                    (if (mTvRight.isVisible) "right " else "") + "columns")
        )

        return convertViewToBitmap(tableView)
    }

    private fun convertViewToBitmap(view: View): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(384, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = createBitmap(view.measuredWidth, view.measuredHeight)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun setTV(tv: TextView, text: String, textSize: Int, textStyle: Int) {
        if (text.isEmpty()){
            println("TextView is null or text is empty")
            return
        }
        println("Setting text: '$text', size: $textSize, style: $textStyle")
        tv.setTextColor(Color.BLACK)

        val spannableString = SpannableString(text)
        spannableString.setSpan(
            AbsoluteSizeSpan(textSize),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = "$spannableString "
        applyTextStyle(tv, text, textStyle)
        tv.visibility = View.VISIBLE
        println("TextView configured: text=' ${tv.text}', textColor= ${tv.currentTextColor}")
    }

    private fun applyTextStyle(tv: TextView, text: String?, textStyle: Int) {
        when (textStyle) {
            Constants.TEXT_STYLE_UNDERLINE -> {
                tv.paint.flags = Paint.UNDERLINE_TEXT_FLAG
                tv.paint.isAntiAlias = true
            }
//            Constants.TEXT_STYLE_ITALIC -> setTVTextItalic(tv, text)
            Constants.TEXT_STYLE_BOLD -> tv.paint.isFakeBoldText = true
            Constants.TEXT_STYLE_REVERSE -> {
                tv.rotation = 180f
                tv.setTextColor(Color.WHITE)
                tv.setBackgroundColor(Color.BLACK)
            }
            else ->tv.setTextColor(Color.BLACK)
        }
    }
}