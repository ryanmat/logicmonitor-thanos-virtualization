# KubeVirt Thanos DataSources for LogicMonitor

LogicMonitor DataSources for monitoring OpenShift Virtualization (KubeVirt) VMs by querying Thanos Querier directly.

## Overview

This project provides a suite of LogicMonitor DataSources to monitor KubeVirt virtual machines running on OpenShift. It queries the Thanos Querier API to collect VM metrics, providing an alternative to the OpenMetrics module with more control over metric collection and instance grouping.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                     OpenShift/ROSA Cluster                          │
│                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │
│  │   KubeVirt  │    │   KubeVirt  │    │   KubeVirt  │            │
│  │    VMI 1    │    │    VMI 2    │    │    VMI N    │            │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘            │
│         │                  │                  │                    │
│         ▼                  ▼                  ▼                    │
│  ┌─────────────────────────────────────────────────────┐          │
│  │              virt-handler (per node)                 │          │
│  │         Exposes kubevirt_vmi_* metrics              │          │
│  └──────────────────────┬──────────────────────────────┘          │
│                         │ scrape                                   │
│                         ▼                                          │
│  ┌─────────────────────────────────────────────────────┐          │
│  │                   Prometheus                         │          │
│  │            (openshift-monitoring namespace)          │          │
│  │         Stores metrics in local TSDB                │          │
│  └──────────────────────┬──────────────────────────────┘          │
│                         │                                          │
│                         ▼                                          │
│  ┌─────────────────────────────────────────────────────┐          │
│  │               Thanos Querier                         │          │
│  │    thanos-querier.openshift-monitoring.svc          │          │
│  │                                                      │          │
│  │  - Provides PromQL API (/api/v1/query)              │          │
│  │  - Aggregates data from Prometheus instances        │          │
│  │  - Handles deduplication                            │          │
│  └──────────────────────┬──────────────────────────────┘          │
│                         │                                          │
└─────────────────────────┼──────────────────────────────────────────┘
                          │ HTTPS (port 443)
                          │ External Route
                          │
                          ▼
              ┌───────────────────────┐
              │   LogicMonitor        │
              │   Collector           │
              │                       │
              │  Groovy Scripts:      │
              │  - Discovery          │
              │  - CPU metrics        │
              │  - Memory metrics     │
              │  - Network metrics    │
              │  - Storage metrics    │
              └───────────────────────┘
```

### How It Works

1. **KubeVirt virt-handler** runs on each node and exposes Prometheus metrics for each VM
2. **Prometheus** (OpenShift Monitoring Stack) scrapes these metrics every 30 seconds
3. **Thanos Querier** provides a unified PromQL API endpoint for querying metrics
4. **LogicMonitor DataSources** query Thanos via HTTPS using a service account token

### Why Thanos Instead of Direct Prometheus?

- **Security**: Thanos Querier is exposed via OpenShift Route with proper RBAC
- **Stability**: Abstracts away Prometheus HA pairs and federation complexity
- **Standard API**: Same PromQL interface regardless of backend topology
- **Future-proof**: Supports multi-cluster aggregation if needed

## DataSources Included

| DataSource | ID | Instances | Collection Interval | Description |
|------------|-----|-----------|---------------------|-------------|
| KubeVirt_VMI_Discovery | 11442178 | Per VM | 1 min | Discovers VMs, tracks running state |
| KubeVirt_VMI_CPU | 11442179 | Per VM | 1 min | CPU usage, system/user time, vCPU metrics |
| KubeVirt_VMI_Memory | 11442180 | Per VM | 1 min | Memory usage, available, cached, swap |
| KubeVirt_VMI_Network | 11442181 | Per VM | 1 min | Network throughput, packets, errors |
| KubeVirt_VMI_Storage | 11442182 | Per VM | 1 min | Disk throughput, IOPS, latency |
| KubeVirt_Cluster_Overview_v2 | 11442177 | Cluster | 1 min | Cluster-wide VM counts and health |

All VMI DataSources use **Instance Level Property (ILP) grouping** by namespace, so VMs are automatically organized by their Kubernetes namespace in LogicMonitor.

## Metrics Collected

### Discovery (1 datapoint)
| Metric | Description |
|--------|-------------|
| `status` | VM state: 1=Running, 2=Scheduling, 3=Pending, 4=Failed, 0=Unknown |

### CPU (6 datapoints)
| Metric | Description |
|--------|-------------|
| `cpu_usage_seconds` | Overall CPU utilization (%) |
| `cpu_system_seconds` | System CPU time (%) |
| `cpu_user_seconds` | User CPU time (%) |
| `vcpu_seconds` | vCPU utilization (%) |
| `vcpu_wait` | vCPU wait time (%) |
| `vcpu_delay` | vCPU scheduling delay (%) |

### Memory (10 datapoints)
| Metric | Description |
|--------|-------------|
| `memory_usage_percent` | Memory utilization percentage |
| `memory_available_bytes` | Available memory |
| `memory_used_bytes` | Used memory (calculated) |
| `memory_domain_bytes` | Total domain memory |
| `memory_resident_bytes` | Resident set size |
| `memory_cached_bytes` | Cached memory |
| `memory_unused_bytes` | Unused memory |
| `memory_swap_in_bytes` | Swap in traffic |
| `memory_swap_out_bytes` | Swap out traffic |
| `memory_pgmajfault` | Major page faults/sec |

### Network (8 datapoints)
| Metric | Description |
|--------|-------------|
| `rx_bytes` | Receive throughput (bytes/sec) |
| `tx_bytes` | Transmit throughput (bytes/sec) |
| `rx_packets` | Receive packet rate |
| `tx_packets` | Transmit packet rate |
| `rx_errors` | Receive errors/sec |
| `tx_errors` | Transmit errors/sec |
| `rx_dropped` | Receive dropped packets/sec |
| `tx_dropped` | Transmit dropped packets/sec |

### Storage (8 datapoints)
| Metric | Description |
|--------|-------------|
| `read_bytes` | Read throughput (bytes/sec) |
| `write_bytes` | Write throughput (bytes/sec) |
| `read_iops` | Read operations/sec |
| `write_iops` | Write operations/sec |
| `read_latency_ms` | Average read latency (ms) |
| `write_latency_ms` | Average write latency (ms) |
| `flush_requests` | Flush operations/sec |
| `flush_latency_ms` | Average flush latency (ms) |

### Cluster Overview (11 datapoints)
| Metric | Description |
|--------|-------------|
| `vms_total` | Total VMs across all states |
| `vms_running` | Total running VMs |
| `allocatable_nodes` | Allocatable nodes in cluster |
| `nodes_with_kvm` | Nodes with KVM capability |
| `virt_api_up` | virt-api pods running |
| `virt_controller_up` | virt-controller pods running |
| `virt_handler_up` | virt-handler pods running |
| `system_health` | Overall system health status |
| `migrations_pending` | Pending VM migrations |
| `migrations_running` | Active VM migrations |
| `migrations_scheduling` | VM migrations in scheduling state |

## Graphs Included

Each DataSource includes pre-configured graphs:

- **Discovery**: VMI Status
- **CPU**: CPU Usage, vCPU Metrics
- **Memory**: Memory Usage, Memory Allocation, Memory Swap
- **Network**: Network Throughput, Network Packets, Network Errors
- **Storage**: Storage Throughput, Storage IOPS, Storage Latency

## Prerequisites

- OpenShift 4.x with OpenShift Virtualization (KubeVirt/CNV) installed
- LogicMonitor Collector with HTTPS access to OpenShift cluster
- Service account with metrics read access (e.g., `prometheus-k8s` or custom)

## Device Properties Required

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `kubevirt.thanos.host` | Yes | - | Thanos Querier route hostname |
| `kubevirt.thanos.token` | Yes | - | Service account bearer token |
| `kubevirt.thanos.port` | No | 443 | Thanos Querier port |
| `kubevirt.thanos.ssl` | No | true | Use HTTPS |

## Setup Instructions

### 1. Get Thanos Querier Route

```bash
oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}'
```

### 2. Create a Long-Lived Token

Using the existing `prometheus-k8s` service account (has monitoring read access):

```bash
# Create token valid for 1 year
oc create token prometheus-k8s -n openshift-monitoring --duration=8760h
```

Or create a dedicated service account:

```bash
# Create ServiceAccount
oc create serviceaccount logicmonitor-monitoring -n openshift-monitoring

