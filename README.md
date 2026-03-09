<!-- Description: README for LogicMonitor Thanos-based DataSources (KubeVirt suite and generic PromQL collector). -->
<!-- Description: Covers architecture, DataSource inventory, metrics, device properties, setup, and troubleshooting. -->

# LogicMonitor Thanos DataSources

LogicMonitor DataSources that query Thanos Querier endpoints. Includes a purpose-built KubeVirt monitoring suite for OpenShift Virtualization and a generic PromQL collector for custom queries.

## Overview

This project contains two distinct DataSource suites, both querying Thanos Querier over HTTPS:

| Suite | DataSources | Purpose | Device Properties |
|-------|-------------|---------|-------------------|
| **KubeVirt** (6 DataSources) | Cluster Overview, VMI Discovery, CPU, Memory, Network, Storage | Monitor OpenShift Virtualization VMs with pre-built metrics and graphs | `kubevirt.thanos.*` |
| **Thanos PromQL Collector** (1 DataSource) | Generic PromQL collector | Run arbitrary PromQL queries defined in device properties | `thanos.*` |

The two suites use **different property namespaces** and can run independently on separate devices, or on the same device if both property sets are configured.

## Architecture (KubeVirt Suite)

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

---

## KubeVirt DataSources (6)

For the full setup walkthrough, see the [Setup Guide](documentation/setup-guide.md).

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| KubeVirt_Cluster_Overview | 1 (cluster) | 1 min | 15 min | 11 | Cluster-wide VM counts and health |
| KubeVirt_VMI_Discovery | Per VM | 1 min | 15 min | 1 | Discovers VMs, tracks running state |
| KubeVirt_VMI_CPU | Per VM | 1 min | 15 min | 6 | CPU usage, system/user time, vCPU metrics |
| KubeVirt_VMI_Memory | Per VM | 1 min | 15 min | 10 | Memory usage, available, cached, swap |
| KubeVirt_VMI_Network | Per VM | 1 min | 15 min | 8 | Network throughput, packets, errors |
| KubeVirt_VMI_Storage | Per VM | 1 min | 15 min | 8 | Disk throughput, IOPS, latency |

**Total: 44 datapoints across 6 DataSources.**

All KubeVirt DataSources use:
- **appliesTo**: `kubevirt.thanos.host && kubevirt.thanos.pass`
- **Group**: `KubeVirt`
- **ILP grouping** by `auto.vmi.namespace` (VMs organized by Kubernetes namespace)

### Device Properties (KubeVirt)

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `kubevirt.thanos.host` | Yes | - | Thanos Querier route hostname |
| `kubevirt.thanos.pass` | Yes | - | Service account bearer token |
| `kubevirt.thanos.port` | No | 443 | Thanos Querier port |
| `kubevirt.thanos.ssl` | No | true | Use HTTPS |

### Quick Setup (KubeVirt)

For the full step-by-step walkthrough, see the [Setup Guide](documentation/setup-guide.md).

```bash
# 1. Get the Thanos Querier route
oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}'

# 2. Create a dedicated service account
oc create serviceaccount logicmonitor-thanos-reader -n openshift-monitoring

# 3. Grant read-only monitoring access
oc adm policy add-cluster-role-to-user cluster-monitoring-view \
  -z logicmonitor-thanos-reader -n openshift-monitoring

# 4. Generate a bearer token (1 year)
oc create token logicmonitor-thanos-reader -n openshift-monitoring --duration=8760h
```

Then in LogicMonitor:
1. Import all 6 `KubeVirt_*.json` files from `datasources/`
2. Create a device with the Thanos route as hostname
3. Set `kubevirt.thanos.host` and `kubevirt.thanos.pass` as device properties
4. All 6 DataSources attach automatically and Active Discovery finds your VMs

### Instance Properties (KubeVirt)

Each discovered VMI receives these auto-populated properties:

| Property | Description | Example |
|----------|-------------|---------|
| `auto.vmi.name` | VM instance name | `my-rhel-vm` |
| `auto.vmi.namespace` | Kubernetes namespace (ILP grouping) | `default` |
| `auto.vmi.node` | Node hosting the VMI | `worker-0.ocp.example.com` |
| `auto.vmi.phase` | VMI lifecycle phase (Discovery DS only) | `running` |

