package helpers

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.*

/*
* Reference : https://blog.cpming.top/p/httpurlconnection-multipart-form-data
* */

class HelperHttpMultipart {
    private lateinit var _apiUrl: String
    private lateinit var _charset: String
    private var _httpHeaders: Map<String, String>? = null


    private val _line = "\r\n"
    private var boundary: String? = null
    private var httpConn: HttpURLConnection? = null
    private var outputStream: OutputStream? = null
    private var writer: PrintWriter? = null


    fun init(apiUrl: String, charset: String, httpHeaders: Map<String, String>? = null) {
        this._apiUrl = apiUrl
        this._charset = charset
        this._httpHeaders = httpHeaders

        boundary = UUID.randomUUID().toString()

        val url = URL(_apiUrl)
        httpConn = url.openConnection() as HttpURLConnection
        httpConn!!.requestMethod = "POST"
        httpConn!!.useCaches = false
        httpConn!!.doOutput = true // indicates POST method
        httpConn!!.doInput = true
        httpConn!!.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")


        if (_httpHeaders != null) {
            for (httpHeader in _httpHeaders!!) {
                httpConn!!.setRequestProperty(httpHeader.key, httpHeader.value)
            }
        }

        outputStream = httpConn!!.outputStream
        writer = PrintWriter(OutputStreamWriter(outputStream, _charset), true)
    }



    fun addFormField(name: String, value: String?) {
        writer?.append("--$boundary")?.append(_line)
        writer?.append("Content-Disposition: form-data; name=\"$name\"")?.append(_line)
        writer?.append("Content-Type: text/plain; charset=$_charset")?.append(_line)
        writer?.append(_line)
        writer?.append(value)?.append(_line)
        writer?.flush()
    }



    fun addFilePart(fieldName: String, uploadFile: File) {
        val fileName: String = uploadFile.name
        writer?.append("--$boundary")?.append(_line)
        writer?.append("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"")?.append(_line)
        writer?.append("Content-Type: " + URLConnection.guessContentTypeFromName(fileName))?.append(_line)
        writer?.append("Content-Transfer-Encoding: binary")?.append(_line)
        writer?.append(_line)
        writer?.flush()
        val inputStream = FileInputStream(uploadFile)
        val buffer = ByteArray(size = 4096)
        var bytesRead = -1
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream?.write(buffer, 0, bytesRead)
        }
        outputStream?.flush()
        inputStream.close()
        writer?.append(_line)
        writer?.flush()
    }



    fun finish(): String? {
        val response: String?
        writer?.flush()
        writer?.append("--$boundary--")?.append(_line)
        writer?.close()

        // checks server's status code first
        val status: Int = httpConn!!.responseCode
        if (status == HttpURLConnection.HTTP_OK) {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while (httpConn!!.inputStream.read(buffer).also { length = it } != -1) {
                result.write(buffer, 0, length)
            }
            response = result.toString(_charset)
            httpConn!!.disconnect()
        } else {
            throw IOException("($status) -> ${httpConn!!.responseMessage}")
        }
        return response
    }
}