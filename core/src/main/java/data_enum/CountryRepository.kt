package data_enum

object CountryRepository {
    val countries: List<Country> = listOf(
        Country("Malaysia", "MY", "60"),
        Country("Singapore", "SG", "65"),
        Country("Indonesia", "ID", "62")
    )

    fun getDefault(): Country {
        return countries.first()
    }

    fun findByIso(iso: String): Country? {
        return countries.find { it.iso == iso }
    }

    fun findByDialCode(code: String): Country? {
        return countries.find { it.dialCode == code }
    }
}
