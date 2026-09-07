package env

data class EnvironmentVariables(
        val serverHashKey: String,
        val baseUrl: String,
        val signalRUrl: String,
        val nfcAesKey: String,
        val nfcUrl: String,
        val socketServerHashKey: String,
        val socketHandlerUrl: String,
        val simpleUrl: String
)
