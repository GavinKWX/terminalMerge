package com.sc.mf919pro.kotlin.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentPhonenumberBinding
import com.sc.mf919pro.java.activity.Utils
import data_enum.Country
import data_enum.CountryRepository
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import com.sc.mf919pro.kotlin.helper_common.TmsHelper.foodLinkVerificationRequest
import enums.EnumDateFormat
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tms.models.FoodLinkVerificationRequestModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FoodLink membership verification step.
 *
 * Sits between the amount keypad and the payment fragment the operator picked, and is only
 * reached when the merchant config carries IsFoodLink = true (see KeypadFragment). The verified
 * correlationRef / additionalInfo pair is parked in ServiceHolder rather than TransData, because
 * every payment fragment calls transData.reset() on entry and would wipe it.
 */
class PhoneNumberFragment : BaseFragment() {
	lateinit var helperLog: HelperLog
	lateinit var helperLogClassName: String

	lateinit var tvCountry: TextView
	lateinit var tvPhone: TextView
	lateinit var selectedCountry: Country

	private var amountString = ""
	private var posReference: String? = null
	private var orderingItem: String? = null
	private var orderingItemImage: String? = null
	private var destination: String = DEST_CARD

	private var correlationRef: String? = null
	private var additionalInfo: String? = null

	private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

	companion object {
		const val ARG_DESTINATION = "foodLinkDestination"
		const val DEST_CARD = "card"
		const val DEST_SCAN_QR = "scanQr"
		const val DEST_GENERATE_QR = "generateQr"
	}

