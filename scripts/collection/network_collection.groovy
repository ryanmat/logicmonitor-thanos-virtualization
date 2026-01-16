// Description: BATCHSCRIPT collection for KubeVirt VMI Network metrics.
// Description: Collects per-interface network stats in LogicMonitor BATCHSCRIPT format.
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
 * Network Collection Script (BATCHSCRIPT)
 *
 * Metrics collected per interface:
 * - rx_bytes: receive bytes/sec
 * - tx_bytes: transmit bytes/sec
 * - rx_packets: receive packets/sec
 * - tx_packets: transmit packets/sec
 * - rx_errors: receive errors/sec
 * - tx_errors: transmit errors/sec
 * - rx_dropped: receive dropped packets/sec
 * - tx_dropped: transmit dropped packets/sec
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

    // All metrics are rates
    def metrics = [
        "rx_bytes": 'rate(kubevirt_vmi_network_receive_bytes_total[5m])',
        "tx_bytes": 'rate(kubevirt_vmi_network_transmit_bytes_total[5m])',
        "rx_packets": 'rate(kubevirt_vmi_network_receive_packets_total[5m])',
        "tx_packets": 'rate(kubevirt_vmi_network_transmit_packets_total[5m])',
        "rx_errors": 'rate(kubevirt_vmi_network_receive_errors_total[5m])',
        "tx_errors": 'rate(kubevirt_vmi_network_transmit_errors_total[5m])',
        "rx_dropped": 'rate(kubevirt_vmi_network_receive_packets_dropped_total[5m])',
        "tx_dropped": 'rate(kubevirt_vmi_network_transmit_packets_dropped_total[5m])'
    ]

    def allData = [:]

    // Collect all metrics - use 3-part key: namespace/name/interface
    metrics.each { datapoint, query ->
        def results = client.queryGroupedByKey(query, ["namespace", "name", "interface"])
        results.each { key, value ->
            if (!allData[key]) allData[key] = [:]
            allData[key][datapoint] = value
        }
    }

    // Output in BATCHSCRIPT format
    allData.each { wildvalue, datapoints ->
        datapoints.each { datapoint, value ->
            if (value != null) {
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
