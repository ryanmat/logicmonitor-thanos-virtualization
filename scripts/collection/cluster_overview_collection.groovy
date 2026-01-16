// Description: Collection script for KubeVirt Cluster Overview metrics.
// Description: Collects cluster-wide health, VM counts, and migration status.
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
 * Cluster Overview Collection Script
 *
 * Metrics collected:
 * - vms_total: Total number of VMs across all namespaces
 * - vms_running: Number of VMs in running phase
 * - allocatable_nodes: Nodes available for KubeVirt
 * - nodes_with_kvm: Nodes with KVM support
 * - virt_api_up: Number of virt-api pods running
 * - virt_controller_up: Number of virt-controller pods running
 * - virt_handler_up: Number of virt-handler pods running
 * - system_health: HCO system health status (0=healthy, 1=degraded, 2=unhealthy)
 * - migrations_pending: VMI migrations in pending phase
 * - migrations_running: VMI migrations in running phase
 * - migrations_scheduling: VMI migrations in scheduling phase
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

    // Single-value metrics - query and sum/take first result
    def singleMetrics = [
        "vms_total": 'sum(kubevirt_number_of_vms)',
        "vms_running": 'count(kubevirt_vmi_phase_count{phase="running"})',
        "allocatable_nodes": 'kubevirt_allocatable_nodes',
        "nodes_with_kvm": 'kubevirt_nodes_with_kvm',
        "virt_api_up": 'kubevirt_virt_api_up',
        "virt_controller_up": 'kubevirt_virt_controller_up',
        "virt_handler_up": 'kubevirt_virt_handler_up',
        "system_health": 'kubevirt_hco_system_health_status',
        "migrations_pending": 'sum(kubevirt_vmi_migrations_in_pending_phase)',
        "migrations_running": 'sum(kubevirt_vmi_migrations_in_running_phase)',
        "migrations_scheduling": 'sum(kubevirt_vmi_migrations_in_scheduling_phase)'
    ]

    singleMetrics.each { datapoint, query ->
        def json = client.query(query)
        if (json && json.data?.result?.size() > 0) {
            // Take the first result value (for aggregates/single metrics)
            def value = json.data.result[0].value[1]
            if (value != null) {
                println "${datapoint}=${value}"
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
