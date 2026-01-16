// Description: BATCHSCRIPT collection for KubeVirt VMI Storage metrics.
// Description: Collects per-drive storage stats including throughput, IOPS, and latency.
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
 * Storage Collection Script (BATCHSCRIPT)
 *
 * Metrics collected per drive:
 * - read_bytes: read throughput bytes/sec
 * - write_bytes: write throughput bytes/sec
 * - read_iops: read operations/sec
 * - write_iops: write operations/sec
 * - read_latency_ms: average read latency in milliseconds
 * - write_latency_ms: average write latency in milliseconds
 * - flush_requests: flush operations/sec
 * - flush_latency_ms: average flush latency in milliseconds
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

    // Rate metrics for throughput and IOPS
    def rateMetrics = [
        "read_bytes": 'rate(kubevirt_vmi_storage_read_traffic_bytes_total[5m])',
        "write_bytes": 'rate(kubevirt_vmi_storage_write_traffic_bytes_total[5m])',
        "read_iops": 'rate(kubevirt_vmi_storage_iops_read_total[5m])',
        "write_iops": 'rate(kubevirt_vmi_storage_iops_write_total[5m])',
        "read_time_rate": 'rate(kubevirt_vmi_storage_read_times_seconds_total[5m])',
        "write_time_rate": 'rate(kubevirt_vmi_storage_write_times_seconds_total[5m])',
        "flush_requests": 'rate(kubevirt_vmi_storage_flush_requests_total[5m])',
        "flush_time_rate": 'rate(kubevirt_vmi_storage_flush_times_seconds_total[5m])'
    ]

    def allData = [:]

    // Collect all rate metrics - use 3-part key: namespace/name/drive
    rateMetrics.each { datapoint, query ->
        def results = client.queryGroupedByKey(query, ["namespace", "name", "drive"])
        results.each { key, value ->
            if (!allData[key]) allData[key] = [:]
            allData[key][datapoint] = value
        }
    }

    // Calculate latencies and output in BATCHSCRIPT format
    allData.each { wildvalue, datapoints ->
        // Output throughput metrics
        if (datapoints.read_bytes != null) {
            println "${wildvalue}.read_bytes=${datapoints.read_bytes}"
        }
        if (datapoints.write_bytes != null) {
            println "${wildvalue}.write_bytes=${datapoints.write_bytes}"
        }

        // Output IOPS metrics
        if (datapoints.read_iops != null) {
            println "${wildvalue}.read_iops=${datapoints.read_iops}"
        }
        if (datapoints.write_iops != null) {
            println "${wildvalue}.write_iops=${datapoints.write_iops}"
        }

        // Calculate and output read latency (ms)
        // latency = time_rate / iops * 1000 (convert seconds to ms)
        if (datapoints.read_time_rate != null && datapoints.read_iops != null) {
            def readIops = datapoints.read_iops as Double
            if (readIops > 0) {
                def readLatency = (datapoints.read_time_rate as Double) / readIops * 1000
                println "${wildvalue}.read_latency_ms=${readLatency}"
            }
        }

        // Calculate and output write latency (ms)
        if (datapoints.write_time_rate != null && datapoints.write_iops != null) {
            def writeIops = datapoints.write_iops as Double
            if (writeIops > 0) {
                def writeLatency = (datapoints.write_time_rate as Double) / writeIops * 1000
                println "${wildvalue}.write_latency_ms=${writeLatency}"
            }
        }

        // Output flush metrics
        if (datapoints.flush_requests != null) {
            println "${wildvalue}.flush_requests=${datapoints.flush_requests}"
        }

        // Calculate and output flush latency (ms)
        if (datapoints.flush_time_rate != null && datapoints.flush_requests != null) {
            def flushReqs = datapoints.flush_requests as Double
            if (flushReqs > 0) {
                def flushLatency = (datapoints.flush_time_rate as Double) / flushReqs * 1000
                println "${wildvalue}.flush_latency_ms=${flushLatency}"
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
