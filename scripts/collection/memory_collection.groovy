// Description: BATCHSCRIPT collection for KubeVirt VMI Memory metrics.
// Description: Queries Thanos for memory stats and outputs in LogicMonitor BATCHSCRIPT format.
// v4

import groovy.json.JsonSlurper

def loadThanosClient() {
    def clientFile = new File("scripts/lib/thanos_client.groovy")
    if (!clientFile.exists()) {
        clientFile = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile, "lib/thanos_client.groovy")
    }
    if (!clientFile.exists()) throw new RuntimeException("Cannot find thanos_client.groovy")
    return new GroovyShell(this.class.classLoader).evaluate(clientFile)
}

/**
 * Memory Collection Script (BATCHSCRIPT)
 *
 * Metrics collected:
 * - memory_available_bytes: Available memory
 * - memory_used_bytes: Used memory
 * - memory_domain_bytes: Total domain memory
 * - memory_resident_bytes: Resident/active memory
 * - memory_cached_bytes: Cached memory
 * - memory_usable_bytes: Usable memory
 * - memory_unused_bytes: Unused memory
 * - memory_balloon_bytes: Balloon driver reclaimed memory
 * - memory_swap_in_rate: Swap in rate (bytes/sec)
 * - memory_swap_out_rate: Swap out rate (bytes/sec)
 * - memory_usage_percent: Calculated usage percentage
 */

try {
    def props
    if (binding.hasVariable("hostProps")) {
        props = hostProps
    } else {
        props = [
            "kubevirt.thanos.host": System.getenv("THANOS_HOST"),
            "kubevirt.thanos.token": System.getenv("THANOS_TOKEN"),
            "kubevirt.thanos.debug": System.getenv("THANOS_DEBUG") ?: "false"
        ]
    }

    if (!props.get("kubevirt.thanos.host") || !props.get("kubevirt.thanos.token")) {
        System.err.println "ERROR: Missing required properties"
        return 1
    }

    def ThanosClientClass = loadThanosClient()
    def client = ThanosClientClass.getDeclaredConstructor(Map).newInstance(props)

    // Instant metrics (not rates)
    def instantMetrics = [
        "memory_available_bytes": 'kubevirt_vmi_memory_available_bytes',
        "memory_used_bytes": 'kubevirt_vmi_memory_used_bytes',
        "memory_domain_bytes": 'kubevirt_vmi_memory_domain_bytes',
        "memory_resident_bytes": 'kubevirt_vmi_memory_resident_bytes',
        "memory_cached_bytes": 'kubevirt_vmi_memory_cached_bytes',
        "memory_usable_bytes": 'kubevirt_vmi_memory_usable_bytes',
        "memory_unused_bytes": 'kubevirt_vmi_memory_unused_bytes',
        "memory_balloon_bytes": 'kubevirt_vmi_memory_actual_balloon_bytes'
    ]

    // Rate metrics
    def rateMetrics = [
        "memory_swap_in_rate": 'rate(kubevirt_vmi_memory_swap_in_traffic_bytes[5m])',
        "memory_swap_out_rate": 'rate(kubevirt_vmi_memory_swap_out_traffic_bytes[5m])',
        "memory_pgmajfault_rate": 'rate(kubevirt_vmi_memory_pgmajfault_total[5m])',
        "memory_pgminfault_rate": 'rate(kubevirt_vmi_memory_pgminfault_total[5m])'
    ]

    def allData = [:]

    // Collect instant metrics
    instantMetrics.each { datapoint, query ->
        def results = client.queryGroupedByKey(query, ["namespace", "name"])
        results.each { key, value ->
            if (!allData[key]) allData[key] = [:]
            allData[key][datapoint] = value
        }
    }

    // Collect rate metrics
    rateMetrics.each { datapoint, query ->
        def results = client.queryGroupedByKey(query, ["namespace", "name"])
        results.each { key, value ->
            if (!allData[key]) allData[key] = [:]
            allData[key][datapoint] = value
        }
    }

    // Output in BATCHSCRIPT format with calculated metrics
    allData.each { wildvalue, datapoints ->
        datapoints.each { datapoint, value ->
            if (value != null) {
                println "${wildvalue}.${datapoint}=${value}"
            }
        }

        // Calculate usage percentage: (domain - available) / domain * 100
        def domain = datapoints["memory_domain_bytes"]
        def available = datapoints["memory_available_bytes"]
        if (domain && available) {
            try {
                def domainVal = domain as Double
                def availVal = available as Double
                if (domainVal > 0) {
                    def usagePct = ((domainVal - availVal) / domainVal) * 100
                    println "${wildvalue}.memory_usage_percent=${String.format('%.2f', usagePct)}"
                }
            } catch (Exception e) {
                // Skip calculation on error
            }
        }
    }

    return 0

} catch (Exception e) {
    System.err.println "ERROR: Collection failed - ${e.message}"
    if (System.getenv("THANOS_DEBUG") == "true") {
        e.printStackTrace()
    }
    return 1
}
