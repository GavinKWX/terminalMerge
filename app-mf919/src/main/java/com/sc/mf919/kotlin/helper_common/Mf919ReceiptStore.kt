package com.sc.mf919.kotlin.helper_common

import android.content.Context
import com.sc.mf919.kotlin.activity.AppServices
import com.sc.mf919.kotlin.database.repo.BatchTableRepo
import com.sc.mf919.kotlin.database.repo.PreAuthTableRepo
import com.sc.mf919.kotlin.database.repo.ReceiptUploadRepo
import com.sc.mf919.kotlin.database.repo.ReversalBatchTableRepo
import com.sc.mf919.kotlin.helper_common.iso.IsoActivity
import tms.ReceiptStore
import tms.StoredTlv

/** MF919's half of the receipt seam; the reconciler itself lives in `:core` (audit item 86). */
object Mf919ReceiptStore : ReceiptStore {

	override fun approvedBatchTlv(context: Context, stan: String, invNo: String, batchNo: String): StoredTlv? =
		BatchTableRepo.getSingle(context, listOf("stan", "invNo", "batchNo"), arrayOf(stan, invNo, batchNo))
			?.let { StoredTlv(it.batchData) }

	override fun approvedPreAuthTlv(context: Context, invNo: String): StoredTlv? =
		PreAuthTableRepo.getSingle(context, listOf("invNo"), arrayOf(invNo))?.let { StoredTlv(it.addInfo) }

	override fun hasReversal(context: Context, stan: String, invNo: String, batchNo: String): Boolean =
		ReversalBatchTableRepo.getBatchData(context, listOf("stan", "invNo", "batchNo"), arrayOf(stan, invNo, batchNo))
			.isNotEmpty()

	override fun updateReceipt(context: Context, values: HashMap<Any, Any>, criteria: HashMap<Any, Any>) {
		ReceiptUploadRepo.updateData(context, values, criteria)
	}

	override fun eppDetailsJson(eppDe63: String?): String = IsoActivity.parseEppDetailsJson(eppDe63)

	override fun triggerUpload(context: Context) = AppServices.receiptUploadToTms(context)
}
