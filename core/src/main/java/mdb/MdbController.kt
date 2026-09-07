package mdb

import data_enum.ConnMethodEnum
import helpers.TerminalInfo
import utils.AmountFormat
import utils.Bcd

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import utils.HexUtil
import com.google.gson.Gson
import enums.EnumLogFileName
import helpers.HelperLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Physical link to the MDB slave interface. This is the only device-specific part of the
 * MDB stack - each terminal model provides its own implementation (MF919: [MorefunMdbTransport],
 * A99: Vanstone UptApi + UPTCodec framing).
 */
interface MdbTransport {
    /**
     * Open the MDB slave connection and deliver every received frame (raw payload bytes,
     * framing already stripped) to [onFrame]. Returns true when the link is up.
     * [onFrame] may be invoked on a driver/binder thread - it must not block.
     */
    fun connect(onFrame: (ByteArray) -> Unit): Boolean

    /** Queue one reader response for the VMC. Returns false when the driver rejects it. */
    fun send(data: ByteArray): Boolean
}

/**
 * MDB/ICP 4.3 Section 7 cashless-device (reader, address 10H) protocol logic.
 * The terminal is the MDB slave; the vending machine controller (VMC) is the master.
 *
 * Portable across terminal models - only [transport] differs per device.
 *
 * Two pricing modes:
 *  - Terminal pricing ([isVmcPricingMode] = false): the terminal has a denomination list.
 *    "Start Purchase" sends BEGIN SESSION, the VMC answers with VEND REQUEST carrying the price.
 *  - VMC pricing ([isVmcPricingMode] = true): no denomination list. The reader advertises
 *    "Always Idle" and the VMC opens the vend directly with VEND REQUEST.
 */
object MdbController {

    /**
     * The app this controller is running inside. Registered once in the Application class,
     * alongside TerminalInfo and DbSchema. See [MdbHost] for why the seam exists.
     */
    private lateinit var hostRef: MdbHost
    private val host: MdbHost get() = hostRef

    @JvmStatic
    fun register(host: MdbHost) {
        hostRef = host
        if (transport == null) transport = MorefunMdbTransport(host)
    }

    @JvmStatic
    fun isRegistered(): Boolean = ::hostRef.isInitialized


    // ---- VMC -> reader command codes (first payload byte(s), hex-string form) ----
    private const val CMD_RESET = "10"
    private const val CMD_SETUP = "11"
    private const val CMD_POLL = "12"
    private const val CMD_VEND = "13"
    private const val CMD_READER = "14"
    private const val CMD_REVALUE = "15"
    private const val CMD_EXPANSION = "17"

    private const val SETUP_CONFIG_DATA = "1100"
    private const val SETUP_MAX_MIN_PRICES = "1101"
    private const val VEND_REQUEST = "1300"
    private const val VEND_CANCEL = "1301"
    private const val VEND_SUCCESS = "1302"
    private const val VEND_FAILURE = "1303"
    private const val SESSION_COMPLETE = "1304"
    private const val CASH_SALE = "1305"
    private const val READER_DISABLE = "1400"
    private const val READER_ENABLE = "1401"
    private const val READER_CANCEL = "1402"
    private const val REVALUE_REQUEST = "1500"
    private const val REVALUE_LIMIT_REQUEST = "1501"
    private const val EXPANSION_REQUEST_ID = "1700"
    private const val EXPANSION_ENABLE_OPTIONS = "1704"
    private const val EXPANSION_DIAGNOSTICS = "17FF"

    // ---- reader -> VMC responses ----
    private val RESP_JUST_RESET = byteArrayOf(0x00)
    private val RESP_SESSION_CANCEL_REQUEST = byteArrayOf(0x04)
    private val RESP_VEND_DENIED = byteArrayOf(0x06)
    private val RESP_END_SESSION = byteArrayOf(0x07)
    private val RESP_CANCELLED = byteArrayOf(0x08)
    private val RESP_REVALUE_DENIED = byteArrayOf(0x0E)
    private val RESP_DIAGNOSTICS = byteArrayOf(0xFF.toByte())
    // MALFUNCTION/ERROR: refund error (1100b) - reader credit lost, refund not performed
    private val RESP_MALFUNCTION_REFUND_ERROR = byteArrayOf(0x0A, 0xC0.toByte())

    // Reader config data: reader level (see readerFeatureLevel), currency 1458
    // (ISO 4217 MYR), scale factor 1, 2 decimals, application max response time
    // 0xB4 = 180s, misc options 0D (refund capable, has display, cash sale support).
    // The 180s must cover the VEND REQUEST -> VEND APPROVED gap = the full payment flow:
    //   card: 30s option screen + 60s EMV CHECK_CARD_TIME_OUT + ~30s host auth  (~120s)
    //   QR:   30s option screen + QR display + 3x enquiry poll with 5s gaps    (~140s)
    // Re-budget this byte if any of those app-level timers grow.
    private fun buildReaderConfig(): ByteArray =
        byteArrayOf(0x01, readerFeatureLevel.toByte(), 0x14, 0x58, 0x01, 0x02, 0xB4.toByte(), 0x0D)

    // PERIPHERAL ID manufacturer code - 3 ASCII chars, ideally EVA-DTS registered.
    private const val MANUFACTURER_CODE = "SHC"

    // Optional feature bits appended to the PERIPHERAL ID response.
    private const val FEATURE_BITS_ALWAYS_IDLE = "00000020"
    //private const val FEATURE_BITS_ASK_BEGIN_SESSION = "00000220"
    //private const val FEATURE_BITS_BASKET_MODE = "000002A0"

