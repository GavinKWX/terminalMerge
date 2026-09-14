package com.sc.mf919.kotlin.helper_common
import iso.IsoHelperNew

/*
 * KEPT FOR REFERENCE ONLY (2026-07-10).
 * This class configured the legacy Java IsoEngine (deleted — runtime-dead; the live
 * ISO flow is IsoActivity + IsoHelperNew + IsoStepsNew). The original content is
 * preserved below as a commented block for future reference of the old
 * MTI/proc-code/step/DE configurations per acquirer.
 */
/*

import com.sc.mf919.java.activity.IsoEngine

class IsoHelper {

	fun initStaticObject_gobiz_paydee(isoEngine: IsoEngine) {
		// Sign On
		isoEngine.setMtiProcCode("SignOn", "0800", "920000")
		isoEngine.setTxnSteps("SignOn", "6 2 3 22 4 5 9 21")
		isoEngine.setTxnDes("SignOn", "3 11 12 13 24 41 42 57 64")
		isoEngine.setTxnDes("SignOnSens", "0")//sensitive field

		// Contactless Sale
		isoEngine.setMtiProcCode("Sale", "0200", "000000")
		isoEngine.setTxnSteps("Sale", "1 6 31 32 2 3 13 23 49 4 5 9 7 11 8 14")
		isoEngine.setTxnDes("Sale", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		//isoEngine.setTxnDes("SaleTle", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("SaleTle", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("SaleSens", "35")//sensitive field

		isoEngine.setTxnDes("SaleRev", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("SaleRevTle", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("SaleRevSens", "2 14"/ *"35"* /)

		isoEngine.setTxnSteps("Reversal", "15")

		// Void Sale
		//19 6 2 3 23 4 5 9 11
		isoEngine.setMtiProcCode("VoidSale", "0200", "020000")
		isoEngine.setTxnSteps("VoidSale", "19 6 2 3 23 4 5 9 8 11 20")
		isoEngine.setTxnDes("VoidSale", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidSaleTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidSaleSens", "2 14")//sensitive field

		// Contact Sale (No clear-reversal(14) in steps)
		isoEngine.setMtiProcCode("CtSale", "0200", "000000")
		//isoEngine.setTxnSteps("CtSale", "1 6 31 2 3 13 23 49 4 5 9 7 11 8")
		isoEngine.setTxnSteps("CtSale", "14 1 6 31 2 3 13 23 49 4 5 9 7 11 8")
		isoEngine.setTxnDes("CtSale", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("CtSaleTle", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("CtSaleSens", "35")//sensitive field

		isoEngine.setTxnDes("CtSaleRev", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("CtSaleRevTle", "3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("CtSaleRevSens", "2 14")//sensitive field

		//reversal for contact txn, if card decline offline
		isoEngine.setMtiProcCode("CtRev", "0299", "000000")
		isoEngine.setTxnSteps("CtRev", "1 15")

		// Void Contact Sale
		isoEngine.setMtiProcCode("VoidCtSale", "0200", "020000")
		isoEngine.setTxnSteps("VoidCtSale", "19 6 2 3 23 4 5 9 8 11 20")
		isoEngine.setTxnDes("VoidCtSale", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtSaleTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtSaleSens", "2 14")//sensitive field

		// Contact TcUpload
		isoEngine.setMtiProcCode("TcUpload", "0320", "940000")
		//isoEngine.setTxnSteps("TcUpload", "1 14 30 8 27 25 15 2 3 23 4 5 35 9 28")//14=clearReversal 27=saveBatch
		isoEngine.setTxnSteps("TcUpload", "1 14 30 25 15 2 3 23 4 5 9 28")
		isoEngine.setTxnDes("TcUpload", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("TcUploadTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("TcUploadSens", "2 14")//sensitive field

		// PreAuth TcUpload
		isoEngine.setMtiProcCode("PreAuthTcUpload", "0320", "940000")
		isoEngine.setTxnSteps("PreAuthTcUpload", "1 14 30 27 25 15 2 3 23 4 5 35 9 28")//14=clearReversal 27=saveBatch
		isoEngine.setTxnDes("PreAuthTcUpload", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("PreAuthTcUploadTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("PreAuthTcUploadSens", "2 14")//sensitive field

		//MOTO Sale
		isoEngine.setMtiProcCode("Moto", "0200", "000000")
		isoEngine.setTxnSteps("Moto", "44 6 31 32 2 3 13 23 49 4 5 9 7 11 8 14")
		isoEngine.setTxnDes("Moto", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64")
		isoEngine.setTxnDes("MotoTle", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64")
		isoEngine.setTxnDes("MotoSens", "2 14")//sensitive field

		isoEngine.setTxnDes("MotoRev", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64")
		isoEngine.setTxnDes("MotoRevTle", "2 3 4 11 12 13 14 22 24 25 41 42 57 62 63 64")
		isoEngine.setTxnDes("MotoRevSens", "2 14")

		// Void Sale MOTO
		isoEngine.setMtiProcCode("VoidMoto", "0200", "020000")
		isoEngine.setTxnSteps("VoidMoto", "19 6 2 3 23 4 5 9 8 11 20")
		isoEngine.setTxnDes("VoidMoto", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 63 64")
		isoEngine.setTxnDes("VoidMotoTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 63 64")
		isoEngine.setTxnDes("VoidMotoSens", "2 14")//sensitive field

		// UNKNOWN
		isoEngine.setMtiProcCode("xTcUpload", "0220", "010000")
		isoEngine.setTxnSteps("xTcUpload", "1 14 30 8 27")//14=clearReversal 30=defaultApproved, 8=accumulate, 27=saveBatch

		isoEngine.setMtiProcCode("ReverseLast", "0400", "000000")
		isoEngine.setTxnSteps("ReverseLast", "45 15")
		isoEngine.setTxnDes("ReverseLast", "")
		// UNKNOWN

		isoEngine.setMtiProcCode("PreAuth", "0100", "300000")
		//isoEngine.setTxnSteps( "PreAuth", "1 10 15 6 2 3 13 4 5 35 9 11 14" )
		isoEngine.setTxnSteps("PreAuth", "1 6 2 3 13 23 49 4 5 35 9 43 11 41 14")
		isoEngine.setTxnDes("PreAuth", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("PreAuthTle", "3 4 11 22 23 24 25 35 41 42 48 52 55 57 62 64")
		isoEngine.setTxnDes("PreAuthRev", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("PreAuthRevTle", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("PreAuthSens", "35")//sensitive field
		isoEngine.setTxnDes("PreAuthRevSens", "2 14")//sensitive field

		// Void Preauth
		//19 6 2 3 23 4 5 9 11
		isoEngine.setMtiProcCode("VoidPreAuth", "0100", "020000")
		isoEngine.setTxnSteps("VoidPreAuth", "19 6 2 3 23 4 5 9 43 11 42")
		isoEngine.setTxnDes("VoidPreAuth", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidPreAuthTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidPreAuthSens", "2 14")//sensitive field

		isoEngine.setMtiProcCode("CtPreAuth", "0100", "300000")
		//isoEngine.setTxnSteps("CtPreAuth", "1 10 6 2 3 13 23 49 4 5 35 9 43 11 41 14")
		isoEngine.setTxnSteps("CtPreAuth", "1 10 6 2 3 13 23 49 4 5 35 9 43 11 41")
		isoEngine.setTxnDes("CtPreAuth", "3 4 11 22 23 24 25 35 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("CtPreAuthTle", "3 4 11 22 23 24 25 35 41 42 48 52 55 57 62 64")
		isoEngine.setTxnDes("CtPreAuthRev", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("CtPreAuthRevTle", "2 3 4 11 14 22 23 24 25 41 42 55 57 62 64")
		isoEngine.setTxnDes("CtPreAuthSens", "35")//sensitive field
		isoEngine.setTxnDes("CtPreAuthRevSens", "2 14")//sensitive field

		// Void Contact Preauth
		isoEngine.setMtiProcCode("VoidCtPreAuth", "0100", "020000")
		isoEngine.setTxnSteps("VoidCtPreAuth", "19 6 2 3 23 4 5 9 43 11 20")
		isoEngine.setTxnDes("VoidCtPreAuth", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtPreAuthTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtPreAuthSens", "2 14")//sensitive field

//		isoEngine.setMtiProcCode("OffSale", "0220", "000000")
//		isoEngine.setTxnSteps("OffSale", "1 6 31 32 2 3 13 23 49 4 5 9 7 11 8 14")
//		isoEngine.setTxnDes("OffSale", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 55 57 62 64 ")
//		isoEngine.setTxnDes("OffSaleTle", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 55 57 62 64 ")
//		isoEngine.setTxnDes("OffSaleRev", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 55 57 62 64")
//		isoEngine.setTxnDes("OffSaleRevTle", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 55 57 62 64")
//		isoEngine.setTxnDes("OffSaleSens", "2 14")//sensitive field
//		isoEngine.setTxnDes("OffSaleRevSens", "2 14")//sensitive field

		isoEngine.setMtiProcCode("OffSale", "0220", "000000")
		isoEngine.setTxnSteps("OffSale", "19 6 31 2 3 13 23 49 4 5 9 7 11 8 14")
		isoEngine.setTxnDes("OffSale", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64")
		isoEngine.setTxnDes("OffSaleTle", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64")
		isoEngine.setTxnDes("OffSaleRev", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64")
		isoEngine.setTxnDes("OffSaleRevTle", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 57 62 64")
		isoEngine.setTxnDes("OffSaleSens", "2 14")//sensitive field
		isoEngine.setTxnDes("OffSaleRevSens", "2 14")//sensitive field

		// Void offSale
		//19 6 2 3 23 4 5 9 11
		isoEngine.setMtiProcCode("VoidOffSale", "0220", "020000")
		isoEngine.setTxnSteps("VoidOffSale", "19 6 2 3 23 4 5 9 8 11 20")
		isoEngine.setTxnDes("VoidOffSale", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidOffSaleTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidOffSaleSens", "2 14")//sensitive field

		isoEngine.setMtiProcCode("CtOffSale", "0220", "000000")
		isoEngine.setTxnSteps("CtOffSale", "1 10 6 31 32 2 3 13 23 49 4 5 9 7 11 8 14")
		isoEngine.setTxnDes("CtOffSale", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("CtOffSaleTle", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 52 55 57 62 64")
		isoEngine.setTxnDes("CtOffSaleRev", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 55 57 62 64")
		isoEngine.setTxnDes("CtOffSaleRevTle", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 55 57 62 64")
		isoEngine.setTxnDes("CtOffSaleSens", "2 14")//sensitive field
		isoEngine.setTxnDes("CtOffSaleRevSens", "2 14")//sensitive field

		// Void Contact Sale
		isoEngine.setMtiProcCode("VoidCtOffSale", "0220", "020000")
		isoEngine.setTxnSteps("VoidCtOffSale", "19 6 2 3 23 4 5 9 8 11 20")
		isoEngine.setTxnDes("VoidCtOffSale", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtOffSaleTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtOffSaleSens", "2 14")//sensitive field

		//form message only, to obtain TID MID, no need send online
		isoEngine.setMtiProcCode("FakeOffSale", "0220", "000000")
		isoEngine.setTxnSteps("FakeOffSale", "1 2 3")
		isoEngine.setTxnDes("FakeOffSale", "2 3 4 11 24 38 41 42")

		isoEngine.setMtiProcCode("StoreTunneling", "0220", "000000")
		isoEngine.setTxnSteps("StoreTunneling", "1 2 3")
		isoEngine.setTxnDes("StoreTunneling", "2 3 4 11 24 38 41 42")

		isoEngine.setMtiProcCode("StoreOffSale", "0220", "000000")
		isoEngine.setTxnSteps("StoreOffSale", "1 2 3 24 35")
		isoEngine.setTxnDes("StoreOffSale", "2 3 4 11 24 38 41 42")

		isoEngine.setMtiProcCode("BatchUpload", "0320", "000000")
		isoEngine.setTxnSteps("BatchUpload", "19 6 2 3 23 4 5 9 11")
		isoEngine.setTxnDes("BatchUpload", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("BatchUploadRev", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("BatchUploadTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("BatchUploadRevTle", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 57 60 62 64")
		isoEngine.setTxnDes("BatchUploadSens", "2 14")//sensitive field
		isoEngine.setTxnDes("BatchUploadRevSens", "2 14")//sensitive field

		isoEngine.setMtiProcCode("Settle", "0500", "920000")
		isoEngine.setTxnSteps("Settle", "6 2 16 3 23 4 5 12 18 17")
		isoEngine.setTxnDes("Settle", "3 11 24 41 42 57 60 63 64")
		isoEngine.setTxnDes("SettleTle", "3 11 24 41 42 57 60 63 64")
		isoEngine.setTxnDes("SettleSens", "0")//sensitive field

		isoEngine.setMtiProcCode("SettleTrailer", "0500", "960000")
		isoEngine.setTxnSteps("SettleTrailer", "6 2 16 3 23 4 5 9 12 17 9")
		isoEngine.setTxnDes("SettleTrailer", "3 11 24 41 42 57 60 63 64")
		isoEngine.setTxnDes("SettleTrailerTle", "3 11 24 41 42 57 60 63 64")
		isoEngine.setTxnDes("SettleTrailerSens", "0")//sensitive field
	}

	fun initStaticObject_bsnCardzone(isoEngine: IsoEngine) {
		// Sign On
		isoEngine.setMtiProcCode("SignOn", "0800", "920000")
		isoEngine.setTxnSteps("SignOn", "6 2 3 22 4 5 9 50 51")
		isoEngine.setTxnDes("SignOn", "3 11 24 41 42")
		isoEngine.setTxnDes("SignOnSens", "0")//sensitive field

		// Contactless Sale
		isoEngine.setMtiProcCode("Sale", "0200", "000000")
		isoEngine.setTxnSteps("Sale", "1 6 31 2 3 13 23 49 4 5 9 7 11 8 14 51")
		isoEngine.setTxnDes("Sale", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("SaleTle", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("SaleSens", "35")//sensitive field
		isoEngine.setTxnDes("SaleRev", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("SaleRevTle", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("SaleRevSens", "2")

		//Reversal
		isoEngine.setTxnSteps("Reversal", "15 51")

		// Void Sale
		isoEngine.setMtiProcCode("VoidSale", "0200", "020000")
		isoEngine.setTxnSteps("VoidSale", "19 6 2 3 13 23 4 5 9 8 11 20 14 51")
		isoEngine.setTxnDes("VoidSale", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidSaleTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidSaleSens", "2")//sensitive field
		isoEngine.setTxnDes("VoidSaleRev", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidSaleRevTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidSaleRevSens", "2")//sensitive field

		// Contact Sale (No clear-reversal(14) in steps)
		isoEngine.setMtiProcCode("CtSale", "0200", "000000")
//		isoEngine.setTxnSteps("CtSale", "1 6 31 2 3 13 23 49 4 5 9 7 11 8 14 51")
		isoEngine.setTxnSteps("CtSale", "1 6 31 2 3 13 23 49 4 5 9 7 11 8 51 29")
		isoEngine.setTxnDes("CtSale", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("CtSaleTle", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("CtSaleSens", "35")//sensitive field
		isoEngine.setTxnDes("CtSaleRev", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("CtSaleRevTle", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("CtSaleRevSens", "2")//sensitive field

		//reversal for contact txn, if card decline offline
		isoEngine.setMtiProcCode("CtRev", "0299", "000000")
		isoEngine.setTxnSteps("CtRev", "1 15")

		// Void Contact Sale
		isoEngine.setMtiProcCode("VoidCtSale", "0200", "020000")
		isoEngine.setTxnSteps("VoidCtSale", "19 6 2 3 23 4 5 9 8 11 20 51")
		isoEngine.setTxnDes("VoidCtSale", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidCtSaleTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidCtSaleSens", "2")//sensitive field

		// Contact TcUpload
		isoEngine.setMtiProcCode("TcUpload", "0320", "940000")
		//isoEngine.setTxnSteps("TcUpload", "1 14 30 8 27 25 15 2 3 23 4 5 35 9 28")//14=clearReversal 27=saveBatch
		isoEngine.setTxnSteps("TcUpload", "1 14 30 25 2 3 23 4 5 9 28 51")
		isoEngine.setTxnDes("TcUpload", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("TcUploadTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("TcUploadSens", "35")//sensitive field

		//Settlement Header
		isoEngine.setMtiProcCode("Settle", "0500", "920000")
		isoEngine.setTxnSteps("Settle", "6 2 16 3 23 4 5 12 18 17")
		isoEngine.setTxnDes("Settle", "3 11 24 41 42 58 60 63 64")
		isoEngine.setTxnDes("SettleTle", "3 11 24 41 42 58 60 63 64")
		isoEngine.setTxnDes("SettleSens", "0")//sensitive field

		//Batch Upload
		isoEngine.setMtiProcCode("BatchUpload", "0320", "000000")
		isoEngine.setTxnSteps("BatchUpload", "19 6 2 3 23 4 5 9 11")
		isoEngine.setTxnSteps("BatchUpload", "19 6 2 3 23 4 5 9 11")
		isoEngine.setTxnDes("BatchUpload", "3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("BatchUploadRev", "3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("BatchUploadTle", "3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("BatchUploadRevTle", "3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("BatchUploadSens", "2")//sensitive field
		isoEngine.setTxnDes("BatchUploadRevSens", "2")//sensitive field

		//Settlement Trailer
		isoEngine.setMtiProcCode("SettleTrailer", "0500", "960000")
		isoEngine.setTxnSteps("SettleTrailer", "6 2 16 3 23 4 5 9 12 17 9")
		isoEngine.setTxnDes("SettleTrailer", "3 11 24 41 42 58 60 63 64")
		isoEngine.setTxnDes("SettleTrailerTle", "3 11 24 41 42 58 60 63 64")
		isoEngine.setTxnDes("SettleTrailerSens", "0")//sensitive field

		//Preauth
		isoEngine.setMtiProcCode("PreAuth", "0100", "300000")
		isoEngine.setTxnSteps("PreAuth", "1 6 2 3 13 23 49 4 5 35 9 43 11 41 14 51")
		isoEngine.setTxnDes("PreAuth", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("PreAuthTle", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("PreAuthRev", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("PreAuthRevTle", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("PreAuthSens", "35")//sensitive field
		isoEngine.setTxnDes("PreAuthRevSens", "2")//sensitive field

		// Void Preauth
		isoEngine.setMtiProcCode("VoidPreAuth", "0100", "020000")
		isoEngine.setTxnSteps("VoidPreAuth", "19 6 2 3 23 4 5 9 43 11 42 51")
		isoEngine.setTxnDes("VoidPreAuth", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidPreAuthTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidPreAuthSens", "2")//sensitive field

		isoEngine.setMtiProcCode("CtPreAuth", "0100", "300000")
		isoEngine.setTxnSteps("CtPreAuth", "1 10 6 2 3 13 23 49 4 5 35 9 43 11 41 14 51")
		isoEngine.setTxnDes("CtPreAuth", "3 4 11 22 23 24 25 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("CtPreAuthTle", "3 4 11 22 23 24 25 41 42 48 52 55 58 59 62 64")
		isoEngine.setTxnDes("CtPreAuthRev", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("CtPreAuthRevTle", "3 4 11 14 22 23 24 25 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("CtPreAuthSens", "35")//sensitive field
		isoEngine.setTxnDes("CtPreAuthRevSens", "2")//sensitive field

		// PreAuth TcUpload
		isoEngine.setMtiProcCode("PreAuthTcUpload", "0320", "940000")
		isoEngine.setTxnSteps("PreAuthTcUpload", "1 14 30 27 25 15 2 3 23 4 5 35 9 28 51")//14=clearReversal 27=saveBatch
		isoEngine.setTxnDes("PreAuthTcUpload", "3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("PreAuthTcUploadTle", "3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 58 59 60 62 64")
		isoEngine.setTxnDes("PreAuthTcUploadSens", "2")//sensitive field

		// Void Contact Preauth
		isoEngine.setMtiProcCode("VoidCtPreAuth", "0100", "020000")
		isoEngine.setTxnSteps("VoidCtPreAuth", "19 6 2 3 23 4 5 9 43 11 20 51")
		isoEngine.setTxnDes("VoidCtPreAuth", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidCtPreAuthTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidCtPreAuthSens", "2")//sensitive field

		//Preauth Completion
		isoEngine.setMtiProcCode("OffSale", "0220", "000000")
		isoEngine.setTxnSteps("OffSale", "19 6 31 2 3 13 23 49 4 5 9 7 11 8 14 51")
		isoEngine.setTxnDes("OffSale", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("OffSaleTle", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("OffSaleRev", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("OffSaleRevTle", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("OffSaleSens", "2")//sensitive field
		isoEngine.setTxnDes("OffSaleRevSens", "2")//sensitive field

		isoEngine.setMtiProcCode("CtOffSale", "0220", "000000")
		isoEngine.setTxnSteps("CtOffSale", "1 10 6 31 32 2 3 13 23 49 4 5 9 7 11 8 14 51")
		isoEngine.setTxnDes("CtOffSale", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("CtOffSaleTle", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 52 55 58 59 62 64")
		isoEngine.setTxnDes("CtOffSaleRev", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("CtOffSaleRevTle", "3 4 11 12 13 14 22 23 24 25 37 38 41 42 55 58 59 62 64")
		isoEngine.setTxnDes("CtOffSaleSens", "2")//sensitive field
		isoEngine.setTxnDes("CtOffSaleRevSens", "2")//sensitive field

		// Sale Installment
		isoEngine.setMtiProcCode("EppSale", "0200", "000000")
		isoEngine.setTxnSteps("EppSale", "1 6 31 2 52 3 13 23 49 4 5 9 7 11 8 14 51")
		isoEngine.setTxnDes("EppSale", "3 4 11 22 23 24 25 41 42 52 53 55 58 59 61 62 64")
		isoEngine.setTxnDes("EppSaleTle", "3 4 11 22 23 24 25 41 42 52 53 55 58 59 61 62 64")
		isoEngine.setTxnDes("EppSaleSens", "35")//sensitive field
		isoEngine.setTxnDes("EppSaleRev", "3 4 11 14 22 23 24 25 41 42 55 58 59 61 62 64")
		isoEngine.setTxnDes("EppSaleRevTle", "3 4 11 14 22 23 24 25 41 42 55 58 59 61 62 64")
		isoEngine.setTxnDes("EppSaleRevSens", "2")//sensitive field

		// Void Sale EPP
		isoEngine.setMtiProcCode("VoidEPP", "0200", "020000")
		isoEngine.setTxnSteps("VoidEPP", "19 6 2 52 3 13 23 4 5 9 8 11 20 14 51")
		isoEngine.setTxnDes("VoidEPP", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidEPPTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidEPPSens", "2")//sensitive field
		isoEngine.setTxnDes("VoidEPPRev", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidEPPRevTle", "3 4 7 11 12 13 14 22 24 25 37 38 41 42 58 59 62 64")
		isoEngine.setTxnDes("VoidEPPRevSens", "2")//sensitive field

	}

	fun initStaticObject_bsn(isoEngine: IsoEngine) {
		// Sign On
		isoEngine.setMtiProcCode("SignOn", "0800", "93000X")
		isoEngine.setTxnDes("SignOn", "3 11 24 41")
		isoEngine.setTxnSteps("SignOn", "6 2 3 22 4 5 9 21")
		isoEngine.setTxnDes("SignOnSens", "0")//sensitive field

		// Contactless Sale
		isoEngine.setMtiProcCode("Sale", "0200", "000000")
		isoEngine.setTxnSteps("Sale", "1 6 46 47 31 2 3 13 23 49 4 5 45 9 7 11 8 14")
		isoEngine.setTxnDes("Sale", "3 4 11 22 23 24 25 35 41 42 52 53 55 62")//45
		isoEngine.setTxnDes("SaleTle", "3 4 11 22 23 24 25 41 42 53 57 62 64")
		isoEngine.setTxnDes("SaleSens", "35 52 55")//sensitive field

		isoEngine.setTxnDes("SaleRev", "2 3 4 11 14 22 23 24 25 41 42 55 62")
		isoEngine.setTxnDes("SaleRevTle", "3 4 11 22 23 24 25 41 42 57 62 64")
		isoEngine.setTxnDes("SaleRevSens", "2 14 55")//sensitive field
		// --------------------------------------------------------

		// Contact Sale (No clear-reversal(14) in steps)
		isoEngine.setMtiProcCode("CtSale", "0200", "000000")
//		isoEngine.setTxnSteps("CtSale", "1 6 46 47 31 2 3 13 23 49 4 5 45 9 7 11 8 14")
		isoEngine.setTxnSteps("CtSale", "1 6 46 47 31 2 3 13 23 49 4 5 45 9 7 11 8")
		isoEngine.setTxnDes("CtSale", "3 4 11 22 23 24 25 35 41 42 52 53 55 62")
		isoEngine.setTxnDes("CtSaleTle", "3 4 11 22 23 24 25 41 42 53 57 62 64")
		isoEngine.setTxnDes("CtSaleSens", "35 52 55")//sensitive field

		isoEngine.setTxnDes("CtSaleRev", "2 3 4 11 14 22 23 24 25 41 42 55 62")
		isoEngine.setTxnDes("CtSaleRevTle", "3 4 11 22 23 24 25 41 42 57 62 64")
		isoEngine.setTxnDes("CtSaleRevSens", "2 14 55")//sensitive field

		isoEngine.setTxnSteps("Reversal", "48 46 15")

		//MOTO Sale
		isoEngine.setMtiProcCode("Moto", "0200", "000000")
		isoEngine.setTxnSteps("Moto", "44 6 48 46 47 31 2 3 13 23 49 4 5 45 9 7 11 8 14")
		isoEngine.setTxnDes("Moto", "2 3 4 11 12 13 14 22 24 25 41 42 62 63")
		isoEngine.setTxnDes("MotoTle", "3 4 11 12 13 22 24 25 41 42 57 62 63 64")
		isoEngine.setTxnDes("MotoSens", "2 14")//sensitive field

		isoEngine.setTxnDes("MotoRev", "2 3 4 11 12 13 14 22 24 25 41 42 62 63")
		isoEngine.setTxnDes("MotoRevTle", "3 4 11 12 13 22 24 25 41 42 57 62 63 64")
		isoEngine.setTxnDes("MotoRevSens", "2 14")

		// Void Sale MOTO
		isoEngine.setMtiProcCode("VoidMoto", "0200", "020000")
		isoEngine.setTxnSteps("VoidMoto", "19 6 48 46 2 3 23 4 5 45 9 8 11 20")
		isoEngine.setTxnDes("VoidMoto", "2 3 4 11 12 13 14 22 23 24 25 37 41 42 62 63")
		isoEngine.setTxnDes("VoidMotoTle", "3 4 11 12 13 22 23 24 25 37 41 42 57 62 63 64")
		isoEngine.setTxnDes("VoidMotoSens", "2 14")//sensitive field

		// Void Contact Sale
		isoEngine.setMtiProcCode("VoidCtSale", "0200", "020000")
		isoEngine.setTxnSteps("VoidCtSale", "19 6 46 2 3 23 4 5 45 9 8 11 20")
		isoEngine.setTxnDes("VoidCtSale", "2 3 4 11 12 13 14 22 23 24 25 37 41 42 62")
		isoEngine.setTxnDes("VoidCtSaleTle", "3 4 11 12 13 22 23 24 25 37 41 42 57 62 64")
		isoEngine.setTxnDes("VoidCtSaleSens", "2 14")//sensitive field

		// Contact TcUpload
		isoEngine.setMtiProcCode("TcUpload", "0320", "940000")
		//isoEngine.setTxnSteps("TcUpload", "1 46 14 30 8 27 25 15 2 3 23 4 5 35 9 28")//14=clearReversal 27=saveBatch
		isoEngine.setTxnSteps("TcUpload", "1 14 48 46 30 25 2 3 23 4 5 45 9 28")
		isoEngine.setTxnDes("TcUpload", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 60 62")
		isoEngine.setTxnDes("TcUploadTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 60 62 64")
		isoEngine.setTxnDes("TcUploadSens", "2 14 55")//sensitive field

		// (Gavin) Not found in the Implementation Document
		//reversal for contact txn, if card decline offline
		//isoEngine.setMtiProcCode("CtRev", "0299", "000000")
		//isoEngine.setTxnSteps("CtRev", "1 15")

		// Void Sale
		isoEngine.setMtiProcCode("VoidSale", "0200", "020000")
		isoEngine.setTxnSteps("VoidSale", "19 6 46 2 3 23 4 5 45 9 8 11 20")
		isoEngine.setTxnDes("VoidSale", "2 3 4 11 12 13 14 22 23 24 25 37 41 42 62")
		isoEngine.setTxnDes("VoidSaleTle", "3 4 11 12 13 22 23 24 25 37 41 42 57 62 64")
		isoEngine.setTxnDes("VoidSaleSens", "2 14")//sensitive field

		//Settlement
		isoEngine.setMtiProcCode("Settle", "0500", "920000")
		isoEngine.setTxnSteps("Settle", "6 2 16 46 3 23 4 5 45 12 18 17")
		isoEngine.setTxnDes("Settle", "3 11 24 41 42 60 63")
		isoEngine.setTxnDes("SettleTle", "3 11 24 41 42 57 64")
		isoEngine.setTxnDes("SettleSens", "60 63")//sensitive field

		//Batch Upload
		isoEngine.setMtiProcCode("BatchUpload", "0320", "000000")
		isoEngine.setTxnSteps("BatchUpload", "19 6 46 2 3 23 4 5 45 9 11")
		isoEngine.setTxnDes("BatchUpload", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 60 62")
		isoEngine.setTxnDes("BatchUploadRev", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 60 62")
		isoEngine.setTxnDes("BatchUploadTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 60 62 64")
		isoEngine.setTxnDes("BatchUploadRevTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 60 62 64")
		isoEngine.setTxnDes("BatchUploadSens", "2 14 55")//sensitive field
		isoEngine.setTxnDes("BatchUploadRevSens", "2 14 55")//sensitive field

		//Settlement Trailer
		isoEngine.setMtiProcCode("SettleTrailer", "0500", "960000")
		isoEngine.setTxnSteps("SettleTrailer", "6 46 2 16 3 23 4 5 45 9 12 17 9")
		isoEngine.setTxnDes("SettleTrailer", "3 11 24 41 42 60 63")
		isoEngine.setTxnDes("SettleTrailerTle", "3 11 24 41 42 57 64")
		isoEngine.setTxnDes("SettleTrailerSens", "60 63")//sensitive field

		//Instalment Sale
		isoEngine.setMtiProcCode("EppSale", "0200", "000000")
		isoEngine.setTxnSteps("EppSale", "1 14 6 31 2 48 46 47 3 13 23 49 4 5 45 9 7 11 8")
		isoEngine.setTxnDes("EppSale", "3 4 11 22 23 24 25 35 41 42 52 53 55 60 62")
		isoEngine.setTxnDes("EppSaleTle", "3 4 11 22 23 24 25 41 42 53 57 60 62 64")
		isoEngine.setTxnDes("EppSaleSens", "35 52 55")//sensitive field
		isoEngine.setTxnDes("EppSaleRev", "2 3 4 11 14 22 23 24 25 41 42 55 60 62")
		isoEngine.setTxnDes("EppSaleRevTle", "3 4 11 22 23 24 25 41 42 57 60 62 64")
		isoEngine.setTxnDes("EppSaleRevSens", "2 14 55")//sensitive field

		//Contact Instalment Sale
		isoEngine.setMtiProcCode("CtEppSale", "0200", "000000")
		isoEngine.setTxnSteps("CtEppSale", "1 6 31 2 48 46 47 3 13 23 49 4 5 45 9 7 11 8")
		isoEngine.setTxnDes("CtEppSale", "3 4 11 22 23 24 25 35 41 42 52 53 55 60 62")
		isoEngine.setTxnDes("CtEppSaleTle", "3 4 11 22 23 24 25 41 42 53 57 60 62 64")
		isoEngine.setTxnDes("CtEppSaleSens", "35 52 55")//sensitive field
		isoEngine.setTxnDes("CtEppSaleRev", "2 3 4 11 14 22 23 24 25 41 42 55 60 62")
		isoEngine.setTxnDes("CtEppSaleRevTle", "3 4 11 22 23 24 25 41 42 57 60 62 64")
		isoEngine.setTxnDes("CtEppSaleRevSens", "2 14 55")//sensitive field

		// Void Sale EPP
		isoEngine.setMtiProcCode("VoidEPP", "0200", "020000")
		isoEngine.setTxnSteps("VoidEPP", "19 6 2 48 46 47 3 23 4 5 45 9 8 11 20")
		isoEngine.setTxnDes("VoidEPP", "2 3 4 11 12 13 14 22 23 24 25 37 41 42 62") //63
		isoEngine.setTxnDes("VoidEPPTle", "3 4 11 12 13 22 23 24 25 37 41 42 57 62 64") //63
		isoEngine.setTxnDes("VoidEPPSens", "2 14")//sensitive field

		//PreAuth Contactless
		isoEngine.setMtiProcCode("PreAuth", "0100", "300000")
		isoEngine.setTxnSteps("PreAuth", "1 6 46 47 2 3 13 23 49 4 5 45 9 43 11 41 14")
		isoEngine.setTxnDes("PreAuth", "3 4 11 22 23 24 25 35 41 42 52 55 62")
		isoEngine.setTxnDes("PreAuthTle", "3 4 11 22 23 24 25 41 42 48 57 62 64")
		isoEngine.setTxnDes("PreAuthSens", "35 52 55")//sensitive field
		isoEngine.setTxnDes("PreAuthRev", "2 3 4 11 14 22 23 24 25 41 42 55 62")
		isoEngine.setTxnDes("PreAuthRevTle", "3 4 11 22 23 24 25 41 42 57 62 64")
		isoEngine.setTxnDes("PreAuthRevSens", "2 14 55")//sensitive field

		//Void PreAuth Contactless
		isoEngine.setMtiProcCode("VoidPreAuth", "0100", "020000")
		isoEngine.setTxnSteps("VoidPreAuth", "19 6 46 2 3 23 4 5 45 9 43 11 42")
		isoEngine.setTxnDes("VoidPreAuth", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 62")
		isoEngine.setTxnDes("VoidPreAuthTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidPreAuthSens", "2 14")//sensitive field

		//PreAuth Contact
		isoEngine.setMtiProcCode("CtPreAuth", "0100", "300000")
		isoEngine.setTxnSteps("CtPreAuth", "1 6 46 47 2 3 13 23 49 4 5 45 9 43 11 41 14")
		isoEngine.setTxnDes("CtPreAuth", "3 4 11 22 23 24 25 35 41 42 52 55 62")
		isoEngine.setTxnDes("CtPreAuthTle", "3 4 11 22 23 24 25 41 42 48 57 62 64")
		isoEngine.setTxnDes("CtPreAuthSens", "35 52 55")//sensitive field
		isoEngine.setTxnDes("CtPreAuthRev", "2 3 4 11 14 22 23 24 25 41 42 55 62")
		isoEngine.setTxnDes("CtPreAuthRevTle", "3 4 11 22 23 24 25 41 42 57 62 64")
		isoEngine.setTxnDes("CtPreAuthRevSens", "2 14 55")//sensitive field

		//Void PreAuth Contact
		isoEngine.setMtiProcCode("VoidCtPreAuth", "0100", "020000")
		isoEngine.setTxnSteps("VoidCtPreAuth", "19 6 46 2 3 23 4 5 45 9 43 11 20")
		isoEngine.setTxnDes("VoidCtPreAuth", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42")
		isoEngine.setTxnDes("VoidCtPreAuthTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 64")
		isoEngine.setTxnDes("VoidCtPreAuthSens", "2 14")//sensitive field

		//PreAuth TcUpload
		isoEngine.setMtiProcCode("PreAuthTcUpload", "0320", "940000")
		isoEngine.setTxnSteps("PreAuthTcUpload", "1 46 30 25 15 2 3 23 4 5 45 9 28")
		isoEngine.setTxnDes("PreAuthTcUpload", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 55 60 62")
		isoEngine.setTxnDes("PreAuthTcUploadTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 60 62 64")
		isoEngine.setTxnDes("PreAuthTcUploadSens", "2 14 55")//sensitive field

		//Sale Complete
		isoEngine.setMtiProcCode("OffSale", "0220", "000000")
		isoEngine.setTxnSteps("OffSale", "19 6 46 47 31 2 3 13 23 49 4 5 45 9 7 11 8 14")
		isoEngine.setTxnDes("OffSale", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 62")
		isoEngine.setTxnDes("OffSaleTle", "3 4 11 12 13 22 24 25 37 38 41 42 57 62 64")
		isoEngine.setTxnDes("OffSaleSens", "2 14")//sensitive field
		isoEngine.setTxnDes("OffSaleRev", "2 3 4 11 12 13 14 22 24 25 37 38 41 42 62")
		isoEngine.setTxnDes("OffSaleRevTle", "3 4 11 12 13 22 24 25 37 38 41 42 57 62 64")
		isoEngine.setTxnDes("OffSaleRevSens", "2 14")//sensitive field

		//Void Sale Complete
		isoEngine.setMtiProcCode("VoidOffSale", "0220", "020000")
		isoEngine.setTxnSteps("VoidOffSale", "19 6 46 2 3 23 4 5 45 9 8 11 20")
		isoEngine.setTxnDes("VoidOffSale", "2 3 4 11 12 13 14 22 23 24 25 37 38 39 41 42 62")
		isoEngine.setTxnDes("VoidOffSaleTle", "3 4 11 12 13 22 23 24 25 37 38 39 41 42 57 62 64")
		isoEngine.setTxnDes("VoidOffSaleSens", "2 14")//sensitive field
	}
}
*/
