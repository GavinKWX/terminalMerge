package env

import helpers.TerminalInfo

import android.content.SharedPreferences
import kotlin.reflect.KProperty1

class EnvironmentManager(
    // We use shared prefs to save currently selected env between app launches
    private val store: SharedPreferences
) {
    // This is a key for saving env in prefs
    private val currentEnvironmentKey = "current_environment"

    /*
    * Here we need to describe all our strings
    * for all of the supported environments.
    * I use mapping `Env -> EnvVars`, but you can use anything
    * (database, files, etc)
    */

    private val parameters: Map<Environment, EnvironmentVariables> = mapOf(
        Environment.DEVELOPMENT to EnvironmentVariables(
            serverHashKey = "Zb939D3UdqexaJR2",
            baseUrl = "https://devterminal.share-commerce.com/api/terminal/",
            signalRUrl = "https://devterminal.share-commerce.com/scwssR",
            nfcAesKey = "ONdto9FF2iM2W9jN",
            nfcUrl = "devreceipt.share-commerce.com/nfc-receipt/receipt?",
            socketServerHashKey = "DMU9Iw1Oh3GaWDkcIjYNbMBj0O86oqiY",
            socketHandlerUrl = "wss://devterminalws.share-commerce.com",
            simpleUrl = "https://devterminal.share-commerce.com/"
        ),
        Environment.STAGING to EnvironmentVariables(
            serverHashKey = "Zb939D3UdqexaJR2",
            baseUrl = "https://stagingterminal.share-commerce.com/api/terminal/",
            signalRUrl = "https://stagingterminal.share-commerce.com/scwssR",
            nfcAesKey = "ONdto9FF2iM2W9jN",
            nfcUrl = "stagingreceipt.share-commerce.com/nfc-receipt/receipt?",
            socketServerHashKey = "DMU9Iw1Oh3GaWDkcIjYNbMBj0O86oqiY",
            socketHandlerUrl = "wss://stagingterminalws.share-commerce.com",
            simpleUrl = "https://stagingterminal.share-commerce.com/"
        ),
        Environment.PRODUCTION to EnvironmentVariables(
            serverHashKey = "9D3xaJR2UdqZb93e",
            baseUrl = "https://terminal.share-commerce.com/api/terminal/",
            signalRUrl = "https://terminal.share-commerce.com/scwssR",
            nfcAesKey = "ONdto9FF2iM2W9jN",
            nfcUrl = "receipt.share-commerce.com/nfc-receipt/receipt?",
            socketServerHashKey = "DMU9Iw1Oh3GaWDkcIjYNbMBj0O86oqiY",
            socketHandlerUrl = "wss://terminalws.share-commerce.com",
            simpleUrl = "https://terminal.share-commerce.com/"
        )
    )

    /*
    * Here is an interesting part.
    * We use `currentEnvironment` variable to store and retrieve current env from
    * SharedPreferences and decide which variables to pull depending on Environment.
    *
    * We will explore `TerminalInfo.defaultEnvId()` later on.
    */
    var currentEnvironment: Environment
        get() {
            val default = Environment.create(TerminalInfo.defaultEnvId())
            val current = store.getString(currentEnvironmentKey, null)?.let {
                Environment.create(it)
            }
            return current ?: default
        }
        set(value) {
            store.edit().putString(currentEnvironmentKey, value.id).apply()
        }

    // This one allows to pull desired variable just by property reference
    fun get(property: KProperty1<EnvironmentVariables, String>): String {
        val params = parameters[currentEnvironment]
            ?: throw Exception("Unsupported ENV $currentEnvironment")
        return property.get(params)
    }

    fun getByJava(environmentName: String): String {
        val params =
            parameters[currentEnvironment] ?: throw Exception("Unsupported ENV $currentEnvironment")

        val variableProperty: KProperty1<EnvironmentVariables, String> = when (environmentName) {
            "baseUrl" -> EnvironmentVariables::baseUrl
            "signalRUrl" -> EnvironmentVariables::signalRUrl
            "serverHashKey" -> EnvironmentVariables::serverHashKey
            else -> throw Exception("Invalid EnvironmentVariables")
        }

        return get(variableProperty)
    }
}