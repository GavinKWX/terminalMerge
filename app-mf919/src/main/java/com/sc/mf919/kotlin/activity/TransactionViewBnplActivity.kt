package com.sc.mf919.kotlin.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.morefun.yapi.device.printer.FontFamily
import com.morefun.yapi.device.printer.MulPrintStrEntity
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.database.repo.PrintReceiptBnplRepo
import com.sc.mf919.java.activity.Utils.TextItem
import com.sc.mf919.kotlin.data_enum.AcquirerLogoDataEnum
import com.sc.mf919.kotlin.data_enum.AcquirerLogoEnumModel
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.helper_common.*
import data_enum.SalesModel
import enums.EnumLogFileName
import helpers.HelperCommon
import tms.models.AcquirerLogoReplaceObject
import tms.models.ioupay.IoupayScanQrResponseModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

class TransactionViewBnplActivity : ActivityBase() {
	private val isHideBottom get() = com.sc.mf919.BuildConfig.hide_bottom
	lateinit var previousBtn: ImageButton
	lateinit var nextBtn: ImageButton
	lateinit var mainReceipt: LinearLayout
	lateinit var topLogoView: ImageView
	lateinit var editText: EditText

	var mid: String = ""
	var tid: String = ""

	var current_pos = 0
	var max_pos = 0
	var invList: List<String>? = null
	var idList: List<String>? = null
	var useID = false

	var selectedSalesModel: SalesModel? = null
	var dbModelMerchantConfig: DbModelMerchantConfig? = null
	private var acquirerLogoConfig: AcquirerLogoReplaceObject? = null
	private lateinit var acqLogoEnumModel: AcquirerLogoEnumModel

	lateinit var bnplResp: IoupayScanQrResponseModel
	private val SUPERSCRIPT_REGEX = "(?<=\\b\\d{0,9999})(st|nd|rd|th)(?=\\b)"
	private val PATTERN: Pattern = Pattern.compile(SUPERSCRIPT_REGEX)

