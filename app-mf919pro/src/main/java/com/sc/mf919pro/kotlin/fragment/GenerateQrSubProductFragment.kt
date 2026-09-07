package com.sc.mf919pro.kotlin.fragment

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentSubproductselBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.QrProductDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919pro.kotlin.database.model.DbModelProductList
import com.sc.mf919pro.kotlin.database.model.DbModelProductListGet
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class GenerateQrSubProductFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    lateinit var containerLinear: LinearLayout

    private var txnAmount: Long = 0
    private var paymentCode: String? = null

    var aloneProduct: DbModelProductList? = null
    var txnMap: HashMap<String, String> = hashMapOf()
    var jObject: JSONObject = JSONObject()

    private var _binding: FragmentSubproductselBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSubproductselBinding.inflate(inflater, container, false)
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
            helperLog.appendLine(helperLogClassName, "GenerateQrSubProduct OnDestroyView :: screen ended")
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
            "Render Generate QR Sub Product"
        )
        helperLog.appendLine(helperLogClassName, "Initialize Render Generate QR Sub Product Activity")
        containerLinear = view.findViewById(R.id.scrollLinearCon)
        view.findViewById<TextView>(R.id.toolbarTV).text = "GENERATE QR"

        binding.toolbarCP.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener { customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        ServiceHolder.saleModelCache?.let {
            txnAmount = it.TransAmount
        }
        //txnAmount = arguments?.getInt("txnAmt")?: 0
        paymentCode = arguments?.getString("paymentCode")?:""

        //Currently only support generate QR
        if(!paymentCode.isNullOrEmpty()) {
            val generateQrModel = ProductListRepo.getSingle(ServiceHolder.mContext, listOf("Product", "QrProductCode", "IsActive"), arrayOf(ProductCatSelectionDataEnum.GENERATE_QR.name, paymentCode!!, "true"))
            generateQrModel?.let { it ->
                val jsonProductList = Gson().toJson(generateQrModel)
                val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
                saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                saleModelNew.TransAmount = txnAmount
                ServiceHolder.saleModelCache = saleModelNew
                navigateSafe(R.id.action_genQrSubProduct_to_generateQr)
            }
        } else {
            //renderDynamicProduct()
            checkingForGenerateQr()
            checkingForBNPL()

            if(aloneProduct != null) {
                println("Start Straight to Generate QR")
                val saleModelNew = Gson().fromJson(Gson().toJsonTree(aloneProduct), SaleModelNew::class.java)
                saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                saleModelNew.TransAmount = txnAmount
                ServiceHolder.saleModelCache = saleModelNew
                navigateSafe(R.id.action_genQrSubProduct_to_generateQr)
                println("Start Straight to Generate QR")
            }
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    fun checkingForGenerateQr() {
        helperLog.appendLine(helperLogClassName, "Start Rendering Generate QR Product List")
        val productCat = ProductCatSelectionDataEnum.GENERATE_QR.name
        val subProductList = ProductListRepo.getSelectedProduct(
            requireContext(),
            mutableListOf("Product"),
            arrayOf(productCat)
        )
        helperLog.appendLine(helperLogClassName, "Obtaining Sub Product List :: ", subProductList.toString())
        if (subProductList.isEmpty()) return

        if (subProductList.size == 1) {
            aloneProduct = subProductList.first()
            println("aloneProduct :: $aloneProduct")

            /*val getRow: Any = subProductList.first()
            val jsonObject: JsonObject = Gson().toJsonTree(getRow).asJsonObject
            val saleModelNew = Gson().fromJson(Gson().toJsonTree(modelData), SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
            saleModelNew.TransAmount = txnAmount
            ServiceHolder.saleModelCache = saleModelNew
            navigateSafe(R.id.action_genQrSubProduct_to_generateQr)*/
        }

        renderDynamicUi("E-Wallet", subProductList)
    }

    fun checkingForBNPL() {
        helperLog.appendLine(helperLogClassName, "Start Rendering BNPL List")
        val productCat = ProductCatSelectionDataEnum.BNPL.name
        val subProductList = ProductListRepo.getSelectedProduct(
            requireContext(),
            mutableListOf("Product"),
            arrayOf(productCat)
        )
        helperLog.appendLine(helperLogClassName, "Obtaining Sub Product List :: ", subProductList.toString())
        if (subProductList.isEmpty()) return

        aloneProduct = null
        renderDynamicUi("BNPL", subProductList)
    }

    fun renderDynamicUi(titleHeader: String, subProductList: List<DbModelProductList>) {
        val headerLinear = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(20, 10, 10, 0)
            }
            textSize = 22f
            setTextColor(Color.BLACK)
            // Underline text
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            text = titleHeader
        }
        containerLinear.addView(headerLinear)

        try {
            val columnCount = 3
            val rowCount = if(subProductList.size > columnCount) (subProductList.size + columnCount)/columnCount else 1

            for (rowIndex in 0 until rowCount) {
                val rowLinearLayout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(10, 20, 10, 10)
                    }
                }

                val startIndex = rowIndex * columnCount
                val endIndex = minOf((startIndex + columnCount), subProductList.size)
                val itemCountInRow = endIndex - startIndex

                for (index in startIndex until endIndex) {
                    val modelData = subProductList[index]

                    var tempProductModel = QrProductDataEnum.valueOf("QR_EMPTY").data
                    try {
                        tempProductModel = QrProductDataEnum.valueOf(modelData.QrProductCode).data
                    } catch (e: java.lang.Exception) {
                        helperLog.appendLine(helperLogClassName, "Exception in Error -> ", e.toString())
                        helperLog.logToFile(EnumLogFileName.TerminaLogException)
                        e.printStackTrace()
                    }

                    val itemLinear = LinearLayout(requireContext()).apply {
                        gravity = Gravity.CENTER
                        orientation = LinearLayout.VERTICAL
                        background = AppCompatResources.getDrawable(requireContext(), R.drawable.white_card_shadow)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            Helper.getInstance().getDpValue(150),
                        ).apply {
                            weight = 5.0f
                            setMargins(5,0, 5, 0)
                        }
                    }

                    val prodImageView = ImageView(requireContext()).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageResource(tempProductModel.logoImage)
                        setPadding(7, 10, 7, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            0
                        ).apply {
                            weight = 3.5f
                        }
                    }

                    // Create TextView for product name
                    val tv = TextView(requireContext()).apply {
                        gravity = Gravity.CENTER
                        text = modelData.ProductName
                        textSize = 20f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                        ellipsize = TextUtils.TruncateAt.END
                        maxLines = 2
                        setPadding(7, 0, 7, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0
                        ).apply {
                            weight = 1.5f
                        }
                    }
                    itemLinear.addView(prodImageView)
                    itemLinear.addView(tv)
                    itemLinear.setOnClickListener {
                        if(tempProductModel.LayoutFragmentId != -1) {
                            val saleModelNew = Gson().fromJson(Gson().toJsonTree(modelData), SaleModelNew::class.java)
                            saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                            saleModelNew.TransAmount = txnAmount
                            ServiceHolder.saleModelCache = saleModelNew
                            navigateSafe(R.id.action_genQrSubProduct_to_generateQr)
                        } else {
                            val saleModelNew = Gson().fromJson(Gson().toJsonTree(modelData), SaleModelNew::class.java)
                            saleModelNew.SalesType = ProductCatSelectionDataEnum.BNPL.data.SalesType
                            saleModelNew.TransAmount = txnAmount
                            ServiceHolder.saleModelCache = saleModelNew
                            navigateSafe(R.id.action_genQrSubProduct_to_scanQr)
                        }
                    }
                    rowLinearLayout.addView(itemLinear)
                }

                if (itemCountInRow < 3) {
                    val totalEmptyView = columnCount - itemCountInRow
                    for (i in 0 until totalEmptyView) {
                        val emptyLinear = createEmptyLinearItem()
                        rowLinearLayout.addView(emptyLinear)
                    }
                }
                containerLinear.addView(rowLinearLayout)
            }
        } catch (e: Exception) {
            helperLog.appendLine(helperLogClassName, "Exception in Rendering Product List -> ", e.toString())
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
            e.printStackTrace()
        }
    }

    private fun renderDynamicProduct() {
        helperLog.appendLine(helperLogClassName, "Start Rendering Product List")
        val productCat = ProductCatSelectionDataEnum.GENERATE_QR.name
        val subProductList = ProductListRepo.getSelectedProduct(
            requireContext(),
            mutableListOf("Product"),
            arrayOf(productCat)
        )
        if (subProductList.isEmpty()) return

        helperLog.appendLine(helperLogClassName, "Obtaining Sub Product List :: ", subProductList.toString())
        if (subProductList.size == 1) {
            val modelData = subProductList.first()
            val saleModelNew = Gson().fromJson(Gson().toJsonTree(modelData), SaleModelNew::class.java)
            saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
            saleModelNew.TransAmount = txnAmount
            ServiceHolder.saleModelCache = saleModelNew
            navigateSafe(R.id.action_genQrSubProduct_to_generateQr)
        } else {
            val headerLinear = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(20, 10, 10, 0)
                }
                textSize = 20f
                setTypeface(null, Typeface.BOLD_ITALIC)
                // Underline text
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                text = "E-Wallet Product"
            }
            containerLinear.addView(headerLinear)

            try {
                val columnCount = 3
                val rowCount = if(subProductList.size > columnCount) (subProductList.size + columnCount)/columnCount else 1

                for (rowIndex in 0 until rowCount) {
                    val rowLinearLayout = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(10, 20, 10, 10)
                        }
                    }

                    val startIndex = rowIndex * columnCount
                    val endIndex = minOf((startIndex + columnCount), subProductList.size)
                    val itemCountInRow = endIndex - startIndex

                    for (index in startIndex until endIndex) {
                        val modelData = subProductList[index]

                        var tempProductModel = QrProductDataEnum.valueOf("QR_EMPTY").data
                        try {
                            tempProductModel = QrProductDataEnum.valueOf(modelData.QrProductCode).data
                        } catch (e: java.lang.Exception) {
                            helperLog.appendLine(helperLogClassName, "Exception in Error -> ", e.toString())
                            helperLog.logToFile(EnumLogFileName.TerminaLogException)
                            e.printStackTrace()
                        }

                        val itemLinear = LinearLayout(requireContext()).apply {
                            gravity = Gravity.CENTER
                            orientation = LinearLayout.VERTICAL
                            background = AppCompatResources.getDrawable(requireContext(), R.drawable.white_card_shadow)
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                Helper.getInstance().getDpValue(150),
                            ).apply {
                                weight = 5.0f
                                setMargins(5,0, 5, 0)
                            }
                        }

                        val prodImageView = ImageView(requireContext()).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageResource(tempProductModel.logoImage)
                            setPadding(7, 10, 7, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                0
                            ).apply {
                                weight = 3.5f
                            }
                        }

                        // Create TextView for product name
                        val tv = TextView(requireContext()).apply {
                            gravity = Gravity.CENTER
                            text = modelData.ProductName
                            textSize = 20f
                            setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                            ellipsize = TextUtils.TruncateAt.END
                            maxLines = 2
                            setPadding(7, 0, 7, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0
                            ).apply {
                                weight = 1.5f
                            }
                        }
                        itemLinear.addView(prodImageView)
                        itemLinear.addView(tv)
                        itemLinear.setOnClickListener {
                            val saleModelNew = Gson().fromJson(Gson().toJsonTree(modelData), SaleModelNew::class.java)
                            saleModelNew.SalesType = ProductCatSelectionDataEnum.GENERATE_QR.data.SalesType
                            saleModelNew.TransAmount = txnAmount
                            ServiceHolder.saleModelCache = saleModelNew
                            navigateSafe(R.id.action_genQrSubProduct_to_generateQr)
                        }
                        rowLinearLayout.addView(itemLinear)
                    }

                    if (itemCountInRow < 3) {
                        val totalEmptyView = columnCount - itemCountInRow
                        for (i in 0 until totalEmptyView) {
                            val emptyLinear = createEmptyLinearItem()
                            rowLinearLayout.addView(emptyLinear)
                        }
                    }
                    containerLinear.addView(rowLinearLayout)
                }
            } catch (e: Exception) {
                helperLog.appendLine(helperLogClassName, "Exception in Rendering Product List -> ", e.toString())
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
                e.printStackTrace()
            }
        }
    }

    private fun createEmptyLinearItem(): LinearLayout {
        val emptyItemLinear = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                Helper.getInstance().getDpValue(150),
            ). apply {
                weight = 5.0f
                setMargins(5,0, 5, 0)
            }
        }
        return emptyItemLinear
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        navigateToHome(dbModelTerminalConfig)
    }
}