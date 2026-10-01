package com.sc.mf919.kotlin.helper_common

import android.content.Intent
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.activity.CardPaymentActivity
import com.sc.mf919.kotlin.activity.EppAcquirerActivity
import com.sc.mf919.kotlin.activity.GenerateQrActivity
import com.sc.mf919.kotlin.activity.KeypadActivitySaleCom
import com.sc.mf919.kotlin.activity.MotoSaleActivity
import com.sc.mf919.kotlin.activity.QrScanActivity
import com.sc.mf919.kotlin.activity.SettlementActivity
import com.sc.mf919.kotlin.activity.SettlementQrActivity
import com.sc.mf919.kotlin.activity.VoidOffSaleActivity
import com.sc.mf919.kotlin.activity.VoidPreauthActivity
import com.sc.mf919.kotlin.activity.VoidQrActivity
import com.sc.mf919.kotlin.activity.VoidSaleActivity
import com.sc.mf919.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919.kotlin.data_enum.SaleModelNew
import com.sc.mf919.kotlin.database.model.DbModelProductListGet
import com.sc.mf919.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.database.repo.ProductListRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.TransactionQrRepo
import data_enum.SalesModel
import ecr.Destination
import ecr.EnquiryQr
import ecr.EnquiryReceipt
import ecr.NewIntegrationHost
import enums.EnumResponseCode
import utils.AmountFormat

/**
 * MF919's half of the new-integration seam (audit item 98, phase 3). Requests reach it only with
 * `IsNewIntegration: true`; everything else stays on MF919's own protocol. It opens the same
 * Activities, with the same extras and caches, as MF919's old branches.
 */
object Mf919NewIntegrationHost : NewIntegrationHost {

	private val gson = Gson()

	/** True while the current ECR request is new integration; result and settlement screens read it. */
	@Volatile
	var active = false

	/** SETTLE_OPTION's type (CARD / QR / ALL, upper-cased) while a new-integration settlement runs. */
	@Volatile
	var settlementType: String? = null

	/** For ALL: the card part's entries, waiting for the QR part to finish. */
	@Volatile
	var pendingCardHttp: JsonArray? = null

	@Volatile
	var pendingCardA2a: ArrayList<HashMap<String, String?>>? = null

	@Volatile
	private var lastAmountCents: Long = 0

	/** Clears the per-request state; called for every ECR request, old or new. */
	fun begin(isNew: Boolean) {
		active = isNew
		settlementType = null
		pendingCardHttp = null
		pendingCardA2a = null
		lastAmountCents = 0
	}

	override fun autoSettlementIsRunning() = ServiceHolder.autoSettlementIsRunning

	override fun configFlag(name: String) =
		DbModelTerminalConfig.getBooleanValue(ServiceHolder.getTerminalConfig(), name)

	override fun clearSettlementBatch() = ServiceHolder.clearSettlementBatch

	override fun activeProduct(product: String, qrProductCode: String?): Any? =
		if (qrProductCode == null) {
			ProductListRepo.getSingle(ServiceHolder.getContext(), listOf("Product", "IsActive"), arrayOf(product, "true"))
		} else {
			ProductListRepo.getSingle(ServiceHolder.getContext(), listOf("Product", "QrProductCode", "IsActive"), arrayOf(product, qrProductCode, "true"))
		}.also {
			if (it == null) log(TAG, "No active ProductList row :: Product=$product QrProductCode=${qrProductCode ?: "-"}")
		}

	/** Both caches, as MF919's old branches set them. The amount is kept for the screen's txnAmt extra. */
	override fun setSaleModel(productRow: Any, amountCents: Long?, salesType: Int) {
		val row = productRow as DbModelProductListGet
		if (amountCents != null) lastAmountCents = amountCents
		ServiceHolder.selectedCacheModel = SalesModel(
			salesType, row.Product, row.AcqCode, row.AcqMid, row.AcqTid, row.QrProductCode,
			row.ProductName, row.EppProductCode, row.EppTenure, row.EppTenureCode
		)
		val saleModelNew = gson.fromJson(gson.toJson(row), SaleModelNew::class.java)
		saleModelNew.SalesType = salesType
		ServiceHolder.saleModelCache = saleModelNew
	}

	override fun hasEppAcquirer() = ProductListRepo.getDistinctEppAcquirer(ServiceHolder.getContext()).isNotEmpty()

	override fun receiptByPosRef(posReference: String): EnquiryReceipt? =
		ReceiptUploadRepo.getSingleDesc(ServiceHolder.getContext(), listOf("POS_REF_NO"), arrayOf(posReference))
			?.let { gson.fromJson(gson.toJson(it), EnquiryReceipt::class.java) }

