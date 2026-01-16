// Description: Active Discovery script for KubeVirt VMI instances.
// Description: Queries Thanos for running VMs and outputs LogicMonitor discovery format.
// v4

import groovy.json.JsonSlurper

// Load the ThanosClient class
def loadThanosClient() {
    def scriptFile = new File(getClass().protectionDomain.codeSource.location.path)
    def libDir = new File(scriptFile.parentFile.parentFile, "lib")
    def clientFile = new File(libDir, "thanos_client.groovy")

    if (!clientFile.exists()) {
        // Try relative to current working directory (for testing)
        clientFile = new File("scripts/lib/thanos_client.groovy")
    }

    if (!clientFile.exists()) {
        throw new RuntimeException("Cannot find thanos_client.groovy")
    }

    def shell = new GroovyShell(this.class.classLoader)
    return shell.evaluate(clientFile)
}

def ThanosClientClass = loadThanosClient()

/**
 * VMI Discovery Script
 *
 * Discovers running KubeVirt Virtual Machine Instances and outputs them
 * in LogicMonitor Active Discovery format.
 *
 * Output format per instance:
 * wildvalue##wildalias##description####property1=value1&property2=value2
 *
 * Example:
 * demo-vms/fedora-demo##fedora-demo##VM in namespace demo-vms####auto.kubevirt.namespace=demo-vms&auto.kubevirt.vmi_name=fedora-demo
 */

try {
    // Get hostProps - in LM context this is provided, for testing we create mock
    def props
    if (binding.hasVariable("hostProps")) {
        props = hostProps
    } else {
        // Standalone testing mode
        props = [
            "kubevirt.thanos.host": System.getenv("THANOS_HOST"),
            "kubevirt.thanos.token": System.getenv("THANOS_TOKEN"),
            "kubevirt.thanos.debug": System.getenv("THANOS_DEBUG") ?: "false"
        ]
    }

    // Validate required properties
    if (!props.get("kubevirt.thanos.host") || !props.get("kubevirt.thanos.token")) {
        System.err.println "ERROR: Missing required properties kubevirt.thanos.host and/or kubevirt.thanos.token"
        return 1
    }

    // Initialize Thanos client
    def client = ThanosClientClass.getDeclaredConstructor(Map).newInstance(props)

    // Query for running VMIs using kubevirt_vmi_info
    // This metric has name, namespace, phase, node, and other useful labels
    def query = 'kubevirt_vmi_info{phase="running"}'
    def results = client.queryWithLabels(query)

    if (results == null || results.isEmpty()) {
        // No running VMs found - this is valid, just no instances
        System.err.println "INFO: No running VMIs found"
        return 0
    }

    // Output each VM in LogicMonitor discovery format
    results.each { labels, value ->
        def namespace = labels.get("namespace")
        def name = labels.get("name")
        def node = labels.get("node") ?: "unknown"
        def phase = labels.get("phase") ?: "unknown"
        def instanceType = labels.get("instance_type") ?: ""
        def guestOs = labels.get("guest_os_name") ?: ""
        def preference = labels.get("preference") ?: ""

        // Skip if missing required labels
        if (!namespace || !name) {
            System.err.println "WARN: Skipping VMI with missing namespace or name: ${labels}"
            return
        }

        // Build wildvalue: namespace/vmi_name
        def wildvalue = "${namespace}/${name}"

        // Build wildalias: just the VM name for cleaner display
        def wildalias = name

        // Build description
        def description = "VM ${name} in namespace ${namespace}"
        if (guestOs) {
            description += " (${guestOs})"
        }

        // Build instance properties
        def properties = [
            "auto.kubevirt.namespace=${namespace}",
            "auto.kubevirt.vmi_name=${name}",
            "auto.kubevirt.node=${node}",
            "auto.kubevirt.phase=${phase}"
        ]

        if (instanceType) {
            properties << "auto.kubevirt.instance_type=${instanceType}"
        }
        if (guestOs) {
            properties << "auto.kubevirt.guest_os=${guestOs}"
        }
        if (preference) {
            properties << "auto.kubevirt.preference=${preference}"
        }

        // Output in LogicMonitor discovery format
        // Format: wildvalue##wildalias##description####properties
        println "${wildvalue}##${wildalias}##${description}####${properties.join('&')}"
    }

    return 0

} catch (Exception e) {
    System.err.println "ERROR: Discovery failed - ${e.message}"
    if (System.getenv("THANOS_DEBUG") == "true") {
        e.printStackTrace()
    }
    return 1
}
