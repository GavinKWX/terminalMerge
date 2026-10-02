package com.sc.mf919pro.kotlin.helper_common

import androidx.core.os.bundleOf
import com.google.gson.Gson
import com.sc.mf919pro.R
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.SaleModelNew
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919pro.kotlin.database.repo.TransactionQrRepo
import ecr.Destination
import ecr.EnquiryQr
import ecr.EnquiryReceipt
import ecr.NewIntegrationHost

/** Pro's half of the ECR router seam; the router and use cases live in `:core` `ecr` (audit item 98). */
object ProNewIntegrationHost : NewIntegrationHost {

	private val gson = Gson()

	override fun autoSettlementIsRunning() = ServiceHolder.autoSettlementIsRunning

	override fun configFlag(name: String) =
		DbModelTerminalConfig.getBooleanValue(ServiceHolder.getTerminalConfig(), name)

	override fun clearSettlementBatch() = ServiceHolder.clearSettlementBatch

	override fun activeProduct(product: String, qrProductCode: String?): Any? =
		if (qrProductCode == null) {
			ProductListRepo.getSingle(ServiceHolder.getContext(), listOf("Product", "IsActive"), arrayOf(product, "true"))
		} else {
			ProductListRepo.getSingle(ServiceHolder.getContext(), listOf("Product", "QrProductCode", "IsActive"), arrayOf(product, qrProductCode, "true"))
		}

	override fun setSaleModel(productRow: Any, amountCents: Long?, salesType: Int) {
		val saleModelNew = gson.fromJson(gson.toJson(productRow), SaleModelNew::class.java)
		if (amountCents != null) saleModelNew.TransAmount = amountCents
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

	/**
	 * ScMid/ScTid when the product the transaction ran on is a TPA account (item 116 D). Several
	 * products can share an acquirer pair, so TXN_TYPE picks the row first (item 118 L-1).
	 */
	override fun tpaMidTid(acqMid: String?, acqTid: String?, txnType: String?): Pair<String, String>? {
		if (acqMid.isNullOrEmpty() || acqTid.isNullOrEmpty()) return null
		val product = when {
			txnType?.contains("Moto", ignoreCase = true) == true -> "MOTO"
			txnType?.contains("Instalment", ignoreCase = true) == true -> "EPP"
			else -> "CARD_SETTINGS"
		}
		val ctx = ServiceHolder.getContext()
		val row = ProductListRepo.getSinglev2(ctx, listOf("Product", "AcqMid", "AcqTid"), listOf(product, acqMid, acqTid))
			?: ProductListRepo.getSinglev2(ctx, listOf("AcqMid", "AcqTid"), listOf(acqMid, acqTid))
			?: return null
		if (row.IsTpaAccount?.lowercase() != "true") return null
		val merchant = ServiceHolder.getMerchantInfo()
		val mid = DbModelMerchantConfig.getSafeValue(merchant, "ScMid")
		val tid = DbModelMerchantConfig.getSafeValue(merchant, "ScTid")
		return if (mid.isNotEmpty() && tid.isNotEmpty()) mid to tid else null
	}

	override fun log(tag: String, message: String) = Utils.debugLogPrint(tag, message)

	/** The navigation destination Pro shows for [destination]. */
	fun navId(destination: Destination): Int = when (destination) {
		Destination.CARD_SALE, Destination.PREAUTH -> R.id.cardPaymentFragment
		Destination.SCAN_QR -> R.id.scanQrFragment
		Destination.GENERATE_QR -> R.id.generateQrFragment
		Destination.EPP_ACQUIRER -> R.id.eppAcquirerFragment
		Destination.KEYPAD_MOTO -> R.id.keypadMotoFragment
		Destination.VOID_SALE -> R.id.voidSaleFragment
		Destination.VOID_QR -> R.id.voidQrFragment
		Destination.SETTLE_OPTION -> R.id.settleOptionFragment
		Destination.SALE_COMPLETION -> R.id.keypadSaleCompletionFragment
		Destination.VOID_PREAUTH -> R.id.voidPreAuthFragment
		Destination.VOID_SALE_COMPLETION -> R.id.voidSaleCompletionFragment
	}

	fun bundle(args: Map<String, Any?>) = bundleOf(*args.toList().toTypedArray())
}