	override fun qrByRef(refId: String): EnquiryQr? =
		TransactionQrRepo.getSingleTransactionQr(ServiceHolder.getContext(), listOf("refId"), listOf(refId))
			?.let { gson.fromJson(gson.toJson(it), EnquiryQr::class.java) }

	/** ScMid/ScTid when the product row with this acquirer pair is a TPA account (item 116 D). */
	override fun tpaMidTid(acqMid: String?, acqTid: String?): Pair<String, String>? {
		if (acqMid.isNullOrEmpty() || acqTid.isNullOrEmpty()) return null
		val row = ProductListRepo.getSinglev2(ServiceHolder.getContext(), listOf("AcqMid", "AcqTid"), listOf(acqMid, acqTid))
			?: return null
		if (row.IsTpaAccount?.lowercase() != "true") return null
		val merchant = ServiceHolder.getMerchantInfo()
		val mid = DbModelMerchantConfig.getSafeValue(merchant, "ScMid")
		val tid = DbModelMerchantConfig.getSafeValue(merchant, "ScTid")
		return if (mid.isNotEmpty() && tid.isNotEmpty()) mid to tid else null
	}

	override fun log(tag: String, message: String) = Utils.debugLogPrint(tag, message)

	/** MF919's own config flags for these flows (its old branches checked them; `:core` checks only SALES_CARD). */
	override fun gate(destination: Destination): EnumResponseCode? {
		val flag = when (destination) {
			Destination.PREAUTH -> "PreAuth"
			Destination.SALE_COMPLETION -> "SaleComOnline"
			Destination.KEYPAD_MOTO -> "MOTO"
			else -> return null
		}
		return if (configFlag(flag)) null else EnumResponseCode.TRANSACTION_NOT_SUPPORTED
	}

