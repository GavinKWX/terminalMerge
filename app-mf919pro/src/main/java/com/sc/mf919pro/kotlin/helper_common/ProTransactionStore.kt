package com.sc.mf919pro.kotlin.helper_common

import com.sc.mf919pro.kotlin.database.model.DbModelIsoBatchInfo
import android.content.Context
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.ProductListRepo
import com.sc.mf919pro.kotlin.database.repo.SecureDataRepo
import iso.AcquirerProduct
import iso.TransactionStore

/**
 * This app's side of [TransactionStore]: the six repository calls the ISO forming code in :core
 * needs. Registered once per process in MF919.onCreate, next to CurrentTxn and MdbController.
 *
 * Nothing here decides anything -- it forwards. The repositories stay per app because each fleet's
 * database layer may diverge; this file is the only thing that has to know that.
 */
object ProTransactionStore : TransactionStore {

	override fun allocateCounter(context: Context, tag: String, subtag: String): String =
		IsoBatchInfoRepo.allocateCounter(context, tag, subtag)

	override fun batchInfoValue(context: Context, tag: String, subtag: String): String? =
		IsoBatchInfoRepo.getBatchInfo(context, tag, subtag)?.value

	override fun storeBatchInfo(context: Context, tag: String, subtag: String, value: String) {
		IsoBatchInfoRepo.insertToBatchInfo(context, DbModelIsoBatchInfo(tag, subtag, value))
	}

	override fun acquirerProduct(context: Context, mid: String, tid: String): AcquirerProduct? =
		ProductListRepo.getSingle(context, listOf("AcqMid", "AcqTid"), arrayOf(mid, tid))?.let {
			AcquirerProduct(
				acqCode = it.AcqCode,
				product = it.Product,
				acqMid = it.AcqMid,
				acqTid = it.AcqTid,
				ksn = it.Ksn,
				pinKsn = it.PinKsn,
			)
		}

	override fun updateProduct(
		context: Context,
		values: Map<String, String>,
		criteria: Map<String, String>,
	): Boolean = ProductListRepo.updateData(
		context,
		HashMap<Any, Any>(values),
		HashMap<Any, Any>(criteria),
	)

	override fun secureValue(context: Context, tag: String, subtag: String): String? =
		SecureDataRepo.getDecryptedSingle(context, listOf("tag", "subtag"), listOf(tag, subtag))?.value

	override fun storeSecureValue(
		context: Context,
		tag: String,
		subtag: String,
		data: ByteArray,
		dataLen: Int,
	) = SecureDataRepo.setSecureData(context, tag, subtag, data, dataLen)
}
