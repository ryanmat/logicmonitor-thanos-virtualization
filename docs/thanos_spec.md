# KubeVirt Thanos DataSource Technical Specification

## Executive Summary

This project creates a suite of LogicMonitor DataSources to monitor OpenShift Virtualization (KubeVirt) VMs by querying Thanos Querier directly, providing an alternative to the OpenMetrics module for customers who prefer not to use it.

## Architecture Overview

### Target Environment
- OpenShift 4.x with OpenShift Virtualization installed
- Thanos Querier accessible via OpenShift route
- LogicMonitor Collector with network access to OpenShift API

### Authentication Model
- ServiceAccount with `cluster-monitoring-view` ClusterRole
- Bearer token authentication to Thanos Querier API
- Token stored as device property: `kubevirt.thanos.token`

### API Endpoints
```
Base URL: https://<thanos-querier-route>/api/v1/query
Method: GET with query parameter
Headers: Authorization: Bearer <token>
```

## DataSource Suite

### 1. KubeVirt_VMI_Discovery (PropertySource)
**Purpose**: Discover VMs and set properties for other DataSources to use

**AppliesTo**: `kubevirt.thanos.host && kubevirt.thanos.token`

**Properties Set**:
- `auto.kubevirt.enabled` = "true"
- `auto.kubevirt.cluster_name`
- `auto.kubevirt.vm_count`

**PromQL Query**:
```promql
kubevirt_vmi_phase_count{phase="Running"} == 1
```

---

### 2. KubeVirt_VMI_CPU (Multi-Instance DataSource)
**Purpose**: CPU performance metrics per VM

**Instance Format**: `{namespace}/{vmi_name}`

**Metrics**:
| Datapoint | PromQL | Type | Unit |
|-----------|--------|------|------|
| vcpu_seconds_total | `rate(kubevirt_vmi_vcpu_seconds_total[5m])` | GAUGE | seconds/sec |
| vcpu_wait_seconds | `rate(kubevirt_vmi_vcpu_wait_seconds_total[5m])` | GAUGE | seconds/sec |
| cpu_system_usage | `rate(kubevirt_vmi_cpu_system_usage_seconds_total[5m])` | GAUGE | seconds/sec |
| cpu_user_usage | `rate(kubevirt_vmi_cpu_user_usage_seconds_total[5m])` | GAUGE | seconds/sec |
| cpu_usage_percent | (calculated from vcpu_seconds) | GAUGE | percent |

---

### 3. KubeVirt_VMI_Memory (Multi-Instance DataSource)
**Purpose**: Memory metrics per VM

**Instance Format**: `{namespace}/{vmi_name}`

**Metrics**:
| Datapoint | PromQL | Type | Unit |
|-----------|--------|------|------|
| memory_available_bytes | `kubevirt_vmi_memory_available_bytes` | GAUGE | bytes |
| memory_resident_bytes | `kubevirt_vmi_memory_resident_bytes` | GAUGE | bytes |
| memory_domain_total_bytes | `kubevirt_vmi_memory_domain_total_bytes` | GAUGE | bytes |
| memory_balloon_current_bytes | `kubevirt_vmi_memory_balloon_current_bytes` | GAUGE | bytes |
| memory_swap_in_bytes | `rate(kubevirt_vmi_memory_swap_in_traffic_bytes_total[5m])` | GAUGE | bytes/sec |
| memory_swap_out_bytes | `rate(kubevirt_vmi_memory_swap_out_traffic_bytes_total[5m])` | GAUGE | bytes/sec |
| memory_usage_percent | (calculated) | GAUGE | percent |

---

### 4. KubeVirt_VMI_Network (Multi-Instance DataSource)
**Purpose**: Network metrics per interface per VM

**Instance Format**: `{namespace}/{vmi_name}/{interface}`