	private var _binding: FragmentPhonenumberBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = FragmentPhonenumberBinding.inflate(inflater, container, false)
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
			"Phone Number Fragment"
		)
		helperLog.appendLine(helperLogClassName, "Initialize PhoneNumber Fragment")

		binding.toolbarPhone.apply {
			setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
			setNavigationOnClickListener { customOnBackPress() }
		}
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() { customOnBackPress() }
			}
		)

		posReference = arguments?.getString("posReference")
		orderingItem = arguments?.getString("orderingItem")
		orderingItemImage = arguments?.getString("orderingItemImage")
		destination = arguments?.getString(ARG_DESTINATION) ?: DEST_CARD

		// The amount is already committed to the sale cache by KeypadFragment; FoodLink quotes
		// fees against it, so read it from there rather than re-deriving it from the keypad.
		amountString = Utils.getActualAmount(ServiceHolder.saleModelCache?.TransAmount?.toString() ?: "0")
		helperLog.appendLine(helperLogClassName, "txnAmt :: $amountString")
		helperLog.appendLine(helperLogClassName, "destination :: $destination")

		binding.scanPhoneQr.setDebouncedOnClickListener {
			helperLog.appendLine(helperLogClassName, "scanPhoneQr OnClick Detected")
			scanner()
		}

		tvCountry = binding.tvCountry
		tvPhone = binding.tvPhoneNo
		binding.keypadPhone.setFilter(tvPhone, false, 14, null)

		selectedCountry = CountryRepository.getDefault()
		tvCountry.text = "${selectedCountry.iso} +${selectedCountry.dialCode}"
		tvCountry.setOnClickListener {
			PopupMenu(requireContext(), tvCountry).apply {
				CountryRepository.countries.forEachIndexed { index, country ->
					menu.add(0, index, 0, "${country.iso} ${country.dialCode}")
				}

				setOnMenuItemClickListener { item ->
					selectedCountry = CountryRepository.countries[item.itemId]
					tvCountry.text = "${selectedCountry.iso} +${selectedCountry.dialCode}"
					true
				}
				show()
			}
		}

		binding.buttonSkip.setDebouncedOnClickListener {
			helperLog.appendLine(helperLogClassName, "FoodLink Verification skipped")
			ServiceHolder.clearFoodLinkCache()
			correlationRef = ""
			additionalInfo = ""
			salesNavigation()
		}

		binding.buttonVerify.setDebouncedOnClickListener { verifyPhoneNumber() }
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	private fun verifyPhoneNumber() {
		viewLifecycleOwner.lifecycleScope.launch {
			val countryCode = selectedCountry.dialCode
			val phoneNumber = tvPhone.text.toString()
			val fullPhone = "+$countryCode$phoneNumber"

			if (!isValidInternationalPhone(fullPhone)) {
				helperLog.appendLine(helperLogClassName, "Invalid PhoneNumber >> $fullPhone")
				showToast("Please enter valid phone number", Toast.LENGTH_SHORT)
				return@launch
			}

			showProgress("", "Verifying Phone Number...")
			val terminalSN = ServiceHolder.getTerminalSerialNumber()
			val dbMerchantConfig = ServiceHolder.getMerchantInfo()
			val systemMID = DbModelMerchantConfig.getSafeValue(dbMerchantConfig, "ScMid")
			val systemTID = DbModelMerchantConfig.getSafeValue(dbMerchantConfig, "ScTid")

			correlationRef = buildString {
				append(terminalSN)
				append("-")
				append(
					SimpleDateFormat(
						EnumDateFormat.yyyyMMddHHmmss_XDot.dateFormat,
						Locale.ENGLISH
					).format(Date())
				)
			}
			helperLog.appendLine(helperLogClassName, "correlationRef :: $correlationRef")

			val foodLinkVerificationRequestModel = FoodLinkVerificationRequestModel(
				correlationRef, systemMID, systemTID,
				countryCode, phoneNumber, amountString)
			additionalInfo = Gson().toJson(foodLinkVerificationRequestModel)
			helperLog.appendLine(helperLogClassName, "additionalInfo :: $additionalInfo")

			try {
				val success = foodLinkVerification(
					correlationRef ?: "",
					systemMID,
					systemTID,
					countryCode,
					phoneNumber,
					amountString
				)

				AlertDialog.Builder(requireContext())
					.setTitle("FoodLink Verification")
					.setMessage(
						if (success)
							"FoodLink Verification successful."
						else
							"FoodLink Verification unsuccessful.\nPlease try again."
					)
					.setPositiveButton("OK") { _, _ ->
						if (success) {
							salesNavigation()
						}
					}
					.show()
			} finally {
				hideProgress()
			}
		}
	}

	private suspend fun foodLinkVerification(
		correlationRef: String,
		systemMid: String,
		systemTid: String,
		phoneCountryCode: String,
		phoneNo: String,
		txnAmt: String
	) = withContext(Dispatchers.IO) {
		foodLinkVerificationRequest(
			helperLog,
			correlationRef,
			systemMid,
			systemTid,
			phoneCountryCode,
			phoneNo,
			txnAmt
		)
	}

	private fun salesNavigation() {
		ServiceHolder.foodLinkCorrelationRef = correlationRef ?: ""
		ServiceHolder.foodLinkAdditionalInfo = additionalInfo ?: ""

		val actionId = when (destination) {
			DEST_SCAN_QR -> R.id.action_phoneNumber_to_scanQr
			DEST_GENERATE_QR -> R.id.action_phoneNumber_to_generateQrSubProduct
			else -> R.id.action_phoneNumber_to_cardPayment
		}

		val args = Bundle().apply {
			putString("posReference", posReference)
			putString("orderingItem", orderingItem)
			putString("orderingItemImage", orderingItemImage)
		}

		helperLog.appendLine(helperLogClassName, "Navigate to :: $destination")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		navigateSafe(actionId, args)
	}

	private fun isValidInternationalPhone(phone: String): Boolean {
		return try {
			val number = phoneUtil.parse(phone, null)
			val formatted = phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
			phone == formatted && phoneUtil.isValidNumber(number)
		} catch (e: NumberParseException) {
			false
		}
	}

	private fun scanner() {
		try {
			val scanOption = ScanOptions()
			scanOption.setDesiredBarcodeFormats(
				ScanOptions.QR_CODE, ScanOptions.PDF_417, ScanOptions.CODE_39, ScanOptions.CODE_128
			)
			scanOption.setPrompt("")
			scanOption.setBeepEnabled(true)
			scanOption.setBarcodeImageEnabled(false)
			val cameraFacing = 1
			scanOption.setCameraId(cameraFacing)
			barcodeLauncher.launch(scanOption)
		} catch (e: Exception) {
			helperLog.appendLine(helperLogClassName, "scanner (Exception)", e.toString())
			e.printStackTrace()
		}
	}

	private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
		if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "onScanResult: ${result.contents}")
		if (result.contents == null) {
			val originalIntent = result.originalIntent
			if (originalIntent == null) {
				if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Cancelled scan")
			} else if (originalIntent.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION)) {
				if (this::helperLog.isInitialized) helperLog.appendLine(helperLogClassName, "Cancelled scan due to missing camera permission")
			}
			return@registerForActivityResult
		}

		try {
			val number = phoneUtil.parse(result.contents, null)
			helperLog.appendLine(helperLogClassName, "Parsed Phone Number >> $number")
			val region = phoneUtil.getRegionCodeForNumber(number)
			val phoneNumber = number.nationalNumber.toString()

			CountryRepository.countries.find { it.iso == region }?.let {
				selectedCountry = it
				tvCountry.text = "${it.iso} +${it.dialCode}"
				tvPhone.text = phoneNumber
				binding.keypadPhone.setFilter(tvPhone, false, 14, null)
			} ?: showToast("Unsupported country in scanned number", Toast.LENGTH_SHORT)
		} catch (e: NumberParseException) {
			helperLog.appendLine(helperLogClassName, "Scanned value is not a phone number", e.toString())
			showToast("Scanned code is not a valid phone number", Toast.LENGTH_SHORT)
		}
	}

	private fun customOnBackPress() {
		helperLog.appendLine(helperLogClassName, "OnBack Press Detected")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		ServiceHolder.clearFoodLinkCache()
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		navigateToHome(dbModelTerminalConfig)
	}
}
