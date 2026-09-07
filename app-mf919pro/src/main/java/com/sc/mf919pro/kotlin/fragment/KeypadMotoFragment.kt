package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentKeypadMotoBinding
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.KeypadNum
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.java.utils.EmvUtil
import utils.HexUtil
import utils.TextFormatter
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.iso.IsoActivity
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeypadMotoFragment: BaseFragment() {
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    lateinit var tvCardNo: TextView
    lateinit var tvExpDt: TextView
    lateinit var tvAmt: TextView
    lateinit var keypadNum: KeypadNum

    var posReference: String? = null
    var orderingItem: String? = null
    var orderingItemImage: String? = null

    private var _binding: FragmentKeypadMotoBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeypadMotoBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "KeypadMoto OnDestroyView :: screen ended")
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
            "Moto Keypad Fragment Start"
        )
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        helperLog.appendLine(helperLogClassName, "Initialize Moto Sale Activity")

        transData.reset()
        keypadNum = binding.keypadMoto
        tvCardNo = binding.tvCardNo
        tvCardNo.setDebouncedOnClickListener {
            keypadNum.setFilter(tvCardNo, false, 20, null)
        }
        tvExpDt = binding.tvExpDt
        tvExpDt.setDebouncedOnClickListener {
            keypadNum.setFilter(tvExpDt, false, 4, expiryFormatter)
        }
        tvAmt = binding.tvAmt
        tvAmt.setDebouncedOnClickListener {
            tvAmt.text = "0.00"
            keypadNum.setFilter(tvAmt, true, 12, null)
        }
        posReference = arguments?.getString("posReference") ?: "-"
        orderingItem = arguments?.getString("orderingItem")
        orderingItemImage = arguments?.getString("orderingItemImage")

        // default set to focus on cardNumber
        keypadNum.setFilter(tvCardNo, false, 20, null)
        //TODO Bottom Group
        binding.buttonBack.setDebouncedOnClickListener {
            helperLog.appendLine(helperLogClassName, "User Cancel :: MOTO entry abandoned")
            customOnBackPress()
        }
        binding.buttonOK.setDebouncedOnClickListener {
            val msgCardNo = tvCardNo.text.toString()
            val msgExpDt = tvExpDt.text.toString().replace("/", "")
            val msgAmt = tvAmt.text.toString()

            when {
                msgCardNo.isEmpty() -> {
                    helperLog.appendLine(helperLogClassName, "REJECT :: card number is empty")
                    showToast( "Card Number is Empty!", Toast.LENGTH_SHORT)
                }
                msgCardNo.length != 13 && msgCardNo.length != 16 && msgCardNo.length != 19 -> {
                    helperLog.appendLine(helperLogClassName, "REJECT :: invalid card number length")
                    showToast("Invalid Card Number Length!", Toast.LENGTH_SHORT)
                }
                msgExpDt.isEmpty() -> {
                    helperLog.appendLine(helperLogClassName, "REJECT :: expiry date is empty")
                    showToast("Expiry Date is Empty!", Toast.LENGTH_SHORT)
                }
                msgExpDt.length != 4 -> {
                    helperLog.appendLine(helperLogClassName, "REJECT :: expiry date must be 4 characters")
                    showToast("Expiry Date must be exactly 4 characters!", Toast.LENGTH_SHORT)
                }
                msgAmt.toDoubleOrNull() == null || msgAmt.toDouble() <= 0.00 -> {
                    helperLog.appendLine(helperLogClassName, "REJECT :: amount must be greater than 0.00")
                    showToast("Amount must be greater than 0.00!", Toast.LENGTH_SHORT)
                }
                else -> {
                    helperLog.appendLine(helperLogClassName, "Input Moto details :: CardNo[${Utils.hideCardDetails(msgCardNo)}], Amount[$msgAmt] ")
                        viewLifecycleOwner.lifecycleScope.launch {
                            val newMsgExpDt = msgExpDt.substring(2) + msgExpDt.substring(0, 2)
                            helperLog.appendLine(helperLogClassName, "Input Moto details :: CardNo[${Utils.hideCardDetails(msgCardNo)}], Amount[$msgAmt])")
                            motoSales(msgCardNo, newMsgExpDt, msgAmt)
                    }
                }
            }
        }

        if (ServiceHolder.appIntent || ServiceHolder.appHTTP) {
            val txnAmountArgument = arguments?.getLong("txnAmt", 0)
            val cardNumberArgument = arguments?.getString("cardNumber")
            val expDateArgument = arguments?.getString("expDate")

            if(!cardNumberArgument.isNullOrEmpty() && !expDateArgument.isNullOrEmpty()) {
                tvCardNo.text = cardNumberArgument
                tvExpDt.text = expDateArgument
                val tempAmount = Utils.getActualAmount(txnAmountArgument.toString())
                tvAmt.text = tempAmount
                helperLog.appendLine(helperLogClassName, "MOTO Request from Intent :: CardNo[${Utils.hideCardDetails(cardNumberArgument)}], Amount[$tempAmount] ")
                viewLifecycleOwner.lifecycleScope.launch {
                    motoSales(cardNumberArgument, expDateArgument, tempAmount)
                }
            }
        }
    }

    private suspend fun motoSales(msgCardNo: String, msgExpDt: String, msgAmt: String) {
        ServiceHolder.isoComm = null
        helperLog.appendLine(helperLogClassName, "Start Moto Sales....")
        showProgress("Bank Authorization", "Waiting for Approval")

        val mContext = requireContext()
        val isNotCompl = booleanArrayOf(true)
        try {
            transData.startTime = System.currentTimeMillis()
            val motoProductModel = ProductListRepo.getSinglev2(requireContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.MOTO.name))
                ?: ProductListRepo.getSinglev2(requireContext(), listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
            helperLog.appendLine(helperLogClassName, "Obtaining Product List :: ", motoProductModel.toString())

            if(motoProductModel == null) {
                helperLog.appendLine(helperLogClassName, "Product List is Empty")
                return
            }
            val jsonProductList = Gson().toJson(motoProductModel)
            val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.MOTO.data.SalesType
            ServiceHolder.saleModelCache = saleModelNew
            helperLog.appendLine(helperLogClassName, "Update Moto Model :: ", saleModelNew.toString())
            helperLog.appendLine(helperLogClassName, "End Process Moto Sale Onclick")

            ServiceHolder.saleModelCache?.let {
                transData.salesType = it.SalesType
                transData.acqCode = it.AcqCode ?: ""
                transData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
                transData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
                transData.product = it.Product ?: ""
                transData.productName = it.ProductName ?: ""
                //transData.productCode = it.Product ?: ""
                transData.eppTenure = it.EppTenure ?: ""
                transData.eppTenureCode = it.EppTenureCode ?: ""
                transData.ksn = it.Ksn ?: ""
                transData.pinKsn = it.PinKsn ?: ""
            }

            val txnDt = EmvUtil.getCurrentTime("yyyyMMddHHmmss")
            transData.transDateAsci = txnDt
            transData.txnTypeLabel = "Moto"
            transData.schemeId = "Moto"
            transData.entryModeLabel = "Manual"
            transData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_ENTRY_MODE, Utils.ASCIItoHexString("Manual"))
            posReference?.let {
                transData.posReference = it
                helperLog.appendLine(helperLogClassName, "Add Pos Reference :: $it")
            }
            orderingItem?.let {
                helperLog.appendLine(helperLogClassName, "Add Ordering Item :: ", it)
                transData.orderingItem = it
            }
            orderingItemImage?.let {
                helperLog.appendLine(helperLogClassName, "Add Ordering Item Image :: ", it)
                transData.orderingItemImage = it
            }

            withContext(Dispatchers.IO) {
                //Copy from void not sure correct or not
                transData.cvm = "3E3030"
                transData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARD_CVM,  "3E3030")
                helperLog.appendLine(helperLogClassName, "Insert CVM -> ", "3E3030")

                transData.maskedPan = Utils.hideCardDetails(msgCardNo)
                transData.hashedPan = msgCardNo.substring(0,9)

                val bytePan = msgCardNo.toByteArray()
                bytePan.copyInto(transData.pan, 0)
                transData.panLen = bytePan.size
                transData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARDPAN_MASKBCD, Utils.ASCIItoHexString(Utils.hideCardDetails(msgCardNo)))
                transData.addHexStrIntoTransDB(Global.cube.CUBE_TAG_CARDPAN_HASH, Utils.ASCIItoHexString(msgCardNo.substring(0,9)))
                transData.addHexStrIntoTransDB(Global.iso.tag.PANSTRING, msgCardNo)
                helperLog.appendLine(helperLogClassName, "Insert Card No to Iso-buffer")

                val byteExpDt = msgExpDt.toByteArray()
                byteExpDt.copyInto(transData.expirationDate, 0)
                transData.addHexStrIntoTransDB(Global.iso.tag.EXPDATE, msgExpDt)
                helperLog.appendLine(helperLogClassName, "Insert card exp to Iso-buffer")

                val strTxnAmt = Utils.zeroPadding(msgAmt.replace(".", ""), 12)
                if (strTxnAmt.isNotEmpty()) {
                    transData.amount = strTxnAmt.toLong()
                    HexUtil.hexStringToByte(strTxnAmt).copyInto(transData.amountAuth)
                    helperLog.appendLine(helperLogClassName, "Insert amount to Iso-buffer -> ", strTxnAmt)
                }

                CoroutineScope(Dispatchers.IO).launch {
                    IsoActivity.processMoto(mContext, helperLog)
                    isNotCompl[0] = false
                }
                helperLog.appendLine(helperLogClassName, "Moto Sales executing....")
                while (isNotCompl[0]) {
                    val isoComm = ServiceHolder.isoComm
                    if (isoComm != null) {
                        val status = isoComm.connectionStatus
                        if (!status.isNullOrEmpty()) {
                            updateProgress(null, status)
                        }
                    }
                    delay(500L)
                }
                helperLog.appendLine(helperLogClassName, "Moto Sales finish.")
            }
        } catch (ex : Exception) {
            ex.printStackTrace()
        } finally {
            //isNotCompl[0] = false
            withContext(Dispatchers.IO) {
                helperLog.appendLine(helperLogClassName, "MOTO Transaction End")
                helperLog.logToFile(EnumLogFileName.TerminaLog)
            }
            delay(500L)
            hideProgress()
        }
        navigateSafe(R.id.action_moto_to_transactionResult)
    }

    var expiryFormatter: TextFormatter = object : TextFormatter {
        override fun format(input: String): String {
            var digits = input.replace("\\D".toRegex(), "")
            if (digits.length > 4) digits = digits.substring(0, 4)

            if (digits.length <= 2) return digits

            return digits.substring(0, 2) + "/" + digits.substring(2)
        }
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}