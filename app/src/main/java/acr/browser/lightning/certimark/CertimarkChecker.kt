package acr.browser.lightning.certimark

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Checks SSL certificates against the Certimark API (Bitmark blockchain).
 */
class CertimarkChecker(
    private val okHttpClient: OkHttpClient,
    private val apiUrl: String = "https://certimark.cc",
    private val apiKeyPin: String? = null,
    private val proxy: java.net.Proxy? = null
) {

    private val cache = mutableMapOf<String, CachedResult>()
    private val cacheTtl = 5 * 60 * 1000L // 5 minutes

    /**
     * OkHttpClient for API calls with optional proxy and public key pinning.
     */
    private val apiClient: OkHttpClient by lazy {
        val builder = okHttpClient.newBuilder()

        if (proxy != null) {
            builder.proxy(proxy)
        }

        if (apiKeyPin != null && apiUrl.startsWith("https", ignoreCase = true)) {
            builder.addNetworkInterceptor(Interceptor { chain ->
                val connection = chain.connection()
                val certs = connection?.handshake()?.peerCertificates
                if (certs.isNullOrEmpty()) {
                    throw SecurityException("API server presented no certificates")
                }
                val cert = certs[0] as? X509Certificate
                    ?: throw SecurityException("API server certificate is not X.509")
                val serverKeyHash = sha256Hex(cert.publicKey.encoded)
                if (serverKeyHash != apiKeyPin) {
                    throw SecurityException(
                        "API server public key mismatch. Expected: $apiKeyPin, got: $serverKeyHash"
                    )
                }
                chain.proceed(chain.request())
            })
        }

        builder.build()
    }

    private data class CachedResult(
        val result: CertimarkResult,
        val timestamp: Long
    )

    /**
     * Check an onion domain. No TLS check needed — the onion address is the identity.
     * Queries the API only to fetch the description.
     * Must be called from a background thread.
     */
    fun checkOnion(domain: String): CertimarkResult {
        val cached = cache[domain]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < cacheTtl) {
            return cached.result
        }

        val result = try {
            val apiData = queryApi(domain)
            CertimarkResult(
                status = CertimarkStatus.ONION_SECURE,
                domain = domain,
                description = apiData?.description
            )
        } catch (_: Exception) {
            CertimarkResult(
                status = CertimarkStatus.ONION_SECURE,
                domain = domain
            )
        }

        cache[domain] = CachedResult(result, System.currentTimeMillis())
        return result
    }

    /**
     * Check the certificate for the given domain.
     * Makes a separate TLS connection to get the cert, then queries the API.
     * Must be called from a background thread.
     */
    fun check(domain: String): CertimarkResult {
        // Check cache
        val cached = cache[domain]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < cacheTtl) {
            return cached.result
        }

        val result = try {
            doCheck(domain)
        } catch (e: Exception) {
            CertimarkResult(
                status = CertimarkStatus.ERROR,
                domain = domain,
                errorMessage = e.message
            )
        }

        cache[domain] = CachedResult(result, System.currentTimeMillis())
        return result
    }

    private fun doCheck(domain: String): CertimarkResult {
        // Step 1: Connect to domain and get certificate
        val certInfo = fetchCertificate(domain)
            ?: return CertimarkResult(
                status = CertimarkStatus.ERROR,
                domain = domain,
                errorMessage = "Could not retrieve certificate"
            )

        // Step 2: Query Certimark API
        val apiData = queryApi(domain)
            ?: return CertimarkResult(
                status = CertimarkStatus.ERROR,
                domain = domain,
                browserCertHash = certInfo.fingerprint,
                errorMessage = "Could not reach Certimark API"
            )

        // Not marked
        if (!apiData.marked || apiData.certs.isEmpty()) {
            return CertimarkResult(
                status = CertimarkStatus.NOT_MARKED,
                domain = domain,
                browserCertHash = certInfo.fingerprint,
                description = apiData.description
            )
        }

        // Step 3: Compare fingerprints
        var matchIndex = -1
        for (i in apiData.certs.indices) {
            if (apiData.certs[i].hashHex == certInfo.fingerprint) {
                matchIndex = i
                break
            }
        }

        // Step 4: If no hash match, try public key comparison
        var keyMatch = false
        if (matchIndex == -1) {
            for (i in apiData.certs.indices) {
                val certUrl = apiData.certs[i].certUrl ?: continue
                try {
                    val originalCert = downloadCert(certUrl) ?: continue
                    if (certInfo.spkiSha256 != null && certInfo.spkiSha256 == computeSpkiSha256(originalCert)) {
                        matchIndex = i
                        keyMatch = true
                        break
                    }
                } catch (_: Exception) {
                    // skip this cert
                }
            }
        }

        val status = when {
            matchIndex == 0 -> CertimarkStatus.MATCH_TOP
            matchIndex > 0 -> CertimarkStatus.MATCH_OTHER
            else -> CertimarkStatus.NO_MATCH
        }

        return CertimarkResult(
            status = status,
            domain = domain,
            browserCertHash = certInfo.fingerprint,
            matchedCert = if (matchIndex >= 0) apiData.certs[matchIndex] else null,
            matchIndex = matchIndex,
            keyMatch = keyMatch,
            certs = apiData.certs,
            description = apiData.description
        )
    }

    private data class CertInfo(
        val fingerprint: String,  // lowercase hex SHA-256 of DER cert
        val spkiSha256: String?,  // lowercase hex SHA-256 of SPKI
        val cert: X509Certificate
    )

    private fun fetchCertificate(domain: String): CertInfo? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = if (proxy != null) {
            val rawSocket = Socket(proxy)
            rawSocket.connect(InetSocketAddress.createUnresolved(domain, 443), 10000)
            factory.createSocket(rawSocket, domain, 443, true) as SSLSocket
        } else {
            factory.createSocket(domain, 443) as SSLSocket
        }
        try {
            socket.soTimeout = 10000
            socket.startHandshake()
            val certs = socket.session.peerCertificates
            if (certs.isEmpty()) return null
            val cert = certs[0] as? X509Certificate ?: return null

            val derBytes = cert.encoded
            val fingerprint = sha256Hex(derBytes)
            val spkiSha256 = sha256Hex(cert.publicKey.encoded)

            return CertInfo(fingerprint, spkiSha256, cert)
        } finally {
            socket.close()
        }
    }

    private data class ApiResponse(
        val marked: Boolean,
        val certs: List<CertEntry>,
        val description: String?
    )

    private fun queryApi(domain: String): ApiResponse? {
        val url = "$apiUrl/check?domain=${java.net.URLEncoder.encode(domain, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        val response = apiClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        val json = JSONObject(response.body?.string() ?: return null)
        if (json.has("error")) return null

        val marked = json.optBoolean("marked", false)
        val description = json.optString("description", null)
        val certsArray = json.optJSONArray("certs") ?: return ApiResponse(marked, emptyList(), description)

        val certs = mutableListOf<CertEntry>()
        for (i in 0 until certsArray.length()) {
            val c = certsArray.getJSONObject(i)
            certs.add(
                CertEntry(
                    hashType = c.optString("hash_type"),
                    hashHex = c.optString("hash_hex"),
                    weight = c.optLong("weight", 0),
                    certUrl = c.optString("cert_url", null)
                )
            )
        }

        return ApiResponse(marked, certs, description)
    }

    private fun downloadCert(url: String): X509Certificate? {
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        val bytes = response.body?.bytes() ?: return null
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")

        // Try DER first, then PEM
        return try {
            factory.generateCertificate(java.io.ByteArrayInputStream(bytes)) as X509Certificate
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSpkiSha256(cert: X509Certificate): String {
        return sha256Hex(cert.publicKey.encoded)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