# Grant cluster-monitoring-view role
oc adm policy add-cluster-role-to-user cluster-monitoring-view \
  -z logicmonitor-monitoring -n openshift-monitoring

# Create long-lived token
oc create token logicmonitor-monitoring -n openshift-monitoring --duration=8760h
```

### 3. Create Device in LogicMonitor

1. Add a new device in LogicMonitor
2. Set the hostname to the Thanos Querier route (e.g., `thanos-querier-openshift-monitoring.apps.cluster.example.com`)
3. Add the following custom properties:
   - `kubevirt.thanos.host` = Thanos Querier route hostname
   - `kubevirt.thanos.token` = Service account bearer token

### 4. Apply DataSources

The DataSources should automatically apply to any device with both `kubevirt.thanos.host` and `kubevirt.thanos.token` properties set. Active Discovery will run and discover all VMIs.

## Instance Grouping

VMI instances are automatically grouped by namespace using Instance Level Properties (ILP). The `auto.vmi.namespace` property is set during discovery and used for grouping.

Example instance properties:
- `auto.vmi.name` - VM name
- `auto.vmi.namespace` - Kubernetes namespace
- `auto.vmi.node` - Node where VM is running
- `auto.vmi.phase` - VM phase (running, pending, etc.)

## Token Expiration

Service account tokens created with `--duration` will expire. Monitor token expiration and rotate before it expires:

```bash
# Check token expiration (decode JWT)
echo "YOUR_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '.exp | todate'

# Create new token
oc create token prometheus-k8s -n openshift-monitoring --duration=8760h
```

Update the `kubevirt.thanos.token` property in LogicMonitor when rotating tokens.

## Troubleshooting

### No Data Collected

1. Verify Thanos connectivity:
   ```bash
   curl -k -H "Authorization: Bearer $TOKEN" \
     "https://$THANOS_HOST/api/v1/query?query=kubevirt_vmi_info"
   ```

2. Check token validity (401 = expired/invalid token)

3. Verify VMIs exist:
   ```bash
   oc get vmi -A
   ```

### Zeros After Token Refresh

The LogicMonitor collector caches device properties. After updating the token, wait 5-10 minutes for the cache to refresh.

### Discovery Not Finding VMs

Ensure `kubevirt_vmi_info` metric exists in Thanos:
```bash
curl -k -H "Authorization: Bearer $TOKEN" \
  "https://$THANOS_HOST/api/v1/query?query=kubevirt_vmi_info" | jq '.data.result[].metric.name'
```

## License

Apache 2.0