---

## Thanos PromQL Collector (1)

A generic, configurable DataSource that executes arbitrary PromQL queries against any Thanos endpoint. Queries are defined entirely through device properties, so no code changes are needed to add new metrics.

### How It Works

1. Set `thanos.host` and `thanos.pass` on a device to activate the DataSource
2. Define numbered query properties (`thanos.query.1.name`, `thanos.query.1.promql`, etc.)
3. Active Discovery creates one LM instance per query
4. Collection executes each PromQL query and stores the numeric result

### Device Properties (PromQL Collector)

**Connection properties:**

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `thanos.host` | Yes | - | Thanos Querier hostname |
| `thanos.pass` | Yes | - | Bearer token |
| `thanos.port` | No | 443 | Thanos Querier port |
| `thanos.ssl` | No | true | Use HTTPS |

**Query properties** (repeat for each query, numbered 1-99):

| Property | Required | Description |
|----------|----------|-------------|
| `thanos.query.N.name` | Yes | Instance name (used as LM instance ID) |
| `thanos.query.N.promql` | Yes | PromQL query expression |
| `thanos.query.N.group` | No | Instance group name (default: `default`) |

### Datapoints

| Datapoint | Description |
|-----------|-------------|
| `value` | Numeric result of the PromQL query |
| `query_status` | Execution status: 0=success, 1=empty/non-numeric, 2=connection/auth error |

### Example

To monitor custom metrics, set these device properties:

```
thanos.host = thanos-querier-openshift-monitoring.apps.cluster.example.com
thanos.pass = <bearer-token>
thanos.query.1.name = etcd_db_size
thanos.query.1.promql = sum(etcd_mvcc_db_total_size_in_bytes)
thanos.query.1.group = etcd
thanos.query.2.name = api_request_rate
thanos.query.2.promql = sum(rate(apiserver_request_total[5m]))
thanos.query.2.group = apiserver
```

Active Discovery creates two instances (`etcd_db_size` and `api_request_rate`), grouped under `etcd` and `apiserver` respectively.

---

## Prerequisites

- OpenShift 4.12+ with OpenShift Virtualization (KubeVirt/CNV) installed (for KubeVirt suite)
- Any Thanos Querier endpoint accessible over HTTPS (for PromQL Collector)
- LogicMonitor Collector with network access to the Thanos endpoint
- Service account with monitoring read access (recommended: dedicated `logicmonitor-thanos-reader` SA)

## Metrics Collected (KubeVirt)

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

## Graphs Included (KubeVirt)

Each KubeVirt DataSource includes pre-configured graphs:

- **Discovery**: VMI Status
- **CPU**: CPU Usage, vCPU Metrics
- **Memory**: Memory Usage, Memory Allocation, Memory Swap
- **Network**: Network Throughput, Network Packets, Network Errors
- **Storage**: Storage Throughput, Storage IOPS, Storage Latency

## Token Rotation

Service account tokens expire based on the `--duration` flag. Rotate before expiration:

```bash
# Check token expiration (decode JWT)
echo "YOUR_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '.exp | todate'

# Generate a new token
oc create token logicmonitor-thanos-reader -n openshift-monitoring --duration=8760h
```

Update the relevant property (`kubevirt.thanos.pass` or `thanos.pass`) in LogicMonitor. The Collector caches properties for 5-10 minutes, so data collection resumes automatically after the cache refreshes.

## Troubleshooting

### No DataSources Appearing on Device

- Verify both required properties are set (check the correct namespace: `kubevirt.thanos.*` for KubeVirt, `thanos.*` for PromQL Collector)
- Property names are case-sensitive
- Both required properties must be non-empty

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

The LogicMonitor Collector caches device properties. After updating the token, wait 5-10 minutes for the cache to refresh.

### Discovery Not Finding VMs

Ensure `kubevirt_vmi_info` metric exists in Thanos:
```bash
curl -k -H "Authorization: Bearer $TOKEN" \
  "https://$THANOS_HOST/api/v1/query?query=kubevirt_vmi_info" | jq '.data.result[].metric.name'
```

## License

GPL v3
