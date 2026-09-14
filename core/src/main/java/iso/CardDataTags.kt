package iso

enum class CardTagsEnum {
    UNKNOWN,
    GOBIZ(
        arrayOf("50", "5F2A", "5F34", "82", "84", "95", "9A", "9C", "9F02", "9F03", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37", "9F6E"),
        arrayOf("50", "71", "72", "82", "84", "91", "95", "9A", "9C", "5F2A", "5F30", "5F34", "9F02", "9F03", "9F06", "9F09", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F28", "9F29", "9F33", "9F34", "9F35", "9F36", "9F37", "9F41", "9F53", "9F6E", "DF31"),
        arrayOf("50", "5F2A", "5F34", "82", "84", "95", "9A", "9C", "9F02", "9F03", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F33", "9F35", "9F36", "9F37", "9F6E"),
        null
    ),
    PAYDEE(
        arrayOf("50", "5F2A", "5F34", "82", "84", "95", "9A", "9C", "9F02", "9F03", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37", "9F6E"),
        arrayOf("50", "71", "72", "82", "84", "91", "95", "9A", "9C", "5F2A", "5F30", "5F34", "9F02", "9F03", "9F06", "9F09", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F28", "9F29", "9F33", "9F34", "9F35", "9F36", "9F37", "9F41", "9F53", "9F6E", "DF31"),
        arrayOf("50", "5F2A", "5F34", "82", "84", "95", "9A", "9C", "9F02", "9F03", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F33", "9F35", "9F36", "9F37", "9F6E"),
        null
    ),
    BSN(
        //arrayOf("57", "82", "84", "95", "9A", "9C", "5F2A", "5F34", "9F02", "9F03", "9F10", "9F1A", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37", "9F6E"),
        arrayOf("82", "84", "95", "9A", "9C", "5F2A", "5F34", "9F02", "9F03", "9F10", "9F1A", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37", "9F6E"),
        arrayOf("71", "72", "82", "84", "91", "95", "9A", "9C", "5F2A", "5F34", "9F02", "9F03", "9F09", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F28", "9F29", "9F33", "9F34", "9F35", "9F36", "9F37", "9F41", "9F53", "DF31"),
        null,
        arrayOf("82", "84", "95", "9A", "9C", "5F2A", "9F02", "9F03", "9F10", "9F1A", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37"),
    ),
    FINEXUS(
        arrayOf("50", "5F2A", "5F34", "82", "84", "95", "9A", "9C", "9F02", "9F03", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37", "9F6E"),
        arrayOf("50", "71", "72", "82", "84", "91", "95", "9A", "9C", "5F2A", "5F30", "5F34", "9F02", "9F03", "9F06", "9F09", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F28", "9F29", "9F33", "9F34", "9F35", "9F36", "9F37", "9F41", "9F53", "9F6E", "DF31"),
        null,
        //arrayOf("50", "5F2A", "5F34", "82", "84", "95", "9A", "9C", "9F02", "9F03", "9F10", "9F1A", "9F1E", "9F26", "9F27", "9F33", "9F35", "9F36", "9F37"),
        arrayOf("82", "84", "95", "9A", "9C", "5F2A", "9F02", "9F03", "9F10", "9F1A", "9F26", "9F27", "9F33", "9F34", "9F35", "9F36", "9F37", "9F6E"),
    );

    var visaChipTags: Array<String>?
    var masterChipTags: Array<String>?
    var upiChipTags: Array<String>?
    var mccsChipTags: Array<String>?
    constructor(visaTag: Array<String>?, masterTag: Array<String>?, upiTag: Array<String>?, mccsTag: Array<String>?) {
        this.visaChipTags = visaTag
        this.masterChipTags = masterTag
        this.upiChipTags = upiTag
        this.mccsChipTags = mccsTag
    }
    constructor() {
        this.visaChipTags = null
        this.masterChipTags = null
        this.upiChipTags = null
        this.mccsChipTags = null
    }

    companion object{
        fun getAcquirerChipTags(acquirer: String, schemeType: String): Array<String>? {
            val acquirer =  when(acquirer.uppercase()){
                "GOBIZ" -> GOBIZ
                "FINEXUS" -> FINEXUS
                "PAYDEE" -> PAYDEE
                "BSN" -> BSN
                "BSN_CARDZONE" -> BSN
                else -> UNKNOWN
            }

            return when (schemeType.uppercase()){
                "VISA" -> acquirer.visaChipTags
                "MASTER" -> acquirer.masterChipTags
                "PBOC" -> acquirer.upiChipTags
                "MCCS" -> acquirer.mccsChipTags
                else -> null
            }
        }
    }
}