**Metrics**:
| Datapoint | PromQL | Type | Unit |
|-----------|--------|------|------|
| rx_bytes | `rate(kubevirt_vmi_network_receive_bytes_total[5m])` | GAUGE | bytes/sec |
| tx_bytes | `rate(kubevirt_vmi_network_transmit_bytes_total[5m])` | GAUGE | bytes/sec |
| rx_packets | `rate(kubevirt_vmi_network_receive_packets_total[5m])` | GAUGE | packets/sec |
| tx_packets | `rate(kubevirt_vmi_network_transmit_packets_total[5m])` | GAUGE | packets/sec |
| rx_errors | `rate(kubevirt_vmi_network_receive_errors_total[5m])` | GAUGE | errors/sec |
| tx_errors | `rate(kubevirt_vmi_network_transmit_errors_total[5m])` | GAUGE | errors/sec |
| rx_dropped | `rate(kubevirt_vmi_network_receive_packets_dropped_total[5m])` | GAUGE | packets/sec |
| tx_dropped | `rate(kubevirt_vmi_network_transmit_packets_dropped_total[5m])` | GAUGE | packets/sec |

---

### 5. KubeVirt_VMI_Storage (Multi-Instance DataSource)
**Purpose**: Storage metrics per disk per VM

**Instance Format**: `{namespace}/{vmi_name}/{drive}`

**Metrics**:
| Datapoint | PromQL | Type | Unit |
|-----------|--------|------|------|
| read_bytes | `rate(kubevirt_vmi_storage_read_traffic_bytes_total[5m])` | GAUGE | bytes/sec |
| write_bytes | `rate(kubevirt_vmi_storage_write_traffic_bytes_total[5m])` | GAUGE | bytes/sec |
| read_iops | `rate(kubevirt_vmi_storage_iops_read_total[5m])` | GAUGE | ops/sec |
| write_iops | `rate(kubevirt_vmi_storage_iops_write_total[5m])` | GAUGE | ops/sec |
| read_latency_seconds | `rate(kubevirt_vmi_storage_read_times_seconds_total[5m])` | GAUGE | seconds |
| write_latency_seconds | `rate(kubevirt_vmi_storage_write_times_seconds_total[5m])` | GAUGE | seconds |

---

### 6. KubeVirt_VMI_Migration (Multi-Instance DataSource)
**Purpose**: Live migration tracking per VM

**Instance Format**: `{namespace}/{vmi_name}`

**Metrics**:
| Datapoint | PromQL | Type | Unit |
|-----------|--------|------|------|
| migration_succeeded | `kubevirt_vmi_migration_succeeded` | GAUGE | count |
| migration_failed | `kubevirt_vmi_migration_failed` | GAUGE | count |
| migration_data_processed_bytes | `kubevirt_vmi_migration_data_processed_bytes` | GAUGE | bytes |
| migration_data_remaining_bytes | `kubevirt_vmi_migration_data_remaining_bytes` | GAUGE | bytes |
| migration_dirty_memory_rate | `kubevirt_vmi_migration_dirty_memory_rate_bytes` | GAUGE | bytes/sec |
| migration_memory_transfer_rate | `kubevirt_vmi_migration_memory_transfer_rate_bytes` | GAUGE | bytes/sec |

---

### 7. KubeVirt_Cluster_Overview (Single-Instance DataSource)
**Purpose**: Cluster-wide KubeVirt statistics

**Metrics**:
| Datapoint | PromQL | Type | Unit |
|-----------|--------|------|------|
| total_vmis_running | `count(kubevirt_vmi_phase_count{phase="Running"} == 1)` | GAUGE | count |
| total_vmis_pending | `count(kubevirt_vmi_phase_count{phase="Pending"} == 1)` | GAUGE | count |
| total_vmis_failed | `count(kubevirt_vmi_phase_count{phase="Failed"} == 1)` | GAUGE | count |
| total_migrations_running | `kubevirt_vmi_migrations_in_pending_phase` | GAUGE | count |
| virt_api_up | `up{job="kubevirt-virt-api"}` | GAUGE | boolean |
| virt_controller_up | `up{job="kubevirt-virt-controller"}` | GAUGE | boolean |
| virt_handler_up | `count(up{job="kubevirt-virt-handler"} == 1)` | GAUGE | count |

