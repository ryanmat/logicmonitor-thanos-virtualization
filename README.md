<!-- Description: README for LogicMonitor OpenShift Thanos DataSource suite. -->
<!-- Description: Covers architecture, DataSource inventory, metrics, device properties, setup, and troubleshooting. -->

# OpenShift Thanos DataSource Suite

LogicMonitor DataSource suite for monitoring OpenShift environments via the Thanos Querier API. All suites share a single set of connection properties (`openshift.thanos.*`) and activate on the same device.

## Overview

| Suite | DataSources | Datapoints | Directory | Purpose |
|-------|-------------|------------|-----------|---------|
| **KubeVirt** | 6 | 44 | `datasources/kubevirt/` | Monitor OpenShift Virtualization VMs |
| **Etcd** | 3 | 23 | `datasources/etcd/` | Monitor etcd cluster health, disk, and network |
| **Thanos PromQL Collector** | 1 | 2 | `datasources/promql/` | Run arbitrary PromQL queries |

All suites use unified connection properties: `openshift.thanos.host` and `openshift.thanos.pass`. One device represents one cluster's Thanos Querier endpoint.

## Shared Device Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `openshift.thanos.host` | Yes | - | Thanos Querier route hostname |
| `openshift.thanos.pass` | Yes | - | Service account bearer token |
| `openshift.thanos.port` | No | 443 | Thanos Querier port |
| `openshift.thanos.ssl` | No | true | Use HTTPS |

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
- **appliesTo**: `openshift.thanos.host && openshift.thanos.pass`
- **Group**: `KubeVirt`
- **ILP grouping** by `auto.vmi.namespace` (VMs organized by Kubernetes namespace)

### Quick Setup

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
1. Import DataSource JSON files from the relevant `datasources/` subdirectories
2. Create a device with the Thanos route as hostname
3. Set `openshift.thanos.host` and `openshift.thanos.pass` as device properties
4. All DataSources attach automatically and Active Discovery finds instances

### Instance Properties (KubeVirt)

Each discovered VMI receives these auto-populated properties:

| Property | Description | Example |
|----------|-------------|---------|
| `auto.vmi.name` | VM instance name | `my-rhel-vm` |
| `auto.vmi.namespace` | Kubernetes namespace (ILP grouping) | `default` |
| `auto.vmi.node` | Node hosting the VMI | `worker-0.ocp.example.com` |
| `auto.vmi.phase` | VMI lifecycle phase (Discovery DS only) | `running` |

---

## Etcd DataSources (3)

Monitors OpenShift etcd cluster health via the same Thanos Querier endpoint. Etcd metrics are always available on OpenShift clusters since etcd is a core control plane component. No additional service account permissions are needed beyond `cluster-monitoring-view`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| Etcd_Cluster_Overview | 1 (cluster) | 1 min | 15 min | 8 | Cluster-wide member counts, leader health, aggregate proposals |
| Etcd_Member_Discovery | Per member | 1 min | 15 min | 2 | Discovers members, tracks leader status |
| Etcd_Member_Performance | Per member | 1 min | 15 min | 13 | WAL/commit latency, proposals, db size, peer network |

**Total: 23 datapoints across 3 DataSources.**

All Etcd DataSources use:
- **appliesTo**: `openshift.thanos.host && openshift.thanos.pass`
- **Group**: `Etcd`
- **ILP grouping** by `auto.etcd.namespace`

### Metrics Collected (Etcd)

#### Discovery (2 datapoints)
| Metric | Description |
|--------|-------------|
| `has_leader` | 1 if member sees a leader, 0 if not |
| `is_leader` | 1 if this member is the current leader |

#### Performance (13 datapoints)
| Metric | Description |
|--------|-------------|
| `leader_changes` | Leader election rate (alert if > 3/sec) |
| `proposals_committed` | Committed proposals/sec |
| `proposals_applied` | Applied proposals/sec |
| `proposals_pending` | Pending proposals (alert if > 100) |
| `proposals_failed` | Failed proposals/sec (alert if > 0) |
| `wal_fsync_p99` | WAL fsync p99 latency in ms (alert if > 15ms) |
| `backend_commit_p99` | Backend commit p99 latency in ms (alert if > 25ms) |
| `db_total_size` | Database size in bytes (alert if > 6GB) |
| `db_in_use_size` | Database size in use (post-compaction) |
| `keys_total` | Total keys in the store |
| `peer_rtt_p99` | Peer round-trip time p99 in ms |
| `peer_sent_bytes` | Peer sent bytes/sec |
| `peer_received_bytes` | Peer received bytes/sec |

#### Cluster Overview (8 datapoints)
| Metric | Description |
|--------|-------------|
| `members_total` | Total etcd members |
| `members_with_leader` | Members that see a leader (alert if < 3) |
| `leaders_active` | Active leaders (alert if != 1) |
| `total_db_size` | Aggregate database size |
| `total_keys` | Aggregate key count |
| `proposals_pending_total` | Aggregate pending proposals |
| `leader_changes_total` | Aggregate leader election rate |
| `proposals_failed_total` | Aggregate failed proposal rate |

### Instance Properties (Etcd)

Each discovered etcd member receives these auto-populated properties:

| Property | Description | Example |
|----------|-------------|---------|
| `auto.etcd.pod` | Etcd pod name | `etcd-master-0.ocp.example.com` |
| `auto.etcd.instance` | Etcd endpoint (IP:port) | `10.0.1.5:9979` |
| `auto.etcd.namespace` | Kubernetes namespace | `openshift-etcd` |

---

## Thanos PromQL Collector (1)

A generic, configurable DataSource that executes arbitrary PromQL queries against any Thanos endpoint. Queries are defined entirely through device properties, so no code changes are needed to add new metrics.

### How It Works

1. Set `openshift.thanos.host` and `openshift.thanos.pass` on a device (shared connection properties)
2. Define numbered query properties (`openshift.thanos.query.1.name`, `openshift.thanos.query.1.promql`, etc.)
3. Active Discovery creates one LM instance per query
4. Collection executes each PromQL query and stores the numeric result

### Query Properties (PromQL Collector)

Repeat for each query, numbered 1-99:

| Property | Required | Description |
|----------|----------|-------------|
| `openshift.thanos.query.N.name` | Yes | Instance name (used as LM instance ID) |
| `openshift.thanos.query.N.promql` | Yes | PromQL query expression |
| `openshift.thanos.query.N.group` | No | Instance group name (default: `default`) |

### Datapoints

| Datapoint | Description |
|-----------|-------------|
| `value` | Numeric result of the PromQL query |
| `query_status` | Execution status: 0=success, 1=empty/non-numeric, 2=connection/auth error |

### Example

To monitor custom metrics, set these device properties:

```
openshift.thanos.host = thanos-querier-openshift-monitoring.apps.cluster.example.com
openshift.thanos.pass = <bearer-token>
openshift.thanos.query.1.name = etcd_db_size
openshift.thanos.query.1.promql = sum(etcd_mvcc_db_total_size_in_bytes)
openshift.thanos.query.1.group = etcd
openshift.thanos.query.2.name = api_request_rate
openshift.thanos.query.2.promql = sum(rate(apiserver_request_total[5m]))
openshift.thanos.query.2.group = apiserver
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

Update `openshift.thanos.pass` in LogicMonitor. The Collector caches properties for 5-10 minutes, so data collection resumes automatically after the cache refreshes.

## Troubleshooting

### No DataSources Appearing on Device

- Verify both required properties are set: `openshift.thanos.host` and `openshift.thanos.pass`
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
