package com.sc.mf919pro.kotlin.fragment

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.lifecycle.lifecycleScope
import com.sc.mf919pro.databinding.FragmentNfcReceiptBinding
import com.sc.mf919pro.databinding.FragmentReceiptQrBinding
import com.sc.mf919pro.java.activity.Utils
import com.sc.mf919pro.kotlin.data_enum.variables.TransData
import com.sc.mf919pro.kotlin.database.model.DbModelTerminalConfig
import com.sc.mf919pro.kotlin.helper_common.Helper
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TTSManager
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperCommon.Db.Companion.setDebouncedOnClickListener
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.text.ifEmpty

class FragmentReceiptQr : BaseFragment() {
    lateinit var helperLog: HelperLog
    lateinit var helperLogClassName: String

    private var isEReceipt = true
    private var isShowNfcQr = false
    private var listener: OnFragmentInteractionListener? = null

    lateinit var details: Array<String>

    private var timeSelected : Int = ServiceHolder.ackCountDownSecond
    private var timeCountDown: CountDownTimer? = null
    private var timeProgress = 0
    private var mediaPlayer: MediaPlayer? = null

    private var _binding: FragmentNfcReceiptBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNfcReceiptBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        timeCountDown?.cancel()
        timeCountDown = null
        MfHelper.closeNfcUrlInterface()
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized && this::helperLogClassName.isInitialized) {
            helperLog.appendLine(helperLogClassName, "FragmentReceiptQr OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    interface OnFragmentInteractionListener {
        fun fragmentReceiptQrBackAction()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val parent = parentFragment
        if (parent is OnFragmentInteractionListener) {
            listener = parent
        } else {
            throw RuntimeException("$parent must implement OnFragmentInteractionListener")
        }
    }
    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            "FragmentReceiptQr",
            helperLogClassName,
            helperLogClassName
        )
        // Custom back press handling
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { listener?.fragmentReceiptQrBackAction() }
            }
        )

        details = transData.generateReceiptInfoQr()
        helperLog.appendLine(helperLogClassName, "Receipt Information :: ${details.joinToString()}")
        val dbModelTerminalConfig = ServiceHolder.getTerminalConfig()
        val buttonGroupLeft = binding.buttonGroupLeft
        val buttonGroupRight = binding.buttonGroupRight
        if(DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "UNATTENDED_MODE")) {
            helperLog.appendLine(helperLogClassName, "UNATTENDED_MODE hide Left and Right Button")
            buttonGroupLeft.visibility = View.GONE
            buttonGroupRight.visibility = View.GONE
        } else {
            helperLog.appendLine(helperLogClassName, "Normal Mode")
            buttonGroupLeft.background.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.LTGRAY, BlendModeCompat.SRC_ATOP)
            buttonGroupLeft.setDebouncedOnClickListener {
                helperLog.appendLine(helperLogClassName, "E-Receipt QR Requested...")
                isShowNfcQr = true
                eReceiptByQR()
            }
            buttonGroupRight.background.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.LTGRAY, BlendModeCompat.SRC_ATOP)
            buttonGroupRight.setDebouncedOnClickListener {
                helperLog.appendLine(helperLogClassName, "Print Receipt Requested...")
                isEReceipt = false
                val bundleValue = Bundle().apply {
                    putString("acqCode", transData.acqCode)
                    putBoolean("isTpa", transData.isTpaAccount)
                    putBoolean("isUnionPayTxn", transData.isUPIQR)
                }
                printReceiptQr(null, details,"CUSTOMER", bundleValue)
                viewLifecycleOwner.lifecycleScope.launch {
                    MfHelper.closeNfcUrlInterface()
                    eReceiptByNFC(false)
                    if(isShowNfcQr) {
                        eReceiptByQR()
                    }
                    helperLog.logToFile(EnumLogFileName.TerminaLog)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (transData.salesType == 0 || DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "ReceiptPrint")) {
                helperLog.appendLine(helperLogClassName, "Sales Type :: ${transData.salesType}")
                helperLog.appendLine(helperLogClassName, "ReceiptPrint :: ${DbModelTerminalConfig.getBooleanValue(dbModelTerminalConfig, "ReceiptPrint")}")
                helperLog.appendLine(helperLogClassName, "Auto Print Receipt...")
                val bundleValue = Bundle().apply {
                    putString("acqCode", transData.acqCode)
                    putBoolean("isTpa", transData.isTpaAccount)
                    putBoolean("isUnionPayTxn", transData.isUPIQR)
                }
                printReceiptQr(null, details, "MERCHANT", bundleValue)
            }
            if(transData.salesType != 0) {
                HelperCommon.formatTTSAmount(transData.amount, TTSManager)
            } else {
                playCustomSoundFromAssets()
            }

            startTimer(0)
            eReceiptByNFC(true)
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    suspend fun eReceiptByNFC(runDelay: Boolean) = withContext(Dispatchers.IO) {
        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
        val aesData = MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
            transData.mid,
            transData.tid,
            "",
            "",
            "",
            transData.approvalCode,
            transData.qrRef,
            isEReceipt,
            "NFC",
            ServiceHolder.getTerminalSerialNumber()
        )
        helperLog.appendLine(helperLogClassName, "E-Receipt By NFC :: ${environmentManager.get(EnvironmentVariables::nfcUrl)}$aesData")
        MfHelper.openNfcUrlInterface(4, "${environmentManager.get(EnvironmentVariables::nfcUrl)}$aesData")
        if(runDelay) {
            delay(2 * 1000)
        }
        if(isEReceipt) {
            MfHelper.showAuxLcdMsg(
                Color.BLACK,
                "Tap For \nE-Receipt",
                34,
                Color.WHITE
            )
        }
    }

    fun eReceiptByQR() {
        val environmentManager = EnvironmentManager(Helper.getInstance().getPrefs()!!)
        val qrAesData = MfHelper.encryptAsAesUrl(environmentManager.get(EnvironmentVariables::nfcAesKey),
            transData.mid,
            transData.tid,
            "",
            "",
            "",
            transData.approvalCode,
            transData.qrRef,
            isEReceipt,
            "QR",
            ServiceHolder.getTerminalSerialNumber()
        )
        helperLog.appendLine(helperLogClassName, "E-Receipt By QR :: ${environmentManager.get(EnvironmentVariables::nfcUrl)}$qrAesData")
        MfHelper.showAuxLcdQrCode("${environmentManager.get(EnvironmentVariables::nfcUrl)}$qrAesData", null)
    }

    private fun resetTime() {
        if (timeCountDown!=null) {
            timeCountDown!!.cancel()
            timeProgress=0
            timeSelected=0
            timeCountDown=null
        }
    }

    private fun startTimer(pauseOffSetL: Long) {
        timeCountDown = object :CountDownTimer((timeSelected*1000).toLong() - pauseOffSetL*1000, 1000) {
            override fun onTick(p0: Long) {
                timeProgress++
            }

            override fun onFinish() {
                resetTime()
                listener?.fragmentReceiptQrBackAction()
            }
        }.start()
    }

    private fun playCustomSoundFromAssets() {
        try {
            val assetManager: AssetManager = requireContext().assets
            val fileName = "successnfc.mp3"
            val assetFileDescriptor: AssetFileDescriptor = assetManager.openFd("sound/$fileName")
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(assetFileDescriptor.fileDescriptor, assetFileDescriptor.startOffset, assetFileDescriptor.length)
            mediaPlayer?.prepare()
            mediaPlayer?.start()

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}