	private var helperlogClassName:String = ""
	lateinit var helperLog: helpers.HelperLog

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_txnview_bnpl)

		helperlogClassName = this::class.qualifiedName.toString()

		helperLog = helpers.HelperLog(
			HelperCommon.getSession(),
			TmsHelper.checkIsConnectedWifi(applicationContext),
			Utils.getIPAddress(),
			"TransactionViewBnplActivity",
			TransactionViewBnplActivity::class.java.simpleName,
			TransactionViewBnplActivity::class.java.simpleName,
		)
		helperLog.appendLine(helperlogClassName, "BNPL transaction history view opened")

		acqLogoEnumModel = AcquirerLogoEnumModel(
			R.mipmap.blank, "image/logo_footer_small.bmp", R.mipmap.ic_launcher, getResources().getString(R.string.app_name_about)
		)

		val btnMoreOtp = findViewById<LinearLayout>(R.id.moreOption).apply {
			visibility = View.VISIBLE
		}
		btnMoreOtp.setOnClickListener {
			helperLog.appendLine(helperlogClassName, "Dialog opened :: [PRINT HISTORY MENU]")
			val popupMenu: PopupMenu = PopupMenu(this, btnMoreOtp)
			popupMenu.menuInflater.inflate(R.menu.print_history_menu, popupMenu.menu)
			popupMenu.setOnMenuItemClickListener { item ->
				when (item.itemId) {
					R.id.printDetails -> {
						helperLog.appendLine(helperlogClassName, "Selected :: Print Details [PRINT HISTORY MENU]")
						helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> TransactionDetailsBnplActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val intent =
							Intent(applicationContext, TransactionDetailsBnplActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						startActivity(intent)
						finish()
					}

					R.id.printSummary -> {
						helperLog.appendLine(helperlogClassName, "Selected :: Print Summary [PRINT HISTORY MENU]")
						helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> SettlementBnplActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val intent = Intent(applicationContext, SettlementBnplActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						intent.putExtra("previewSettlement", 1)
						startActivity(intent)
						finish()
					}

					R.id.printLastSettlement -> {
						helperLog.appendLine(helperlogClassName, "Selected :: Print Last Settlement [PRINT HISTORY MENU]")
						helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> SettlementBnplActivity")
						helperLog.logToFile(EnumLogFileName.TerminaLog)
						val intent = Intent(applicationContext, SettlementBnplActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
						intent.putExtra("Last", 1)
						intent.putExtra("previewLastSettlement", 1)
						startActivity(intent)
						finish()
					}

				}
				true
			}
			popupMenu.show()
		}
		onBackPressedDispatcher.addCallback(this@TransactionViewBnplActivity, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				customOnBackPress()
			}
		})

		dbModelMerchantConfig = ServiceHolder.getMerchantInfo()
		acquirerLogoConfig = Gson().fromJson(DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "AcquirerLogo"), AcquirerLogoReplaceObject::class.java)
		selectedSalesModel = (ServiceHolder.selectedSettlementModel as SalesModel?)?.let {
			SalesModel(0, "", "", "", "", "", "", "", "", "")
		}
		acqLogoEnumModel = com.sc.mf919.kotlin.helper_common.Helper.getInstance().checkConfigOverrideLogo(
			AcquirerLogoDataEnum.from(selectedSalesModel?.QrProductCode)?.data ?: AcquirerLogoDataEnum.BLANK.data
		)

		mainReceipt = findViewById(R.id.mainReceipt)
		topLogoView = findViewById(R.id.txnViewLogoImage)
		previousBtn = findViewById(R.id.previousBtn)
		nextBtn = findViewById(R.id.nextBtn)

		var iSaleTxnCount = PrintReceiptBnplRepo.countByTxnType(applicationContext, "Sale")
		if (iSaleTxnCount < 0) {
			iSaleTxnCount = 0
		}

		var saleTxnTotal = PrintReceiptBnplRepo.sumTxnAmtByTxnType(applicationContext, "Sale")
		if (saleTxnTotal == null) {
			saleTxnTotal = "0"
		}
		var txnTotal = (saleTxnTotal.toInt()).toString()
		if (txnTotal != "0") {
			txnTotal = Utils.getActualAmount(txnTotal)
		}

		(findViewById<View>(R.id.sale_count) as TextView).text = iSaleTxnCount.toString() // this value
		(findViewById<View>(R.id.sale_amt) as TextView).text = txnTotal // this value

		val (inv_NOs, ids) = PrintReceiptBnplRepo.getRefIdAndIdList(applicationContext)

		helperLog.appendLine(helperlogClassName, "BNPL history loaded :: saleCount=$iSaleTxnCount saleTotal=$txnTotal records=${inv_NOs.size}")

		run {
			max_pos = inv_NOs.size
			invList = inv_NOs
			idList = ids
			editText = findViewById(R.id.inv)
			editText.addTextChangedListener(callbackET)
			if (invList?.size != 0) {
				useID = true
				editText.setText(invList?.get(current_pos))
			}
		}

		window.setSoftInputMode(
			WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
		)
		helperLog.logToFile(EnumLogFileName.TerminaLog)
	}

	override fun onDestroy() {
		if (this::helperLog.isInitialized) {
			helperLog.appendLine(helperlogClassName, "TransactionViewBnpl OnDestroy :: BNPL history view ended")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
		}
		super.onDestroy()
	}

	fun customOnBackPress() {
		helperLog.appendLine(helperlogClassName, "User Cancel :: leaving BNPL history view")
		helperLog.appendLine(helperlogClassName, "Validation passed :: navigate -> HomeScreen")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
		val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
		newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(newIntent)
		finish()
	}

	var callbackET: TextWatcher = object : TextWatcher {
		override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
			Utils.debugLogPrint("TAG", "beforeTextChanged: $s---$count")
		}

		@RequiresApi(Build.VERSION_CODES.Q)
		override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
			Utils.debugLogPrint("TAG", "onTextChanged: $s---$count")
			if (s.length == 12) {
				if (invList!!.contains(s.toString())) {
					if (!useID) {
						current_pos = invList!!.indexOf(s.toString())
					} else {
						useID = false
					}

					val msg = PrintReceiptBnplRepo.getRowArrayById(applicationContext, idList!![current_pos])
					if (msg != null) {
						helperLog.appendLine(helperlogClassName, "Selected :: BNPL record ref=$s position=${current_pos + 1}/$max_pos [BNPL HISTORY]")
						if (current_pos == max_pos - 1) {
							nextBtn.visibility = View.INVISIBLE
						} else {
							nextBtn.visibility = View.VISIBLE
						}
						if (current_pos == 0) {
							previousBtn.visibility = View.INVISIBLE
						} else {
							previousBtn.visibility = View.VISIBLE
						}


						bnplResp = Gson().fromJson(msg[20], IoupayScanQrResponseModel::class.java)

						closeKeyboard()

						// if acqCode not null update logo
						if (msg[16] != null) {
							try {
								acqLogoEnumModel = AcquirerLogoDataEnum.valueOf(msg[16]!!).data
							} catch (e: java.lang.Exception) {
								e.printStackTrace()
								helperLog.appendLine(helperlogClassName, "Exception in Obtain Dynamic AcqCode -> ", e.toString())
								helperLog.logToFile(EnumLogFileName.TerminaLogException)
							}
						}
						formLayout(msg)
					}
				}
				/*else {
					editText.setText(invList!![current_pos])
				}*/
			}
		}

		override fun afterTextChanged(s: Editable) {
			Utils.debugLogPrint("TAG", "afterTextChanged: $s")
		}
	}

	fun closeKeyboard() {
		val inputMethodManager = this.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
		if (inputMethodManager.isAcceptingText) {
			inputMethodManager.hideSoftInputFromWindow(this.currentFocus!!.windowToken, 0)
		}
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private fun formLayout(details: Array<String?>) {
		var powerByBmp: Bitmap? = null
		val paths = ServiceHolder.getInternalFilesPaths() + "powerLogo.png"
		val imgFile = File(paths)
		if (imgFile.exists()) {
			powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
			Utils.debugLogPrint("TAG", "onCreate: image")
		}
		if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
			if(powerByBmp != null) {
				topLogoView.setImageBitmap(powerByBmp)
			} else {
				topLogoView.setImageResource(R.mipmap.logo)

			}
		} else {
			topLogoView.setImageResource(acqLogoEnumModel.HeaderLogoPng)
		}

		mainReceipt.removeAllViews()
		val headerTags = arrayOf("DATE/TIME", "TXN ID", "TOTAL", "TENURE", "STATUS")
		val headerDetails = arrayOf(
			details[5], details[10],
			"RM" + Utils.getActualAmount(details[8]), details[19], details[13]
		)
		val name = TextView(applicationContext)
		name.textSize = 9f
		name.textAlignment = View.TEXT_ALIGNMENT_CENTER
		name.setTextColor(resources.getColor(R.color.black))
		name.text = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName")
		val addr = TextView(applicationContext)
		addr.textSize = 9f
		addr.textAlignment = View.TEXT_ALIGNMENT_CENTER
		addr.setTextColor(resources.getColor(R.color.black))
		addr.text = DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantAddress")
		mainReceipt.addView(name)
		mainReceipt.addView(addr)
		for (j in headerTags.indices) {
			if (headerDetails[j] == null || headerDetails[j] == "") {
				continue
			}
			val ll = LinearLayout(applicationContext)
			ll.orientation = LinearLayout.HORIZONTAL
			ll.weightSum = 2f
			val params1 = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params1.setMargins(5, 0, 5, 0)
			params1.weight = 1f
			val tv = TextView(applicationContext)
			tv.layoutParams = params1
			tv.textSize = 11f
			tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
			tv.setTextColor(resources.getColor(R.color.black))
			tv.text = headerTags[j]
			val tv1 = TextView(applicationContext)
			tv1.layoutParams = params1
			tv1.textSize = 11f
			tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
			tv1.setTypeface(tv1.typeface, Typeface.BOLD)
			tv1.setTextColor(resources.getColor(R.color.black))
			tv1.text = headerDetails[j]
			ll.addView(tv)
			ll.addView(tv1)
			mainReceipt.addView(ll)
		}

		if (bnplResp.SCHEDULE_LIST?.isNotEmpty() == true) {
			renderScheduleItem()
		}

		val footerTags = arrayOf(
			"1st PAYMENT", "TOTAL PAYABLE", "REF ID"
		)
		val footerDetails = arrayOf(
			"RM" + (bnplResp.SCHEDULE_LIST?.get(0)?.SCHEDULE_AMOUNT_STRING), "RM" + Utils.getActualAmount(details[8]), details[9]
		)
		for (j in footerTags.indices) {
			if (footerDetails[j] == null || footerDetails[j] == "") {
				continue
			}
			val ll = LinearLayout(applicationContext)
			/*val params = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)*/
			ll.orientation = LinearLayout.HORIZONTAL
			ll.weightSum = 2f
			val params1 = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
			)
			params1.setMargins(5, 0, 5, 0)
			params1.weight = 1f
			val tv = TextView(applicationContext)
			tv.layoutParams = params1
			tv.textSize = 11f
			tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
			tv.setTextColor(resources.getColor(R.color.black))
			tv.text = footerTags[j]
			val tv1 = TextView(applicationContext)
			tv1.layoutParams = params1
			tv1.textSize = 11f
			tv1.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
			tv1.setTypeface(tv1.typeface, Typeface.BOLD)
			tv1.setTextColor(resources.getColor(R.color.black))
			tv1.text = footerDetails[j]
			ll.addView(tv)
			ll.addView(tv1)
			mainReceipt.addView(ll)
		}

		val footer = LinearLayout(applicationContext)
		val params = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		params.setMargins(0, 5, 0, 0)
		params.gravity = Gravity.CENTER
		footer.orientation = LinearLayout.VERTICAL
		footer.layoutParams = params
		footer.setPadding(0, 0, 0, 20)
		val power1 = TextView(applicationContext)
		power1.textSize = 7.5f
		power1.textAlignment = View.TEXT_ALIGNMENT_CENTER
		power1.setTextColor(resources.getColor(R.color.black))
		power1.text = "I AGREE TO PAY THE ABOVE TOTAL AMOUNT"
		footer.addView(power1)
		val power = TextView(applicationContext)
		power.textSize = 9f
		power.textAlignment = View.TEXT_ALIGNMENT_CENTER
		power.setTypeface(power.typeface, Typeface.BOLD_ITALIC)
		power.setTextColor(resources.getColor(R.color.black))
		//power.text = "POWERED BY"
		//footer.addView(power)

		val im = ImageView(applicationContext)
		val params1 = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, Helper.getInstance().getDpValue(30)
		)
		params1.gravity = Gravity.CENTER
		im.layoutParams = params1
		im.adjustViewBounds = true

		if(!isHideBottom) {
			if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
				//if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
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
				} else {
					im.setImageResource(R.mipmap.logo)
				}
				footer.addView(im)
			}
		}

		mainReceipt.addView(footer)
	}

	fun move2Previous(view: View?) {
		current_pos = current_pos - 1
		Utils.debugLogPrint("TAG", "move2Previous: $max_pos--$current_pos")
		if (current_pos < 0) {
			current_pos = 0
			ToastMake(applicationContext, "First Transaction", Toast.LENGTH_SHORT)
		} else {
			useID = true
			editText.setText(invList!![current_pos])
		}
	}

	fun move2Next(view: View?) {
		current_pos = current_pos + 1
		Utils.debugLogPrint("TAG", "move2Next: 0--$current_pos")
		if (current_pos >= max_pos) {
			current_pos = max_pos - 1
			ToastMake(applicationContext, "Last Transaction", Toast.LENGTH_SHORT)
		} else {
			useID = true
			editText.setText(invList!![current_pos])
		}
	}

	private fun printInfo(details: Array<String?>) {
		val list: MutableList<MulPrintStrEntity> = ArrayList()
		val fontSize = FontFamily.MIDDLE
		val acquirerBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, acqLogoEnumModel.HeaderLogoBmp)
		var powerByBmp = PrintsActivity.getImageFromAssetsFile(applicationContext, "image/logo_footer_small.bmp")
		val paths = ServiceHolder.getInternalFilesPaths() + "powerBWLogo.png"
		val imgFile = File(paths)
		if (imgFile.exists()) {
			powerByBmp = BitmapFactory.decodeFile(imgFile.absolutePath)
		}

		val tags = arrayOf("DATE/TIME", "TXN ID", "TOTAL", "TENURE", "STATUS")
		var entity = MulPrintStrEntity("", fontSize)
		entity.bitmap = if(acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) powerByBmp else acquirerBmp
		entity.marginX = 50
		entity.gravity = Gravity.CENTER
		entity.isUnderline = true
		entity.yspace = 30
		list.add(entity)
		var mulPrintStrEntity = MulPrintStrEntity(
			DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantName"),
			FontFamily.SMALL, false,
			Gravity.CENTER
		)
		list.add(mulPrintStrEntity)
		mulPrintStrEntity = MulPrintStrEntity(
			DbModelMerchantConfig.getSafeValue(dbModelMerchantConfig, "MerchantAddress"),
			FontFamily.SMALL, false,
			Gravity.CENTER
		)
		list.add(mulPrintStrEntity)
		list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))
		list.add(
			MulPrintStrEntity(
				Utils.makeLineText(
					TextItem(tags[0]).setFont(fontSize), TextItem(
						details[5]
					).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		)
		list.add(
			MulPrintStrEntity(
				Utils.makeLineText(
					TextItem(tags[1]).setFont(fontSize), TextItem(
						details[10]
					).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		)
		list.add(
			MulPrintStrEntity(
				Utils.makeLineText(
					TextItem(tags[2]).setFont(fontSize), TextItem(
						"RM" + Utils.getActualAmount(details[8])
					).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		)
		list.add(
			MulPrintStrEntity(
				Utils.makeLineText(
					TextItem(tags[3]).setFont(fontSize), TextItem(
						details[19]
					).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		)
		list.add(
			MulPrintStrEntity(
				Utils.makeLineText(
					TextItem(tags[4]).setFont(fontSize), TextItem(
						details[13]
					).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
				), fontSize
			)
		)

		list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

		if (bnplResp.SCHEDULE_LIST?.isNotEmpty() == true) {
			for (i in bnplResp.SCHEDULE_LIST?.indices!!) {
				var paymentTitle: String?
				if (bnplResp.SCHEDULE_LIST!![i].IS_TODAY_PAYMENT == true) {
					paymentTitle = "1st Payment"
				} else {
					val currIndex = i+1
					val suffix = Helper.getInstance().getNumberSuffix(currIndex)
					paymentTitle = currIndex.toString() + suffix + " Payment "
				}

				list.add(
					MulPrintStrEntity(
						Utils.makeLineText(
							TextItem(paymentTitle.trimEnd()).setFont(fontSize), TextItem(
								" RM" + bnplResp.SCHEDULE_LIST!![i].SCHEDULE_AMOUNT_STRING
							).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
						), fontSize
					).setGravity(Gravity.RIGHT)
				)

				val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
				val date = dateFormat.parse(bnplResp.SCHEDULE_LIST!![i].PAYMENT_DATE)
				val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
				val timeStamp = sdf.format(date)

				list.add(
					MulPrintStrEntity(
						Utils.makeLineText(
							TextItem("(" + timeStamp + ")").setFont(fontSize), TextItem(
								""
							).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
						), fontSize
					).setYspace(10)
				)
			}

			list.add(MulPrintStrEntity("  ", FontFamily.MIDDLE, false, Gravity.CENTER))

			list.add(
				MulPrintStrEntity(
					Utils.makeLineText(
						TextItem("1st PAYMENT ").setFont(fontSize), TextItem(
							" RM" + bnplResp.SCHEDULE_LIST!![0].SCHEDULE_AMOUNT_STRING
						).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
					), fontSize
				).setYspace(10).setGravity(Gravity.RIGHT)
			)

			list.add(
				MulPrintStrEntity(
					Utils.makeLineText(
						TextItem("TOTAL PAYABLE").setFont(fontSize), TextItem(
							"RM" + Utils.getActualAmount(details[8])
						).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
					), fontSize
				)
			)

			list.add(
				MulPrintStrEntity(
					Utils.makeLineText(
						TextItem("REF ID").setFont(fontSize), TextItem(
							details[9]
						).setFont(fontSize).setPaddingAlign(Gravity.RIGHT)
					), fontSize
				).setYspace(10)
			)
		}

		list.add(
			MulPrintStrEntity(
				"I AGREE TO PAY THE ABOVE TOTAL AMOUNT", FontFamily.SMALL, false, Gravity.CENTER
			)
		)
		list.add(
			MulPrintStrEntity(
				"***** DUPLICATE COPY *****", FontFamily.MIDDLE, false, Gravity.CENTER
			).setIsBold(Typeface.BOLD).setYspace(10)
		)

		if(!isHideBottom) {
			if (acquirerLogoConfig?.IS_REPLACE_ACQ_LOGO == true) {
				//if IS_SHOW_BOTTOM_ACQ_LOGO false then do not show logo
				if (acquirerLogoConfig?.IS_SHOW_BOTTOM_ACQ_LOGO == true) {
					list.add(MulPrintStrEntity("PARTNER WITH", FontFamily.SMALL, false, Gravity.CENTER))

					entity = MulPrintStrEntity("", fontSize)
					entity.bitmap = acquirerBmp
					entity.marginX = 50
					entity.gravity = Gravity.CENTER
					entity.isUnderline = true
					entity.yspace = 30
					list.add(entity)
				}
			} else {
				list.add(MulPrintStrEntity("POWERED BY", FontFamily.SMALL, false, Gravity.CENTER))

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
		entity.yspace = 80
		list.add(entity)
		print(list)
	}

	fun print_receipt(view: View?) {
		if (idList.isNullOrEmpty()) {
			helperLog.appendLine(helperlogClassName, "REJECT :: print pressed with no BNPL records")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}

		val msg = PrintReceiptBnplRepo.getRowArrayById(applicationContext, idList!![current_pos]) ?: run {
			helperLog.appendLine(helperlogClassName, "REJECT :: BNPL record not found for print, position=$current_pos")
			helperLog.logToFile(EnumLogFileName.TerminaLog)
			return
		}
		helperLog.appendLine(helperlogClassName, "Print pressed :: BNPL receipt position=${current_pos + 1}/$max_pos")
		helperLog.logToFile(EnumLogFileName.TerminaLog)
		printInfo(msg)

	}

	fun tv_btn_cancel(view: View?) {
		helperLog.appendLine(helperlogClassName, "User Cancel :: cancel pressed on BNPL history view")
		customOnBackPress()
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private fun renderScheduleItem() {
		val firstItemTitle = SpannableString("1st Payment")
		val matcher: Matcher = PATTERN.matcher(firstItemTitle)

		while (matcher.find()) {
			val start = matcher.start()
			val end = matcher.end()
			firstItemTitle.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			firstItemTitle.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}

		val mainLinear = LinearLayout(applicationContext)
		val mainLinearParam = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
		)
		mainLinearParam.setMargins(5,15,5,20)
		mainLinear.layoutParams = mainLinearParam
		mainLinear.orientation = LinearLayout.VERTICAL

		try {
			for (i in bnplResp.SCHEDULE_LIST?.indices!!) {
				val productMainLV = LinearLayout(applicationContext)
				val productMainLVParam = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				productMainLV.layoutParams = productMainLVParam
				productMainLV.orientation = LinearLayout.VERTICAL

				val productLV = LinearLayout(applicationContext)
				val productLVParam = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.MATCH_PARENT
				)
				productLV.setPadding(0,5,0,0)
				productLV.layoutParams = productLVParam


				val productLV2 = LinearLayout(applicationContext)
				val productLV2Param = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				productLV2.setPadding(0,5,0,0)
				productLV2.layoutParams = productLV2Param

				val productTV1 = TextView(applicationContext)
				val productTV2 = TextView(applicationContext)
				val productTV3 = TextView(applicationContext)
				productTV1.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				productTV2.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT
				)
				productTV3.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.MATCH_PARENT
				)
				productTV1.gravity = Gravity.LEFT
				productTV2.gravity = Gravity.RIGHT
				productTV3.gravity = Gravity.LEFT

				if (bnplResp.SCHEDULE_LIST!![i].IS_TODAY_PAYMENT == true) {
					productTV1.text = firstItemTitle
				} else {
					val currIndex = i+1
					val suffix = Helper.getInstance().getNumberSuffix(currIndex)
					val itemTitle = SpannableString(currIndex.toString() + suffix + " Payment ")
					val matcher: Matcher = PATTERN.matcher(itemTitle)

					while (matcher.find()) {
						val start = matcher.start()
						val end = matcher.end()
						itemTitle.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
						itemTitle.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
					}
					productTV1.text = itemTitle
				}

				productTV2.text = "RM " + bnplResp.SCHEDULE_LIST!![i].SCHEDULE_AMOUNT_STRING
				val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
				val date = dateFormat.parse(bnplResp.SCHEDULE_LIST!![i].PAYMENT_DATE)
				val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
				val timeStamp = sdf.format(date)
				productTV3.text = "(" + timeStamp.toString() + ")"

				productTV1.setTypeface(productTV1.typeface, Typeface.NORMAL)
				productTV1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				productTV1.setTextColor(Color.parseColor("#000000"))
				productTV1.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

				productTV2.setTypeface(productTV2.typeface, Typeface.BOLD)
				productTV2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				productTV2.setTextColor(Color.parseColor("#000000"))
				productTV2.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

				productTV3.setTypeface(productTV3.typeface, Typeface.NORMAL)
				productTV3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				productTV3.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

				productLV.addView(productTV1)
				productLV.addView(productTV2)

				productLV2.addView(productTV3)

				productMainLV.addView(productLV)
				productMainLV.addView(productLV2)
				mainLinear.addView(productMainLV)
			}
		} catch (e: Exception) {
			e.printStackTrace()
			helperLog.appendLine(helperlogClassName, "Render BNPL schedule (Exception) -> $e")
			helperLog.logToFile(EnumLogFileName.TerminaLogException)
		}
		mainReceipt.addView(mainLinear)
	}
}
