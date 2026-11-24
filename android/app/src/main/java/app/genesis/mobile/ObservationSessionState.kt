package app.genesis.mobile

/** In-process, short-lived credentials for one explicitly started observation session. */
object ObservationSessionState {
    @Volatile var endpoint: String? = null
    @Volatile var sessionId: String? = null
    @Volatile var packageName: String? = null
    @Volatile var token: String? = null

    fun configure(endpoint: String, sessionId: String, packageName: String, token: String) {
        this.endpoint = endpoint.trimEnd('/')
        this.sessionId = sessionId
        this.packageName = packageName
        this.token = token
    }

    fun clear() {
        token = null
        sessionId = null
        packageName = null
        endpoint = null
    }
}
