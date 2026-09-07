package enums

class EnumCryptoCipher {

    enum class AesTransformation(val value: String) {
        AES_ECB_PKCS7Padding("AES/ECB/PKCS7Padding")
    }
}