---

## Device Properties Required

| Property | Description | Example |
|----------|-------------|---------|
| `kubevirt.thanos.host` | Thanos Querier route hostname | `thanos-querier-openshift-monitoring.apps.cluster.example.com` |
| `kubevirt.thanos.token` | ServiceAccount bearer token | `eyJhbGciOiJSUzI1NiIsImtpZCI6...` |
| `kubevirt.thanos.port` | Port (optional, default 443) | `443` |
| `kubevirt.thanos.ssl` | Use SSL (optional, default true) | `true` |

---

## Instance Lifecycle Management

### Discovery Schedule
- CPU/Memory: 15 minutes (moderate change frequency)
- Network/Storage: 15 minutes (interface/disk hot-add detection)
- Migration: 5 minutes (capture active migrations)

### DeleteInactiveInstances
- Enabled: true
- Threshold: 6 missed polls (30 minutes for 5-min collection)

### Instance Identity
- Use wildvalue as unique identifier (enables renames without history loss)
- Wildvalue format: `{namespace}/{vmi_name}` or `{namespace}/{vmi_name}/{subresource}`

---

## Groovy Script Standards

### Version Declaration
```groovy
// v4
```

### HTTP Helper Usage
```groovy
import com.santaba.agent.groovyapi.http.*
import groovy.json.JsonSlurper
```

### Error Handling Pattern
```groovy
try {
    // API call
} catch (Exception e) {
    println "Error: ${e.message}"
    return 1
}
return 0
```

### Output Formats

**Active Discovery**:
```
wildvalue##wildalias##description####property1=value1&property2=value2
```

**Collection (BATCHSCRIPT)**:
```
wildvalue.datapoint=value
```

---

## Testing Strategy

### Unit Testing
- Mock Thanos API responses
- Validate PromQL query construction
- Verify output format parsing

### Integration Testing
- Connect to real OpenShift cluster with test VMs
- Verify instance discovery
- Validate metric collection accuracy

### Test VMs Required
- Minimum 2 VMs in different namespaces
- At least one VM with multiple network interfaces
- At least one VM with multiple disks

---

## File Structure

```
/datasources/
  KubeVirt_VMI_Discovery.json
  KubeVirt_VMI_CPU.json
  KubeVirt_VMI_Memory.json
  KubeVirt_VMI_Network.json
  KubeVirt_VMI_Storage.json
  KubeVirt_VMI_Migration.json
  KubeVirt_Cluster_Overview.json
/scripts/
  discovery/
    vmi_discovery.groovy
    network_discovery.groovy
    storage_discovery.groovy
  collection/
    cpu_collection.groovy
    memory_collection.groovy
    network_collection.groovy
    storage_collection.groovy
    migration_collection.groovy
    cluster_overview_collection.groovy
  lib/
    thanos_client.groovy
/docs/
  setup_guide.md
  troubleshooting.md
```

---

## vSphere Parity Reference

| vSphere Metric | KubeVirt Equivalent | Parity |
|----------------|---------------------|--------|
| cpu.usage.average | vcpu_seconds_total (rate) | Full |
| cpu.ready.summation | vcpu_wait_seconds_total | Full |
| mem.active.average | memory_resident_bytes | Full |
| mem.usage.average | calculated from available/total | Full |
| disk.read.average | storage_read_traffic_bytes_total | Full |
| disk.write.average | storage_write_traffic_bytes_total | Full |
| net.received.average | network_receive_bytes_total | Full |
| net.transmitted.average | network_transmit_bytes_total | Full |
| Power state | phase_count labels | Full |
| vMotion count | migration_succeeded/failed | Full |
| cpu.costop | N/A | None (different virtualization) |
| mem.shared (TPS) | N/A | None (KVM doesn't use TPS) |

**Overall Parity: ~85%**
