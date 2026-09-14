package com.sc.mf919pro.kotlin.fragment
import enums.EnumResponseCode

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentEppAcquirerBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.activity.TransactionTransmitter
import com.sc.mf919pro.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelProductListGet
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
import kotlinx.coroutines.launch

class EppProductFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var acquirerScroll: ScrollView
    lateinit var selectedModel: SaleModelNew

    var extraBundle: Bundle? = null
    var posReference: String? = null

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
            helperLog.appendLine(helperLogClassName, "EppProduct OnDestroyView :: screen ended")
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
            "EPP Product Selection Fragment"
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
        helperLog.appendLine(helperLogClassName, "Initialize EPP Product Fragment")

        extraBundle = arguments
        acquirerScroll = binding.eppAcquirerScroll
        ServiceHolder.saleModelCache?.let {
            selectedModel = it
            viewLifecycleOwner.lifecycleScope.launch {
                renderInformationHeader()
                renderDynamicProduct()
            }
        } ?: run {
            Toast.makeText(requireContext(), "Invalid EPP Process", Toast.LENGTH_SHORT).show()
            val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
            navigateToHome(dbModelTerminalConfig)
        }

        binding.buttonCancel.setDebouncedOnClickListener { customOnBackPress() }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun renderInformationHeader() {
        val infoLinearLayout = binding.eppAcquirerHeader
        var infoTV = TextView(requireContext())
        var bankName = ""
        try {
            val acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(selectedModel.AcqCode ?: "").data
            bankName = acqLogoEnumModel.DisplayName
        } catch (e: Exception) {
            e.printStackTrace()
        }

        infoTV.text = "Bank: $bankName"
        infoTV.setPadding(
            Helper.getInstance().getDpValue(20), Helper.getInstance().getDpValue(30),
            Helper.getInstance().getDpValue(20), Helper.getInstance().getDpValue(5)
        )
        infoTV.setTypeface(infoTV.typeface, Typeface.BOLD)
        infoTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        infoLinearLayout.addView(infoTV)

        infoTV = TextView(requireContext())
        infoTV.text = "Amount: RM${Utils.getActualAmount(selectedModel.TransAmount.toString())}"
        infoTV.setPadding(
            Helper.getInstance().getDpValue(20), Helper.getInstance().getDpValue(5),
            Helper.getInstance().getDpValue(20), Helper.getInstance().getDpValue(30)
        )
        infoTV.setTypeface(infoTV.typeface, Typeface.BOLD)
        infoTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        infoLinearLayout.addView(infoTV)

        val seperatorLL = LinearLayout(requireContext())
        val seperatorLinearParam = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(80)
        )
        seperatorLL.setBackgroundResource(R.color.BoxColor)
        seperatorLL.gravity = Gravity.CENTER
        seperatorLL.layoutParams = seperatorLinearParam
        val seperateTV = TextView(requireContext())
        seperateTV.text = "Please Select Tenure"
        seperateTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        seperateTV.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        seperateTV.setTypeface(seperateTV.typeface, Typeface.BOLD)
        seperatorLL.addView(seperateTV)
        infoLinearLayout.addView(seperatorLL)
    }

    fun renderDynamicProduct() {
        val eppProductList = ProductListRepo.getSelectedProduct(
            requireContext(), mutableListOf("Product", "AcqCode"), arrayOf("EPP", selectedModel.AcqCode!!)
        )

        if(eppProductList.isNotEmpty()) {
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
            var count = eppProductList.size
            val rowItem = 2

            try {
                for (i in eppProductList.indices) {
                    val getRow: Any = eppProductList[i]
                    val jsonObject: JsonObject = Gson().toJsonTree(getRow).asJsonObject
                    val modelData = Gson().fromJson(jsonObject, DbModelProductListGet::class.java)

                    if (i % rowItem == 0) {
                        rowLinear = LinearLayout(requireContext())
                        val rowLinearParam = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )

                        if (i >= rowItem) {
                            rowLinear.background = ResourcesCompat.getDrawable(
                                resources, R.drawable.border_top, requireActivity().theme
                            )
                        }
                        rowLinear.layoutParams = rowLinearParam
                        rowLinear.orientation = LinearLayout.HORIZONTAL
                        rowLinear.gravity = Gravity.CENTER
                    }

                    val productLV = LinearLayout(requireContext())
                    val productLVParam = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    productLVParam.weight = 1f
                    productLV.setPadding(
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(25),
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(25)
                    )

                    if (i % rowItem == 0) {
                        productLV.background = ResourcesCompat.getDrawable(
                            resources, R.drawable.border_right, requireActivity().theme
                        )
                    }
                    productLV.gravity = Gravity.CENTER
                    productLV.layoutParams = productLVParam

                    val productTV = TextView(requireContext())
                    productTV.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    productTV.gravity = Gravity.CENTER
                    productTV.text = modelData.EppTenure
                    productTV.setTypeface(productTV.typeface, Typeface.BOLD)
                    productTV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20F)
                    productLV.addView(productTV)
                    productLV.setOnClickListener {
                        val jsonProductList = Gson().toJson(modelData)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = selectedModel.SalesType
                        saleModelNew.TransAmount = selectedModel.TransAmount
                        saleModelNew.CashOutAmount = selectedModel.CashOutAmount
                        ServiceHolder.saleModelCache = saleModelNew
                        navigateSafe(R.id.action_eppProduct_to_cardPayment, extraBundle)
                    }

                    count--
                    rowLinear.addView(productLV)
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

    /*private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }*/
    fun customOnBackPress() {
        val txnMap = HashMap<String, String>()
        val jObject = JsonObject()
        txnMap["TransactionType"] = ServiceHolder.txnType.toString()
        txnMap["ResponseCode"] = EnumResponseCode.USER_CANCELLED.code
        txnMap["ResponseDescription"] = EnumResponseCode.USER_CANCELLED.description
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        try {
            jObject.addProperty("ResponseCode", EnumResponseCode.USER_CANCELLED.code)
            jObject.addProperty("ResponseDescription", EnumResponseCode.USER_CANCELLED.description)
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
        if (this@EppProductFragment::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "EppProduct :: returning to caller app, response -> ${txnMap["ResponseCode"]}")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        startActivity(Intent(requireContext(), TransactionTransmitter::class.java).apply {
            putExtra(TxnKeys.EXTRA_TXN_MAP, txnMap)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        requireActivity().finish()
    }
}