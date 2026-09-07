package com.sc.mf919pro.kotlin.fragment

import utils.HexUtil

import mdb.MdbController

import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.sc.mf919pro.kotlin.helper_common.UiEvent
import com.sc.mf919pro.kotlin.helper_common.AppBus
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentPaymentDenominationBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919pro.kotlin.database.model.DbModelDenominationList
import com.sc.mf919pro.kotlin.database.model.DbModelProductListGet
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumDenominationType
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DenominationPaymentOptionFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var productTextView: TextView
    lateinit var optionList: LinearLayout

    var dbModelDenominationList: DbModelDenominationList? = null
    var dbModelProductModel: DbModelProductListGet? = null

    //TODO Timer
    private var timeSelected : Int = 30
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0
    //TODO Timer

    private var _binding: FragmentPaymentDenominationBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPaymentDenominationBinding.inflate(inflater, container, false)
        return binding.root
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
            "Denomination Payment Option Fragment Initialization"
        )

        // React the moment the VMC aborts the vend (RESET / VEND CANCEL / reader disable) rather
        // than waiting for the selection timer or a back-press. MdbController broadcasts that as
        // UiEvent.MdbVendingForceEnd. MF919 does the same in DenominationPaymentOptionActivity;
        // the port dropped it, leaving this screen up after the VMC had moved on.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppBus.uiEvents.collect { event ->
                    if (event is UiEvent.MdbVendingForceEnd) {
                        helperLog.appendLine(helperLogClassName,
                            "Vend ABORTED by VMC :: force end received on payment option screen")
                        withContext(Dispatchers.Main) { onMdbVendingForceEnd() }
                    }
                }
            }
        }

        val toolbar = view.findViewById<Toolbar>(R.id.toolbarDeno)
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener {
            timeCountDown?.let {
                it.cancel()
                timeCountDown = null
            }
            customOnBackPress()
        }

        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    timeCountDown?.let {
                        it.cancel()
                        timeCountDown = null
                    }
                    customOnBackPress()
                }
            }
        )

        productTextView = view.findViewById(R.id.linearProductTV)
        val tempDenomination = arguments?.getString("denomination_product") ?: ""
        dbModelDenominationList = Gson().fromJson(tempDenomination, DbModelDenominationList::class.java)
        dbModelDenominationList?.let {
            var amountDisplay = it.Amount
            if(amountDisplay.contains(".")) {
                val tempList = amountDisplay.split(".")
                amountDisplay = if (tempList[1] == "00") {
                    // remove decimals
                    tempList[0]
                } else {
                    // keep original
                    amountDisplay
                }
            }

            if(it.Desc.isNotEmpty()) {
                productTextView.text = "RM$amountDisplay = ${it.Desc}"
            } else {
                productTextView.text = "RM$amountDisplay"
            }
        }
        optionList = view.findViewById(R.id.optionList)

        startTimer(0)
        lifecycleScope.launch {
            val modelProductLists = ProductListRepo.getAll(requireContext())
            val filteredList = modelProductLists.filter { item ->
                item.Product == "CARD_SETTINGS" || (item.Product == "GENERATE_QR" && item.QrProductCode == "QR_DUITNOW")
            }
            if(filteredList.size == 1 ) {
                timeCountDown?.let {
                    it.cancel()
                    timeCountDown = null
                }

                val item = filteredList.first()
                val autoSelectProductModel = DbModelProductListGet(
                    Id = "",
                    Product = item.Product,
                    AcqCode = item.AcqCode,
                    AcqMid = item.AcqMid,
                    AcqTid = item.AcqTid,
                    QrProductCode = item.QrProductCode,
                    ProductName = item.ProductName,
                    EppProductCode = item.EppProductCode,
                    EppTenure = item.EppTenure,
                    EppTenureCode = item.EppTenureCode,
                    IsSettlement = item.IsSettlement,
                    IsSettled = item.IsSettled,
                    BatchNo = item.BatchNo,
                    IsActive = item.IsActive,
                    Ksn = item.Ksn,
                    PinKsn = item.PinKsn,
                    IsTpaAccount = item.IsTpaAccount
                )
                helperLog.appendLine(helperLogClassName, "Proceed AutoSelect Payment Method")
                productNavigation(autoSelectProductModel)
            } else {
                renderDynamicProduct()
            }
        }
    }

    private suspend fun renderDynamicProduct() {
        helperLog.appendLine(helperLogClassName, "Render Dynamic Product Listing")
        withContext(Dispatchers.Main) {
            showProgress("", "Loading...")

            try {
                val dbModelProductModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
                dbModelProductModel?.let {
                    optionCheckBoxRow(dbModelProductModel, "Card", R.mipmap.deno_logo_card)
                }

                val generateQrModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, "QR_DUITNOW", "true"))
                generateQrModel?.let {
                    optionCheckBoxRow(generateQrModel, "DuitNow QR", R.mipmap.deno_logo_duitnow)
                }
            } catch (ex: Exception) {
                helperLog.appendLine(helperLogClassName, "Exception in Rendering :: ", "${ex.message}")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                ex.printStackTrace()
            }
            hideProgress()
        }
    }

    private fun optionCheckBoxRow(renderProduct: DbModelProductListGet, title: String, optionLogo: Int) {
        val settleMethod = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Helper.getInstance().getDpValue(80)
            ).apply {
                gravity = Gravity.CENTER
                setMargins(Helper.getInstance().getDpValue(10), 0, Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(20))
            }
            setBackgroundResource(R.drawable.border3)
        }

        val textViewLeft = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(10, 0, 0, 0)
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.BoxColor))
        }

        val logoImageView = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                Helper.getInstance().getDpValue(80),
                Helper.getInstance().getDpValue(80),
            )
            adjustViewBounds = true
            setPadding(20, 5, 10,5)
            setImageResource(optionLogo)
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorGreyDD))
        }

        // Create CheckedTextView
        val checkedTextView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                weight = 1.0f
            }
            setPadding(20, 0, 0, 0)
            textSize = 20f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.WordColor))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorGreyDD))
        }

        settleMethod.addView(textViewLeft)
        settleMethod.addView(logoImageView)
        checkedTextView.text = title
        settleMethod.addView(checkedTextView)

        settleMethod.setOnClickListener {
            timeCountDown?.let {
                it.cancel()
                timeCountDown = null
            }
            dbModelProductModel = renderProduct
            productNavigation(renderProduct)
        }
        optionList.addView(settleMethod)
    }

    fun productNavigation(productSelected: DbModelProductListGet?) {
        dbModelProductModel = productSelected
        val amountDisplay = dbModelDenominationList?.Amount ?: "0.00"
        val amountLong = amountDisplay.replace(".", "").toLongOrNull() ?: 0
        println("amountDisplay :: $amountDisplay")
        println("amountLong :: $amountLong")
        if(dbModelProductModel?.Product == ProductCatSelectionDataEnum.CARD_SETTINGS.name) {
            helperLog.appendLine(helperLogClassName, "Obtaining Product List >> ", dbModelProductModel.toString())
            val salesModel = SalesModel(
                ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
                dbModelProductModel?.Product ?: "",
                dbModelProductModel?.AcqCode ?: "",
                dbModelProductModel?.AcqMid ?: "",
                dbModelProductModel?.AcqTid ?: "",
                dbModelProductModel?.QrProductCode ?: "",
                dbModelProductModel?.ProductName ?: "",
                dbModelProductModel?.EppProductCode ?: "",
                dbModelProductModel?.EppTenure ?: "",
                dbModelProductModel?.EppTenureCode ?: ""
            )
            ServiceHolder.selectedCacheModel = salesModel
            val jsonProductList = Gson().toJson(dbModelProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
            if (MdbController.mdbVending) {
                // The VMC priced this vend, so the amount on screen is authoritative and the
                // controller needs it in MDB wire form to answer VEND APPROVED with it. This is
                // not a token-dispense sale, so the denomination fields stay unset.
                val hexPrice = String.format("%04x", amountLong.toInt())
                MdbController.priceHex = hexPrice
                MdbController.priceBytes = HexUtil.hexStringToByte(hexPrice)
            } else {
                saleModelNew.DenominationType = EnumDenominationType.TokenDispense.value
                saleModelNew.DenominationProduct = dbModelDenominationList
            }

            ServiceHolder.saleModelCache = saleModelNew
            saleModelNew.TransAmount = amountLong
            helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            navigateSafe(R.id.action_denominationPaymentOption_to_cardPayment)
        } else {
            val saleModelNew = Gson().fromJson("{}", SaleModelNew::class.java)
            saleModelNew.TransAmount = amountLong
            if (MdbController.mdbVending) {
                val hexPrice = String.format("%04x", amountLong.toInt())
                MdbController.priceHex = hexPrice
                MdbController.priceBytes = HexUtil.hexStringToByte(hexPrice)
            } else {
                saleModelNew.DenominationType = EnumDenominationType.TokenDispense.value
                saleModelNew.DenominationProduct = dbModelDenominationList
            }

            ServiceHolder.saleModelCache = saleModelNew
            helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLog)

            val bundle = bundleOf("paymentCode" to "QR_DUITNOW",)
            navigateSafe(R.id.action_denominationPaymentOption_to_generateQrSubProduct, bundle)
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :
            CountDownTimer((timeSelected * 1000).toLong() - pauseOffSetL * 1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
            }

            override fun onFinish() {
                customOnBackPress()
            }
        }.start()
    }

    /**
     * The VMC aborted the vend while this option screen was showing.
     *
     * It has already had its answer (VEND DENIED, or a RESET), and nothing was charged here, so
     * this only clears the vend flags and goes home -- calling sendVendDenied again would put a
     * second 06 on the bus behind the first.
     */
    private fun onMdbVendingForceEnd() {
        if (!isAdded) return
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
        MdbController.mdbVending = false
        MdbController.mdbVendingForceEnd = false
        helperLog.appendLine(helperLogClassName, "Vend force-end handled :: navigate home")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        navigateToHome(ServiceHolder.getTerminalConfig())
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        // isVending is literally "a VEND REQUEST is open" -- see CardPaymentFragment.
        if (MdbController.isVending) {
            // Leaving without answering it would hang the VMC until the controller's watchdog
            // fires; answering one that is already closed puts a duplicate on the bus.
            helperLog.appendLine(helperLogClassName, "Vend DENIED :: payment option abandoned, notifying VMC")
            MdbController.sendVendDenied()
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "DenominationPaymentOption OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}