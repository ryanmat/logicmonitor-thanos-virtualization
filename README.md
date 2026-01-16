# KubeVirt Thanos DataSources for LogicMonitor

LogicMonitor DataSources for monitoring OpenShift Virtualization (KubeVirt) VMs by querying Thanos Querier directly.

## Overview

This project provides a suite of 6 LogicMonitor DataSources to monitor KubeVirt virtual machines running on OpenShift. It queries the Thanos Querier API to collect VM metrics, providing an alternative to the OpenMetrics module.

## Directory Structure

```
/datasources/          # Final JSON DataSource files for import into LogicMonitor
/scripts/
  /discovery/          # Active Discovery Groovy scripts
  /collection/         # Data collection Groovy scripts
  /lib/                # Shared library code (Thanos client)
/tests/
  /mock_responses/     # Mock Thanos API responses for testing
```

## DataSources Included

| DataSource | Type | Instances | Description |
|------------|------|-----------|-------------|
| KubeVirt_VMI_Discovery | Multi-Instance | Per VM | Discovers running VMs with metadata |
| KubeVirt_VMI_CPU | Multi-Instance | Per VM | CPU usage, system/user time, vCPU metrics |
| KubeVirt_VMI_Memory | Multi-Instance | Per VM | Memory usage, swap, page faults |
| KubeVirt_VMI_Network | Multi-Instance | Per VM/Interface | Network throughput, packets, errors |
| KubeVirt_VMI_Storage | Multi-Instance | Per VM/Drive | Disk throughput, IOPS, latency |
| KubeVirt_Cluster_Overview | Single-Instance | Cluster | Health, VM counts, migrations |

## Metrics Summary

### CPU Metrics (8 datapoints)
- `cpu_usage_rate_percent` - Overall CPU utilization percentage
- `cpu_system_rate` - System CPU time rate
- `cpu_user_rate` - User CPU time rate
- `vcpu_seconds_rate_percent` - vCPU utilization percentage
- `vcpu_seconds_rate` - vCPU time rate
- `vcpu_wait_rate` - vCPU wait time
- `vcpu_delay_rate` - vCPU scheduling delay
- `vcpu_count` - Number of vCPUs

### Memory Metrics (13 datapoints)
- `memory_available_bytes` - Available memory
- `memory_used_bytes` - Used memory (calculated)
- `memory_usage_percent` - Memory utilization percentage
- `memory_domain_bytes` - Total domain memory
- `memory_resident_bytes` - Resident set size
- `memory_swap_in_bytes` - Swap in traffic
- `memory_swap_out_bytes` - Swap out traffic
- `memory_cached_bytes` - Cached memory
- `memory_unused_bytes` - Unused memory
- `memory_usable_bytes` - Usable memory
- `memory_actual_balloon_bytes` - Balloon memory
- `memory_pgmajfault_rate` - Major page faults/sec
- `memory_pgminfault_rate` - Minor page faults/sec

### Network Metrics (8 datapoints per interface)
- `rx_bytes` - Receive throughput (bytes/sec)
- `tx_bytes` - Transmit throughput (bytes/sec)
- `rx_packets` - Receive packet rate
- `tx_packets` - Transmit packet rate
- `rx_errors` - Receive errors/sec
- `tx_errors` - Transmit errors/sec
- `rx_dropped` - Receive dropped packets/sec
- `tx_dropped` - Transmit dropped packets/sec

### Storage Metrics (8 datapoints per drive)
- `read_bytes` - Read throughput (bytes/sec)
- `write_bytes` - Write throughput (bytes/sec)
- `read_iops` - Read operations/sec
- `write_iops` - Write operations/sec
- `read_latency_ms` - Average read latency (ms)
- `write_latency_ms` - Average write latency (ms)
- `flush_requests` - Flush operations/sec
- `flush_latency_ms` - Average flush latency (ms)

