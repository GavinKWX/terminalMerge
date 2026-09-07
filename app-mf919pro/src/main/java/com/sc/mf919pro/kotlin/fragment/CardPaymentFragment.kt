package com.sc.mf919pro.kotlin.fragment

import mdb.MdbController

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.library.terminal.Utility
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentCardpaymentBinding
import com.sc.mf919pro.java.activity.Global
import com.sc.mf919pro.java.activity.Utils
import utils.HexUtil
import com.sc.mf919pro.kotlin.data_enum.ProductCatSelectionDataEnum
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.helper_common.AppBus
import com.sc.mf919pro.kotlin.helper_common.MfHelper
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import com.sc.mf919pro.kotlin.helper_common.TTSManager
import com.sc.mf919pro.kotlin.helper_common.TmsHelper
import helpers.StorageGuard
import com.sc.mf919pro.kotlin.helper_common.UiEvent
import enums.EnumLogFileName
import helpers.HelperCommon
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CardPaymentFragment : EmvFragment() {
    private lateinit var helperLog: HelperLog
    private lateinit var helperLogClassName: String
    private var posReference: String? = null
    private var orderingItem: String? = null
    private var orderingItemImage: String? = null
    var cashOutAmount: Long = 0

    var paymentJob: Job? = null

    private var _binding: FragmentCardpaymentBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCardpaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        paymentJob?.cancel()

        // Final boundary for this screen: the card-search / payment flow can be torn down at any
        // point (user back-press, cancelled job) and whatever is still buffered belongs on disk.
        // helperLog is built in onViewCreated, but guard anyway -- onDestroyView can run after an
        // early teardown where it never got there.
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(helperLogClassName, "CardPayment OnDestroyView :: card payment screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        helperLogClassName = this::class.simpleName.toString()
        helperLog = HelperLog(
            HelperCommon.getSession(),
            TmsHelper.checkIsConnectedWifi(requireContext()),
            Utils.getIPAddress(),
            helperLogClassName,
            helperLogClassName,
            "Card Payment Detection"
        )
        helperLog.appendLine(helperLogClassName, "Initialize CardPayment Fragment")
        binding.appToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
            setNavigationOnClickListener {  customOnBackPress() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { customOnBackPress() }
            }
        )

        transData.reset()
        posReference = arguments?.getString("posReference")
        orderingItem = arguments?.getString("orderingItem")
        orderingItemImage = arguments?.getString("orderingItemImage")
        println("orderingItem :: $orderingItem")
        println("orderingItemImage :: $orderingItemImage")
        ServiceHolder.saleModelCache?.let {
            cashOutAmount = it.CashOutAmount
            binding.textViewAmount.text = Utils.getActualAmount(it.TransAmount.toString())
        }
        //TODO REVAMP

        // Primary gate: refuse BEFORE the reader is armed. Blocking later (inside the ISO
        // layer) works, but by then the EMV kernel is mid-transaction and has to be unwound; here
        // the card has not even been presented, so nothing needs undoing and the customer is not
        // asked to tap for a sale that cannot be recorded.
        if (!StorageGuard.canTransact(requireContext())) {
            StorageGuard.logBlocked(requireContext(), "CardPaymentFragment")
            helperLog.appendLine(helperLogClassName, "BLOCKED :: insufficient storage", StorageGuard.describe(requireContext()))
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            transData.transResult = Global.iso.err.txnNotAllowed
            transData.respCode = StorageGuard.RESP_CODE_HEX
            navigateSafe(R.id.action_cardPayment_to_transactionResult)
            return
        }

        paymentJob = lifecycleScope.launch {
            helperLog.appendLine(helperLogClassName, "Start Search Card Coroutine")
            searchCardCoroutines()
        }

        showToast("Search card, please insert or wave", Toast.LENGTH_SHORT)
        viewLifecycleOwner.lifecycleScope.launch {
            showAuxWaveLogo()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A force-end raised while this screen was stopped would have been missed by the
                // collector below -- the flag outlives the event, so settle it once on (re)start.
                if (MdbController.mdbVendingForceEnd) onMdbVendingForceEnd()
                AppBus.uiEvents.collect { event ->
                    when(event) {
                        is UiEvent.EndPaymentSession -> {
                            customOnBackPress()
                        }
                        // The VMC aborted the vend while we are still waiting for a card
                        // (VEND CANCEL / RESET / reader disable). Event-driven rather than polled:
                        // see onMdbVendingForceEnd.
                        is UiEvent.MdbVendingForceEnd -> {
                            onMdbVendingForceEnd()
                        }
                        else -> { /* not required */ }
                    }
                }
            }
        }
        helperLog.logToFile(EnumLogFileName.TerminaLog)
    }

    private suspend fun searchCardCoroutines() = withContext(Dispatchers.IO) {
        helperLog.appendLine(helperLogClassName, "Searching Card Set Transaction Data")
        transData.payMethod = Global.paymentMethod.Non
        withContext(Dispatchers.Default) {
            MfHelper.closeNfcUrlInterface()
        }
        TTSManager.speak("Please Tap Your Card")
        val merchantConfig = ServiceHolder.getMerchantInfo()
        transData.tpaMid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScMid")
        transData.tpaTid = DbModelMerchantConfig.getSafeValue(merchantConfig, "ScTid")
        // Gavin Predefined some data
        posReference?.let {
            helperLog.appendLine(helperLogClassName, "Add Pos Reference :: ", it)
            transData.posReference = it
        }
        //TODO FOODLINK INTEGRATION
        // Consume-once: the cache is only ever populated by a verified PhoneNumberFragment hop,
        // and clearing it here stops it bleeding into a later sale that skipped that hop
        // (sale completion, MOTO, cash out, EPP all reach this fragment directly).
        transData.correlationRef = ServiceHolder.foodLinkCorrelationRef
        transData.additionalInfo = ServiceHolder.foodLinkAdditionalInfo
        ServiceHolder.clearFoodLinkCache()
        helperLog.appendLine(helperLogClassName, "Add correlationRef :: ", transData.correlationRef)
        helperLog.appendLine(helperLogClassName, "Add additionalInfo :: ", transData.additionalInfo)
        //TODO FOODLINK INTEGRATION
        orderingItem?.let {
            helperLog.appendLine(helperLogClassName, "Add Ordering Item :: ", it)
            transData.orderingItem = it
        }
        orderingItemImage?.let {
            helperLog.appendLine(helperLogClassName, "Add Ordering Item Image :: ", it)
            transData.orderingItemImage = it
        }

        ServiceHolder.saleModelCache?.let {
            transData.salesType = it.SalesType
            transData.amount = it.TransAmount
            transData.amountString = Utils.getActualAmount(it.TransAmount.toString())
            transData.acqCode = it.AcqCode ?: ""
            transData.mid = it.AcqMid ?: Utils.paddingWith("", "0", 12, true)
            transData.tid = it.AcqTid ?: Utils.paddingWith("", "0", 8, true)
            transData.product = it.Product ?: ""
            transData.productName = it.ProductName ?: ""
            transData.productCode = it.EppProductCode ?: ""
            transData.eppTenure = it.EppTenure ?: ""
            transData.eppTenureCode = it.EppTenureCode ?: ""
            transData.ksn = it.Ksn ?: ""
            transData.pinKsn = it.PinKsn ?: ""
            transData.isTpaAccount = it.IsTpaAccount?.lowercase() == "true"
        }

        when (transData.salesType){
            8 -> transData.txnTypeLabel = "Pre Authorization"
            ProductCatSelectionDataEnum.CASH_OUT.data.SalesType -> transData.txnTypeLabel = "Cash Out"
            ProductCatSelectionDataEnum.EPP.data.SalesType -> transData.txnTypeLabel = "Instalment Sale"
            else -> transData.txnTypeLabel = "Sale"
        }

        HexUtil.hexStringToByte(Utils.zeroPadding(transData.amount.toString(), 12)).copyInto(transData.amountAuth)
        helperLog.appendLine(helperLogClassName, "Transaction Amount :: ${transData.amount}")
        transData.cashOutAmount = cashOutAmount
        HexUtil.hexStringToByte(Utils.zeroPadding(cashOutAmount.toString(), 12)).copyInto(transData.cashOutAmountAuth)
        helperLog.appendLine(helperLogClassName, "CashOut Amount :: $cashOutAmount")

        helperLog.appendLine(helperLogClassName, "Start Search Card")
        startEMV(requireContext(), transData.amountString, cashOutAmount, false, helperLog)
        // Parks this coroutine until the EMV flow ends. endEMV() clears isNotEnd, so both a normal
        // card read and onMdbVendingForceEnd() release it.
        //
        // MF919 polls `mdbVending && mdbVendingForceEnd` in here because an Activity has no
        // callback into this loop. Pro does have one -- AppBus -- so the abort is handled as an
        // event instead (see onMdbVendingForceEnd). That is not just tidier: the poll wakes a
        // thread twice a second for the whole card-search window to read two volatile flags, and
        // it depends on both being true at the instant it looks. They are not, because VEND CANCEL
        // raises mdbVendingForceEnd and then answers the vend microseconds later -- and any future
        // change to when mdbVending is cleared silently disarms the abort again, which is exactly
        // how a 42-second window of soliciting a card for an already-cancelled vend was introduced
        // and measured on the SR800 on 2026-09-07.
        while (isNotEnd){
            Thread.sleep(500L)
        }
        helperLog.appendLine(helperLogClassName, "Search Card End")
        helperLog.logToFile(EnumLogFileName.TerminaLog)

        // After finishing background task, switch to Main thread for UI updates:
        withContext(Dispatchers.Main) {
            navigateSafe(R.id.action_cardPayment_to_transactionResult)
        }
    }

    fun showAuxWaveLogo() {
        val bitmap = BitmapFactory.decodeResource(requireContext().resources, R.mipmap.aux_wave)
        MfHelper.showAuxLcdImg(bitmap)
    }

    /**
     * The VMC aborted the vend while this screen was waiting for a card.
     *
     * The VMC has already had its answer (VEND DENIED, or a RESET), so this must NOT send another
     * one -- clearing mdbVending first is what stops the teardown path in customOnBackPress from
     * doing so, the same guard GenerateQrFragment.onMdbVendingForceEnd uses.
     *
     * endEMV() clears isNotEnd, which releases searchCardCoroutines' wait and lets it navigate to
     * the result screen with SHC005.
     */
    private fun onMdbVendingForceEnd() {
        if (!isNotEnd) return
        helperLog.appendLine(helperLogClassName, "Vend force-end :: VMC aborted while searching for card")
        helperLog.logToFile(EnumLogFileName.TerminaLog)
        MdbController.mdbVending = false
        MdbController.mdbVendingForceEnd = false
        stopSearch()
        endEMV()
        transData.stan = ""
        transData.invoiceNo = ""
        transData.respCode = Utility.ASCIItoHexString("SHC005")
    }

    private fun customOnBackPress() {
        helperLog.appendLine(helperLogClassName, "OnBack Press Detected")

        // Never tear down while the host owns the transaction. `isNotEnd` stays
        // true throughout the authorisation, so without this a back press (or the toolbar arrow)
        // mid-0200 blanked stan/invoiceNo/respCode and the approval landed on cleared data — the
        // same clobber MF919 hit via android:noHistory, reached by a different route. The card is
        // either about to be charged or already has been; the user does not get to cancel here.
        if (isHostRequestInFlight) {
            helperLog.appendLine(helperLogClassName, "Back ignored :: host request in flight")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
            return
        }

        if (isNotEnd) {
            // isVending: only answer a vend that is still open. After a VMC cancel the
            // controller has already sent VEND DENIED, and answering again puts a second 06 on
            // the bus behind it.
            if (MdbController.isVending) {
                helperLog.appendLine(helperLogClassName, "Vend DENIED :: card payment abandoned, notifying VMC")
                MdbController.sendVendDenied()
            }
            stopSearch()
            endEMV()
            transData.stan = ""
            transData.invoiceNo = ""
            transData.respCode = Utility.ASCIItoHexString("SHC005")
        }
    }
}