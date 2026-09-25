# Changelog — MF919 Pro (`com.sc.mf919pro`)

Extracted verbatim from the comment block that headed `app/build.gradle.kts` before the Phase 0 restructure.

```
/*
 * 1.0.0    20260518    Initial Version For MF919 Pro
 * 1.0.01   20260526    chore: Add Most of the HelperLog in the button and screen
 *                      chore: Add OrderingItem and OrderingItemImage in Receipt Upload and POS
 *                      chore: Quick Fix of Auto Settlement Recovery loop hole for manual settlement
 * 1.0.02   20260622    chore: Fix void option fetch wrong product model for same mid tid
 *                      chore: Comment out MyDebit CAPK for Finexus for public bank card
 *                      chore: Hide QR Receipt detail (MID,TID, BANK AUTH CODE)
 *                      chore: Enhance AppToApp EnquiryUseCase
 *                      chore: Add on HelperLog to Admin, and button function
 *                      chore: Revamp HelperLog Base logic to reduce CPU usage
 *                      feat: Enhance of delete Receipt/MM logo from MerchantConfig
 *                      chore: Merge SettlementFragment and SettlePreviewFragment to remove redundant code
 *                      feat: Dynamic printing font size in terminal level
 *                      chore: Fix Synchronized printing race condition issue
 * 1.0.03   20260717    feat: Add New Blocking for Sales if settlement triggered for BSN
 *                      chore: Fix Paydee MOTO pos condition code and BF63 crash
 *                      chore: Migrate of AlertDialog to DialogFragment
 *                      chore: Revamp and enhance HttpServer, WebSocketServer performance and stability
 *                      chore: Add Manual Configuration Modification in AdminFragment for Ip,Port and ....
 *                      chore: Fix of Battery Saver cause app and wifi idle in Android 13
 *                      chore: Revamp and Cleanup Old DB handler
 * 1.0.04   20260817    chore: Fix Wi-Fi Setting lock Screen issue
 *                      chore: Fix UI Glitch Issue, and Model Info In App Info
 *                      chore: Harden DB durability, Storage exhaustion and sensitive information logging
 *                      chore: Fix TransData Race Condition and corruption
 *                      chore: Enhance NanoHttpD Read big packet network corrupt issue
 *                      chore: FoodLink Customization and Integration
 *                      chore: Fix DF37 38 39 Cache Issue
 * 1.0.05   2026xxxx    feat: Add BYPASS_PIN in TerminalConfig to handle from Portal, gate the
 *                      feat: empty-PIN entry on it, and add ZQ error code for PIN Not Entered
*/
```
