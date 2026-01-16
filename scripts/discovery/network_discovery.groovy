// Description: Active Discovery script for KubeVirt VMI network interfaces.
// Description: Discovers all VM/interface combinations for per-interface monitoring.
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
 * Network Discovery Script
 *
 * Discovers network interfaces per VMI using 3-part wildvalue:
 * {namespace}/{vmi_name}/{interface}
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

    // Query network metrics to discover all VM/interface combinations
    def results = client.queryWithLabels('kubevirt_vmi_network_receive_bytes_total')

    if (!results) {
        System.err.println "INFO: No network interfaces found"
        return 0
    }

    // Track unique combinations
    def discovered = [] as Set

    results.each { labels, value ->
        def namespace = labels.get("namespace")
        def name = labels.get("name")
        def iface = labels.get("interface")

        if (!namespace || !name || !iface) return

        def key = "${namespace}/${name}/${iface}"
        if (discovered.contains(key)) return
        discovered.add(key)

        // 3-part wildvalue: namespace/vmi_name/interface
        def wildvalue = key
        def wildalias = "${name}/${iface}"
        def description = "Interface ${iface} on VM ${name}"

        def properties = [
            "auto.kubevirt.namespace=${namespace}",
            "auto.kubevirt.vmi_name=${name}",
            "auto.kubevirt.interface=${iface}"
        ]

        println "${wildvalue}##${wildalias}##${description}####${properties.join('&')}"
    }

    return 0

} catch (Exception e) {
    System.err.println "ERROR: Discovery failed - ${e.message}"
    return 1
}
