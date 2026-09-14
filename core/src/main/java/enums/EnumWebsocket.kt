package enums

//id is unsued, previous mistake
enum class EnumWebsocket(val socketCommand : String) {
    //Socket Handler
    ValidateSale("ValidateSales"),
    Signon("SignOnService"),
    // Manual token dispense. PRODUCTION SENDS THIS -- observed 2026-09-11 addressed to the
    // terminal by TerminalSN, carrying a real payload (amount, package GUIDs) -- and neither app
    // has a handler, so it is dropped with an "unhandled command" log line.
    //
    // Before implementing it: the dispense payload names a DeviceSerialNo, and nothing in
    // onMessage checks that a message is addressed to this terminal. Acting on a dispense
    // command without that check dispenses on someone else's instruction if routing ever slips.
    // See docs/merge-audit-mf919.md item 57.
    TerminalDMDispense("TerminalDMDispense"),
    UpdatePrice("UpdatePrice"),
}