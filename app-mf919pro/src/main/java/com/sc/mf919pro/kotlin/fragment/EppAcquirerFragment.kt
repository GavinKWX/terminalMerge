package com.sc.mf919pro.kotlin.fragment

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentEppAcquirerBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.HTTPServer
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.intent_helper.TxnKeys
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog

class EppAcquirerFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var acquirerScroll: ScrollView
    var extraBundle: Bundle? = null

    private var _binding: FragmentEppAcquirerBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEppAcquirerBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "EppAcquirer OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "EPP Acquirer Selection Fragment"
        )
        val toolbar = binding.appToolbar
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {  customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            }
        )
        helperLog.appendLine(helperLogClassName, "Initialize EPP Acquirer Fragment")

        extraBundle = arguments
        acquirerScroll = binding.eppAcquirerScroll
        binding.buttonCancel.setDebouncedOnClickListener { customOnBackPress() }

        //TODO
        transData.reset()
        ServiceHolder.saleModelCache = null
        renderDynamicAcquirer()
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun renderDynamicAcquirer() {
        val listAcquirer = ProductListRepo.getDistinctEppAcquirer(requireContext())
        if (listAcquirer.isNotEmpty()) {
            val mainLinear = LinearLayout(requireContext())
            val mainLinearParam = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mainLinearParam.setMargins(
                Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10),
                Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10)
            )
            mainLinear.layoutParams = mainLinearParam
            mainLinear.orientation = LinearLayout.VERTICAL

            var rowLinear = LinearLayout(requireContext())
            var count = listAcquirer.size
            val rowItem = 2

            try {
                for (i in listAcquirer.indices) {
                    // copy() so the mutation below doesn't touch the shared list element
                    val modelData = listAcquirer[i].copy()
                    modelData.Product = ProductCatSelectionDataEnum.EPP.name
                    val tempAcquirerModel = AcquirerLogoDataEnum.valueOf(modelData.AcqCode).data

                    if (i % rowItem == 0) {
                        rowLinear = LinearLayout(requireContext())
                        val rowLinearParam = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        rowLinear.layoutParams = rowLinearParam
                        rowLinear.orientation = LinearLayout.HORIZONTAL
                        rowLinear.gravity = Gravity.CENTER
                    }

                    val acquirerLV = LinearLayout(requireContext())
                    val acquirerLVParam = LinearLayout.LayoutParams(
                        Helper.getInstance().getDpValue(0), LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    acquirerLVParam.weight = 1f
                    acquirerLVParam.setMargins(
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10),
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10)
                    )
                    acquirerLV.gravity = Gravity.CENTER
                    acquirerLV.layoutParams = acquirerLVParam

                    val acquirerImageView = ImageView(requireContext())
                    acquirerImageView.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    acquirerImageView.setImageResource(tempAcquirerModel.HeaderLogoPng)
                    acquirerImageView.adjustViewBounds = true
                    acquirerLV.addView(acquirerImageView)
                    acquirerLV.setOnClickListener {
                        val jsonProductList = Gson().toJson(modelData)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = ProductCatSelectionDataEnum.EPP.data.SalesType
                        ServiceHolder.saleModelCache = saleModelNew
                        helperLog.appendLine(helperLogClassName, "Update EPP Model :: ", jsonProductList)
                        helperLog.appendLine(helperLogClassName, "End Process EPP Sale Onclick")
                        helperLog.logToFile(EnumLogFileName.TerminaLog)
                        navigateSafe(R.id.action_appAcquirer_to_eppKeypad, extraBundle)
                    }

                    count--
                    rowLinear.addView(acquirerLV)
                    if ((i + 1) % rowItem == 0 || count == 0) {
                        mainLinear.addView(rowLinear)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            acquirerScroll.addView(mainLinear)
        }
    }

    /*fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }*/

    fun customOnBackPress() {
        val txnMap = HashMap<String, String>()
        val jObject = JsonObject()
        txnMap["TransactionType"] = ServiceHolder.txnType.toString()
        txnMap["ResponseCode"] = "SHC005"
        txnMap["ResponseDescription"] = "User Cancel the Transaction"
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        try {
            jObject.addProperty("ResponseCode", "SHC005")
            jObject.addProperty("ResponseDescription", "User Cancel the Transaction")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (ServiceHolder.appIntent) {
                onBackToApp(txnMap)
            } else {
                if (ServiceHolder.appHTTP) {
                    HTTPServer.getInstance().setResponseMessage(jObject.toString())
                    ServiceHolder.appHTTP = false
                }

                val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
                navigateToHome(dbModelTerminalConfig)
            }
        }
    }

    fun onBackToApp(txnMap: HashMap<String, String>) {
        // Handing control back to the calling POS app: this path ends in finish(), which
        // can be the last thing this process does -- onDestroyView is not guaranteed to
        // run to completion after it. Flush so the hand-back is always on disk.
        if (this@EppAcquirerFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "EppAcquirer :: returning to caller app, response -> ${txnMap["ResponseCode"]}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txnMap)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}