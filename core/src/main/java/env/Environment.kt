package env

enum class Environment(val id: String) {
    DEVELOPMENT("development"),
    STAGING("staging"),
    PRODUCTION("production");

    companion object {
        fun create(env: String): Environment =
                when (env) {
                    "development" -> DEVELOPMENT
                    "staging" -> STAGING
                    "production" -> PRODUCTION
                    else -> throw Exception("Unsupported environment $env")
                }
    }
}