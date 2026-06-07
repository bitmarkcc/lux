package acr.browser.lightning.certimark

enum class CertimarkStatus {
    MATCH_TOP,      // Cert matches the top-weighted marked cert
    MATCH_OTHER,    // Cert matches a non-top-weighted marked cert
    NO_MATCH,       // Domain is marked but cert doesn't match any
    NOT_MARKED,     // Domain has no marked certificates
    ERROR,          // Could not check
    HTTP_INSECURE,  // Plain HTTP connection (no TLS)
    ONION_SECURE    // Tor onion address (cryptographically secure by design)
}

data class CertEntry(
    val hashType: String,
    val hashHex: String,
    val weight: Long,
    val certUrl: String?
)

data class CertimarkResult(
    val status: CertimarkStatus,
    val domain: String,
    val browserCertHash: String? = null,
    val matchedCert: CertEntry? = null,
    val matchIndex: Int = -1,
    val keyMatch: Boolean = false,
    val certs: List<CertEntry> = emptyList(),
    val description: String? = null,
    val errorMessage: String? = null
)
