// Description: Thanos Querier client library for LogicMonitor DataSources.
// Description: Provides HTTP communication, authentication, and response parsing for Prometheus/Thanos API.
// v4

import groovy.json.JsonSlurper

/**
 * ThanosClient - Handles communication with Thanos Querier API
 *
 * Usage in LogicMonitor scripts:
 *   def client = new ThanosClient(hostProps)
 *   def result = client.query("kubevirt_vmi_phase_count")
 */
class ThanosClient {

    private String host
    private String token
    private int port
    private boolean ssl
    private String baseUrl
    private boolean debug

    /**
     * Initialize ThanosClient from LogicMonitor hostProps
     *
     * Required properties:
     *   - kubevirt.thanos.host: Thanos Querier hostname
     *   - kubevirt.thanos.token: Bearer token for authentication
     *
     * Optional properties:
     *   - kubevirt.thanos.port: Port number (default: 443)
     *   - kubevirt.thanos.ssl: Use HTTPS (default: true)
     *   - kubevirt.thanos.debug: Enable debug output (default: false)
     */
    ThanosClient(Map hostProps) {
        this.host = hostProps.get("kubevirt.thanos.host")
        this.token = hostProps.get("kubevirt.thanos.token")
        this.port = (hostProps.get("kubevirt.thanos.port") ?: "443") as int
        this.ssl = (hostProps.get("kubevirt.thanos.ssl") ?: "true").toLowerCase() == "true"
        this.debug = (hostProps.get("kubevirt.thanos.debug") ?: "false").toLowerCase() == "true"

        if (!this.host) {
            throw new IllegalArgumentException("kubevirt.thanos.host property is required")
        }
        if (!this.token) {
            throw new IllegalArgumentException("kubevirt.thanos.token property is required")
        }

        this.baseUrl = buildUrl(this.host, this.port, this.ssl)
    }

    /**
     * Build base URL from components
     */
    private String buildUrl(String host, int port, boolean ssl) {
        def protocol = ssl ? "https" : "http"
        def portSuffix = ((ssl && port == 443) || (!ssl && port == 80)) ? "" : ":${port}"
        return "${protocol}://${host}${portSuffix}"
    }

    /**
     * Execute an instant query against Thanos Querier
     *
     * Returns the raw parsed JSON response, or null on error
     */
    def query(String promql) {
        def encodedQuery = java.net.URLEncoder.encode(promql, "UTF-8")
        def url = "${baseUrl}/api/v1/query?query=${encodedQuery}"

        if (debug) {
            println "DEBUG: Querying URL: ${url}"
        }

        try {
            def response = executeHttpGet(url)
            def json = new JsonSlurper().parseText(response)

            if (json.status != "success") {
                System.err.println "ERROR: Thanos query failed - ${json.error ?: 'Unknown error'}"
                return null
            }

            if (debug) {
                println "DEBUG: Query returned ${json.data?.result?.size() ?: 0} results"
            }

            return json

        } catch (Exception e) {
            System.err.println "ERROR: Failed to query Thanos - ${e.message}"
            if (debug) {
                e.printStackTrace()
            }
            return null
        }
    }

    /**
     * Execute a query and return results as a list of [labels, value] pairs
     *
     * Useful for iterating over metric results:
     *   client.queryWithLabels("kubevirt_vmi_phase_count").each { labels, value ->
     *       println "${labels.namespace}/${labels.name} = ${value}"
     *   }
     */
    List queryWithLabels(String promql) {
        def json = query(promql)
        if (!json || !json.data?.result) {
            return []
        }

        return json.data.result.collect { result ->
            def labels = result.metric ?: [:]
            def value = result.value ? result.value[1] : null
            [labels, value]
        }
    }

    /**
     * Execute a query and return results grouped by a composite key
     *
     * Useful for BATCHSCRIPT output:
     *   client.queryGroupedByKey("kubevirt_vmi_memory_available_bytes", ["namespace", "name"]).each { key, value ->
     *       println "${key}.memory_available_bytes=${value}"
     *   }
     */
    Map queryGroupedByKey(String promql, List<String> keyLabels, String separator = "/") {
        def results = [:]

        queryWithLabels(promql).each { labels, value ->
            def keyParts = keyLabels.collect { labels[it] ?: "" }
            if (keyParts.every { it }) {
                def key = keyParts.join(separator)
                results[key] = value
            }
        }

        return results
    }

