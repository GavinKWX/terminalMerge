package tms.models

data class WebSocketClientReq(
	val RequestRef: String,
	val TerminalSN: String,
	val Command: String,
	val RequestBase64: String?,
	val Checksum: String
)

/*<----------------------------------------------------------------->*/
data class WebSocketClientResp(
	val Event: String?,
	val Data: Any?
)
