package tms.handlers

import helpers.TerminalInfo
import helpers.HelperDate
import helpers.HelperCrypto

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumDateFormat
import helpers.HelperHttp
import helpers.HelperLog
import tms.models.FoodLinkVerificationRequestModel
import tms.models.FoodLinkVerificationResponseModel
import java.io.IOException

class FoodLinkVerificationHandler(private val envManager: EnvironmentManager) {
    private val className: String = (FoodLinkVerificationHandler::class.qualifiedName).toString()

    @Throws(IOException::class)
    fun invoke(
        log: HelperLog,
        correlationRef: String,
        systemMID: String,
        systemTID: String,
        phoneCountryCode: String,
        phoneNo: String,
        txnAmt: String,
    ): FoodLinkVerificationResponseModel {
        val gson = Gson()
        val foodLinkVerificationRequestModel = FoodLinkVerificationRequestModel(
            correlationRef, systemMID, systemTID,
            phoneCountryCode, phoneNo, txnAmt)

        val requestJsonString = gson.toJson(foodLinkVerificationRequestModel)
        val httpHeaders: MutableMap<String, String> = mutableMapOf()
        httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
        httpHeaders["APP-VER"] = TerminalInfo.appVersion()
        httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(
            EnumDateFormat.yyyyMMddHHmmss.dateFormat
        )
        httpHeaders["CHECKSUM"] = HelperCrypto.toHmacSha256(
            requestJsonString, envManager.get(EnvironmentVariables::serverHashKey)
        )

        val resp = HelperHttp.invokeSend(
            log,
            apiUrl = envManager.get(EnvironmentVariables::simpleUrl) + "v1/verification/membership",
            postData = requestJsonString,
            httpHeaders = httpHeaders
        )

        try {
            val gsonResp = gson.fromJson(resp, FoodLinkVerificationResponseModel::class.java)
            val respMsg = gsonResp?.msg

            if (!respMsg.equals("success", true)) {
                throw IOException(resp)
            }
            return gsonResp
        } catch (ex: Exception) {
            log.appendLine(className, "$className (Exception)", ex.toString())
            try {
                JsonParser.parseString(ex.message)
                throw IOException(ex.message)
            } catch (e: JsonSyntaxException) {
                throw IOException("{\"msg\":\"failed\", \"data\":\"${resp}\"}")
            }
        }
    }
}