    // BEGIN SESSION, Level-01 VMC: short form, funds available = FFFEh (max).
    //private val BEGIN_SESSION_L1 = byteArrayOf(0x03, 0xFF.toByte(), 0xFE.toByte())
    private val BEGIN_SESSION_L1 = byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte())
    // BEGIN SESSION, Level-02/03 VMC: long form - funds available FFFEh (max),
    // payment media ID unknown (FFFFFFFF), payment type 00, payment data 0000.
    private const val BEGIN_SESSION_L3_TERMINAL_PRICING = "03" + "FFFF" + "FFFFFFFF" + "00" + "00" + "00"
    // BEGIN SESSION, VMC pricing: Level-03 long form - funds not yet determined (FFFF),
    // payment media ID unknown (FFFFFFFF), payment type 00, payment data 0000.
    private const val BEGIN_SESSION_L3 = "03" + "FFFF" + "FFFFFFFF" + "00" + "00" + "00"

    /** Device-specific link. Swap per terminal model. */
    var transport: MdbTransport? = null

    // ---- protocol state ----
    @Volatile var running = false
        private set
    @Volatile var mdbLoading = false
    @Volatile var isVending: Boolean = false
    @Volatile var isForceReset: Boolean = true
    @Volatile var isRequestSession: Boolean = false

    /**
     * Terminal is configured to hold a BEGIN SESSION open continuously (first denomination row
     * has Desc = AUTO_SESSION). Owned here rather than by AttendDenominationActivity so the
     * session survives the payment and acknowledge screens.
     */
    @Volatile var autoSessionEnabled = false

    /**
     * A *payment* screen (option / card / QR) owns the bus. No standing session while one is up -
     * a second VEND REQUEST mid-payment could not be answered.
     *
     * Deliberately NOT set for the acknowledge screen: keeping the session open across it is what
     * removes the ~33s dead window between sales (30s ack countdown + 3s re-arm). Cleared by
     * sendVendApproved/sendVendDenied, i.e. the moment the vend cycle is answered.
     */
    @Volatile var paymentUiActive = false
    // One-shot so the redundant-READER-ENABLE diagnostic writes at most one file line per
    // session (see handleReader). Rearmed by requestStartSession/requestAskBeginSessionL3.
    @Volatile private var redundantEnableLogged = false
    @Volatile var isEnableReader: Boolean = false
    @Volatile var priceBytes: ByteArray = byteArrayOf()
    @Volatile var priceHex: String = ""

    // true = no denomination list, VMC pushes the price via VEND REQUEST (Always Idle)
    @Volatile var isVmcPricingMode = true

    // Feature level this reader declares to the VMC (Z2 of Reader Config Data).
    // Tied to the pricing mode by design:
    //  - VMC pricing (no denomination list): declare Level 3 and advertise Always Idle
    //    so the VMC can open vends directly with VEND REQUEST.
    //  - Terminal pricing (denomination list exists): declare Level 1 - no Always Idle;
    //    sessions are opened manually via requestStartSession() (3-byte L1 BEGIN SESSION).
    val readerFeatureLevel: Int
        get() = if (isVmcPricingMode) 3 else 1

    // VMC feature level (Y2 of SETUP Config Data).
    // Default 1 = safest short forms until the VMC declares itself.
    @Volatile var vmcFeatureLevel = 1

    // Both sides declare independently and the bus operates at the LOWER of the two
    // (an L3 VMC must support L1..L3 readers; an L1 VMC only understands L1 forms).
    // Decides the BEGIN SESSION form (3-byte L1 vs 10-byte L2/L3) and whether
    // PERIPHERAL ID carries the L3 feature bits.
    val operatingLevel: Int
        get() = minOf(readerFeatureLevel, vmcFeatureLevel)

    // ---- app-level session state (owned here so all MDB state lives in one place) ----
    /** An MDB-funded payment is in flight; payment/result activities answer the VMC when set. */
    @Volatile var mdbVending = false
    /** Abort signal for an in-flight MDB payment (VMC reset / vend cancel / reader disable).
     *  Setting it true also broadcasts [UiEvent.MdbVendingForceEnd] so payment screens can
     *  dismiss immediately instead of polling this flag. */
    @Volatile
    var mdbVendingForceEnd = false
        set(value) {
            field = value
            if (value) {
                host.onVendingForceEnd()
            }
        }

    @Volatile var isAttendReady: Boolean = false
    @Volatile var pendingVendFailed: Boolean = false
    var pendingVendFailedInvoice: String? = null

    /**
     * QR reference of the payment that funded a failed vend, or empty when the vend was funded by
     * card. Snapshotted here for the same reason as [pendingVendFailedInvoice]: TransData is global
     * and a later transaction can take it over before the auto-void runs.
     *
     * The card and QR voids are NOT interchangeable - VoidSaleActivity searches the card batch by
     * invoice number, VoidQrActivity searches the QR table by refId - so the auto-void has to know
     * which one funded the vend. See AttendDenominationActivity.mdbVoidVendingFailed.
     */
    var pendingVendFailedQrRef: String? = null

    // Set when the VMC must re-run its init sequence (e.g. pricing mode changed after
    // the bus was already initialized). Served on the next idle POLL, cleared on RESET.
    @Volatile private var pendingReinit = false

    private var idleLoopCount = 0

    // Watchdog for the in-flight vend (see VEND_RESPONSE_TIMEOUT_MS). Armed when a vend
    // is handed to the payment flow, cancelled once the vend is answered.
    @Volatile private var vendTimeoutJob: Job? = null

    // Reinit (0B) active-send retry: the reader queues COMMAND OUT OF SEQUENCE and repeats
    // until the VMC issues RESET. Capped so we never send 0B forever if the VMC ignores it.
    private const val REINIT_RETRY_MS = 1500L
    private const val REINIT_MAX_ATTEMPTS = 20

    // Safety-net timeout for answering a VEND REQUEST. Once a vend is in flight the VMC
    // waits for the reader to respond; if the UI payment flow stalls or an activity is
    // torn down without answering, this fires a VEND DENIED so the vend never hangs open
    // (previously it relied entirely on the VMC sending VEND CANCEL). Kept under the
    // application max response time declared in buildReaderConfig (0xB4 = 180s) so the
    // reader self-denies before the VMC's own timeout.
    private const val VEND_RESPONSE_TIMEOUT_MS = 170_000L

    /**
     * Start or stop the MDB slave according to the terminal's cable-connection config.
     * Safe to call repeatedly (e.g. every time the home screen loads).
     */
    @JvmStatic
    fun startIfConfigured() {
        val connMethod = host.cableConnectionMethod()
        val tmpHelperLog = newLog("MDB Start If Configured")
        tmpHelperLog?.appendLine(TAG, "MDB startIfConfigured :: connMethod=$connMethod running=$running")

        if (connMethod == ConnMethodEnum.MDB.value && !running) {
            mdbLoading = false
            isVending = false
            isForceReset = true
            isEnableReader = false
            priceBytes = byteArrayOf()
            priceHex = ""
            isRequestSession = false
            paymentUiActive = false

            host.onTransportActive(true)
            running = true

            val connected = transport?.connect { frame -> onFrameReceived(frame) } ?: false
            tmpHelperLog?.appendLine(TAG, "Transport connect result :: $connected " +
                    "(readerLevel=$readerFeatureLevel vmcPricing=$isVmcPricingMode)")
            if (connected) {
                send(RESP_JUST_RESET)
                startAutoSessionSupervisor()
            } else {
                running = false
                host.onTransportActive(false)
            }
            // Boundary: the bus is either up or it is not.
            tmpHelperLog?.logToFile(
                if (connected) EnumLogFileName.TerminaLog else EnumLogFileName.TerminaLogException
            )
        } else if (connMethod != ConnMethodEnum.MDB.value && running) {
            host.onTransportActive(false)
            running = false
            stopAutoSessionSupervisor()
            isForceReset = true
            tmpHelperLog?.appendLine(TAG, "MDB reading closed (connMethod is now $connMethod)")
            tmpHelperLog?.logToFile(EnumLogFileName.TerminaLog)
        }
        // No else: this is called on every home-screen load, and when nothing changed the
        // single line above is already on disk (sink 1) - a block per load would be noise.
    }

    private fun onFrameReceived(frame: ByteArray) {
        if (!running || frame.isEmpty()) return
        val reda = Bcd.bcdToASCString(frame)

        try {
            val typeCode = reda.substring(0, 2)
            if (idleLoopCount > 20 || typeCode != CMD_POLL) {
                if (typeCode == CMD_POLL) {
                    idleLoopCount = 0
                }
                host.printLog("MDB Receive: $reda")
            } else {
                idleLoopCount++
            }

            when (typeCode) {
                "00" -> {
                    // empty frame from driver - ignore
                }
                CMD_RESET -> {
                    // A RESET aborts anything in flight, so it is the end of a vend cycle too.
                    if (vendLog != null) {
                        endVendLog("RESET from VMC mid-cycle")
                    } else {
                        logLine("MDB RESET from VMC")
                    }
                    reset()
                    // Queue JUST RESET so it is delivered on the VMC's next poll. This must be
                    // sent actively (not only from the CMD_POLL branch): the Morefun driver
                    // handles polling at the link layer and never surfaces a POLL frame to the
                    // app, so an app that only answers observed polls would never send it.
                    send(RESP_JUST_RESET)
                }
                CMD_POLL -> {
                    // Only reached on transports that surface POLL frames to the app (A99's
                    // Vanstone read loop). MF919's Morefun driver never delivers POLLs here -
                    // JUST RESET and reinit (0B) are queued actively instead. Harmless on A99:
                    // a duplicate JUST RESET until SETUP is spec-acceptable.
                    if (isForceReset) {
                        send(RESP_JUST_RESET)
                    }
                }
                CMD_SETUP -> handleSetup(reda)
                CMD_EXPANSION -> handleExpansion(reda)
                CMD_VEND -> handleVend(reda)
                CMD_READER -> handleReader(reda)
                CMD_REVALUE -> handleRevalue(reda)
                else -> {
                    logReceiveDiagnostic("MDB unknown command frame :: $reda")
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            logReceiveDiagnostic("MDB frame handling failed :: ${ex.javaClass.simpleName}: ${ex.message} frame=$reda")
        }
    }

    /*
     * Receive-thread diagnostics, de-duplicated on the message itself. A VMC that repeats an
     * unsupported command - or keeps delivering the same malformed frame - does so on every
     * poll, so an unconditional file line here would be a per-poll disk loop. The logcat trail
     * from onFrameReceived above still shows every occurrence.
     */
    @Volatile
    private var lastReceiveDiagnostic = ""

    private fun logReceiveDiagnostic(message: String) {
        if (lastReceiveDiagnostic == message) return
        lastReceiveDiagnostic = message
        logLine(message)
    }

    private fun handleSetup(reda: String) {
        val setupType = reda.substring(0, 4)
        isForceReset = false

        if (setupType == SETUP_CONFIG_DATA) {
            // Y2 = VMC feature level (payload: "1100" + level + columns + rows + display info)
            vmcFeatureLevel = reda.substring(4, 6).toIntOrNull(16) ?: 1
            logLine("MDB SETUP Config Data :: vmcLevel=$vmcFeatureLevel readerLevel=$readerFeatureLevel " +
                    "operatingLevel=$operatingLevel")

            if (isRequestSession) {
                mdbLoading = false
                mdbVendingForceEnd = true
                vendLine("Open session ended by SETUP from VMC")
            }
            send(buildReaderConfig())
        } else if (setupType == SETUP_MAX_MIN_PRICES) {
            // No data response
            if (isRequestSession) {
                mdbLoading = false
                mdbVendingForceEnd = true
                vendLine("Open session ended by SETUP Max/Min Prices from VMC")
            }
        }
    }

    private fun handleExpansion(reda: String) {
        val setupType = reda.substring(0, 4)
        isForceReset = false

        if (setupType == EXPANSION_REQUEST_ID) {
            // PERIPHERAL ID: report the terminal's own identity. Optional feature bits
            // (Z31-Z34) exist only in the Level-03 response form - an L1/L2 bus expects 30 bytes.
            send(buildPeripheralId(includeFeatureBits = operatingLevel >= 3))
        } else if (setupType == EXPANSION_ENABLE_OPTIONS) {
            // Pricing mode is decided by the denomination list
            // (see AttendDenominationActivity), not by this command
        } else if (setupType == EXPANSION_DIAGNOSTICS) {
            // Level 01+ command - answer with a Diagnostics Response (no payload)
            send(RESP_DIAGNOSTICS)
        }
    }

    private fun handleRevalue(reda: String) {
        when (reda.substring(0, 4)) {
            // This reader has no revalue capability - deny both request forms so the
            // uninterruptable REVALUE sequence is always answered.
            REVALUE_REQUEST, REVALUE_LIMIT_REQUEST -> {
                logReceiveDiagnostic("MDB REVALUE request denied (not supported) :: ${reda.substring(0, 4)}")
                send(RESP_REVALUE_DENIED)
            }
        }
    }

    private fun handleVend(reda: String) {
        when (reda.substring(0, 4)) {
            VEND_REQUEST -> handleVendRequest(reda)

            VEND_CANCEL -> {
                // Tear the payment UI down either way - the VMC has withdrawn the vend.
                mdbVendingForceEnd = true

                // VEND DENIED is the spec's required answer to VEND CANCEL, but only while the
                // vend is still unanswered. Both sendVendApproved() and sendVendDenied() clear
                // isVending, so it reads exactly as "this vend sequence is still open". Without
                // the guard, a VEND CANCEL arriving after we already approved would put a
                // contradictory 06 on the bus behind our own 05. Same guard startVendTimeout()
                // uses before its watchdog deny.
                if (isVending) {
                    vendLine("VEND CANCEL from VMC - denying the vend")
                    sendVendDenied()
                } else {
                    vendLine("VEND CANCEL from VMC - vend already answered, no second response sent")
                }
            }

            VEND_SUCCESS -> {
                // End of the machine's side of the cycle: the product was dispensed.
                logLine("MDB VEND SUCCESS from VMC :: invoice=${host.currentInvoiceNo()}")
            }

            VEND_FAILURE -> {
                logLine("MDB VEND FAILURE from VMC :: invoice=${host.currentInvoiceNo()} - " +
                        "returning to home screen to void the captured payment")
                pendingVendFailed = true
                pendingVendFailedInvoice = host.currentInvoiceNo()
                // A QR-funded vend must be voided through the QR path. qrRespCode 0000 is the only
                // thing that says "this sale was paid by QR"; anything else means card.
                pendingVendFailedQrRef = host.currentQrRef()

                // The vend is over -- what follows is a refund, not a payment. Without this the
                // void's own result screen still sees an in-flight vend and sends a second
                // VEND APPROVED (0x05) after the failure, telling the VMC to dispense goods that
                // were just refunded. Measured on Pro 2026-09-07: 050028 went out 4s after 1303.
                mdbVending = false

                // Back to the home screen, which voids the captured payment (see
                // AttendDenominationActivity.mdbVoidVendingFailed)
                host.navigateHome()
                host.finishVendingScreen()
            }

            SESSION_COMPLETE -> {
                logLine("MDB SESSION COMPLETE from VMC - answering END SESSION")
                endSessionState()
                send(RESP_END_SESSION)
            }

            CASH_SALE -> {
                // Cash-transaction audit report (we advertise support via misc options b3).
                // No data response required - log for traceability.
                logLine("MDB CASH SALE report :: $reda")
            }

            else -> {
                logReceiveDiagnostic("MDB unhandled VEND subcommand :: $reda")
            }
        }
    }

    private fun handleVendRequest(reda: String) {
        // Cycle boundary: a VEND REQUEST is the start of an unattended payment. Only opened
        // once - a duplicate request while one is in flight must not replace the open block.
        if (vendLog == null) {
            startVendLog("VEND REQUEST :: pricing=${if (isVmcPricingMode) "VMC" else "Terminal"} " +
                    "readerEnabled=$isEnableReader sessionOpen=$isRequestSession isVending=$isVending")
        } else {
            vendLine("VEND REQUEST while a vend is already in flight :: isVending=$isVending")
        }
        if (isVmcPricingMode) {
            if (isEnableReader) {
                if (!isVending) {
                    isVending = true
                    // navigation + wait must not block the MDB receive callback thread
                    CoroutineScope(Dispatchers.Default).launch {
                        val ready = host.isVendingScreenReady()
                        vendLine("Vending screen ready :: $ready")
                        if (!ready) {
                            isAttendReady = false
                            vendLine("Returning to the vending home screen")

                            host.navigateHome()
                            host.finishVendingScreen()

                            waitForVendingScreen()
                        } else {
                            isAttendReady = true
                        }

                        if (isAttendReady) {
                            isRequestSession = true
                            priceChecking(reda)
                        } else {
                            // activity never became ready - answer the VMC instead of leaving the vend sequence open
                            vendLine("Payment screen never became ready - denying the vend")
                            mdbVendingForceEnd = true
                            sendVendDenied()
                        }
                    }
                }
            } else {
                vendLine("VEND REQUEST while the reader is disabled - denying")
                mdbVendingForceEnd = true
                sendVendDenied()
            }
        } else {
            if (isEnableReader && isRequestSession) {
                if (!isVending) {
                    priceChecking(reda)
                } else {
                    vendLine("Terminal-pricing VEND REQUEST ignored - a vend is already in flight")
                }
            } else {
                vendLine("VEND REQUEST without an open session (readerEnabled=$isEnableReader " +
                        "sessionOpen=$isRequestSession) - denying")
                mdbVendingForceEnd = true
                sendVendDenied()
            }
        }
    }

    private fun handleReader(reda: String) {
        val readerType = reda.substring(0, 4)
        isForceReset = false

        if (readerType == READER_DISABLE) {
            // Only the real transition is written: a VMC may re-assert its state on every
            // poll, and a file line per assertion would be a per-poll disk write.
            if (isEnableReader) {
                logLine("MDB READER DISABLE from VMC (sessionOpen=$isRequestSession)")
            }
            if (!isVmcPricingMode) {
                if (isRequestSession) {
                    // endSessionState(), not just mdbLoading: under AUTO_SESSION the supervisor
                    // owns the session and no Activity runnable is left to clear the flag on its
                    // way out, so a stale isRequestSession blocks every reopen until the 120s check.
                    endSessionState()
                    mdbVendingForceEnd = true
                    vendLine("Open session ended by READER DISABLE")
                }
            }
            isEnableReader = false
            host.onReaderStateChanged(false)
        } else if (readerType == READER_ENABLE) {
            val wasEnabled = isEnableReader
            if (!wasEnabled) {
                logLine("MDB READER ENABLE from VMC (sessionOpen=$isRequestSession)")
            }
            if (!isVmcPricingMode) {
                // Only a real Disabled -> Enabled transition invalidates a pending BEGIN SESSION:
                // it means the VMC was in a state where the session could not have been accepted,
                // so nothing will answer it and the caller must stop waiting.
                //
                // A READER ENABLE received while we are *already* enabled is the VMC re-asserting
                // a state it is in - it is not evidence that our session died. Aborting on it used
                // to kill a perfectly live session, which AUTO_SESSION makes far more likely to be
                // hit because it holds the session open for minutes rather than seconds. If the
                // session really is gone, the caller's own wait timeout still cleans it up.
                if (isRequestSession && !wasEnabled) {
                    // endSessionState(), not just mdbLoading: under AUTO_SESSION the supervisor
                    // owns the session and no Activity runnable is left to clear the flag on its
                    // way out, so a stale isRequestSession blocks every reopen until the 120s check.
                    endSessionState()
                    mdbVendingForceEnd = true
                    vendLine("Pending session abandoned - READER ENABLE arrived while disabled")
                } else if (isRequestSession) {
                    // Logcat every time (cheap), but only one file line per session - a VMC that
                    // re-asserts ENABLE on every poll would otherwise write to disk from the
                    // receive thread on every frame.
                    host.printLog("Redundant READER ENABLE during an open session - session kept")
                    if (!redundantEnableLogged) {
                        redundantEnableLogged = true
                        logLine("Redundant READER ENABLE during an open session - session kept")
                    }
                }
            } else {
                isRequestSession = false  // set idle for VMC mode
            }
            isEnableReader = true
            host.onReaderStateChanged(true)
        } else if (readerType == READER_CANCEL) {
            // VMC aborts a pending request (e.g. escrow return in Enabled state).
            // Uninterruptable sequence - must answer CANCELLED.
            if (isRequestSession) {
                    // endSessionState(), not just mdbLoading: under AUTO_SESSION the supervisor
                    // owns the session and no Activity runnable is left to clear the flag on its
                    // way out, so a stale isRequestSession blocks every reopen until the 120s check.
                endSessionState()
                mdbVendingForceEnd = true
                vendLine("READER CANCEL from VMC - open session ended")
            } else {
                logLine("MDB READER CANCEL from VMC (no open session)")
            }
            send(RESP_CANCELLED)
        }
    }

    private fun priceChecking(reda: String) {
        isVending = true
        val tempPriceHex = reda.substring(4, 8)

        if (tempPriceHex != "0000") {
            try {
                // One line, not three: the hex is what the VMC sent and the amount is what the
                // customer is charged. The decimal in between was just the arithmetic.
                vendLine("Vend amount :: priceHex=$tempPriceHex " +
                        "amount=${AmountFormat.getActualAmount(tempPriceHex.toInt(16).toString())}")
                priceHex = tempPriceHex
                priceBytes = HexUtil.hexStringToByte(priceHex)
                mdbLoading = false
                // The session just carried a vend - restart its idle clock so the supervisor's
                // stale check measures time-since-use, not time-since-open.
                sessionActivityAtMs = SystemClock.elapsedRealtime()
                // A vend is now in flight and must be answered - arm the watchdog so the
                // VMC still gets a VEND DENIED if the payment flow stalls or is torn down.
                startVendTimeout()
                if (isVmcPricingMode) {
                    // requestAskBeginSessionL3() is intentionally not sent (commit fead997),
                    // but the abort flag it used to clear must still be reset here. Otherwise a
                    // stale mdbVendingForceEnd (from a prior vend/reset) trips the card-search
                    // loop on its first check and the reader never waits for a card tap.
                    mdbVendingForceEnd = false
                    host.onVendingPrice(priceHex)
                } else if (autoSessionEnabled) {
                    // Terminal-pricing AUTO_SESSION: the session belongs to the supervisor, so no
                    // Activity runnable is waiting on mdbLoading to pick this vend up - navigate
                    // from here or nothing ever answers it. Same stale-flag reset as above.
                    mdbVendingForceEnd = false
                    navigateToPaymentOption(priceHex)
                }
            } catch (ex: Exception) {
                isVending = false
                mdbVendingForceEnd = true
                mdbLoading = false
                if (isVmcPricingMode) {
                    // no session was opened - the vend sequence ends with this deny
                    isRequestSession = false
                }
                // Flushed here, synchronously, before the deny: a price we cannot read is the
                // one thing about this cycle worth surviving a process kill.
                endVendLog("price handling failed :: ${ex.javaClass.simpleName}: ${ex.message}", isError = true)
                sendVendDenied()
                ex.printStackTrace()
            }
        } else {
            // a zero-price VEND REQUEST cannot be fulfilled - the vend sequence must still be answered
            vendLine("Zero price vend request - denying")
            mdbVendingForceEnd = true
            mdbLoading = false
            if (isVmcPricingMode) {
                // no session was opened - the vend sequence ends with this deny
                isRequestSession = false
            }
            sendVendDenied()
        }
    }

    /** Reset to the Inactive-equivalent state (VMC RESET command, or app-driven recovery). */
    @JvmStatic
    fun reset() {
        // Cycle boundary: a reset aborts anything in flight, so an open block ends here rather
        // than staying open until the next vend.
        if (vendLog != null) {
            endVendLog("controller reset")
        }
        pendingReinit = false
        mdbLoading = false
        cancelVendTimeout()
        isEnableReader = false
        host.onReaderStateChanged(false)
        mdbVendingForceEnd = true
        isVending = false
        isForceReset = true
        // The broadcast above dismisses any payment screen, so it no longer owns the bus.
        paymentUiActive = false
        // A VMC RESET reinitialises the whole bus - no session can survive it. Without this
        // the supervisor kept believing a session was open and refused to reopen for 120s.
        endSessionState()
    }

    /**
     * Update the pricing mode. If the bus is already initialized and the mode actually
     * changed, the VMC's view of our level/feature bits is stale - trigger a
     * re-initialization so it re-reads them.
     */
    @JvmStatic
    fun updatePricingMode(vmcPricing: Boolean) {
        val changed = isVmcPricingMode != vmcPricing
        isVmcPricingMode = vmcPricing
        if (changed) {
            // Only the transition: this is called every time the denomination screen loads.
            logLine("MDB pricing mode -> ${if (vmcPricing) "VMC" else "Terminal"} " +
                    "(readerLevel=$readerFeatureLevel running=$running)")
        }
        if (changed && running) {
            reinitialize()
        }
    }

    /**
     * Force the VMC to re-run its init sequence (RESET -> SETUP -> EXPANSION -> ENABLE)
     * so it re-reads our declared feature level and Always-Idle bits. The reader cannot
     * reset the VMC directly; per spec 7.4.4 a COMMAND OUT OF SEQUENCE poll response
     * always causes the VMC to issue RESET. Served on the next POLL once no vend or
     * payment is in flight.
     */
    @JvmStatic
    fun reinitialize() {
        if (pendingReinit) return  // already driving a reinit
        logLine("MDB reinitialize requested (reader level $readerFeatureLevel, vmc level $vmcFeatureLevel)")
        pendingReinit = true
        // Actively queue COMMAND OUT OF SEQUENCE and retry until the VMC answers with RESET.
        // The response is delivered on the VMC's next poll by the link layer, so this works
        // even though the app never observes the POLL itself (MF919). reset() clears
        // pendingReinit when the VMC's RESET arrives, ending the loop.
        CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            while (pendingReinit && attempts < REINIT_MAX_ATTEMPTS) {
                if (!isVending && !mdbVending && !isRequestSession && !isForceReset) {
                    send(buildCommandOutOfSequence())
                    attempts++
                }
                delay(REINIT_RETRY_MS)
            }
            if (pendingReinit) {
                val log = newLog("MDB Reinitialize")
                log?.appendLine(TAG, "MDB reinit: VMC did not RESET after $attempts attempts - giving up")
                log?.logToFile(EnumLogFileName.TerminaLogException)
                pendingReinit = false
            }
        }
    }

    // COMMAND OUT OF SEQUENCE (0BH). Level-02/03 VMCs expect a second status byte
    // (02 = Disabled, 03 = Enabled); a Level-01 VMC expects the single byte.
    private fun buildCommandOutOfSequence(): ByteArray {
        return if (vmcFeatureLevel >= 2) {
            byteArrayOf(0x0B, if (isEnableReader) 0x03 else 0x02)
        } else {
            byteArrayOf(0x0B)
        }
    }

    /** VEND APPROVED (0x05) + vend amount - payment captured, VMC may dispense. */
    @JvmStatic
    fun sendVendApproved() {
        val approvedPriceHex = priceHex
        vendLine("VEND APPROVED :: priceHex=$approvedPriceHex invoice=${host.currentInvoiceNo()}")
        cancelVendTimeout()
        isVending = false
        // Payment is over; the acknowledge screen does not hold the bus, so the supervisor may
        // reopen a standing session while the customer is still reading their result.
        paymentUiActive = false

        val dataReq = byteArrayOf(0x05) + priceBytes
        priceHex = ""
        send(dataReq)
        // The VMC has its answer, so this transaction is no longer an in-flight MDB vend. Cleared
        // here rather than left to the home screen: the caller has already read the flag to decide
        // to call us, and any result screen shown AFTER this point -- an auto-void's result, most
        // of all -- must not read it as "still vending" and answer the VMC a second time.
        mdbVending = false
        // Cycle boundary: the VMC has its answer and may dispense.
        endVendLog("VEND APPROVED")
    }

    /** VEND DENIED (0x06) - payment failed/cancelled, VMC must not dispense. */
    @JvmStatic
    fun sendVendDenied() {
        vendLine("VEND DENIED :: priceHex=$priceHex")
        cancelVendTimeout()
        isVending = false
        paymentUiActive = false
        priceHex = ""
        send(RESP_VEND_DENIED)
        // Deliberately does NOT clear mdbVending, unlike sendVendApproved().
        //
        // This is the one vend answer that can be sent while a payment screen is still up: VEND
        // CANCEL raises mdbVendingForceEnd and lands here microseconds later. The screens observe
        // that abort and clear both flags themselves (CardPaymentFragment /
        // DenominationPaymentOptionFragment.onMdbVendingForceEnd), which is also what stops their
        // teardown path putting a second VEND DENIED on the bus behind this one. Clearing
        // mdbVending here would pre-empt them and, on MF919 -- whose card screen still polls
        // `mdbVending && mdbVendingForceEnd` because an Activity has no callback into its search
        // loop -- makes that guard unsatisfiable outright.
        //
        // That failure was measured on the SR800 on 2026-09-07: the card screen kept soliciting a
        // card for 42s after the VMC had cancelled, ending in a read timeout rather than an abort.
        // A customer tapping inside that window is charged for a vend the machine already gave up
        // on, and no auto-void covers it because pendingVendFailed is only set on VEND FAILURE.
        // Cycle boundary: the VMC has its answer and will not dispense.
        endVendLog("VEND DENIED")
    }

    @JvmStatic
    fun sendManualReset() {
        logLine("MDB manual reset (JUST RESET queued)")
        send(RESP_JUST_RESET)
    }

    /**
     * Arm the vend-response watchdog (see [VEND_RESPONSE_TIMEOUT_MS]). Replaces any
     * previous timer so only one vend is ever tracked. If the vend is still unanswered
     * ([isVending] true) when it fires, deny it so the VEND REQUEST is not left open.
     */
    private fun startVendTimeout() {
        vendTimeoutJob?.cancel()
        vendTimeoutJob = CoroutineScope(Dispatchers.Default).launch {
            delay(VEND_RESPONSE_TIMEOUT_MS)
            if (isVending) {
                vendLine("Vend response timeout after ${VEND_RESPONSE_TIMEOUT_MS}ms - auto-denying")
                mdbVendingForceEnd = true
                if (isVmcPricingMode) {
                    // no session stays open past our own deny
                    isRequestSession = false
                }
                sendVendDenied()
            }
        }
    }

    /** Disarm the vend-response watchdog once the vend has been answered or aborted. */
    private fun cancelVendTimeout() {
        vendTimeoutJob?.cancel()
        vendTimeoutJob = null
    }

    // ---- AUTO_SESSION standing session ----------------------------------------------------
    // How long the VMC gets to answer our BEGIN SESSION before we cancel and reopen. Kept under
    // the application max response time declared in buildReaderConfig (0xB4 = 180s).
    private const val AUTO_SESSION_WAIT_MS = 120_000L
    // Supervisor cadence. Also the worst-case recovery time for any session-ending route we did
    // not explicitly hook - which is the point of polling rather than relying on events alone.
    private const val AUTO_SESSION_TICK_MS = 2_000L

    private var autoSessionJob: Job? = null
    /**
     * Timestamp of the last activity on the standing session - set when it opens and refreshed
     * whenever a VEND REQUEST actually uses it. It is deliberately "last activity", not "opened
     * at": measured on the bench 2026-08-28, a session opened at 22:27:19 that carried a vend
     * until 22:28:34 was stale-cancelled at 22:29:19, i.e. after only 45s of real idle rather
     * than the intended 120s. A slow sale could otherwise trigger a cancel moments after it.
     */
    @Volatile private var sessionActivityAtMs = 0L

    /**
     * The session is over as far as the VMC is concerned.
     *
     * Clearing this in BOTH pricing modes is the fix for the dead window measured on the bench
     * 2026-08-28: terminal-pricing used to leave isRequestSession set, so the only thing that ever
     * cleared it was the session runnable timing out after its full wait (120s under AUTO_SESSION),
     * which then fired a SESSION CANCEL REQUEST for a session the VMC had already closed.
     */
    private fun endSessionState() {
        isRequestSession = false
        mdbLoading = false
    }

    /**
     * Single owner of the AUTO_SESSION standing session, alive for as long as the MDB slave is
     * running. Previously this lived in AttendDenominationActivity gated on the activity being
     * RESUMED, so the session could only exist while the home screen was foreground - which is why
     * the machine was unable to take payment for the whole acknowledge countdown after every sale.
     *
     * Polling rather than pure event-driven on purpose: any session-ending route that is missed
     * recovers on the next tick instead of leaving the machine dead until a timeout.
     */
    private fun startAutoSessionSupervisor() {
        if (autoSessionJob?.isActive == true) return
        autoSessionJob = CoroutineScope(Dispatchers.Default).launch {
            logLine("Auto session supervisor started")
            while (true) {
                delay(AUTO_SESSION_TICK_MS)
                if (!running) break
                if (!autoSessionEnabled || isForceReset) continue

                if (isRequestSession) {
                    // Session open. Cancel it only if the VMC never sent a VEND REQUEST - a vend in
                    // flight owns the session and must be left alone.
                    val idle = SystemClock.elapsedRealtime() - sessionActivityAtMs
                    if (idle > AUTO_SESSION_WAIT_MS && !isVending && !paymentUiActive) {
                        logLine("Auto session :: no VEND REQUEST in ${AUTO_SESSION_WAIT_MS}ms - cancelling")
                        requestSessionCancel()
                        endSessionState()
                    }
                    continue
                }

                // No session. Open one once the bus is free and no payment screen owns it.
                //
                // Deliberately NOT gated on mdbVending: that flag means "this transaction is
                // MDB-funded" (the result screen reads it to answer the VMC) and stays set until
                // AttendDenominationActivity.onCreate clears it - i.e. right through the
                // acknowledge screen. Gating on it would reinstate the dead window this exists to
                // remove. paymentUiActive is the bus-ownership signal; isVending covers a vend in
                // flight.
                if (isEnableReader && !paymentUiActive && !isVending) {
                    logLine("Auto session :: opening standing session")
                    isRequestSession = true
                    sessionActivityAtMs = SystemClock.elapsedRealtime()
                    requestStartSession()
                }
            }
            logLine("Auto session supervisor stopped")
        }
    }

    private fun stopAutoSessionSupervisor() {
        autoSessionJob?.cancel()
        autoSessionJob = null
    }

    /**
     * Open the payment screen for a vend the supervisor's session accepted.
     *
     * Under AUTO_SESSION nothing in the UI is waiting on this vend - the session belongs to the
     * supervisor, not to an Activity's runnable - so the controller navigates itself, the same way
     * VEND FAILURE opens the home screen. The tap-driven flow is untouched and still navigates from
     * AttendDenominationActivity.mdbPriceChecking().
     */
    private fun navigateToPaymentOption(priceHexValue: String) {
        try {
            val decimalPrice = priceHexValue.toInt(16).toString()
            mdbVending = true
            paymentUiActive = true
            host.navigateToPaymentOption(decimalPrice)
            vendLine("Auto session :: navigate -> payment option (price=$decimalPrice)")
        } catch (ex: Exception) {
            // Cannot show a payment screen, so the vend can never be answered - deny it now rather
            // than leaving the VEND sequence open until the watchdog fires.
            paymentUiActive = false
            mdbVendingForceEnd = true
            endVendLog("payment screen launch failed :: ${ex.javaClass.simpleName}: ${ex.message}", isError = true)
            sendVendDenied()
            ex.printStackTrace()
        }
    }

    /** BEGIN SESSION for terminal-pricing mode ("Start Purchase" button), in the negotiated level form. */
    @JvmStatic
    fun requestStartSession() {
        logLine("MDB BEGIN SESSION requested (operating level $operatingLevel)")
        mdbVendingForceEnd = false
        mdbLoading = true
        redundantEnableLogged = false
        if (operatingLevel >= 2) {
            send(HexUtil.hexStringToByte(BEGIN_SESSION_L3_TERMINAL_PRICING))
        } else {
            send(BEGIN_SESSION_L1)
        }
    }

    /** Level-03 BEGIN SESSION issued while answering a VMC-priced vend. */
    fun requestAskBeginSessionL3() {
        logLine("MDB Level-03 BEGIN SESSION requested")
        mdbVendingForceEnd = false
        mdbLoading = true
        redundantEnableLogged = false
        send(HexUtil.hexStringToByte(BEGIN_SESSION_L3))
    }

    /** SESSION CANCEL REQUEST (0x04) - VMC follows up with SESSION COMPLETE and we answer END SESSION. */
    @JvmStatic
    fun requestSessionCancel() {
        vendLine("MDB SESSION CANCEL REQUEST sent to VMC")
        send(RESP_SESSION_CANCEL_REQUEST)
    }

    /**
     * MALFUNCTION/ERROR refund error (spec 7.4.8 vend failure sequence): tells the VMC
     * that the refund after a VEND FAILURE could not be performed and the customer's
     * payment was kept. Call when the vend-failed auto-void cannot run or is declined.
     */
    @JvmStatic
    fun reportRefundError() {
        logLine("MDB refund error reported to VMC (auto-void failed)")
        send(RESP_MALFUNCTION_REFUND_ERROR)
    }

    /*
     * Deliberately logcat-only on the success path. Every VMC poll can produce a send (JUST
     * RESET while awaiting SETUP), so a file line per frame is a per-poll disk write; the
     * frames that matter - vend answers, session requests, resets - are each logged by name
     * at their call site. Only a send the transport could not place is written to file.
     */
    private fun send(data: ByteArray) {
        host.printLog("MDB Send :: ${HexUtil.bytesToHexString(data)}")
        // A null transport means the link was never brought up; treat that as a failed send
        // rather than crashing the state machine.
        if (transport?.send(data) != true) {
            vendLine("MDB send failed after retries :: ${HexUtil.bytesToHexString(data)}")
        }
    }

    /**
     * PERIPHERAL ID response (spec 7.4.4): 09H + manufacturer code (3 ASCII) +
     * serial number (12 ASCII) + model number (12 ASCII) + software version (2 bytes
     * packed BCD) [+ optional feature bits (4 bytes), Level-03 VMC only].
     */
    private fun buildPeripheralId(includeFeatureBits: Boolean): ByteArray {
        val manufacturer = MANUFACTURER_CODE.padEnd(3, ' ').take(3)
        val serial = TerminalInfo.serialNumber().padEnd(12, ' ').take(12)
        val model = TerminalInfo.deviceModel().padEnd(12, ' ').take(12)

        // Software version fixed at 0000 for now. To report the app version instead,
        // pack BuildConfig.VERSION_NAME major.minor as 2-digit BCD each
        // ("2.2.21" -> 0x02 0x02, "2.10.21" -> 0x02 0x10).
        val versionBcd = byteArrayOf(0x00, 0x00)

        val featureBits = if (includeFeatureBits) {
            HexUtil.hexStringToByte(FEATURE_BITS_ALWAYS_IDLE)
        } else {
            byteArrayOf()
        }

        return byteArrayOf(0x09) +
                (manufacturer + serial + model).toByteArray(Charsets.US_ASCII) +
                versionBcd +
                featureBits
    }

    private suspend fun waitForVendingScreen(timeoutMs: Long = 10_000L) {
        isAttendReady = false
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            if (host.isVendingScreenReady()) {
                isAttendReady = true
                vendLine("Vending screen ready after " +
                        "${SystemClock.elapsedRealtime() - startTime}ms")
                delay(100) //short delay before start process Price
                return
            }
            delay(100)
        }
        vendLine("Vending screen not ready - timeout after ${timeoutMs}ms")
    }

    /**
     * Construction is guarded: this runs on the driver's receive thread, and getSession /
     * getContext are not guaranteed to be up on every path into here. A logging failure must
     * never propagate into the MDB state machine - a thrown exception here would leave a vend
     * sequence unanswered.
     */
    private fun newLog(purpose: String): HelperLog? = try {
        host.logHeader().let { h ->
            HelperLog(h.session, h.isWifi, h.ipAddress, TAG, TAG, purpose)
        }
    } catch (ex: Exception) {
        Log.w(TAG, "log init failed: ${ex.javaClass.simpleName}: ${ex.message}")
        null
    }

    /** One-shot event: its own single-line block, flushed immediately. */
    private fun logLine(message: String) {
        val log = newLog("MDB Controller") ?: return
        log.appendLine(TAG, message)
        log.logToFile(EnumLogFileName.TerminaLog)
    }

    /*
     * The vend cycle is the one multi-phase flow on this bus (VEND REQUEST -> activity
     * navigation -> price -> payment -> VEND APPROVED/DENIED), and it runs unattended with
     * nobody watching the screen. Lines for it are accumulated in one HelperLog so the whole
     * cycle reads as a single block; every line is on disk the moment it is appended, so a
     * cycle that never reaches its [END] (VMC reset, process kill mid-payment) still leaves
     * the full trail - only the grouping is lost.
     */
    @Volatile
    private var vendLog: HelperLog? = null

    private fun startVendLog(reason: String) {
        val log = newLog("MDB Vend Cycle") ?: return
        vendLog = log
        log.appendLine(TAG, "MDB Vend Cycle [START] :: $reason")
    }

    /** Append to the open vend cycle if there is one, otherwise emit a standalone line. */
    private fun vendLine(message: String) {
        val log = vendLog
        if (log != null) {
            log.appendLine(TAG, message)
        } else {
            logLine(message)
        }
    }

    private fun endVendLog(reason: String, isError: Boolean = false) {
        val log = vendLog
        if (log == null) {
            // No cycle open - e.g. a payment screen answering a vend this process never saw
            // start. The normal outcomes already wrote their own line through [vendLine];
            // only a failure reason would otherwise be lost.
            if (isError) logLine(reason)
            return
        }
        vendLog = null
        log.appendLine(TAG, "MDB Vend Cycle [END] :: $reason")
        log.logToFile(if (isError) EnumLogFileName.TerminaLogException else EnumLogFileName.TerminaLog)
    }

    private const val TAG = "MdbController"
}
