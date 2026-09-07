package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.loader.content.Loader
import com.google.gson.Gson
import com.sc.mf919.R
import com.sc.mf919.java.activity.*
import com.sc.mf919.kotlin.database.repo.BnplPayTableRepo
import com.sc.mf919.kotlin.database.repo.PrintReceiptBnplRepo
import utils.HexUtil
import com.sc.mf919.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import data_enum.SalesModel
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import tms.handlers.QrPaymentAckHandler
import tms.handlers.ioupay.IoupayScanEnquiryHandler
import tms.models.QrPaymentAckResponseModel
import tms.models.ioupay.IoupayScanEnquiryResponseModel
import tms.models.ioupay.IoupayScanQrResponseModel
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern


class BnplIoupayViewDetailsActivity : ActivityBase(), LoaderManager.LoaderCallbacks<Any> {
    lateinit var mContext: Context
    private val SUPERSCRIPT_REGEX = "(?<=\\b\\d{0,9999})(st|nd|rd|th)(?=\\b)"
    private val PATTERN: Pattern = Pattern.compile(SUPERSCRIPT_REGEX)
    lateinit var loaderManager: LoaderManager

    var txnType: String? = ""
    var txnAmt: String? = ""
    var txnDt: String? = ""
    var apprCode: String? = ""
    var invNo: String? = ""
    var rrn: String? = ""
    var stan: String? = ""
    var mid: String? = ""
    var tid: String? = ""
    var batchNo: String? = ""
    var appLabel: String? = ""
    var cvm: String? = ""
    var tvr: String? = ""
    var arqc: String? = ""
    var aid: String? = ""
    var cube: CubeActivity? = null
    lateinit var bnplResp: IoupayScanQrResponseModel
    val gson = Gson()
    lateinit var bnplScheduleScroll: ScrollView
    private val ENQUIRYSTATUS = 6000001
    var currRetryAttempt = 1
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * navigationToResultPage in the companion constructs BnplIoupayViewDetailsActivity()
     * directly, so onCreate never runs on that instance and helperLog is unset. Log calls
     * reachable from such a path must go through here instead of touching helperLog.
     */
    private fun logBnpl(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint(BnplIoupayViewDetailsActivity::class.java.simpleName, msg)
        }
    }

    companion object {
        var qrTxnDt = ""
        var qrRefId = ""
        var acqMid = ""
        var acqTid = ""
        var qrRespRefId = "" // remove maybe?
        var qrRespTxnRefNo = ""
        var qrRespHostRefNo = ""
        var qrRespPayBrand = ""
        var qrRespPayBrandDesc = ""
        var respCode = "1100"
        var qrRespAcqCode = ""
        var acqCode = ""
        var respDesc = ""
        var amt: String? = "000000000000"
        var maxRetryAttempt = 0;
        var paymentType = ""
        var tenure = ""
        var tenureDesc = ""

        fun navigationToResultPage(context: Context) {
            BnplIoupayViewDetailsActivity().closeProgressDialog()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
            val txnDt = sdf.format(Date())

            //val cacheSales = ServiceHolder.selectedCacheModel as SalesModel
            //cacheSales.ProductName = qrRespPayBrand
            ServiceHolder.selectedCacheModel?.let {
                val tempModel = ServiceHolder.selectedCacheModel as SalesModel
                tempModel.ProductName = qrRespPayBrand
                //tempModel.AcqMid = Utils.maskString(acqMid, 4)
                //tempModel.AcqTid = Utils.maskString(acqTid, 4)
                ServiceHolder.selectedCacheModel = tempModel
            }

            val intent = Intent(context, TransactionResultBnplActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("txnType", "Sale")
            intent.putExtra("amount", "RM" + Utils.getActualAmount(amt))
            intent.putExtra("txnId", qrRespHostRefNo)
            //intent.putExtra("txnRefId", qrRespRefId)
            intent.putExtra("txnRefId", qrRefId)
            intent.putExtra("txnDt", txnDt)
            intent.putExtra("respCode", respCode)
            intent.putExtra("eWallet", qrRespPayBrand)
            intent.putExtra("acqCode", qrRespAcqCode)
            context.startActivity(intent)
            BnplIoupayViewDetailsActivity().finish()
        }

        private const val TAG = "Print"
        private fun sysPrint(message: String) {
            Utils.debugLogPrint(TAG, message)
        }

        private fun sysPrint(message: String, data: ByteArray, dataOffset: Int, dataLen: Int) {
            Utils.debugLogPrint(TAG, message + HexUtil.bcd2str(data, dataOffset, dataLen))
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_bnpl_ioupay_details)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "BNPL Ioupay Payment Details"
        )

        bnplScheduleScroll = findViewById(R.id.bnpl_schedule_scroll)

        initializeData()
        mContext = this

        val intent = intent
        qrRefId = intent.getStringExtra("qrRefId").toString()
        loaderManager = LoaderManager.getInstance(this)

        val bnplDetailsResp = BnplPayTableRepo.getRowArrayByRefId(applicationContext, qrRefId)

        if (bnplDetailsResp != null) {
            qrRespPayBrand = bnplDetailsResp[3] ?: ""
            qrRespPayBrandDesc = bnplDetailsResp[4] ?: ""
            amt = Utils.zeroPadding(
                bnplDetailsResp[9]!!.replace(".", ""), 12)
            qrRespAcqCode = bnplDetailsResp[15] ?: ""
            tenure = bnplDetailsResp[19] ?: ""
            tenureDesc = bnplDetailsResp[20] ?: ""
            bnplResp = gson.fromJson(bnplDetailsResp[21], IoupayScanQrResponseModel::class.java)

            if (bnplResp != null) {
                maxRetryAttempt = bnplResp.TOTAL_ENQUIRY!!
                qrRespHostRefNo = bnplResp.QR_HOST_REFNO!!
                qrRefId = bnplResp.QR_REFID!!
            }

            (findViewById<View>(R.id.trxId_tr) as TextView).text = qrRespHostRefNo;
            (findViewById<View>(R.id.status_tr) as TextView).text = bnplResp?.RESP_DESC;
            (findViewById<View>(R.id.totalAmt_tr) as TextView).text = "RM " + Utils.getActualAmount(amt);
            (findViewById<View>(R.id.tenure_tr) as TextView).text = tenureDesc
            helperLog.appendLine(helperLogClassName, "BNPL details loaded :: refId=$qrRefId brand=$qrRespPayBrand tenure=$tenureDesc amount=${Utils.getActualAmount(amt)} maxRetry=$maxRetryAttempt")
        }

        renderScheduleList()
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        (findViewById<View>(R.id.cancelBtnImageview) as ImageView).setImageResource(R.drawable.cancel_icon)
        (findViewById<View>(R.id.cancelBtnTextview) as TextView).text = "Cancel"
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                customOnBackPress()
            }
        })
    }

    private fun initializeData() {
        respCode = "1100"
        acqMid = ""
        acqTid = ""
        qrRespRefId = ""
        qrRespPayBrand = ""
        qrRespPayBrandDesc = ""
        qrRespTxnRefNo = ""
        qrRespHostRefNo = ""
    }

    private fun renderScheduleList() {
        cube = CubeActivity()
        cube!!.setCubess_tlv_db(intent.getStringExtra("Cube"))

        if (bnplResp.SCHEDULE_LIST?.isNotEmpty() == true) {
            val mainLinear = LinearLayout(applicationContext)
            val mainLinearParam = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mainLinearParam.setMargins(
                Helper.getInstance().getDpValue(15), Helper.getInstance().getDpValue(10),
                Helper.getInstance().getDpValue(15), Helper.getInstance().getDpValue(10)
            )
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
                    productLV.setPadding(
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10),
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(5)
                    )
                    productLV.layoutParams = productLVParam


                    val productLV2 = LinearLayout(applicationContext)
                    val productLV2Param = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    productLV2.setPadding(
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(0),
                        Helper.getInstance().getDpValue(10), Helper.getInstance().getDpValue(10)
                    )
                    productLV2.gravity = Gravity.LEFT
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
                        val itemTitle = SpannableString("1st Payment (Today)")
                        val matcher: Matcher = PATTERN.matcher(itemTitle)

                        while (matcher.find()) {
                            val start = matcher.start()
                            val end = matcher.end()
                            itemTitle.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            itemTitle.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        productTV1.text = itemTitle
                    } else {
                        var currIndex = i+1;
                        val suffix = Helper.getInstance().getNumberSuffix(currIndex);
                        val itemTitle = SpannableString(currIndex.toString() + suffix + " Payment ")
                        val matcher: Matcher = PATTERN.matcher(itemTitle)

                        while (matcher.find()) {
                            val start = matcher.start()
                            val end = matcher.end()
                            itemTitle.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            itemTitle.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                    productTV1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20F)
                    productTV1.setTextColor(Color.parseColor("#000000"))
                    productTV1.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

                    productTV2.setTypeface(productTV2.typeface, Typeface.BOLD)
                    productTV2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20F)
                    productTV2.setTextColor(Color.parseColor("#000000"))
                    productTV2.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

                    productTV3.setTypeface(productTV3.typeface, Typeface.NORMAL)
                    productTV3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17F)
                    productTV3.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE

                    productLV.addView(productTV1)
                    productLV.addView(productTV2)

                    productLV2.addView(productTV3)

                    productMainLV.setBackgroundResource(R.drawable.divider);

                    productMainLV.addView(productLV)
                    productMainLV.addView(productLV2)
                    mainLinear.addView(productMainLV)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                helperLog.appendLine(helperLogClassName, "Render BNPL schedule (Exception) -> $e")
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
            bnplScheduleScroll.addView(mainLinear)
        }
    }

    fun print_bnpl_cancel(view: View?) {
        helperLog.appendLine(helperLogClassName, "User Cancel :: BNPL payment abandoned, refId=$qrRefId marked FAILED")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        BnplPayTableRepo.updateByRefId(applicationContext, "status", "FAILED", qrRefId)
        return navigationToResultPage(mContext)
    }

    fun enquiry_status(view: View?) {
        helperLog.appendLine(helperLogClassName, "Selected :: Retry enquiry attempt $currRetryAttempt/$maxRetryAttempt [BNPL DETAILS]")
        try {
            loaderManager.initLoader(ENQUIRYSTATUS, null, this@BnplIoupayViewDetailsActivity)
        } catch (e: RemoteException) {
            e.printStackTrace()
            helperLog.appendLine(helperLogClassName, "Enquiry loader start (Exception) -> $e")
            helperLog.logToFile(EnumLogFileName.TerminaLogException)
        }
    }

    fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "User Cancel :: leaving BNPL details, refId=$qrRefId")
        helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> HomeScreen")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val newIntent = HelperCommon.getHomeScreenIntent(applicationContext, dbModelTerminalConfig)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(newIntent)
        finish()
    }

    override fun onDestroy() {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "BnplIoupayViewDetails OnDestroy :: BNPL details screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        loaderManager.destroyLoader(ENQUIRYSTATUS)
        super.onDestroy()
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Any> {
        if (id == ENQUIRYSTATUS) {
            startProgressDialog(mContext, "Enquiring BNPL Txn Status", "Loading...")
            return BnplIoupayEnquiryLoader(mContext)
        }
        return Loader(mContext)
    }

    override fun onLoadFinished(loader: Loader<Any>, data: Any?) {
        val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
        if (merchantInfo == null || currRetryAttempt >= maxRetryAttempt) {
            helperLog.appendLine(helperLogClassName, "BNPL payment DENIED :: refId=$qrRefId marked FAILED, attempts=$currRetryAttempt/$maxRetryAttempt merchantInfo=${merchantInfo != null} respCode=$respCode")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            BnplPayTableRepo.updateByRefId(applicationContext, "status", "FAILED", qrRefId)
            return navigationToResultPage(mContext)
        }

        if (data == false) {
            currRetryAttempt++;
            (findViewById<View>(R.id.retryBtnView) as TextView).text = "Retry ($currRetryAttempt / 5)";
            helperLog.appendLine(helperLogClassName, "BNPL enquiry not settled :: retry available $currRetryAttempt/$maxRetryAttempt respCode=$respCode")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            closeProgressDialog()
            loaderManager.destroyLoader(ENQUIRYSTATUS)
            return
        } else {
            helperLog.appendLine(helperLogClassName, "BNPL payment APPROVED :: refId=$qrRefId respCode=$respCode hostRefNo=$qrRespHostRefNo brand=$qrRespPayBrand")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            BnplPayTableRepo.updateByRefId(applicationContext, "status", "SUCCESSFUL", qrRefId)

            PrintReceiptBnplRepo.insertToDb(
                applicationContext,
                "Sale",
                qrRespPayBrand,
                qrRespPayBrandDesc,
                qrTxnDt,
                acqMid,
                acqTid,
                amt,
                qrRefId,
                qrRespHostRefNo,
                qrRespTxnRefNo,
                respCode,
                "SUCCESSFUL",
                "",
                "",
                qrRespAcqCode,
                "",
                tenure,
                tenureDesc,
                Gson().toJson(data).toString()
            )

            helperLog.appendLine(helperLogClassName, "Validation passed :: navigate -> result page")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return navigationToResultPage(mContext)
        }
    }

    override fun onLoaderReset(loader: Loader<Any>) {
        logBnpl("BNPL enquiry loader reset")
        closeProgressDialog()
    }

    class BnplIoupayEnquiryLoader(context: Context) : AsyncTaskLoader<Any>(context) {
        lateinit var log: HelperLog
        val logClassName = BnplIoupayViewDetailsActivity::class.java.name

        override fun onStartLoading() {
            log = HelperLog(
                HelperCommon.getSession(),
                TmsHelper.checkIsConnectedWifi(context),
                Utils.getIPAddress(),
                "BNPL Scan Activity",
                BnplIoupayViewDetailsActivity::class.java.simpleName,
                BnplIoupayViewDetailsActivity::class.java.name,
            )
            forceLoad()
        }

        override fun loadInBackground(): Any {
            try {
                val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
                if (merchantInfo == null) {
                    navigationToResultPage(context)
                }

                val res : Any = checkPayment();
                if (res == false) {
                    //display error
                    log.appendLine(logClassName, "REJECT :: BNPL enquiry failed, respCode=$respCode")
                    log.logToFile(EnumLogFileName.TerminaLog)
                    return false
                }

                val pAck = paymentAck()
                if (pAck == false) {
                    //display error
                    log.appendLine(logClassName, "REJECT :: BNPL payment ack failed, respCode=$respCode")
                    log.logToFile(EnumLogFileName.TerminaLog)
                    return false
                }

                log.appendLine(logClassName, "BNPL enquiry and ack finished :: respCode=$respCode")
                log.logToFile(EnumLogFileName.TerminaLog)
                return res
            } catch (e: Exception) {
                e.printStackTrace()
                log.appendLine(logClassName, "Failed Enquiry BNPL payment")
                log.logToFile(EnumLogFileName.TerminaLogException)
                return false
            }
        }

        private fun checkPayment(): Any {
            log.appendLine(logClassName, "Processing BNPL Payment - Enquiry Payment")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
            val timeStamp = sdf.format(Date())

            try {
                val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
                val ioupayScanEnquiryHandler = IoupayScanEnquiryHandler(environmentManager)
                val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
                if (merchantInfo == null) {
                    navigationToResultPage(context)
                }

                val resp = ioupayScanEnquiryHandler.invoke(
                    log,
                    merchantInfo?.QrMid ?: "", //QR_MID
                    merchantInfo?.QrTid ?: "", //QR_TID
                    qrRefId, //QR_REFID
                )
                log.appendLine(logClassName, "IoupayScanEnquiryHandler Response -> ", resp.toString())


                /*
                if (resp == null) {
                    HelperLog.AppendLine(sbLog, "Response: ", "BCBase:Error: Body Message = NULL")
                    HelperLog.Log(sbLog, HelperLogFileName.TerminaLog)
                    return false
                }
                if (resp.RESP_CODE.toString() != "0000") {
                    HelperLog.AppendLine(sbLog, "Response: ", "ERR:Invalid RESP_CODE")
                    HelperLog.Log(sbLog, HelperLogFileName.TerminaLog)
                    return false
                }
                * */

                /*
                qrRespRefId = resp.QR_REFID.toString()
                if (qrRespRefId != qrRefId) {
                    HelperLog.AppendLine(sbLog, "Response: ", "Invalid QR_REFID")
                    HelperLog.Log(sbLog, HelperLogFileName.TerminaLog)
                    return false
                }
                * */

                qrTxnDt = timeStamp
                respCode = resp.RESP_CODE ?: ""
                qrRespTxnRefNo = resp.QR_TXN_REFNO ?: ""
                qrRespHostRefNo = resp.QR_HOST_REFNO ?: ""
                respDesc = resp.RESP_DESC ?: ""
                qrRespPayBrand = resp.QR_PAY_BRAND ?: ""
                qrRespPayBrandDesc = resp.QR_PAY_BRAND_DESC ?: ""
                acqMid = merchantInfo?.QrMid ?: ""
                acqTid = merchantInfo?.QrTid ?: ""

                if (resp.RESP_CODE != "0000") {
                    log.appendLine(logClassName, "REJECT :: BNPL enquiry host respCode=$respCode desc=$respDesc")
                    return false
                }

                return resp
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    val errorResponse = e.message?.split("Response: ")
                    if (!errorResponse?.get(1).isNullOrEmpty()) {
                        val modelData = Gson().fromJson(
                            errorResponse?.get(1),
                            IoupayScanEnquiryResponseModel::class.java
                        )
                        respCode = modelData.RESP_CODE ?: "1100"
                        qrRespRefId = modelData.QR_REFID ?: ""
                        qrRespPayBrand = modelData.QR_PAY_BRAND ?: ""
                        qrRespTxnRefNo = modelData.QR_TXN_REFNO ?: ""
                        qrRespHostRefNo = modelData.QR_HOST_REFNO ?: ""
                    }
                } catch (ex: Exception) {
                    log.appendLine(logClassName, "Exception Get Error Resp -> ", ex.toString())
                }

                log.appendLine(logClassName, "IoupayScanEnquiryHandler (Exception) -> ", e.toString())
                log.logToFile(EnumLogFileName.TerminaLogException)
            }
            return false
        }

        private fun paymentAck(): Any {
            log.appendLine(logClassName, "Processing BNPL Scan - Payment Ack")

            try {
                val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
                val qrPaymentAckHandler = QrPaymentAckHandler(environmentManager)
                val merchantInfo: DbModelMerchantConfig? = ServiceHolder.getMerchantInfo()
                if (merchantInfo == null) {
                    navigationToResultPage(context)
                }

                val resp = qrPaymentAckHandler.invoke(
                    log,
                    merchantInfo?.QrMid ?: "", //QR_MID
                    merchantInfo?.QrTid ?: "", //QR_TID
                    qrRespTxnRefNo, //QR_TXN_REFNO
                    qrRefId //QR_REFID

                )
                log.appendLine(logClassName, "QrPaymentAckHandler Response -> ", resp.toString())


                /*
                 if (resp == null) {
                     HelperLog.AppendLine(sbLog, "Response: ", "BCBase:Error: Body Message = NULL")
                     HelperLog.Log(sbLog, HelperLogFileName.TerminaLog)
                     return false
                 }
                 if (resp.RESP_CODE.toString() != "0000") {
                     HelperLog.AppendLine(sbLog, "Response: ", "ERR:Invalid RESP_CODE")
                     HelperLog.Log(sbLog, HelperLogFileName.TerminaLog)
                     return false
                 }
                * */
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    val errorResponse = e.message?.split("Response: ")
                    if (!errorResponse?.get(1).isNullOrEmpty()) {
                        val modelData = Gson().fromJson(
                            errorResponse?.get(1),
                            QrPaymentAckResponseModel::class.java
                        )
                        respCode = modelData.RESP_CODE ?: "1100"
                        qrRespRefId = modelData.QR_REFID ?: ""
                        qrRespPayBrand = modelData.QR_PAY_BRAND ?: ""
                        qrRespTxnRefNo = modelData.QR_TXN_REFNO ?: ""
                        qrRespHostRefNo = modelData.QR_HOST_REFNO ?: ""
                    }
                } catch (ex: Exception) {
                    log.appendLine(logClassName, "Exception Get Error Resp -> ", ex.toString())

                }

                log.appendLine(logClassName, "QrPaymentAckHandler (Exception) -> ", e.toString())
                log.logToFile(EnumLogFileName.TerminaLogException)
            }
            return false
        }
    }
}