    /**
     * Execute HTTP GET request with bearer token authentication
     *
     * This method attempts to use LogicMonitor's HTTP helper when available,
     * falling back to standard Java HTTP for standalone testing.
     */
    private String executeHttpGet(String url) {
        try {
            // Try LogicMonitor HTTP helper first
            def httpClass = Class.forName("com.santaba.agent.groovyapi.http.HTTP")
            def http = httpClass.newInstance()
            http.open(url)
            http.setHeader("Authorization", "Bearer ${token}")
            def response = http.get()
            http.close()
            return response
        } catch (ClassNotFoundException e) {
            // Fall back to standard Java HTTP for standalone testing
            return executeStandardHttpGet(url)
        }
    }

    /**
     * Standard Java HTTP GET for standalone testing
     */
    private String executeStandardHttpGet(String url) {
        def connection = new URL(url).openConnection() as HttpURLConnection
        connection.setRequestMethod("GET")
        connection.setRequestProperty("Authorization", "Bearer ${token}")
        connection.setConnectTimeout(30000)
        connection.setReadTimeout(60000)

        // Handle SSL certificate verification for self-signed certs
        if (ssl) {
            try {
                def sc = javax.net.ssl.SSLContext.getInstance("TLS")
                sc.init(null, [new javax.net.ssl.X509TrustManager() {
                    void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    java.security.cert.X509Certificate[] getAcceptedIssuers() { return null }
                }] as javax.net.ssl.TrustManager[], new java.security.SecureRandom())
                connection.setSSLSocketFactory(sc.getSocketFactory())
                connection.setHostnameVerifier({ hostname, session -> true })
            } catch (Exception e) {
                // Ignore SSL setup errors, proceed with default
            }
        }

        def responseCode = connection.getResponseCode()

        if (responseCode == 401) {
            throw new RuntimeException("Authentication failed (401) - check bearer token")
        }
        if (responseCode == 400) {
            def errorStream = connection.getErrorStream()
            def errorBody = errorStream ? errorStream.text : "Bad request"
            throw new RuntimeException("Query error (400) - ${errorBody}")
        }
        if (responseCode != 200) {
            throw new RuntimeException("HTTP error ${responseCode}")
        }

        return connection.getInputStream().text
    }

    /**
     * Test connectivity to Thanos Querier
     * Returns true if connection successful, false otherwise
     */
    boolean testConnection() {
        try {
            def result = query("up")
            return result != null && result.status == "success"
        } catch (Exception e) {
            System.err.println "ERROR: Connection test failed - ${e.message}"
            return false
        }
    }
}

// Standalone test when run directly
if (this.binding.hasVariable("args") || !this.binding.hasVariable("hostProps")) {
    println "ThanosClient standalone test mode"
    println "================================="

    // Check for environment variables or command line args
    def testHost = System.getenv("THANOS_HOST")
    def testToken = System.getenv("THANOS_TOKEN")

    if (!testHost || !testToken) {
        println "Set THANOS_HOST and THANOS_TOKEN environment variables to test"
        println "Example:"
        println "  export THANOS_HOST=thanos-querier-openshift-monitoring.apps.cluster.example.com"
        println "  export THANOS_TOKEN=\$(oc whoami -t)"
        println "  groovy thanos_client.groovy"
        System.exit(0)
    }

    def props = [
        "kubevirt.thanos.host": testHost,
        "kubevirt.thanos.token": testToken,
        "kubevirt.thanos.debug": "true"
    ]

    try {
        def client = new ThanosClient(props)

        println "\nTesting connection..."
        if (client.testConnection()) {
            println "Connection successful"
        } else {
            println "Connection failed"
            System.exit(1)
        }

        println "\nQuerying kubevirt_vmi_info (for discovery)..."
        client.queryWithLabels("kubevirt_vmi_info{phase=\"running\"}").each { labels, value ->
            println "  ${labels.namespace}/${labels.name} phase=${labels.phase} node=${labels.node}"
        }

        println "\nQuerying memory metrics grouped by namespace/name..."
        client.queryGroupedByKey("kubevirt_vmi_memory_available_bytes", ["namespace", "name"]).each { key, value ->
            println "  ${key}.memory_available_bytes=${value}"
        }

        println "\nTest complete"

    } catch (Exception e) {
        println "ERROR: ${e.message}"
        e.printStackTrace()
        System.exit(1)
    }
}

return ThanosClient
