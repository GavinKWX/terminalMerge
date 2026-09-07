package tms.handlers

import helpers.TerminalInfo
import helpers.HelperDate

import com.google.gson.Gson
import env.EnvironmentManager
import env.EnvironmentVariables
import enums.EnumDateFormat
import helpers.HelperHttpMultipart
import helpers.HelperLog
import tms.models.UploadLogResponseModel
import java.io.File
import java.io.IOException

class OldUploadLogHandler(private val envManager: EnvironmentManager) {
    private val className: String = (OldUploadLogHandler::class.qualifiedName).toString()

    @Throws(IOException::class)
    fun invoke(
        log: HelperLog,
        seqNum: String,
        terminalSN: String,
        uploadFile: File
    ): UploadLogResponseModel {
        val httpHeaders: MutableMap<String, String> = mutableMapOf()
        httpHeaders["DEV-SN"] = TerminalInfo.serialNumber()
        httpHeaders["APP-VER"] = TerminalInfo.appVersion()
        httpHeaders["TERMINAL-DT"] = HelperDate.getDateString(
            EnumDateFormat.yyyyMMddHHmmss.dateFormat
        )

        val apiUrl = "${envManager.get(EnvironmentVariables::baseUrl)}tms/UploadLog"

        try {
            val gson = Gson()
            val multipart = HelperHttpMultipart()
            multipart.init(apiUrl, "utf-8", httpHeaders)

            //Add form data
            multipart.addFormField("SEQ_NO", seqNum)
            multipart.addFormField("DEV_SN", terminalSN)

            //Add files data
            multipart.addFilePart("FILE", uploadFile)

            val resp = multipart.finish()
            log.appendLine(className, "response -> $resp")
            val gsonResp = gson.fromJson(resp, UploadLogResponseModel::class.java)
            return gsonResp
        }catch (ex: Exception){
            log.appendLine(className, "$className (Exception)", ex.toString())
            throw IOException(ex.message)
        }
    }
}