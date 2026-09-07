package com.sc.mf919pro.kotlin.database.model


data class DbModelMerchantConfig(
	var MerchantName: String?,
	var MerchantAddress: String?,
	var AutoSettleT1: String?,
	var AutoSettleT2: String?,
	var AutoSettleT3: String?,
	var WaitCardMs: String?,
	var QrMid: String?,
	var QrTid: String?,
	var McVer: String?,
	var AcqCode: String?,
	var AcqMid: String?,
	var AcqTid: String?,
	var PrimaryHostIp: String?,
	var PrimaryHostPort: String?,
	var PrimaryHostSSL: String?,
	var SecondaryHostIp: String?,
	var SecondaryHostPort: String?,
	var SecondaryHostSSL: String?,
	var TPDU: String?,
	var NII: String?,
	var HostTimeoutMs: String?,
	var LastSettlementBatchNo: String?,
	var LastStan: String?,
	var LastInvoiceNo: String?,
	var Customization: String?,
	val Marketing: String?,
	val Advertisement: String?,
	val AcquirerLogo: String?,
	val Action1: String?,
	val Action2: String?,
	val Action3: String?,
	val Action4: String?,
	val SkipTxnValidation: String?,
	val IsTpaAccount: String?,
	val TpaMerchantLogoUrl: String?,
	val ScMid: String?,
	val ScTid: String?,
	val Support: String?,
	val IsFoodLink: String?,
) {
	companion object {
		// For Java file
		fun getSafeValue(mcModel: DbModelMerchantConfig?, attr: String): String {
			var result = ""
			val attrName = "get$attr"

			mcModel?.let {
				try {
					val tempResult = it.javaClass.getMethod(attrName).invoke(it)
					tempResult?.let { obtainedValue ->
						result = obtainedValue.toString()
					}
				} catch (e: Exception) {
					e.printStackTrace()
				}
			}
			return result
		}

		fun getSafeValue(mcModel: DbModelMerchantConfig?, attr: String, defaultValue:String = ""): String {
			var result = defaultValue
			val attrName = "get$attr"

			mcModel?.let {
				try {
					val tempResult = it.javaClass.getMethod(attrName).invoke(it)
					tempResult?.let { obtainedValue ->
						result = obtainedValue.toString()
					}
				} catch (e: Exception) {
					e.printStackTrace()
				}
			}
			return result
		}

		fun getBooleanValue(mcModel: DbModelMerchantConfig?, attr: String): Boolean {
			var result = "false"
			val attrName = "get$attr"

			mcModel?.let {
				try {
					val tempResult = it.javaClass.getMethod(attrName).invoke(it)
					tempResult?.let { obtainedValue ->
						if(obtainedValue.toString() == "1"){
							result = "true"
						}
					}
				} catch (e: Exception) {
					e.printStackTrace()
				}
			}
			return result.toBoolean()
		}

		fun setSafeValue(value: String): String {
			val result = value.ifEmpty {
				""
			}
			return result
		}
	}
}