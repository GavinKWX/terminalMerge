package com.sc.mf919pro.kotlin.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentKeypadBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import data_enum.SalesModel
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
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
import kotlinx.coroutines.launch
import java.math.BigDecimal

class KeypadFragment : BaseFragment() {
	lateinit var helperLog: HelperLog
	lateinit var helperLogClassName: String
	lateinit var textViewAmount: TextView

	var amountString = ""
	//TODO
	private var typeOfSale = 0

	var enableCardSale = false
	var enableEWalletSale = false

	private var _binding: FragmentKeypadBinding? = null
	private val binding get() = _binding!!
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = FragmentKeypadBinding.inflate(inflater, container, false)
		return binding.root
	}
	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
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
			"Keypad Input Action"
		)
		val toolbar = binding.toolbarSale
		toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
		toolbar.setNavigationOnClickListener {  customOnBackPress() }
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					customOnBackPress()
				}
			}
		)
		val buttonGenQR = binding.buttonGenQr
		val buttonScanQR = binding.buttonQrScan
		val buttonCard = binding.buttonCard

		helperLog.appendLine(helperLogClassName, "Initialize Keypad Activity")
		typeOfSale = arguments?.getInt("typeofSale") ?: 0
		enableCardSale = arguments?.getBoolean("enableCard") ?: false
		enableEWalletSale = arguments?.getBoolean("enableEWallet") ?: false

		//Start Checking Available Option
		val merchantProduct = ServiceHolder.getMerchantProduct()
		helperLog.appendLine(helperLogClassName, "Obtaining Merchant Product :: ", merchantProduct.toString())
		merchantProduct?.let {
			for(proItem in merchantProduct) {
				when (proItem) {
					ProductCatSelectionDataEnum.CARD_SETTINGS.name -> {
						if(enableCardSale) {
							buttonCard.isClickable = true
							buttonCard.background = AppCompatResources.getDrawable(requireContext(), R.drawable.sales_selection_background)
							buttonCard.setDebouncedOnClickListener {
								val result = checkAmountValidity()
								if(result > 0) {
									//runLastReversal()
									viewLifecycleOwner.lifecycleScope.launch {
										IsoActivity.runLastReversalIo(requireContext(), helperLogClassName,
											onStartLoading = {
												showProgress("", "Running Last Reversal")
											},
											onStopLoading = {
												hideProgress()
											},
											onComplete = {
												cardSale()
											}
										)
									}
								}
							}
						}
					}
					ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name -> {
						if(enableEWalletSale) {
							buttonScanQR.isClickable = true
							buttonScanQR.background = AppCompatResources.getDrawable(requireContext(), R.drawable.sales_selection_background)
							buttonScanQR.setDebouncedOnClickListener {
								//scanQrSale()
								val result = checkAmountValidity()
								if(result > 0) {
									viewLifecycleOwner.lifecycleScope.launch {
										IsoActivity.runLastReversalIo(requireContext(), helperLogClassName,
											onStartLoading = {
												showProgress("", "Running Last Reversal")
											},
											onStopLoading = {
												hideProgress()
											},
											onComplete = {
												scanQrSale()
											}
										)
									}
								}
							}
						}
					}
					ProductCatSelectionDataEnum.BNPL.name, ProductCatSelectionDataEnum.GENERATE_QR.name -> {
						if(enableEWalletSale) {
							buttonGenQR.isClickable = true
							buttonGenQR.background = AppCompatResources.getDrawable(requireContext(), R.drawable.sales_selection_background)
							buttonGenQR.setDebouncedOnClickListener {
								//generateQrSale()
								val result = checkAmountValidity()
								if(result > 0) {
									viewLifecycleOwner.lifecycleScope.launch {
										IsoActivity.runLastReversalIo(requireContext(), helperLogClassName,
											onStartLoading = {
												showProgress("", "Running Last Reversal")
											},
											onStopLoading = {
												hideProgress()
											},
											onComplete = {
												generateQrSale()
											}
										)
									}
								}
							}
						}
					}
					else -> println("None available method")
				}
			}
		}
		//End Checking Available Option
		textViewAmount = binding.textViewAmount

		val keypad = binding.keypad1
		keypad.setFilter(textViewAmount, true, 12, null)
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun checkAmountValidity(): Long{
		helperLog.appendLine(helperLogClassName, "Amount entered :: ", textViewAmount.text.toString())
		amountString = textViewAmount.text.toString().replace("RM ".toRegex(), "")
		if (amountString == "0.00") {
			helperLog.appendLine(helperLogClassName, "REJECT :: amount is zero")
			showToast("Trade amount should be greater than 0", Toast.LENGTH_SHORT)
			return 0
		}

		val ss = BigDecimal(amountString)
		val ss1 = BigDecimal("999999.99")
		if (ss > ss1) {
			helperLog.appendLine(helperLogClassName, "REJECT :: amount exceeds 999999.99 limit")
			showToast("Trade amount should be less than 999999.99", Toast.LENGTH_SHORT)
			return 0
		}

		val validated = amountString.replace(".", "").toLongOrNull() ?: 0L
		if (validated > 0) {
			helperLog.appendLine(helperLogClassName, "Validation passed :: amount accepted", amountString)
		} else {
			helperLog.appendLine(helperLogClassName, "REJECT :: amount not parseable", amountString)
		}
		return validated
	}

	private fun cardSale(){
		helperLog.appendLine(helperLogClassName, "Selected :: Card payment [KEYPAD]")
		val result = checkAmountValidity()
		if(result > 0) {
			val cardProductModel = ProductListRepo.getSingle(requireContext(), listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name, "true"))
			helperLog.appendLine(helperLogClassName, "Obtaining Product List :: ", Gson().toJson(cardProductModel))
			val salesModel = SalesModel(
				ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType,
				cardProductModel?.Product ?: "",
				cardProductModel?.AcqCode ?: "",
				cardProductModel?.AcqMid ?: "",
				cardProductModel?.AcqTid ?: "",
				cardProductModel?.QrProductCode ?: "",
				cardProductModel?.ProductName ?: "",
				cardProductModel?.EppProductCode ?: "",
				cardProductModel?.EppTenure ?: "",
				cardProductModel?.EppTenureCode ?: ""
			)
			ServiceHolder.selectedCacheModel = salesModel
			val jsonProductList = Gson().toJson(cardProductModel)
			val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
			saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
			ServiceHolder.saleModelCache = saleModelNew
			saleModelNew.TransAmount = result
			helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
			helperLog.appendLine(helperLogClassName, "End Process Card Sale Onclick")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			CoroutineScope(Dispatchers.Main).launch {
				if (isFoodLinkEnabled()) {
					navigateToFoodLink(PhoneNumberFragment.DEST_CARD)
				} else {
					navigateSafe(R.id.action_keypadNew_to_cardPayment)
				}
			}
		}
	}

	private fun scanQrSale(){
		val result = checkAmountValidity()
		helperLog.appendLine(helperLogClassName, "Selected :: Scan QR payment [KEYPAD]")
		if(result > 0){
			val qrScanModel = ProductListRepo.getSingle(requireContext(), listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name, "true"))
			/*if(qrScanModel == null) {
				qrScanModel = ProductListRepo.getSingle(requireContext(), listOf("Product", "IsActive"), arrayOf(ProductCatSelectionDataEnum.BNPL.name, "true"))
			}*/
			helperLog.appendLine(helperLogClassName, "Obtaining Product List :: ", Gson().toJson(qrScanModel))
			val salesModel = SalesModel(
				ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType,
				qrScanModel?.Product ?: "",
				qrScanModel?.AcqCode ?: "",
				qrScanModel?.AcqMid ?: "",
				qrScanModel?.AcqTid ?: "",
				qrScanModel?.QrProductCode ?: "",
				qrScanModel?.ProductName ?: "",
				qrScanModel?.EppProductCode ?: "",
				qrScanModel?.EppTenure ?: "",
				qrScanModel?.EppTenureCode ?: ""
			)
			ServiceHolder.selectedCacheModel = salesModel
			val jsonProductList = Gson().toJson(qrScanModel)
			val saleModelNew = Gson().fromJson(jsonProductList, SaleModelNew::class.java)
			saleModelNew.SalesType = ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.data.SalesType
			saleModelNew.TransAmount = result
			ServiceHolder.saleModelCache = saleModelNew
			helperLog.appendLine(helperLogClassName, "Update Sale Model :: ", saleModelNew.toString())
			helperLog.appendLine(helperLogClassName, "End Process Scan QR Sale Onclick")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			CoroutineScope(Dispatchers.Main).launch {
				if (isFoodLinkEnabled()) {
					navigateToFoodLink(PhoneNumberFragment.DEST_SCAN_QR)
				} else {
					navigateSafe(R.id.action_keypadNew_to_scanQr)
				}
			}
		}
	}

	private fun generateQrSale(){
		val result = checkAmountValidity()
		if(result > 0){
			helperLog.appendLine(helperLogClassName, "Selected :: Generate QR payment [KEYPAD]")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			val saleModelNew = Gson().fromJson("{}", SaleModelNew::class.java)
			saleModelNew.TransAmount = result
			ServiceHolder.saleModelCache = saleModelNew
			CoroutineScope(Dispatchers.Main).launch {
				if (isFoodLinkEnabled()) {
					navigateToFoodLink(PhoneNumberFragment.DEST_GENERATE_QR)
				} else {
					navigateSafe(R.id.action_keypadNew_to_generateQrSubProduct)
				}
			}
		}
	}

	/*
	* FoodLink Integration
	* The flag lives on the merchant config (not the terminal config): it is provisioned per
	* merchant by TMS, so a terminal moved between merchants picks up the right behaviour on its
	* next config download.
	* */
	private fun isFoodLinkEnabled(): Boolean {
		val merchantConfig = ServiceHolder.getMerchantInfo()
		return DbModelMerchantConfig.getSafeValue(merchantConfig, "IsFoodLink") == "true"
	}

	private fun navigateToFoodLink(destination: String) {
		helperLog.appendLine(helperLogClassName, "FoodLink enabled, route to PhoneNumber :: ", destination)
		ServiceHolder.clearFoodLinkCache()
		val args = Bundle().apply {
			putString(PhoneNumberFragment.ARG_DESTINATION, destination)
		}
		navigateSafe(R.id.action_keypadNew_to_phoneNumber, args)
	}

	private fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		navigateToHome(dbModelTerminalConfig)
	}
}