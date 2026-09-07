# Changelog — MF919 (`com.sc.mf919`)

Extracted verbatim from the comment block that headed `app/build.gradle` before the Phase 0 restructure.

```
/*
 * (Frankie)
 * 1.1.0.0  20220330    Added: UPI
 *                      Bugfixed: pinEncode and pinDecode for upi
 *                      Modified: Move save batch and increase txn count from tcUpload to ctsale
 * 1.2.0.0  20220412    Enchance: Receipt upload method
 * 1.2.0.1  20220517    Added: autosettle enable n disable
 *                      Added: tms log
 *                      Modified: Increase log file size to 50mb
 * 1.2.0.2  20220517    Added: serverkey logs
 * (Frankie)
 * 1.2.1    20220601    feat: Move Merchant & Terminal Configuration from .ini to DB
 *                      feat: Add ReceiptUpload & ProductList Table
 *                      feat: Migrate ScanQr to Kotlin
 * 1.2.2    20220615    Chore: Quick fix of ScanQr Bug in fail scenario
 *                      feat: Migrate ServiceHolder to Kotlin
 * 1.2.3    20220618    Chore: Add Column (LastUpdateDt) in ReceiptUpload
 *                      feat: Enhance ReceiptUpload with Housekeeping functionality
 * 1.2.4    20220630    feat: Add Migration Code to fix of modification in SqlLite without fresh install
 * 1.3.5    20220725    feat: Merge GoBiz and Paydee Terminal Project with
 *                      feat: Migrate BaseActivity to Kotlin
 *                      feat: Qr Transaction able to print with dynamic acquirer logo
 * 1.3.6    20220729    chore:Add New enum in QrCodeEnum
 *                      feat: Revamp QrGeneration and add Overlay function
 *                      chore: Card Transaction Result Display description instead of response code
 *                      chore: Change application ID to as same as previous project
 *                      chore: add apache common lang library to handle Integer parse
 * 1.3.7    20220615    feat: VISA requirement to mask mid and tid in receipt
 *                      chore: bug fix for app crash if no sign-on
 * 1.3.8    20220823    chore: QR Settlement with loading ui and hide brand with no sales
 *                      feat: terminal void pin development
 *                      chore: fix of card sales are sometimes excluded in settlement
 *                      chore: add housekeeping for log file to delete old log file for each file
 *                      chore: AppInfo name, wording for sales selection, upgrade mcash logo asset
 *                      chore: fix of stan number checking on ISO
 * 1.3.9    20220901    chore: fix of tap too fast and loading forever
 *                      chore: Show Error message and Error Code for different stan receive with request
 *                      chore: Migrate Print card and qr activity to Kotlin
 * 1.3.10   20221025    chore: amend Preauth receipt label
 *                      chore: change of download configuration on fresh app start or reboot
 *                      feat: add features to signon on app launch
 *                      feat: enhance download of merchant logo based on mc version
 *                      feat: MOTO function
 *                      feat: Add Write Log feature to the middleware for custom tracking
 *                      feat: Add Settlement on Last QR and Prevent auto settlement if no transaction found for the batch
 * 1.3.11   20221205    chore: Extend Scan QR Processing time to 3 minutes due to IOUpay
 *                      feat: Modify Loading dialog in Scan QR
 * 1.3.12   20221207    chore:Add keystore to enable generation on different devices
 *                      chore:Default blank logo while loading or no transactions
 *                      chore: Enhance Api base to allow different timeout
 * 1.3.13   20230106    feat: BNPL
 *                      chore: dynamic option dialog (print receipt, settlement, void)
 *                      chore: Settlement add PIN dialog
 *                      chore: Print receipt add more option (View Details, Preview Sale Summary, Last Settlement)
 *                      chore: Fix QR scan duplicate
 *                      chore: Fix settlement skip batchNo
 *                      chore: Hide confirm btn & add remark for void Duitnow transaction
 *  1.3.14  20230116    chore: fix settlement QR void count issue
 *  1.3.15  20230203    chore: Print history listing separate card scheme (Card)
 *  1.4.0   20230213    feat: Initial Version of BSN
 *                      feat: Multiple Settlement capability for Card and EPP
 *                      feat: Block Sales when last batch transaction is more than 20 hours unblock after Settlement
 *                      feat: EPP Sales Capability
 *                      chore: Change of YSDK to 5.3
 *                      feat: SSL TCP connection base added for EPP
 *  1.4.1   20230214    feat: add wakeup and unlock screen for calling over http
 *                      feat: add posReferencesNo in http and receiptUpload
 *  1.4.2   20230217    feat: Injection Key Screen for BSN
 *                      feat: Fix LockScreen Auto Unlock not working in Android 10
 *                      chore: Fix of Bcd2bin length issue
 *                      chore: Fix Card Pan 19 Digit Padding on BSN
 *                      chore: Revamp BaseActivity for Based on MoreFun latest code
 *  1.4.3   20230302    feat: Revamp Qr Scan Activity
 *                      feat: Add new field in Terminal Configuration
 *                      chore: BSN of Force PBOC Online false
 *  1.4.4   20230320    chore: Fix of miss leading dialogue in Void Sales
 *                      chore: Enhance emv_fallBack Always load
 *                      chore: Fix Void Sales Completion Card No Missing
 *                      chore: Enhance KSN and PIN KSN in Individual usage
 *                      chore: Revamp and Enhance on QR Generate
 *                      chore: Fix decoding DE field broken when length is too long
 *                      chore: Enhance of support log
 *                      feat: Home screen view transaction button amend from "PRINT" to "HISTORY"
 *                      feat: print QR scan history details enhancement (separate acquirer)
 *  1.4.5   20230404    chore: Add New Qr Product Asset (RedPay)
 *                      revamp: Api Base to Self Custom handler
 *                      feat: Generate QR Screen Add Exit Alert and incremental timer
 *                      feat: Cross check Server and Device date time
 *                      chore: Migrate Attend, Setting, Admin Setting and Moto Screen Kotlin
 *                      feat: Add description column in Qr Acknowledge screen
 *  1.4.6   20230410    revamp: Change of Logo from Acquirer to MM
 *                      chore: Cleanup default image to blank
 *  1.4.7   20230511    feat: Enhance app to app Card Settlement return array instead of single object
 *                      feat: Enhance app print capability if deep linking from others app
 *                      chore: checking system datetime at splashscreen instead of every action
 *                      chore: migrate print result and transaction result to kotlin
 *                      chore: add RM logo
 *                      chore: fix app to app crash if app does not open previously
 * 1.4.8    20230522    bug: fix of err82 cause by de35 incomplete when sent out
 * 1.4.9    20230522    bug: enhance de35 last bit modification on remain high bit
 *                      chore: Add new MM GLYPay
 * 1.4.10   20230630    revamp: Upload Log revamp using custom Http Base
 *                      chore: Remote Retrofit and dependency
 *                      chore: Fix of magstripe and contact, contactless disabling
 *                      chore: Fix of De60 for batchUpload
 *                      feat: Switch to zxing scanner with front camera capability
 *                      feat: BSN EPP web api and app to app
 *                      chore: Modify Receipt upload to periodic work and trigger receipt upload each transaction done
 *                      chore: SpeedUp print animation and modify Settlement and Last Settlement storing
 *                      chore: mix customize Redpay with normal app
 *                      chore: enhance morefun device engine to thread
 *                      chore: add timeout exception description
 *                      chore: add docking mode and force settlement flag
 * 1.4.11   20230710    chore: remove hardcoded SN for RedPay
 *                      chore: enhance settlement printing logic
 *                      chore nanohttp CORS capability
 *                      chore: merge of redpay special app
 * 1.4.12   20230908    feat: initial enhance on shorter iso engine process
 *                      feat: Unattend mode development
 *                      feat: revamp nano httpd to kotlin and method
 *                      feat: special request(BSN) for modify TVR value for visa contactless
 *                      feat: enhance app to app to return if not configured
 *                      feat: enhance of override sales record to void record when void successfully
 *  1.4.13  20231108    chore: fix of magstripe will not update the value in databases
 *                      chore: enhance terminate api for post web integration
 *                      chore: add on UnionPay QR enum model and logo
 *                      chore: enhance generateQR Activity to display base64 Image
 *                      chore: add BagusPos Logo
 *                      chore: allow zero amount for sales complete
 *  1.4.14  20231219    feat: add CVM, EPP_DETAIL and CARD_LABEL into ReceiptUpload table
 *                      feat: Enhance on Transaction Receiver, and add in PosReferenceNo acceot in void transaction action
 *                      feat: add countdown timer in Acknowledge
 *                      feat: Add Transaction Enquiry By PosReferencesNo for App Intent and HTTP Calling
 *                      feat: Fix of BSN MOTO issue
 *                      feat: Fix PreAuth zero amount and App crash with magstripe
 *                      feat: Add Paydibs logo and receipt logo
 *  1.4.15  20240118    feat: Add CardHash in receiptUpload for future enhancement
 *  2.0.0   20240301    feat: MyDebit Development
 *                      chore: Fix Wrong MID/TID in EPP TC Upload
 *                      chore: Production Issue Reversal Transaction are printing receipt
 *                      chore: Remove paybds and add payex mm configuration
 *                      feat: Add payex in acquirere configuration
 *  2.0.01  20240319    chore: Quick Fix of MCCS Batch No Mess up
 *  2.0.02  20240425    chore: Add in 9F6E master in gobiz data tags
 *  2.0.03  20240508    chore: Enable Duitnow Void
 *                      chore: QR settlement dynamic product
 *  2.0.04  20240529    chore: Fix TLE for EPP Reversal
 *                      feat: Reversal Before Settlement
 *  2.0.05  20240531    chore: Amend development flag in Main Activity
 *  2.0.06  20240605    chore: Rectify GoBiz Reversal receive error not deleting in db
 *                      feat: Fix of contact not clear in reversal table
 *                      feat: Fix of MyDebit Batch Upload not sending
 *  2.0.07  20240703    chore: Remove Pin Verified in MagStripe CVM Detection
 *                      chore: Fix of MagStripe not included in settlement accumulate
 *                      chore: Remove Reversal Before Settlement
 *  2.0.08  20240927    chore: Quick Fix of LastSettlementBatchNo return wrong and code sync up manually of different BatchNo
 *  2.1.00  20241111    feat: Enhance Paydee DE02 length
 *                      feat: addd in USB connection for POS
 *                      feat: UPI Special Generate and Scan QR
 *                      feat: Change Transaction History layout
 *                      feat: Add total Settlement amount in settlement receipt
 *                      chore: Fix AppIntent TransactionRefId
 *                      feat: Revamp QR code flow and add in ReEnquiry for QR
 *                      chore: Add ForceSettlementDaily
 *                      feat: CardZone Integration
 *                      chore: Update MCCS CAPK with latest
 * 2.1.01   20241126    feat: WebSocket connectivity
 *                      chore: lib ysdk rollback to 5.8
 *                      chore: add write log in settlement cubeActivity
 *                      chore: add drop and reinsert ReceiptUpload Table in Migration
 *                      chore: Fix GenerateQr Unable to void from api
 * 2.1.02   20241211    chore: Merge Oxpay
 * 2.1.03   20250113    chore: Add Front Camera capability for App and Http
 *                      chore: Fix Http Emv termination problem
 * 2.1.04   20250227    chore: PayDibs Unattend Special Request
 *                      chore: Remove of force Contact continuous flow (46)
 * 2.2.00   20250319    chore: ISO Revamp
 * 2.2.01   20250412    chore: Fix TVR modify for other acquirer remain for BSN
 *                      chore: remove tag57 in de55
 * 2.2.02   20250519    chore: Enhance Receipt Upload for ZW Case
 *                      chore: Enhance Migration with countDownLatch await
 *                      chore: Add Clear Settlement Reversal Button
 *                      chore: modified CVM logic
 *                      chore: remove MyDebit Continues Pin for contactless
 * 2.2.03   20250519    chore: remove crash handler
 *                      chore: WebSocket_Server touchup
 *                      chore: WebSocket for TPA sales validation
 *                      chore: enable of EPP for Asccend BSN
 * 2.2.04   20250522    feat: Adding Capability of printing ShareCommerce Logo TPA pattern dynamically
 *                      chore: Remove of Certification Contactless force pin flow
 *                      chore: Uncaught Exception only on Paydibs Apps
 *                      chore: KSN issue for BSN EPP in Asccend
 *                      chore: TPA CARD Account will show TPA mid tid instead of Card Acquirer MID TID
 *                      chore: Fix of Mydebit Transaction cannot void which update from non-revamp version
 *                      chore: Add Allow Block for particular sales or ewallet only
 *                      feat: bks file from Server for SSL
 *                      feat: Special Migration of BSN to BSN CARDZONE
 *                      chore: Add BSN APP logo
 * 2.2.05   20250603    chore: CASHOUT AMOUNT  in ReceiptUpload Table
 *                      chore: TPA Account missout in QR enquiry
 *                      chore: fix emv_activity and multi thread problem
 *                      chore: fix http_server reset port multi override problem
 *                      chore: fix Select APP Scheme and offline pin handling for foreign card
 * 2.2.06   20250623    chore: Fix of UPI settlementRefTag tag wrong and causing app crash
 * 2.2.07   20250804    chore: Change BSN SSL for year 2025
 * 2.2.08   20250829    feat: Enhance Generate QR Insert To DB before enquiry
 *                      chore: Enhance QrPayment From Activity to Fragment
 *                      feat: Enhance of Card Transaction insert into Print Receipt even is fail
 *                      chore: Enhance prevent double settlement thread
 *                      chore: Enhance of prevent sales action when auto settlement is running and vice versa for integration part
 *                      chore: Add always prompt pin for CashOut transaction
 *                      feat: Add special character checking in receipt upload and remove it
 *                      chore: Fix MOTO in PAYDEE REVAMP
 *                      chore: Enhance Transaction History to lazy load
 *                      chore: Upgrade YSDK jar version
 *                      feat: Revamp ISO Comm base and DNS IP lookup connection
 *                      feat: Add in Finexus ISO
 *                      chore: Fix Offline Pin Foreign Card Issue
 *                      chore: Modify cvm always sign to Pin verify to no pin verify
 *  2.2.09  20251002    chore: Fix of EWallet Transaction Enum Crash and void not showing TPA log
 *                      chore: Fix MyDebit Showing in Settlement or AutoSettlement for FNX
 *                      chore: Fix CardZone Void Reversal not running issue
 *                      chore: IsoHelper sale complete iso tag
 *                      feat: Fix for Void and Sales Complete delete Receipt and Ewallet in Auto Settlement
 *                      feat: Add or Oxpay Logo in mipmap and asset
 *                      chore: Add AhaPay and change Mcash Logo Configuration
 *                      chore: Fix Card History Detail will print include fail transaction
 *                      chore: Fix void with pin not working api or app to app mode
 *                      chore: Fix Log Deletion logic to prevent delete latest log first
 *  2.2.10  20251008    chore: Quick fix of Void With Pin always prompt in integration mode
 *  2.2.11  20251026    feat: Quick fix SSL TCP communication immediate drop after establish to retry with secondary ip and port
 *  2.2.12  20251026    feat: Enhance of Dynamic SSL or Non-SSL to Primary and Secondary by cloud configuration
 *  2.2.14  20251107    chore: 2.2.13 used for debugging skip this version
 *                      feat: add checking SettlementSummary Database to handle and delete duplicate data
 *                      feat: Fix BSN CardZone DE-07 from GMT+0 to GMT+8
 *  2.2.15  20251210    chore: Enhance YSDK EMV reset before starting a new one
 *                      feat: Add on RRN_ORI and APPR_CODE_ORI in receipt upload
 *                      chore: FNX AID and CAPK cleanup for mydebit and master card CVM limit 25001
 *                      feat: MyDebit terminate transaction in app level if AID blocked for AID Priority
 *                      chore: take quick fix for 2.2.11/12/14 into 2.2.15
 *                      chore: Fix reprint receipt app crash when transaction is too many
 *                      chore: Fix last settlement qr crash
 *                      chore: Change of reversal flow to clear the reversal only at settlement
 *  2.2.16  20260108    feat: Enhance ISO Comm to close the connection aggressively when timeout or reversal
 *                      chore: Add last reversal in setting and run last reversal again before sales
 *                      feat: revamp read EMV code structure
 *                      feat: revamp dbhandler and add auto heal for field missing
 *  2.2.17  20260313    chore: Fix MyDebit preauth reversal logic
 *                      chore: Modify Fiuu logo as per CR-300126-2
 *                      feat: Add Connection Status & change HomeBtn into AppInfo btn
 *                      chore: Fix integration issue EMV read not end after session terminated
 *                      chore: Fix missing Fiuu Logo for Android 7
 *                      feat: Show ScMid instead of AcqMid in AppInfo for TPA
 *                      chore: autosettlement enhancement add TransactionTransmitter ctx & comment kill process
 *                      chore: fix AppService not running for AppToApp
 *  2.2.18  20260428    feat: SR800 Development and Upgrade android gradle
 *                      chore: Revamp App Services and remove isoComm special exception handling
 *                      chore: Enhance Inject key and secureData Management
 *                      chore: Oxpay CR of special navigation based on selection for ffastpay and oxpaylite
 *                      chore: Enhance and standardize of oxpay and xendit generation
 *                      chore: Fix Attend Quick Action not checking on payment disabled
 *                      chore: Fix Last Settlement Decimal showing issue
 *                      feat: Revamp Last Settlement Logic and LastSettlementRecovery
 *                      chore: Enhance Inject and Truncate of Secure Data
 *                      chore: OxPay Usb Reset Comm Port
 *  2.2.19  20260526    chore: USB and RS232 usb integration for SR800
 *                      chore: AboutActivity Sim and Wifi Button
 *                      chore: Enhance AutoSettlement in Hibernate mode
 *                      chore: Modify Settlement Footer label and wording
 *                      chore: Modify and Limit Auto Settlement timeframe to one hour
 *                      chore: Fix AutoSettlement Recovery Check break if manual settlement
 *                      chore: Fix MID and TID for TPA to POS integration
 * 2.2.20   20260604    chore: emergency quick remove cable health check for EPSB
 * 2.2.21   20260622    chore: fix void fetch wrong product model for same mid and tid
 *                      chore: Denomination Websocket and development
 *                      chore: Enhance last settlement display sequence
 *                      chore: Remove mid and tid in Ewallet Receipt
 *                      chore: Configure Android Manifest Screen no History
 *                      chore: Fix EMSB UnAttend Cancellation problem
 *                      chore: Remove MM Logo if MerchantConfig Remove
 *                      chore: Fix EMSB UnAttend Cancel Payment Bug
 * 2.2.22   20260717    feat: Add New Blocking for Sales if settlement triggered for BSN
 *                      chore: Fix Paydee MOTO pos condition code and BF63 crash
 *                      chore: Do for dynamic changing of host config and tpdu nii in admin setting
 *                      feat: Revamp log base to reduce cpu stress
 *                      chore: Fix Settlement DB state corrupt when relaunch app in the middle of settlement
 *                      chore: ServiceHolder and Context Enhance
 *                      chore: Migrate Loading Dialog from AlertDialog to ProgressDialogFragment
 *                      chore: Migrate ISOdb to DbHandler and set WAL database
 *                      chore: Enhance MDB and Revamp MDB to Base
 * 2.2.23   20260722    chore: remove denominationTruncate due to crash issue
 * 2.2.24   20260724    chore: Uncomment CableHealthCheck and exclude for paydibs only
 * 2.2.25   20260806    chore: Fix EPP OPTION not showing
 *                      chore: Remove WAL Database
 *                      chore: UI synchronization
 * 2.2.26   20260827    chore: Add ZS error code
 *                      chore: Fix Truncation Crash for Table Missing in DB
 *                      chore: Enhance NanoHttpD ContentLength issue and causing packet drop
 *                      chore: Enhance Mdb Level 1 Flow
 *                      chore: Fix Reversal Send 37 38 only for Adjustment
 *                      chore: Hide Adjustment and Sales Completion Card Presented UI
 *                      chore: Hide Print Button in UnAttend Mode
 *                      chore: Default AppLabel first to prevent empty app label from card
 *                      chore: Fix DF37 38 39 Cache Issue
 *                      Chore: Fix About Activity Network Connectivity Panel
 *                      chore: HelperLog add dual sink and reduce queue time
 *                      chore: Crash Handler Enhance and restart app flow
 *                      chore: YSDK jar file update to 6.14.04
 *                      chore: Refuse ECR request while app fresh start is running
 *                      chore: Fix ECR response sent to wrong caller across HTTP and cable
 *                      chore: Serve one ECR request at a time and bound the response wait
 *                      chore: Amalinsya L1 Auto Session Supervisor and 1303 in QR
 */
```