	/**
	 * Opens [destination] the way MF919's old branch for it does. [finishAttend]: HTTP closes the
	 * attend screen as the old HTTP branches do; MF919's App-to-App branches never did.
	 */
	fun launch(destination: Destination, args: Map<String, Any?>, finishAttend: Boolean) {
		val ctx = ServiceHolder.getContext()
		val pos = args["posReference"] as String?
		val amount = AmountFormat.getActualAmount(lastAmountCents.toString())
		val intent = when (destination) {
			Destination.CARD_SALE -> Intent(ctx, CardPaymentActivity::class.java).apply {
				putExtra("txnAmt", amount)
				putExtra("posReference", pos)
			}
			Destination.PREAUTH -> {
				ServiceHolder.selectedSettlementModel = ServiceHolder.selectedCacheModel
				Intent(ctx, CardPaymentActivity::class.java).apply {
					putExtra("txnAmt", amount)
					putExtra("posReference", pos)
					putExtra("typeofSale", 8)
				}
			}
			Destination.SCAN_QR -> Intent(ctx, QrScanActivity::class.java).apply {
				putExtra("txnAmt", amount)
				putExtra("posReference", pos)
				putExtra("cameraFacing", args["cameraFacing"] as Int? ?: 0)
			}
			Destination.GENERATE_QR -> Intent(ctx, GenerateQrActivity::class.java).apply {
				putExtra("txnAmt", amount)
				putExtra("posReference", pos)
			}
			Destination.EPP_ACQUIRER -> Intent(ctx, EppAcquirerActivity::class.java).apply {
				// MF919 screens read txnAmt as a decimal String; :core passes cents as a Long here.
				putExtra("txnAmt", AmountFormat.getActualAmount((args["txnAmt"] as Number).toLong().toString()))
				putExtra("posReference", pos)
			}
			Destination.KEYPAD_MOTO -> {
				moto(ctx)
				Intent(ctx, MotoSaleActivity::class.java).apply {
					putExtra("cardNumber", args["cardNumber"] as String?)
					putExtra("expDate", args["expDate"] as String?)
					putExtra("txnAmt", AmountFormat.getActualAmount((args["txnAmt"] as Number).toLong().toString()))
					putExtra("posReference", pos)
					putExtra("typeofSale", ProductCatSelectionDataEnum.MOTO.data.SalesType)
				}
			}
			Destination.VOID_SALE -> Intent(ctx, VoidSaleActivity::class.java).apply {
				putExtra("Invoice", args["Invoice"] as String?)
				putExtra("forceVoid", args["forceVoid"] as Int)
				putExtra("posReference", pos)
			}
			Destination.VOID_QR -> {
				qrVoidModel(ctx)
				Intent(ctx, VoidQrActivity::class.java).apply {
					putExtra("Invoice", args["Invoice"] as String?)
					putExtra("forceVoid", args["forceVoid"] as Int)
					putExtra("posReference", pos)
				}
			}
			Destination.SETTLE_OPTION -> {
				// Upper-cased here: Pro's HTTP keeps the case and "all" then settles nothing (item 100 quirk 1).
				val type = (args["settlementType"] as String).trim().uppercase()
				settlementType = type
				if (type == "QR") Intent(ctx, SettlementQrActivity::class.java)
				else Intent(ctx, SettlementActivity::class.java) // CARD, and ALL starts with card
			}
			Destination.SALE_COMPLETION -> {
				// MF919 runs completion on SalesType 4; :core passes 8, which MF919 runs as a pre-auth.
				ServiceHolder.saleModelCache?.SalesType = 4
				ServiceHolder.selectedSettlementModel = ServiceHolder.selectedCacheModel
				Intent(ctx, KeypadActivitySaleCom::class.java).apply {
					putExtra("txnAmt", amount)
					putExtra("posReference", pos)
					putExtra("typeofSale", 4)
					putExtra("apprCode", args["apprCode"] as String?)
					putExtra("rrn", args["rrn"] as String?)
					putExtra("invNo", args["invNo"] as String?)
					putExtra("forceVoid", args["forceVoid"] as Int? ?: 0)
				}
			}
			Destination.VOID_PREAUTH -> {
				cardVoidModel(ctx)
				Intent(ctx, VoidPreauthActivity::class.java).apply {
					putExtra("Invoice", args["Invoice"] as String?)
					putExtra("forceVoid", args["forceVoid"] as Int? ?: 0)
					putExtra("posReference", pos)
				}
			}
			Destination.VOID_SALE_COMPLETION -> {
				cardVoidModel(ctx)
				Intent(ctx, VoidOffSaleActivity::class.java).apply {
					putExtra("Invoice", args["Invoice"] as String?)
					putExtra("forceVoid", args["forceVoid"] as Int? ?: 0)
					putExtra("posReference", pos)
				}
			}
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		log(TAG, "New integration :: $destination")
		ctx.startActivity(intent)
		if (finishAttend) HTTPServer.attendActivityContext?.finish()
	}

	/** Voids of pre-auth and sale completion: MF919 caches the card product with SalesType 1. */
	private fun cardVoidModel(ctx: android.content.Context) {
		val row = ProductListRepo.getSinglev2(ctx, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name)) ?: return
		val saleModelNew = gson.fromJson(gson.toJson(row), SaleModelNew::class.java)
		saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
		ServiceHolder.saleModelCache = saleModelNew
	}

	/** QR void: the scan product, else the generate-QR product, cached with SalesType 1 (as the old branch). */
	private fun qrVoidModel(ctx: android.content.Context) {
		val row = ProductListRepo.getSinglev2(ctx, listOf("Product"), listOf(ProductCatSelectionDataEnum.EWALLET_MERCHANT_SCANS.name))
			?: ProductListRepo.getSinglev2(ctx, listOf("Product"), listOf(ProductCatSelectionDataEnum.GENERATE_QR.name))
			?: return
		val saleModelNew = gson.fromJson(gson.toJson(row), SaleModelNew::class.java)
		saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
		ServiceHolder.saleModelCache = saleModelNew
	}

	/** MOTO: the MOTO product, else card, as the old branch caches them (MotoSaleActivity then refines both). */
	private fun moto(ctx: android.content.Context) {
		val row = ProductListRepo.getSinglev2(ctx, listOf("Product"), listOf(ProductCatSelectionDataEnum.MOTO.name))
			?: ProductListRepo.getSinglev2(ctx, listOf("Product"), listOf(ProductCatSelectionDataEnum.CARD_SETTINGS.name))
			?: return
		ServiceHolder.selectedCacheModel = SalesModel(
			ProductCatSelectionDataEnum.MOTO.data.SalesType, row.Product, row.AcqCode, row.AcqMid, row.AcqTid,
			row.QrProductCode, row.ProductName, row.EppProductCode, row.EppTenure, row.EppTenureCode
		)
		val saleModelNew = gson.fromJson(gson.toJson(row), SaleModelNew::class.java)
		saleModelNew.SalesType = ProductCatSelectionDataEnum.CARD_SETTINGS.data.SalesType
		ServiceHolder.saleModelCache = saleModelNew
	}

	private const val TAG = "Mf919NewIntegration"
}
