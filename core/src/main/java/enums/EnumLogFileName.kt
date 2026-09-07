package enums

//id is unsued, previous mistake
// D6 — retention counts. AsyncLogWriter reads maximumFile from THIS enum (maxBackupsFor), so
// this is the single source of truth. The former helper_common.HelperLogFileName duplicate was
// retired in Phase 1b and is now a @Deprecated typealias onto this enum.
enum class EnumLogFileName(val id : String, val maximumFile: Int) {
    TerminaLog("TerminalLog", 10),
    TerminaLogException("TerminalLog_Exception", 1),
    TerminaDbException("TerminalDb_Exception", 1);
}