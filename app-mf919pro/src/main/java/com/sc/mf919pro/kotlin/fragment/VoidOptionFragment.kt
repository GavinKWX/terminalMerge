package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentVoidoptionBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog

class VoidOptionFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var voidOptionlist: LinearLayout

    private var _binding: FragmentVoidoptionBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoidoptionBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "VoidOption OnDestroyView :: screen ended")
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
            "Void Option Fragment"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Void Option Fragment")
        val toolbar = view.findViewById<Toolbar>(R.id.toolbarVoidOpt)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }

        voidOptionlist = view.findViewById(R.id.voidOptionlist)
        renderDynamicProduct()

        val terminalConfig = ServiceHolder.getTerminalConfig()
        if (DbModelTerminalConfig.getBooleanValue(terminalConfig, "VOID_WITH_PIN")) {
            helperLog.appendLine(helperLogClassName, "VOID_WITH_PIN is true")
            pinDialog("") { canceled ->
                if (canceled) {
                    customOnBackPress()
                }
            }
        }
    }

    private fun renderDynamicProduct() {
        helperLog.appendLine(helperLogClassName, "Rendering Void Product")
        val merchantProduct = ServiceHolder.getMerchantProduct()
        println("MerchantProduct = $merchantProduct")
        var count = 0
        try {
            var isQRShow = false
            for (a in merchantProduct!!) {
                val (_, productTitle, _, active, _, _, _, _, _, voidNextClass) = ProductCatSelectionDataEnum.valueOf(a).data
                if (!active || a == "BNPL" || a == "EPP" || (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && isQRShow) {
                    // Category not yet configure and active on terminal
                    continue
                }

                val voidMethod = LinearLayout(requireContext())
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Helper.getInstance().getDpValue(80)
                )
                params.setMargins(20, 0, 20, 20)
                params.gravity = Gravity.CENTER
                voidMethod.gravity = Gravity.CENTER_VERTICAL
                voidMethod.layoutParams = params
                voidMethod.orientation = LinearLayout.HORIZONTAL
                voidMethod.setBackgroundResource(R.drawable.border3)

                val textViewLeft = TextView(requireContext())
                val leftParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                textViewLeft.layoutParams = leftParams
                textViewLeft.setPadding(10, 0, 0, 0)
                textViewLeft.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mf919_bg_theme_color))

                val textViewRight = TextView(requireContext())
                val rightParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                textViewRight.layoutParams = rightParams
                textViewRight.setTextColor(ContextCompat.getColor(requireContext(), R.color.WordColor))
                textViewRight.textSize = 20f
                textViewRight.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textViewRight.setPadding(30, 0, 10, 0)
                textViewRight.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mf919_dark_gray))

                if ((a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") && !isQRShow) { // GENERATE_QR & EWALLET
                    isQRShow = true
                    textViewRight.text = "QR PAYMENT"
                    voidMethod.addView(textViewLeft)
                    voidMethod.addView(textViewRight)
                } else {
                    textViewRight.text = productTitle
                    voidMethod.addView(textViewLeft)
                    voidMethod.addView(textViewRight)
                }

                if (a == "GENERATE_QR" || a == "EWALLET_MERCHANT_SCANS") {
                    val ewalletProduct = getSpecificProduct("EWALLET_MERCHANT_SCANS")
                    val generateQrProduct = getSpecificProduct("GENERATE_QR")
                    voidMethod.setDebouncedOnClickListener {
                        if (ewalletProduct != null || generateQrProduct != null) {
                            if (ewalletProduct == null) {
                                val salesModel = SalesModel(
                                    0,
                                    generateQrProduct!!.Product,
                                    generateQrProduct.AcqCode,
                                    generateQrProduct.AcqMid,
                                    generateQrProduct.AcqTid,
                                    generateQrProduct.QrProductCode,
                                    generateQrProduct.ProductName,
                                    generateQrProduct.EppProductCode,
                                    generateQrProduct.EppTenure,
                                    generateQrProduct.EppTenureCode
                                )
                                ServiceHolder.selectedCacheModel = salesModel
                            } else {
                                val salesModel = SalesModel(
                                    0,
                                    ewalletProduct.Product,
                                    ewalletProduct.AcqCode,
                                    ewalletProduct.AcqMid,
                                    ewalletProduct.AcqTid,
                                    ewalletProduct.QrProductCode,
                                    ewalletProduct.ProductName,
                                    ewalletProduct.EppProductCode,
                                    ewalletProduct.EppTenure,
                                    ewalletProduct.EppTenureCode
                                )
                                ServiceHolder.selectedCacheModel = salesModel
                            }

                            val args = terminalPIN?.let {
                                Bundle().apply {
                                    putString("terminalPIN", terminalPIN)
                                }
                            }
                            navigateSafe(voidNextClass, args)
                        }
                    }
                } else {
                    //TODO Card Option
                    val dynamicProduct = getSpecificProduct(a)
                    voidMethod.setDebouncedOnClickListener {
                        if (dynamicProduct == null) {
                            if (this@VoidOptionFragment::helperLog.isInitialized) {
                                helperLog.appendLine(helperLogClassName, "REJECT :: no active product configured for void option", a)
                                helperLog.logToFile(EnumLogFileName.TerminaLog)
                            }
                            return@setDebouncedOnClickListener
                        }
                        if (this@VoidOptionFragment::helperLog.isInitialized) {
                            helperLog.appendLine(helperLogClassName, "Selected :: $a [VOID_OPTION]")
                        }

                        val salesModel = SalesModel(
                            0,
                            dynamicProduct.Product,
                            dynamicProduct.AcqCode,
                            dynamicProduct.AcqMid,
                            dynamicProduct.AcqTid,
                            dynamicProduct.QrProductCode,
                            dynamicProduct.ProductName,
                            dynamicProduct.EppProductCode,
                            dynamicProduct.EppTenure,
                            dynamicProduct.EppTenureCode
                        )
                        ServiceHolder.selectedCacheModel = salesModel
                        val productModel = ProductListRepo.getSingle(requireContext(), listOf("AcqCode", "AcqMid", "AcqTid", "Product"),
                            arrayOf(dynamicProduct.AcqCode, dynamicProduct.AcqMid, dynamicProduct.AcqTid, a))
                        val jsonProductList = Gson().toJson(productModel)
                        val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                        saleModelNew.SalesType = 0
                        ServiceHolder.saleModelCache = saleModelNew

                        navigateSafe(voidNextClass)
                    }
                }
                voidOptionlist.addView(voidMethod)
                count++
            }
        } catch (e: Exception) {
            helperLog.appendLine(helperLogClassName, "Exception in Rendering Void Product -> ", e.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            e.printStackTrace()
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private fun getSpecificProduct(name: String): DbModelProductList? {
        val merchantProductList = ServiceHolder.getMerchantProductList()
        if (merchantProductList != null) {
            for (item in merchantProductList) {
                if (item.Product == name) {
                    return item
                }
            }
        }
        return null
    }


    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        ServiceHolder.appIntent = false
        ServiceHolder.appHTTP = false
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
//        findNavController().popBackStack()

//        val dbModelTerminalConfig = getTerminalConfig()
//        var intent = Intent(applicationContext, AttendActivity::class.java)
//        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")){
//            intent = Intent(applicationContext, UnattendActivity::class.java)
//        }
    }
}