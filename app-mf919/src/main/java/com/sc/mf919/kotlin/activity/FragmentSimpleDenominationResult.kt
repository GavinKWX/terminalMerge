package com.sc.mf919.kotlin.activity

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.graphics.drawable.Animatable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sc.mf919.R
import constants.TerminalConstants
import com.sc.mf919.java.activity.Utils
import com.sc.mf919.kotlin.data_enum.variables.TransData
import com.sc.mf919.kotlin.helper_common.Helper
import com.sc.mf919.kotlin.helper_common.MfHelper
import com.sc.mf919.kotlin.helper_common.ServiceHolder
import com.sc.mf919.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class FragmentSimpleDenominationResult: Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0

//    lateinit var linearDescription: LinearLayout
//    lateinit var textViewProduct: TextView
    lateinit var resultAnim: ImageView
    private var mediaPlayer: MediaPlayer? = null
    lateinit var helperLogClassName: String
    lateinit var helperLog: HelperLog

    /**
     * A Fragment method can run before onViewCreated has built helperLog (and after
     * onDestroyView), so every log call goes through here rather than touching the lateinit.
     */
    private fun logResult(msg: String) {
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, msg)
        } else {
            Utils.debugLogPrint(this::class.java.simpleName, msg)
        }
    }

    interface OnFragmentInteractionListener {
        fun fragmentDenominationBackAction()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnFragmentInteractionListener")
        }
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
        timeCountDown?.let {
            it.cancel()
            timeCountDown = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_simple_denomination_result, container, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentSimpleDenominationResult OnDestroy :: result view ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
        timeCountDown?.cancel()
        timeCountDown = null
        MfHelper.closeNfcUrlInterface()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.java.simpleName
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext().applicationContext),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Simple Denomination Result"
        )

        view.findViewById<LinearLayout>(R.id.buttonGroupLeft).setOnClickListener {
            logResult("User Cancel :: back pressed on denomination result")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            listener?.fragmentDenominationBackAction()
        }

        resultAnim = view.findViewById(R.id.imageViewStatus)
        val transactionResult = TransData.transResult
        if(transactionResult == TerminalConstants.iso.err.txnApproved || TransData.qrRespCode == "0000"){
            view.findViewById<TextView>(R.id.tvTitle).text = "Transaction Success"
            view.findViewById<TextView>(R.id.tvTitle).setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
            resultAnim.background = AppCompatResources.getDrawable(requireContext(), R.drawable.avd_success)

            helperLog.appendLine(helperLogClassName, "Denomination result displayed :: APPROVED invoice=${TransData.invoiceNo} rrn=${TransData.rrn}")
            playResult(true)
            playCustomSoundFromAssets(true)
        } else {
            helperLog.appendLine(helperLogClassName, "Denomination result displayed :: FAILED invoice=${TransData.invoiceNo} respCode=${TransData.qrRespCode}")
            playResult(false)
            playCustomSoundFromAssets(false)
        }

        //TODO Dynamic Layout For Small Terminal
        if (MfHelper.isSmallTerminal()) {
            val bglView = view.findViewById<LinearLayout>(R.id.buttonGroupLeft)
            bglView.setPadding(0,0,0,0)
            val bglParams = bglView.getLayoutParams() as ConstraintLayout.LayoutParams
            bglParams.topMargin = 0
            bglView.setLayoutParams(bglParams)

            val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        }
        //TODO Dynamic Layout For Small Terminal


//        linearDescription = view.findViewById(R.id.linearDescriptionList)
//        textViewProduct = view.findViewById(R.id.tvProduct)
//        TransData.denominationProduct?.let {
//            var amountDisplay = it.Amount
//            if(amountDisplay.contains(".")) {
//                val tempList = amountDisplay.split(".")
//                amountDisplay = tempList.first()
//            }
//            textViewProduct.text = "RM$amountDisplay = ${it.Desc}"
//        }
        startTimer(0)
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        if(transactionResult == TerminalConstants.iso.err.txnApproved || TransData.qrRespCode == "0000"){
            viewLifecycleOwner.lifecycleScope.launch {
                openNfc()
            }
        }
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            timeCountDown=null

            val timeLeftTv = view?.findViewById<TextView>(R.id.buttonGroupLeftTv)
            timeLeftTv?.let{
                timeLeftTv.text = "CANCEL (0)"
            }
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :
            CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
                val timeLeftTv = view?.findViewById<TextView >(R.id.buttonGroupLeftTv)
                timeLeftTv?.let {
                    timeLeftTv.text = "BACK (${timeSelected - timeProgress})"
                }
            }
            override fun onFinish() {
                logResult("Denomination result TIMEOUT :: auto-returning to home")
                if (this@FragmentSimpleDenominationResult::helperLog.isInitialized) {
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                }
                resetTime()
                listener?.fragmentDenominationBackAction()
            }
        }.start()
    }

    fun playResult(success: Boolean) {
        val drawableRes = if (success) {
            R.drawable.avd_success
        } else {
            R.drawable.avd_failed
        }

        val drawable = AppCompatResources.getDrawable(requireContext(), drawableRes)
        resultAnim.setImageDrawable(drawable)

        if (drawable is Animatable) {
            drawable.start()
        }
    }

    private fun playCustomSoundFromAssets(success: Boolean) {
        try {
            val assetManager: AssetManager = requireContext().assets
            val fileName = if (success) {
                "successnfc.mp3"
            } else {
                "failnfc.mp3"
            }
            val assetFileDescriptor: AssetFileDescriptor = assetManager.openFd("sound/$fileName")
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(assetFileDescriptor.fileDescriptor, assetFileDescriptor.startOffset, assetFileDescriptor.length)
            mediaPlayer?.prepare()
            mediaPlayer?.start()

        } catch (e: IOException) {
            e.printStackTrace()
            logResult("Result sound playback failed -> ${e.message}")
            if (this::helperLog.isInitialized) {
                helperLog.logToFile(EnumLogFileName.TerminaLogException)
            }
        }
    }

    suspend fun openNfc() = withContext(Dispatchers.IO) {
        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
        val aesData = if (TransData.qrRef.isEmpty()){
            MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
                TransData.mid,
                TransData.tid,
                TransData.batchNo,
                TransData.invoiceNo,
                TransData.prevRRN.ifEmpty {
                    TransData.rrn
                },
                TransData.prevApprovalCode.ifEmpty {
                    TransData.approvalCode
                },
                "",
                //isEReceipt,
                true,
                "NFC",
                ServiceHolder.getTerminalSerialNumber()
            )
        } else {
            MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
                TransData.mid,
                TransData.tid,
                "",
                "",
                "",
                TransData.approvalCode,
                TransData.qrRef,
                //isEReceipt,
                true,
                "NFC",
                ServiceHolder.getTerminalSerialNumber()
            )
        }

        logResult("Opening NFC e-receipt interface")
        MfHelper.openNfcUrlInterface(4, "${environmentManager.get(EnvironmentVariables::nfcUrl)}$aesData")
        if (this@FragmentSimpleDenominationResult::helperLog.isInitialized) {
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }
}