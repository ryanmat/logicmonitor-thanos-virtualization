// Description: BATCHSCRIPT collection for KubeVirt VMI CPU metrics.
// Description: Queries Thanos for CPU rates and outputs in LogicMonitor BATCHSCRIPT format.
// v4

import groovy.json.JsonSlurper

// Load the ThanosClient class for standalone testing
def loadThanosClient() {
    def clientFile = new File("scripts/lib/thanos_client.groovy")
    if (!clientFile.exists()) {
        clientFile = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile, "lib/thanos_client.groovy")
    }
    if (!clientFile.exists()) throw new RuntimeException("Cannot find thanos_client.groovy")
    return new GroovyShell(this.class.classLoader).evaluate(clientFile)
}

/**
 * CPU Collection Script (BATCHSCRIPT)
 *
 * Collects CPU metrics for all VMIs and outputs in BATCHSCRIPT format:
 * wildvalue.datapoint=value
 *
 * Metrics collected:
 * - cpu_usage_rate: rate(kubevirt_vmi_cpu_usage_seconds_total[5m])
 * - cpu_system_rate: rate(kubevirt_vmi_cpu_system_usage_seconds_total[5m])
 * - cpu_user_rate: rate(kubevirt_vmi_cpu_user_usage_seconds_total[5m])
 * - vcpu_seconds_rate: rate(kubevirt_vmi_vcpu_seconds_total[5m]) - aggregated
 * - vcpu_wait_rate: rate(kubevirt_vmi_vcpu_wait_seconds_total[5m])
 * - vcpu_delay_rate: rate(kubevirt_vmi_vcpu_delay_seconds_total[5m])
 */

try {
    // Get hostProps - in LM context this is provided, for testing we create mock
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

    // Initialize Thanos client
    def ThanosClientClass = loadThanosClient()
    def client = ThanosClientClass.getDeclaredConstructor(Map).newInstance(props)

    // Define metrics to collect with their PromQL queries
    def metrics = [
        "cpu_usage_rate": 'rate(kubevirt_vmi_cpu_usage_seconds_total[5m])',
        "cpu_system_rate": 'rate(kubevirt_vmi_cpu_system_usage_seconds_total[5m])',
        "cpu_user_rate": 'rate(kubevirt_vmi_cpu_user_usage_seconds_total[5m])',
        "vcpu_wait_rate": 'rate(kubevirt_vmi_vcpu_wait_seconds_total[5m])',
        "vcpu_delay_rate": 'rate(kubevirt_vmi_vcpu_delay_seconds_total[5m])'
    ]

    // For vcpu_seconds, we need to aggregate by VM since it has per-vCPU data
    def vcpuSecondsQuery = 'sum by (namespace, name) (rate(kubevirt_vmi_vcpu_seconds_total[5m]))'

    // Collect all metrics
    def allData = [:]

    // Get vcpu_seconds aggregated
    def vcpuData = client.queryGroupedByKey(vcpuSecondsQuery, ["namespace", "name"])
    vcpuData.each { key, value ->
        if (!allData[key]) allData[key] = [:]
        allData[key]["vcpu_seconds_rate"] = value
    }

    // Get other metrics
    metrics.each { datapoint, query ->
        def results = client.queryGroupedByKey(query, ["namespace", "name"])
        results.each { key, value ->
            if (!allData[key]) allData[key] = [:]
            allData[key][datapoint] = value
        }
    }

    // Output in BATCHSCRIPT format
    allData.each { wildvalue, datapoints ->
        datapoints.each { datapoint, value ->
            if (value != null) {
                // Convert to percentage for cpu_usage_rate (multiply by 100)
                def outputValue = value
                if (datapoint == "cpu_usage_rate" || datapoint == "vcpu_seconds_rate") {
                    // These are in seconds/second, convert to percentage
                    def numValue = value as Double
                    outputValue = String.format("%.4f", numValue * 100)
                    println "${wildvalue}.${datapoint}_percent=${outputValue}"
                }
                // Also output raw value
                println "${wildvalue}.${datapoint}=${value}"
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