### Cluster Overview Metrics (11 datapoints)
- `vms_total` - Total VMs across namespaces
- `vms_running` - VMs in running state
- `allocatable_nodes` - Nodes available for KubeVirt
- `nodes_with_kvm` - Nodes with KVM support
- `virt_api_up` - virt-api pod count
- `virt_controller_up` - virt-controller pod count
- `virt_handler_up` - virt-handler pod count
- `system_health` - HCO health status (0=healthy)
- `migrations_pending` - Pending migrations
- `migrations_running` - Running migrations
- `migrations_scheduling` - Scheduling migrations

## Prerequisites

- OpenShift 4.x with OpenShift Virtualization (CNV) installed
- LogicMonitor Collector with HTTPS access to OpenShift cluster
- ServiceAccount with `cluster-monitoring-view` ClusterRole

## Device Properties Required

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `kubevirt.thanos.host` | Yes | - | Thanos Querier route hostname |
| `kubevirt.thanos.token` | Yes | - | ServiceAccount bearer token |
| `kubevirt.thanos.port` | No | 443 | Thanos Querier port |
| `kubevirt.thanos.ssl` | No | true | Use HTTPS |

## Setup Instructions

### 1. Create ServiceAccount with Monitoring Permissions

```bash
# Create ServiceAccount
oc create serviceaccount logicmonitor-monitoring -n openshift-monitoring

# Grant cluster-monitoring-view role
oc adm policy add-cluster-role-to-user cluster-monitoring-view \
  -z logicmonitor-monitoring -n openshift-monitoring

# Create long-lived token (OpenShift 4.11+)
oc create token logicmonitor-monitoring -n openshift-monitoring --duration=8760h
```

### 2. Get Thanos Querier Route

```bash
oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}'
```

### 3. Create Device in LogicMonitor

1. Add a new device in LogicMonitor
2. Set the hostname to the Thanos Querier route (or any identifier)
3. Add the following custom properties:
   - `kubevirt.thanos.host` = Thanos Querier route hostname
   - `kubevirt.thanos.token` = ServiceAccount bearer token

### 4. Import DataSources

Import each JSON file from the `/datasources/` directory into LogicMonitor:

1. Settings > DataSources > Add > From File
2. Select the JSON file
3. Save the DataSource

Or use the LogicMonitor API:
```bash
for ds in datasources/*.json; do
  curl -X POST "https://ACCOUNT.logicmonitor.com/santaba/rest/setting/datasources" \
    -H "Authorization: Bearer TOKEN" \
    -H "Content-Type: application/json" \
    -d @"$ds"
done
```

## Testing Scripts Locally

```bash
# Set environment variables
export THANOS_HOST=$(oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}')
export THANOS_TOKEN=$(oc whoami -t)

# Test discovery
groovy scripts/discovery/vmi_discovery.groovy

# Test collection
groovy scripts/collection/cpu_collection.groovy
groovy scripts/collection/memory_collection.groovy
groovy scripts/collection/network_collection.groovy
groovy scripts/collection/storage_collection.groovy
groovy scripts/collection/cluster_overview_collection.groovy
```

## Instance Naming

- **VMI instances**: `{namespace}/{vmi_name}` (e.g., `demo-vms/my-vm`)
- **Network instances**: `{namespace}/{vmi_name}/{interface}` (e.g., `demo-vms/my-vm/default`)
- **Storage instances**: `{namespace}/{vmi_name}/{drive}` (e.g., `demo-vms/my-vm/rootdisk`)

## Alerting

Default alert thresholds are configured for:
- Memory usage > 90%
- Network errors > 0
- Network drops > 10/sec
- Storage latency > 50ms
- No KVM nodes available
- KubeVirt components unavailable
- System health degraded

## vSphere Parity

These DataSources provide approximately 85% feature parity with vSphere VM monitoring:
- CPU usage and scheduling metrics
- Memory active, usage, swap, and ballooning
- Disk read/write throughput, IOPS, and latency
- Network throughput, packets, errors, and drops
- VM state and migration tracking

## License

Apache 2.0
