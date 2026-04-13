<!-- Description: README for LogicMonitor DataSource suites: OpenShift Thanos and Sonatype Nexus. -->
<!-- Description: Covers architecture, DataSource inventory, metrics, device properties, setup, and troubleshooting. -->

# LogicMonitor DataSource Suite

60 LogicMonitor DataSources + 2 PropertySources for monitoring OpenShift environments via Thanos Querier API and Sonatype Nexus Repository Manager via REST API.

## OpenShift Thanos Suite

54 DataSources + 1 PropertySource for monitoring OpenShift environments via the Thanos Querier API. Covers OCP platform, etcd, KubeVirt, ODF/Ceph, ACM, ArgoCD/GitOps, and Portworx. All suites share a single set of connection properties (`openshift.thanos.*`) and use category-based appliesTo gating via a PropertySource that auto-detects installed components.

## Overview

| Suite | DataSources | Datapoints | Directory | appliesTo | Purpose |
|-------|-------------|------------|-----------|-----------|---------|
| **OCP** | 15 | 90 | `datasources/ocp/` | `hasCategory("OpenShift_OCP")` | Operators, API server, scheduler, controller manager, kubelet, certificates, DNS, network, pods, throttling, monitoring, quotas, ingress |
| **Etcd** | 3 | 23 | `datasources/etcd/` | `hasCategory("OpenShift_Etcd")` | etcd cluster health, member performance, consensus |
| **KubeVirt** | 6 | 44 | `datasources/kubevirt/` | `hasCategory("OpenShift_KubeVirt")` | VM CPU, memory, network, storage, cluster overview |
| **ODF** | 10 | 63 | `datasources/odf/` | `hasCategory("OpenShift_ODF")` | Ceph health, OSD, pools, monitors, MDS, RGW, NooBaa |
| **ACM** | 5 | 32 | `datasources/acm/` | `hasCategory("OpenShift_ACM")` | Hub health, controllers, managed clusters, observability |
| **GitOps** | 5 | 28 | `datasources/gitops/` | `hasCategory("OpenShift_GitOps")` | ArgoCD sync, app health, controller, repo server, API server |
| **Portworx** | 9 | 54 | `datasources/portworx/` | `hasCategory("OpenShift_Portworx")` | Cluster health, node status, volumes, pools, disks, KVDB, autopilot |
| **PromQL Collector** | 1 | 2 | `datasources/promql/` | `openshift.thanos.host && openshift.thanos.pass` | Run arbitrary PromQL queries |

**Total: 54 DataSources, 336 datapoints across 8 suites.**

## Shared Device Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `openshift.thanos.host` | Yes | - | Thanos Querier hostname or internal SVC |
| `openshift.thanos.pass` | Yes | - | Service account bearer token |
| `openshift.thanos.port` | No | 443 | Thanos Querier port |
| `openshift.thanos.ssl` | No | true | Use HTTPS |

Internal SVC pattern (preferred): `thanos-querier.openshift-monitoring.svc:9091` with `ssl=true`
External route pattern: `<route-hostname>:443` with `ssl=true`

## Architecture

```
+---------------------------------------------------------------------------+
|                     OpenShift/ROSA Cluster                                |
|                                                                           |
|  +--------+ +------+ +-----+ +------+ +-----+ +-------+ +---------+     |
|  |KubeVirt| | etcd | | OCP | |ODF/  | | ACM | |ArgoCD | |Portworx |     |
|  | VMIs   | |member| |oper.| |Ceph  | | hub | |GitOps | |storage  |     |
|  +---+----+ +--+---+ +--+--+ +--+---+ +--+--+ +---+---+ +----+----+     |
|      |         |        |        |        |        |           |          |
|      v         v        v        v        v        v           v          |
|  +---------------------------------------------------------+             |
|  |  virt-handler / etcd / kube-state / ceph-mgr / ACM /    |             |
|  |  argocd-metrics / portworx-api                          |             |
|  |         Expose component-specific Prometheus metrics     |             |
|  +----------------------------+----------------------------+             |
|                               | scrape                                   |
|                               v                                          |
|  +---------------------------------------------------------+             |
|  |                   Prometheus                             |             |
|  |            (openshift-monitoring namespace)              |             |
|  +----------------------------+----------------------------+             |
|                               |                                          |
|                               v                                          |
|  +---------------------------------------------------------+             |
|  |               Thanos Querier                             |             |
|  |    thanos-querier.openshift-monitoring.svc              |             |
|  |  - Provides PromQL API (/api/v1/query)                  |             |
|  |  - Aggregates data from Prometheus instances            |             |
|  +----------------------------+----------------------------+             |
|                               |                                          |
+-------------------------------+------------------------------------------+
                                | HTTPS
                                v
                    +-----------------------+
                    |   LogicMonitor        |
                    |   Collector           |
                    |                       |
                    |  1. PropertySource    |
                    |     probes Thanos     |
                    |     -> sets categories|
                    |                       |
                    |  2. DataSources       |
                    |     match categories  |
                    |     -> collect metrics|
                    +-----------------------+
```

### How It Works

1. **Metric exporters** (virt-handler, etcd, kube-state-metrics, ceph-mgr, ACM controllers, ArgoCD metrics, Portworx) expose Prometheus metrics
2. **Prometheus** (OpenShift Monitoring Stack) scrapes these metrics every 30 seconds
3. **Thanos Querier** provides a unified PromQL API endpoint
4. **PropertySource** (`addCategory_OpenShift_Thanos`) probes Thanos for component-specific metrics and sets `system.categories` on the device
5. **DataSources** use `hasCategory()` appliesTo to activate only for installed components

### Why Thanos Instead of Direct Prometheus?

- **Security**: Thanos Querier is exposed via OpenShift Route with proper RBAC
- **Stability**: Abstracts away Prometheus HA pairs and federation complexity
- **Standard API**: Same PromQL interface regardless of backend topology
- **Future-proof**: Supports multi-cluster aggregation if needed

---

## PropertySource: Auto-Detection

The `addCategory_OpenShift_Thanos` PropertySource runs on any device with `openshift.thanos.host && openshift.thanos.pass`. It probes 7 PromQL queries to detect which components are installed and appends the corresponding categories to `system.categories`.

| Category | Probe Query | Component |
|----------|-------------|-----------|
| `OpenShift_OCP` | `cluster_version{type="current"}` | OCP platform (always present on OpenShift) |
| `OpenShift_Etcd` | `etcd_server_has_leader` | etcd (always present on OpenShift) |
| `OpenShift_KubeVirt` | `kubevirt_info` | OpenShift Virtualization (CNV) |
| `OpenShift_ODF` | `ceph_health_status` | OpenShift Data Foundation (Ceph) |
| `OpenShift_ACM` | `acm_managed_cluster_info` | Advanced Cluster Management hub |
| `OpenShift_GitOps` | `argocd_app_info` | ArgoCD / OpenShift GitOps |
| `OpenShift_Portworx` | `px_cluster_status_quorum` | Portworx Enterprise storage |

Each DataSource suite uses `hasCategory("OpenShift_XXX")` as its appliesTo, so it only activates on devices where the corresponding component was detected. The PromQL Collector uses a flat property check instead (it is a generic tool, not suite-specific).

---

## Quick Setup

```bash
# 1. Create a dedicated service account
oc create serviceaccount logicmonitor-thanos-reader -n openshift-monitoring

# 2. Grant read-only monitoring access
oc adm policy add-cluster-role-to-user cluster-monitoring-view \
  -z logicmonitor-thanos-reader -n openshift-monitoring

# 3. Generate a bearer token (1 year)
oc create token logicmonitor-thanos-reader -n openshift-monitoring --duration=8760h
```

Then in LogicMonitor:
1. Navigate to **Settings > LogicModules > PropertySources > Add > Import** and upload `propertysources/addCategory_OpenShift_Thanos.json`
2. Navigate to **Settings > LogicModules > DataSources > Add > Import** and upload each JSON file from the relevant `datasources/` subdirectories
3. Set `openshift.thanos.host` and `openshift.thanos.pass` as device properties on the target device (or device group)
4. The PropertySource auto-detects installed components and sets categories
5. DataSources activate automatically based on detected categories

### Verify Installation

After importing and setting device properties, verify data is flowing:

1. **Check categories**: Go to the device's **Info** tab. Under `system.categories`, you should see categories like `OpenShift_OCP`, `OpenShift_Etcd`, etc. for each detected component. If categories are missing, check the PropertySource execution log on the collector.
2. **Check DataSource instances**: Go to the device's **Resources** tab. DataSources should appear grouped by suite (OCP, Etcd, etc.). Multi-instance DataSources (e.g., `OCP_Operator_Health`, `OCP_Kubelet_Health`) should show discovered instances.
3. **Check data collection**: Click into a DataSource instance and view the **Raw Data** tab. After 2-3 collection cycles (2-3 minutes), you should see non-zero values for most datapoints. Datapoints that return `0` on a healthy cluster (e.g., `error_rate`, `nodes_offline`) are expected.
4. **Troubleshoot**: If no data appears, check the Troubleshooting section at the end of this document.

---

## OCP Platform DataSources (15)

Monitors OpenShift platform health: operators, API server, scheduler, controller manager, kubelet, certificates, DNS, networking, pods, CPU throttling, monitoring stack, resource quotas, image pulls, and ingress latency. All metrics come from standard OpenShift components exposed through the Thanos Querier.

DataSource files are located in `datasources/ocp/`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| OCP_Operator_Health | Per operator (~30-35) | 1 min | 15 min | 4 | Per-operator Available, Degraded, Progressing, Upgradeable conditions |
| OCP_Operator_Overview | 1 (cluster) | 1 min | 15 min | 3 | Cluster-wide operator degraded/unavailable counts |
| OCP_API_Server_Performance | 1 (apiserver) | 1 min | 15 min | 8 | API server request rate, p95/p99 latency, inflight, 429s, terminations |
| OCP_Pod_Health | 1 (cluster) | 1 min | 15 min | 7 | Cluster-wide pod failure counts by reason |
| OCP_Image_Pull_Health | Per node + 1 cluster | 1 min | 15 min | 5 | Per-node CRI-O pull rates and cluster pull failure counts |
| OCP_Ingress_Latency | Per route | 1 min | 15 min | 6 | Per-route HAProxy request rate, latency, errors, backend status |
| OCP_Certificate_Health | 1 (cluster) | 1 min | 15 min | 4 | Certificate expiry, kubelet cert TTL, TLS errors, rotation age |
| OCP_Controller_Manager | 1 (controller) | 1 min | 15 min | 6 | Work queue depth, add rate, queue/work duration p99, retries |
| OCP_Scheduler_Performance | 1 (scheduler) | 1 min | 15 min | 7 | Pending pods by queue, scheduling attempts, p99 latency, preemptions |
| OCP_CPU_Throttling | 1 (cluster) | 1 min | 15 min | 5 | Throttled periods rate, ratio, seconds, namespaces above 25% |
| OCP_Monitoring_Health | 1 (monitoring) | 1 min | 15 min | 6 | Prometheus instances up, TSDB series, WAL corruptions, targets down |
| OCP_Kubelet_Health | Per node (~8-10) | 1 min | 15 min | 7 | PLEG relist p99, runtime errors, pod start latency, running pods/containers |
| OCP_CoreDNS_Health | 1 (cluster) | 1 min | 15 min | 7 | DNS request rate, p99 latency, SERVFAIL/NXDOMAIN rates, cache hit ratio |
| OCP_OVN_Network_Health | 1 (cluster) | 1 min | 15 min | 7 | CNI request p99, OVS packet drops/errors, southbound DB connectivity |
| OCP_ResourceQuota_Usage | Per namespace with quotas | 1 min | 15 min | 8 | CPU/memory/pods used vs hard limits and utilization percentages |

**Total: 90 datapoints across 15 DataSources.**

- **appliesTo**: `hasCategory("OpenShift_OCP")`
- **Group**: `OCP`

### Metrics Collected (OCP)

#### Operator Health (4 datapoints)
| Metric | Description |
|--------|-------------|
| `available` | 1 if operator Available condition is true (alert if < 1) |
| `degraded` | 1 if operator Degraded condition is true (alert if > 0) |
| `progressing` | 1 if operator Progressing condition is true |
| `upgradeable` | 1 if operator Upgradeable condition is true |

#### Operator Overview (3 datapoints)
| Metric | Description |
|--------|-------------|
| `operators_degraded` | Count of operators with Degraded=true (alert if > 0) |
| `operators_unavailable` | Count of operators with Available=false (alert if > 0) |
| `operators_total` | Total number of ClusterOperators |

#### API Server Performance (8 datapoints)
| Metric | Description |
|--------|-------------|
| `request_rate` | Total API requests per second |
| `error_rate_5xx` | 5xx responses per second (alert if > 10) |
| `p95_latency_ms` | Request latency p95 in ms, excludes WATCH (alert if > 1000) |
| `p99_latency_ms` | Request latency p99 in ms, excludes WATCH (alert if > 2000) |
| `inflight_mutating` | Current in-flight mutating requests (alert if > 400) |
| `inflight_readonly` | Current in-flight read-only requests (alert if > 600) |
| `rate_limited_429` | Rate-limited 429 responses per second (alert if > 0) |
| `terminated_requests` | Terminated requests per second (alert if > 0) |

#### Pod Health (7 datapoints)
| Metric | Description |
|--------|-------------|
| `pods_crashloopbackoff` | Pods in CrashLoopBackOff state (alert if > 0) |
| `pods_imagepullbackoff` | Pods in ImagePullBackOff state (alert if > 0) |
| `pods_errimagepull` | Pods in ErrImagePull state (alert if > 0) |
| `pods_oomkilled` | Pods with OOMKilled last termination (alert if > 0) |
| `pods_pending` | Pods in Pending phase (alert if > 5) |
| `pods_failed` | Pods in Failed phase (alert if > 0) |
| `pods_createcontainererror` | Pods in CreateContainerConfigError state (alert if > 0) |

#### Image Pull Health (5 datapoints)
| Metric | Description |
|--------|-------------|
| `pull_success_rate` | Image pulls succeeded per second (per-node) |
| `pull_failure_rate` | Image pulls failed per second (per-node, alert if > 0) |
| `pull_duration_p99_ms` | Image pull duration p99 in ms (per-node, alert if > 30000) |
| `cluster_pull_failures` | Total pull failures per second cluster-wide (alert if > 0) |
| `cluster_imagepullbackoff` | Pods in ImagePullBackOff state cluster-wide (alert if > 0) |

#### Ingress Latency (6 datapoints)
| Metric | Description |
|--------|-------------|
| `request_rate` | Total requests per second for this route |
| `error_rate_5xx` | 5xx responses per second (alert if > 10) |
| `response_time_avg_ms` | Average backend response time in ms (alert if > 5000) |
| `connection_errors` | Backend connection errors per second (alert if > 0) |
| `backend_up` | Backend server status, 1=up 0=down (alert if < 1) |
| `active_sessions` | Current active sessions for this route |

#### Certificate Health (4 datapoints)
| Metric | Description |
|--------|-------------|
| `min_cert_expiry_seconds` | Minimum certificate expiry time across all certs in seconds |
| `kubelet_cert_ttl_seconds` | Kubelet serving certificate TTL in seconds |
| `tls_handshake_errors` | TLS handshake errors per second (alert if > 0) |
| `client_cert_rotation_age` | Client certificate rotation age in seconds |

#### Controller Manager (6 datapoints)
| Metric | Description |
|--------|-------------|
| `workqueue_depth` | Controller manager work queue depth (alert if > 100) |
| `adds_rate` | Work queue item additions per second |
| `queue_duration_p99_ms` | Queue wait duration p99 in ms |
| `work_duration_p99_ms` | Work processing duration p99 in ms |
| `retries_rate` | Work queue retries per second (alert if > 5) |
| `unfinished_work_seconds` | Seconds of unfinished work in queue |

#### Scheduler Performance (7 datapoints)
| Metric | Description |
|--------|-------------|
| `pending_active` | Pods in active scheduling queue |
| `pending_backoff` | Pods in scheduling backoff queue |
| `pending_unschedulable` | Pods in unschedulable queue (alert if > 0) |
| `schedule_attempts_rate` | Scheduling attempts per second |
| `scheduling_p99_ms` | End-to-end scheduling latency p99 in ms (alert if > 5000) |
| `preemption_attempts_rate` | Preemption attempts per second |
| `scheduling_errors_rate` | Scheduling errors per second (alert if > 0) |

#### CPU Throttling (5 datapoints)
| Metric | Description |
|--------|-------------|
| `throttled_periods_rate` | CPU throttled periods per second across all containers |
| `total_periods_rate` | Total CFS periods per second |
| `throttle_ratio_pct` | Percentage of periods that are throttled (alert if > 25) |
| `throttled_seconds_rate` | CPU seconds lost to throttling per second |
| `namespaces_above_25pct` | Number of namespaces with throttle ratio above 25% (alert if > 0) |

#### Monitoring Health (6 datapoints)
| Metric | Description |
|--------|-------------|
| `prometheus_instances_up` | Number of Prometheus instances reporting as up |
| `tsdb_head_series` | Total active time series in Prometheus TSDB |
| `wal_corruptions` | Write-ahead log corruption count (alert if > 0) |
| `targets_down` | Number of scrape targets not responding (alert if > 0) |
| `config_reload_success` | Configuration reload success (1=ok, 0=failed, alert if < 1) |
| `query_duration_p99_ms` | Prometheus query duration p99 in ms |

#### Kubelet Health (7 datapoints)
| Metric | Description |
|--------|-------------|
| `pleg_relist_p99_ms` | PLEG relist interval p99 in ms (alert if > 5000) |
| `runtime_errors_rate` | Container runtime operation errors per second (alert if > 0) |
| `pod_start_p99_ms` | Pod startup latency p99 in ms (alert if > 30000) |
| `running_pods` | Number of running pods on this node |
| `running_containers` | Number of running containers on this node |
| `evictions_rate` | Pod evictions per second (alert if > 0) |
| `cert_ttl_seconds` | Kubelet certificate TTL in seconds |

#### CoreDNS Health (7 datapoints)
| Metric | Description |
|--------|-------------|
| `request_rate` | DNS requests per second |
| `request_duration_p99_ms` | DNS request duration p99 in ms (alert if > 500) |
| `servfail_rate` | SERVFAIL responses per second (alert if > 0) |
| `nxdomain_rate` | NXDOMAIN responses per second |
| `cache_hit_ratio_pct` | DNS cache hit ratio percentage |
| `forward_error_rate` | DNS forward errors per second (alert if > 0) |
| `panics_total` | CoreDNS panic count (alert if > 0) |

#### OVN Network Health (7 datapoints)
| Metric | Description |
|--------|-------------|
| `cni_request_p99_ms` | CNI add/del request latency p99 in ms (alert if > 5000) |
| `ovs_rx_dropped_rate` | OVS received packets dropped per second (alert if > 0) |
| `ovs_tx_dropped_rate` | OVS transmitted packets dropped per second (alert if > 0) |
| `ovs_rx_errors_rate` | OVS receive errors per second (alert if > 0) |
| `ovs_tx_errors_rate` | OVS transmit errors per second (alert if > 0) |
| `southbound_db_connected` | Southbound DB connection status, 1=connected (alert if < 1) |
| `sb_disconnects_rate` | Southbound DB disconnection events per second (alert if > 0) |

#### ResourceQuota Usage (8 datapoints)
| Metric | Description |
|--------|-------------|
| `cpu_used` | CPU cores currently used by the namespace |
| `cpu_hard` | CPU core limit (hard quota) for the namespace |
| `cpu_pct` | CPU quota utilization percentage (alert if > 90) |
| `memory_used` | Memory bytes currently used by the namespace |
| `memory_hard` | Memory byte limit (hard quota) for the namespace |
| `memory_pct` | Memory quota utilization percentage (alert if > 90) |
| `pods_used` | Number of pods in the namespace |
| `pods_hard` | Pod count limit (hard quota) for the namespace |

### Instance Properties (OCP)

| Property | Description | Example |
|----------|-------------|---------|
| `auto.operator.name` | ClusterOperator name | `authentication` |
| `auto.node.instance` | CRI-O node instance endpoint | `10.1.14.10:9637` |
| `auto.node.name` | Node hostname (Kubelet_Health, Image_Pull_Health) | `ip-10-55-117-247.us-west-2.compute.internal` |
| `auto.route.name` | HAProxy route name | `console` |
| `auto.route.namespace` | Route namespace (ILP grouping) | `openshift-console` |
| `auto.quota.namespace` | Namespace with ResourceQuota (ILP grouping) | `my-app` |

---

## Etcd DataSources (3)

Monitors OpenShift etcd cluster health via the same Thanos Querier endpoint. Etcd metrics are always available on OpenShift clusters since etcd is a core control plane component. No additional service account permissions are needed beyond `cluster-monitoring-view`.

DataSource files are located in `datasources/etcd/`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| Etcd_Cluster_Overview | 1 (cluster) | 1 min | 15 min | 8 | Cluster-wide member counts, leader health, aggregate proposals |
| Etcd_Member_Discovery | Per member | 1 min | 15 min | 2 | Discovers members, tracks leader status |
| Etcd_Member_Performance | Per member | 1 min | 15 min | 13 | WAL/commit latency, proposals, db size, peer network |

**Total: 23 datapoints across 3 DataSources.**

- **appliesTo**: `hasCategory("OpenShift_Etcd")`
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

| Property | Description | Example |
|----------|-------------|---------|
| `auto.etcd.pod` | Etcd pod name | `etcd-master-0.ocp.example.com` |
| `auto.etcd.instance` | Etcd endpoint (IP:port) | `10.0.1.5:9979` |
| `auto.etcd.namespace` | Kubernetes namespace | `openshift-etcd` |

---

## KubeVirt DataSources (6)

Monitors OpenShift Virtualization (KubeVirt/CNV) virtual machine instances. Requires CNV to be installed on the cluster.

For the full setup walkthrough, see the [Setup Guide](documentation/KubeVirt_Monitoring_Setup_Guide.docx).

DataSource files are located in `datasources/kubevirt/`.

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

- **appliesTo**: `hasCategory("OpenShift_KubeVirt")`
- **Group**: `KubeVirt`
- **ILP grouping** by `auto.vmi.namespace` (VMs organized by Kubernetes namespace)

### Metrics Collected (KubeVirt)

#### Discovery (1 datapoint)
| Metric | Description |
|--------|-------------|
| `status` | VM state: 1=Running, 2=Scheduling, 3=Pending, 4=Failed, 0=Unknown |

#### CPU (6 datapoints)
| Metric | Description |
|--------|-------------|
| `cpu_usage_seconds` | Overall CPU utilization (%) |
| `cpu_system_seconds` | System CPU time (%) |
| `cpu_user_seconds` | User CPU time (%) |
| `vcpu_seconds` | vCPU utilization (%) |
| `vcpu_wait` | vCPU wait time (%) |
| `vcpu_delay` | vCPU scheduling delay (%) |

#### Memory (10 datapoints)
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

#### Network (8 datapoints)
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

#### Storage (8 datapoints)
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

#### Cluster Overview (11 datapoints)
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

### Instance Properties (KubeVirt)

| Property | Description | Example |
|----------|-------------|---------|
| `auto.vmi.name` | VM instance name | `my-rhel-vm` |
| `auto.vmi.namespace` | Kubernetes namespace (ILP grouping) | `default` |
| `auto.vmi.node` | Node hosting the VMI | `worker-0.ocp.example.com` |
| `auto.vmi.phase` | VMI lifecycle phase (Discovery DS only) | `running` |

---

## ODF DataSources (10)

Monitors OpenShift Data Foundation (Ceph) storage: cluster health, OSD daemons, storage pools, monitors, MDS metadata servers, RADOS Gateway, NooBaa object storage, and the Rook operator. Requires ODF to be installed on the cluster.

DataSource files are located in `datasources/odf/`.

Ceph I/O metrics (read_bytes_per_sec, write_bytes_per_sec, read_ops_per_sec, write_ops_per_sec) are pre-computed rates exposed by ceph-mgr. Do not wrap them in `rate()`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| ODF_Ceph_Cluster_Overview | 1 (cluster) | 1 min | 15 min | 10 | Cluster health, capacity, client I/O, OSD count |
| ODF_Ceph_PG_Status | 1 (cluster) | 1 min | 15 min | 8 | Placement group health and recovery status |
| ODF_Ceph_OSD_Discovery | Per OSD | 1 min | 15 min | 4 | OSD up/in status and capacity |
| ODF_Ceph_OSD_Performance | Per OSD | 1 min | 15 min | 8 | Per-OSD latency, IOPS, throughput, utilization |
| ODF_Ceph_Pool_Performance | Per pool | 1 min | 15 min | 8 | Per-pool capacity, IOPS, throughput, objects |
| ODF_Ceph_Monitor_Health | Per monitor | 1 min | 15 min | 4 | Quorum status, elections, store health |
| ODF_NooBaa_Health | 1 (cluster) | 1 min | 15 min | 6 | NooBaa S3 gateway health and bucket counts |
| ODF_Rook_Operator_Health | 1 (cluster) | 1 min | 15 min | 4 | Rook-Ceph operator health summary |
| ODF_Ceph_RGW_Performance | Per RGW daemon | 1 min | 15 min | 6 | RGW request rate, throughput, latency |
| ODF_Ceph_MDS_Health | Per MDS daemon | 1 min | 15 min | 5 | MDS cache, requests, memory |

**Total: 63 datapoints across 10 DataSources.**

- **appliesTo**: `hasCategory("OpenShift_ODF")`
- **Group**: `ODF`
- RGW and MDS DataSources return 0 instances when those components are not deployed (RGW is not deployed by default in ODF 4.20+)

### Metrics Collected (ODF)

#### Cluster Overview (10 datapoints)
| Metric | Description |
|--------|-------------|
| `health_status` | Ceph health: 0=OK, 1=WARN, 2=ERR (alert if > 0) |
| `total_bytes` | Total cluster raw capacity |
| `used_bytes` | Total cluster raw bytes used |
| `available_bytes` | Total cluster raw bytes available |
| `used_percent` | Cluster capacity used percentage (alert if > 85) |
| `read_bytes_per_sec` | Client read throughput (pre-computed rate) |
| `write_bytes_per_sec` | Client write throughput (pre-computed rate) |
| `read_ops_per_sec` | Client read operations/sec (pre-computed rate) |
| `write_ops_per_sec` | Client write operations/sec (pre-computed rate) |
| `osds_total` | Total number of OSDs |

#### PG Status (8 datapoints)
| Metric | Description |
|--------|-------------|
| `total` | Total placement groups |
| `active` | PGs in active state |
| `clean` | PGs in clean state (all replicas present) |
| `degraded` | Degraded PGs (alert if > 0) |
| `undersized` | Undersized PGs (alert if > 0) |
| `stale` | Stale PGs (alert if > 0) |
| `unclean` | Total unclean PGs (alert if > 0) |
| `recovering_bytes_per_sec` | Recovery throughput |

#### OSD Discovery (4 datapoints)
| Metric | Description |
|--------|-------------|
| `up` | OSD daemon running: 1=up, 0=down (alert if < 1) |
| `in_cluster` | OSD in cluster map: 1=in, 0=out (alert if < 1) |
| `total_bytes` | OSD total capacity |
| `used_bytes` | OSD used capacity |

#### OSD Performance (8 datapoints)
| Metric | Description |
|--------|-------------|
| `apply_latency_ms` | OSD apply (flush to disk) latency (alert if > 50ms) |
| `commit_latency_ms` | OSD commit (WAL) latency (alert if > 50ms) |
| `read_ops` | Read operations/sec |
| `write_ops` | Write operations/sec |
| `read_bytes` | Read throughput |
| `write_bytes` | Write throughput |
| `op_latency_avg_ms` | Average operation latency (alert if > 100ms) |
| `used_percent` | OSD capacity used percentage (alert if > 85) |

#### Pool Performance (8 datapoints)
| Metric | Description |
|--------|-------------|
| `bytes_used` | Pool bytes used |
| `max_avail` | Pool maximum available bytes |
| `percent_used` | Pool capacity percentage (alert if > 80) |
| `read_ops` | Pool read operations/sec |
| `write_ops` | Pool write operations/sec |
| `read_bytes` | Pool read throughput |
| `write_bytes` | Pool write throughput |
| `objects` | Number of objects in pool |

#### Monitor Health (4 datapoints)
| Metric | Description |
|--------|-------------|
| `quorum_status` | In quorum: 1=yes, 0=no (alert if < 1) |
| `num_elections` | Leader elections/sec (alert if > 1) |
| `store_log_bytes` | Monitor store log size |
| `session_count` | Active client sessions |

#### NooBaa Health (6 datapoints)
| Metric | Description |
|--------|-------------|
| `system_health` | NooBaa health: 0=OK (alert if > 0) |
| `unhealthy_buckets` | Unhealthy S3 buckets (alert if > 0) |
| `total_usage_bytes` | Total storage used by NooBaa |
| `num_buckets` | Total S3 buckets |
| `num_objects` | Total S3 objects |
| `accounts_total` | Total NooBaa accounts |

#### Rook Operator Health (4 datapoints)
| Metric | Description |
|--------|-------------|
| `ceph_status_healthy` | Ceph healthy: 1=yes, 0=degraded |
| `osd_down_count` | OSDs that are down (alert if > 0) |
| `osd_out_count` | OSDs out of cluster map (alert if > 0) |
| `mon_quorum_count` | Monitors in quorum (alert if < 3) |

#### RGW Performance (6 datapoints)
| Metric | Description |
|--------|-------------|
| `request_rate` | Total RGW requests/sec |
| `failed_request_rate` | Failed requests/sec (alert if > 0) |
| `get_bytes` | GET throughput |
| `put_bytes` | PUT throughput |
| `get_latency_avg_ms` | Average GET latency (alert if > 500ms) |
| `put_latency_avg_ms` | Average PUT latency (alert if > 500ms) |

#### MDS Health (5 datapoints)
| Metric | Description |
|--------|-------------|
| `num_caps` | Client capabilities held by MDS |
| `num_inodes` | Inodes in MDS cache |
| `request_rate` | Metadata requests/sec |
| `root_rbytes` | Total bytes managed by MDS |
| `mem_rss_bytes` | MDS resident memory |

### Instance Properties (ODF)

| Property | Description | Example |
|----------|-------------|---------|
| `auto.osd.id` | OSD daemon ID | `0` |
| `auto.osd.device_class` | Storage device class (ILP grouping) | `ssd` |
| `auto.osd.hostname` | Host running the OSD | `worker-0.ocp.example.com` |
| `auto.pool.id` | Ceph pool ID | `1` |
| `auto.pool.name` | Ceph pool name | `ocs-storagecluster-cephblockpool` |
| `auto.mon.id` | Monitor daemon ID | `a` |
| `auto.rgw.id` | RGW daemon ID | `ocs.rgw.a` |
| `auto.mds.id` | MDS daemon ID | `ocs-storagecluster-cephfilesystem-a` |

---

## ACM DataSources (5)

Monitors Red Hat Advanced Cluster Management hub: hub deployments, controller reconciliation health, managed cluster status, and the ACM Observability Thanos stack. ACM DataSources only activate on the hub cluster (where ACM is installed), not on managed clusters.

DataSource files are located in `datasources/acm/`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| ACM_Hub_Components | Per deployment (~60-70) | 1 min | 15 min | 6 | Hub deployment replica health, restarts |
| ACM_Controller_Health | Per controller (~15) | 1 min | 15 min | 5 | Reconciliation rate, errors, latency, queue depth |
| ACM_Hub_Overview | 1 (hub) | 1 min | 15 min | 7 | Fleet-level managed cluster counts and hub health |
| ACM_Observability_Health | 1 (conditional) | 1 min | 15 min | 8 | Thanos stack health (only if observability is deployed) |
| ACM_Managed_Cluster_Status | Per cluster | 1 min | 15 min | 6 | Per-cluster availability, join status, worker capacity |

**Total: 32 datapoints across 5 DataSources.**

- **appliesTo**: `hasCategory("OpenShift_ACM")`
- **Group**: `ACM`
- ACM_Observability_Health uses conditional discovery: instance only created when the observability addon is deployed

### Metrics Collected (ACM)

#### Hub Components (6 datapoints)
| Metric | Description |
|--------|-------------|
| `replicas_desired` | Desired replica count |
| `replicas_ready` | Ready replicas |
| `replicas_available` | Available replicas |
| `replicas_unavailable` | Unavailable replicas (alert if > 0) |
| `container_restarts` | Container restart rate/sec (alert if > 0) |
| `ready_ratio` | Ready/desired ratio, 1.0 = healthy (alert if < 1) |

#### Controller Health (5 datapoints)
| Metric | Description |
|--------|-------------|
| `reconcile_rate` | Reconciliation rate/sec |
| `reconcile_errors` | Error rate/sec (alert if > 1) |
| `reconcile_duration_p99` | p99 reconciliation duration in seconds (alert if > 30) |
| `workqueue_depth` | Controller work queue depth (alert if > 50) |
| `workqueue_retries` | Work queue retry rate/sec (alert if > 10) |

#### Hub Overview (7 datapoints)
| Metric | Description |
|--------|-------------|
| `clusters_total` | Total managed clusters |
| `clusters_available` | Clusters with Available=true |
| `clusters_unavailable` | Clusters not available (alert if > 0) |
| `clusters_joined` | Clusters with Joined=true |
| `total_worker_cores` | Total worker CPU cores across fleet |
| `hub_reconcile_errors` | Hub controller error rate/sec (alert if > 5) |
| `hub_workqueue_depth` | Hub controller queue depth (alert if > 100) |

#### Observability Health (8 datapoints)
| Metric | Description |
|--------|-------------|
| `thanos_query_up` | Thanos Query up: 1/0 (alert if < 1) |
| `thanos_receive_up` | Thanos Receive up: 1/0 (alert if < 1) |
| `thanos_store_up` | Thanos Store up: 1/0 (alert if < 1) |
| `thanos_compact_up` | Thanos Compactor up: 1/0 (alert if < 1) |
| `receive_request_rate` | Receive HTTP request rate/sec |
| `receive_error_rate` | Receive 5xx error rate/sec (alert if > 5) |
| `grpc_error_rate` | gRPC error rate/sec (alert if > 10) |
| `observability_components_up` | Healthy component count |

#### Managed Cluster Status (6 datapoints)
| Metric | Description |
|--------|-------------|
| `available` | Cluster available: 1/0 (alert if < 1) |
| `joined` | Cluster joined: 1/0 (alert if < 1) |
| `hub_accepted` | Hub accepted registration: 1/0 (alert if < 1) |
| `import_succeeded` | Import succeeded: 1/0 |
| `clock_synced` | Clock synced: 1/0 |
| `worker_cores` | Worker CPU cores for this cluster |

### Instance Properties (ACM)

| Property | Description | Example |
|----------|-------------|---------|
| `auto.deployment.name` | Hub deployment name | `multicluster-operators-hub-subscription` |
| `auto.deployment.namespace` | Deployment namespace (ILP grouping) | `open-cluster-management` |
| `auto.controller.name` | Controller name | `klusterlet-registration-controller` |
| `auto.cluster.name` | Managed cluster name | `local-cluster` |
| `auto.cluster.cloud` | Cloud provider (ILP grouping) | `Azure` |

---

## GitOps DataSources (5)

Monitors ArgoCD / OpenShift GitOps deployments: application sync status, fleet-level health distribution, controller reconciliation performance, git repo server operations, and API server/backend metrics. Requires the OpenShift GitOps operator (or upstream ArgoCD) to be installed on the cluster.

DataSource files are located in `datasources/gitops/`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| ArgoCD_Sync_Overview | 1 (fleet) | 1 min | 15 min | 7 | Fleet-wide app counts by sync/health status, sync operation rates |
| ArgoCD_Application_Health | Per application | 1 min | 15 min | 4 | Per-app sync status, health status, reconciliation count and p95 latency |
| ArgoCD_Controller_Performance | 1 (controller) | 1 min | 15 min | 6 | Reconciliation latency percentiles (p50/p95/p99), error rate, queue depth |
| ArgoCD_Repo_Server | 1 (repo server) | 1 min | 15 min | 5 | Git request/fetch rates, latency percentiles, error rate |
| ArgoCD_Server_Metrics | 1 (API server) | 1 min | 15 min | 6 | gRPC request/error rates, Redis latency, notification delivery health |

**Total: 28 datapoints across 5 DataSources.**

- **appliesTo**: `hasCategory("OpenShift_GitOps")`
- **Group**: `GitOps`
- **ILP Grouping**: Application_Health groups by `auto.app.project`

### Metrics Collected (GitOps)

#### Sync Overview (7 datapoints)
| Metric | Description |
|--------|-------------|
| `total_apps` | Total number of ArgoCD-managed applications |
| `synced_apps` | Applications with sync status Synced |
| `out_of_sync_apps` | Applications with sync status OutOfSync (alert if > 0) |
| `healthy_apps` | Applications with health status Healthy |
| `degraded_apps` | Applications with health status Degraded (alert if > 0) |
| `sync_success_rate` | Successful sync operations per second (5m rate) |
| `sync_failure_rate` | Failed sync operations per second (alert if > 0) |

#### Application Health (4 datapoints)
| Metric | Description |
|--------|-------------|
| `sync_status` | Application sync status: 1=Synced, 0=OutOfSync (alert if < 1) |
| `health_status` | Application health: 1=Healthy, 2=Degraded, 3=Missing, 0=Unknown |
| `reconcile_count` | Reconciliation operations per second (5m rate) |
| `p95_reconcile_ms` | 95th percentile reconciliation duration in ms (alert if > 60000) |

#### Controller Performance (6 datapoints)
| Metric | Description |
|--------|-------------|
| `p50_reconcile_ms` | 50th percentile reconciliation duration in ms |
| `p95_reconcile_ms` | 95th percentile reconciliation duration in ms (alert if > 30000) |
| `p99_reconcile_ms` | 99th percentile reconciliation duration in ms (alert if > 60000) |
| `reconcile_rate` | Total reconciliation operations per second (5m rate) |
| `reconcile_error_rate` | Reconciliation errors per second (alert if > 5) |
| `workqueue_depth` | Application controller work queue depth (alert if > 100) |

#### Repo Server (5 datapoints)
| Metric | Description |
|--------|-------------|
| `git_request_rate` | Total git requests per second (5m rate) |
| `git_fetch_rate` | Git fetch requests per second (5m rate) |
| `p95_git_duration_ms` | 95th percentile git request duration in ms (alert if > 30000) |
| `p99_git_duration_ms` | 99th percentile git request duration in ms (alert if > 60000) |
| `git_error_rate` | Git request errors per second (alert if > 0) |

#### Server Metrics (6 datapoints)
| Metric | Description |
|--------|-------------|
| `grpc_request_rate` | gRPC requests handled per second (5m rate) |
| `grpc_error_rate` | gRPC request errors per second (alert if > 5) |
| `redis_request_rate` | Redis requests per second (5m rate) |
| `p95_redis_latency_ms` | 95th percentile Redis request latency in ms (alert if > 500) |
| `notification_delivery_rate` | Notification deliveries per second (5m rate) |
| `notification_failure_rate` | Notification delivery failures per second (alert if > 0) |

### Instance Properties (GitOps)

| Property | Description | Example |
|----------|-------------|---------|
| `auto.app.name` | ArgoCD application name | `my-web-app` |
| `auto.app.project` | ArgoCD project (ILP grouping) | `default` |
| `auto.app.namespace` | ArgoCD controller namespace | `openshift-gitops` |
| `auto.app.dest_namespace` | Application destination namespace | `production` |

---

## Portworx DataSources (9)

Monitors Portworx Enterprise storage deployments: cluster health and quorum, per-node status, per-volume health/performance/replication, per-pool and per-disk I/O performance, KVDB consensus health, and Autopilot automation status. Requires Portworx Enterprise to be installed on the cluster with metrics exposed to Prometheus.

DataSource files are located in `datasources/portworx/`.

### DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| Portworx_Cluster_Overview | 1 (cluster) | 1 min | 15 min | 12 | Quorum, node counts, storage capacity, CPU/memory utilization, pending I/O |
| Portworx_Node_Status | Per node | 1 min | 15 min | 3 | Per-node status, network health, storage health |
| Portworx_Volume_Health | Per volume | 1 min | 15 min | 6 | Volume capacity, HA level, status, utilization |
| Portworx_Volume_Performance | Per volume | 1 min | 15 min | 7 | Read/write IOPS, throughput, latency, I/O depth |
| Portworx_Volume_Replication | Per volume | 1 min | 15 min | 4 | HA level, replication status, resync progress, replica sets |
| Portworx_Pool_Performance | Per pool | 1 min | 15 min | 7 | Pool capacity, utilization, provisioned bytes, IOPS, throughput |
| Portworx_Disk_Performance | Per disk | 1 min | 15 min | 8 | Disk read/write IOPS, throughput, latency, I/O depth, used bytes |
| Portworx_KVDB_Health | 1 (KVDB) | 1 min | 15 min | 4 | KVDB member counts, disk sync latency |
| Portworx_Autopilot_Health | 1 (autopilot) | 1 min | 15 min | 3 | Active rules, rebalance status, capacity events |

**Total: 54 datapoints across 9 DataSources.**

- **appliesTo**: `hasCategory("OpenShift_Portworx")`
- **Group**: `Portworx`
- **ILP Grouping**: Pool_Performance and Disk_Performance group by `auto.pool.node` / `auto.disk.node`

### Metrics Collected (Portworx)

#### Cluster Overview (12 datapoints)
| Metric | Description |
|--------|-------------|
| `quorum_status` | Cluster quorum status: 1=in quorum, 0=not (alert if < 1) |
| `cluster_size` | Total number of nodes in the cluster |
| `nodes_online` | Number of online nodes |
| `nodes_offline` | Number of offline nodes (alert if > 0) |
| `storage_nodes_online` | Number of online storage nodes |
| `total_bytes` | Total cluster storage capacity in bytes |
| `used_bytes` | Used cluster storage in bytes |
| `available_bytes` | Available cluster storage in bytes |
| `used_percent` | Cluster storage utilization percentage (alert if > 85) |
| `cpu_percent` | Average cluster CPU utilization (alert if > 90) |
| `memory_percent` | Average cluster memory utilization (alert if > 90) |
| `pending_io` | Bytes of pending I/O operations |

#### Node Status (3 datapoints)
| Metric | Description |
|--------|-------------|
| `status` | Node status: 1=online, 0=offline (alert if < 1) |
| `network_status` | Node network health: 1=healthy, 0=degraded (alert if < 1) |
| `storage_status` | Node storage status: 1=up, 0=down (alert if < 1) |

#### Volume Health (6 datapoints)
| Metric | Description |
|--------|-------------|
| `capacity_bytes` | Volume capacity in bytes |
| `ha_level` | Volume HA replication level |
| `vol_status` | Volume status: 1=attached, 0=detached |
| `used_bytes` | Volume used bytes |
| `available_bytes` | Volume available bytes |
| `used_percent` | Volume utilization percentage (alert if > 85) |

#### Volume Performance (7 datapoints)
| Metric | Description |
|--------|-------------|
| `read_iops` | Volume read operations per second (5m rate) |
| `write_iops` | Volume write operations per second (5m rate) |
| `read_throughput` | Volume read throughput in bytes per second |
| `write_throughput` | Volume write throughput in bytes per second |
| `read_latency_ms` | Volume read latency in milliseconds (alert if > 50) |
| `write_latency_ms` | Volume write latency in milliseconds (alert if > 50) |
| `io_depth` | In-flight I/O operations on the volume device |

#### Volume Replication (4 datapoints)
| Metric | Description |
|--------|-------------|
| `ha_level` | Volume HA replication level |
| `repl_status` | Replication status: 1=up to date, 0=resyncing (alert if < 1) |
| `resync_progress` | Replication resync progress percentage (0-100) |
| `repl_sets_online` | Number of online replica sets for the volume |

#### Pool Performance (7 datapoints)
| Metric | Description |
|--------|-------------|
| `total_bytes` | Pool total capacity in bytes |
| `used_bytes` | Pool used capacity in bytes |
| `available_bytes` | Pool available capacity in bytes |
| `used_percent` | Pool utilization percentage (alert if > 85) |
| `provisioned_bytes` | Pool provisioned capacity in bytes |
| `iops` | Pool combined IOPS (read + write) |
| `throughput` | Pool throughput in bytes per second |

#### Disk Performance (8 datapoints)
| Metric | Description |
|--------|-------------|
| `read_iops` | Disk read operations per second (5m rate) |
| `write_iops` | Disk write operations per second (5m rate) |
| `read_throughput` | Disk read throughput in bytes per second |
| `write_throughput` | Disk write throughput in bytes per second |
| `read_latency_ms` | Disk read latency in milliseconds (alert if > 50) |
| `write_latency_ms` | Disk write latency in milliseconds (alert if > 50) |
| `io_depth` | In-progress I/O operations on the disk |
| `used_bytes` | Disk used bytes |

#### KVDB Health (4 datapoints)
| Metric | Description |
|--------|-------------|
| `kvdb_up_count` | Number of KVDB members reporting as up |
| `disk_sync_latency_ms` | KVDB disk sync latency in ms (alert if > 1000) |
| `members_online` | Number of KVDB members online |
| `members_total` | Total number of KVDB members in the cluster |

#### Autopilot Health (3 datapoints)
| Metric | Description |
|--------|-------------|
| `rules_active` | Number of active Autopilot rules |
| `rebalance_status` | Rebalance operations in progress: 1=active, 0=idle |
| `capacity_events` | Capacity management events per second (5m rate) |

### Instance Properties (Portworx)

| Property | Description | Example |
|----------|-------------|---------|
| `auto.node.id` | Portworx node ID | `px-node-01` |
| `auto.node.name` | Portworx node hostname | `worker-1.ocp.example.com` |
| `auto.volume.id` | Portworx volume ID | `1234567890` |
| `auto.volume.name` | Portworx volume name | `pvc-abc123` |
| `auto.pool.id` | Storage pool ID | `0` |
| `auto.pool.node` | Pool host node (ILP grouping) | `worker-1` |
| `auto.disk.device` | Disk device path | `/dev/sdb` |
| `auto.disk.node` | Disk host node (ILP grouping) | `worker-1` |

---

## Thanos PromQL Collector (1)

A generic, configurable DataSource that executes arbitrary PromQL queries against any Thanos endpoint. Queries are defined entirely through device properties, so no code changes are needed to add new metrics. This DataSource is standalone and not part of any suite group.

DataSource files are located in `datasources/promql/`.

- **appliesTo**: `openshift.thanos.host && openshift.thanos.pass` (flat property check, not category-based)
- **Group**: `Thanos`

### How It Works

1. Set `openshift.thanos.host` and `openshift.thanos.pass` on a device
2. Define numbered query properties (`openshift.thanos.query.1.name`, `openshift.thanos.query.1.promql`, etc.)
3. Active Discovery creates one LM instance per query
4. Collection executes each PromQL query and stores the numeric result

### Query Properties

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

---

## Prerequisites

- **OCP suite**: OpenShift 4.12+ (any OpenShift cluster)
- **Etcd suite**: OpenShift 4.12+ (etcd is always present on OpenShift)
- **KubeVirt suite**: OpenShift 4.12+ with OpenShift Virtualization (KubeVirt/CNV) installed
- **ODF suite**: OpenShift 4.12+ with OpenShift Data Foundation (ODF/Ceph) installed
- **ACM suite**: OpenShift 4.12+ with Advanced Cluster Management 2.8+ installed (hub cluster only)
- **GitOps suite**: OpenShift 4.12+ with OpenShift GitOps operator (ArgoCD) installed
- **Portworx suite**: OpenShift 4.12+ with Portworx Enterprise installed and metrics exposed to Prometheus
- **PromQL Collector**: Any Thanos Querier endpoint accessible over HTTPS
- **Nexus suite**: Sonatype Nexus Repository Manager 3.x with REST API enabled
- LogicMonitor Collector with network access to the Thanos endpoint (port 443 or 9091) and/or Nexus endpoint (port 8081)
- Service account with `cluster-monitoring-view` role (recommended: dedicated `logicmonitor-thanos-reader` SA)

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

1. Verify both required properties are set: `openshift.thanos.host` and `openshift.thanos.pass`
2. Check that the PropertySource has run and set the expected `system.categories`
3. Property names are case-sensitive
4. If using internal SVC, verify the collector pod can reach `thanos-querier.openshift-monitoring.svc:9091`

### No Data Collected

1. Verify Thanos connectivity:
   ```bash
   curl -k -H "Authorization: Bearer $TOKEN" \
     "https://$THANOS_HOST/api/v1/query?query=up"
   ```
2. Check token validity (401 = expired/invalid token)
3. Verify the expected metrics exist in Thanos for the suite you are troubleshooting

### PropertySource Not Setting Categories

1. Confirm both `openshift.thanos.host` and `openshift.thanos.pass` are set (PropertySource appliesTo requires both)
2. Check the PropertySource collection log in the LM portal for error output
3. Verify the probe metrics exist on the target cluster (e.g., `ceph_health_status` for ODF, `acm_managed_cluster_info` for ACM)

### ODF DataSources Show 0 Instances

- **OSD Discovery/Performance**: Verify `ceph_osd_metadata` metric exists. ODF must be fully deployed with at least one OSD.
- **RGW Performance**: RGW is not deployed by default in ODF 4.20+. 0 instances is expected if RGW is not configured.
- **MDS Health**: CephFS must be configured for MDS daemons to exist. 0 instances is expected without CephFS.
- **Pool Performance**: Verify `ceph_pool_metadata` metric exists.

### ACM DataSources Show 0 Instances

- **Managed Cluster Status**: Verify `acm_managed_cluster_labels` metric exists. At minimum, `local-cluster` should be discovered.
- **Observability Health**: This DataSource uses conditional discovery. It only creates an instance when the ACM Observability addon is deployed in the `open-cluster-management-observability` namespace.
- **Hub Components**: Verify ACM deployments exist across `open-cluster-management`, `open-cluster-management-hub`, and `multicluster-engine` namespaces.

### Zeros After Token Refresh

The LogicMonitor Collector caches device properties. After updating the token, wait 5-10 minutes for the cache to refresh.

### Image Pull Health Shows 0 Instances

The `container_runtime_crio_image_pulls_success_total` metric is CRI-O specific. If the cluster uses containerd or another container runtime, this DataSource will not discover any instances.

### Ingress Latency Shows 0 Instances

The `haproxy_server_up` metric is specific to the OpenShift HAProxy router. Clusters using a different ingress controller will not have this metric.

---

## Sonatype Nexus Suite

6 DataSources + 1 PropertySource for monitoring Sonatype Nexus Repository Manager via REST API. Standalone suite, independent of the Thanos/PromQL architecture. Uses Basic Auth against the Nexus REST API to monitor system health, blob stores, repositories, HTTP metrics, system resources, and scheduled tasks.

### Nexus Device Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `nexus.host` | Yes | - | Nexus Repository Manager hostname |
| `nexus.user` | Yes | - | Username with `nx-metrics-all` and `nx-atlas-all` privileges |
| `nexus.pass` | Yes | - | Password or user token passCode |
| `nexus.port` | No | 8081 | Nexus HTTP port |
| `nexus.ssl` | No | true | Use HTTPS |

### Nexus PropertySource

| PropertySource | appliesTo | Probe | Category Set |
|----------------|-----------|-------|-------------|
| addCategory_Nexus | `nexus.host && nexus.pass` | `GET /service/rest/v1/status` (no auth) | `Nexus` |

### Nexus DataSource Inventory

| DataSource | Instances | Collection | Discovery | Datapoints | Description |
|------------|-----------|------------|-----------|------------|-------------|
| Nexus_System_Health | 1 (server) | 1 min | 15 min | 3 | Liveness, write availability, freeze state |
| Nexus_Blob_Store_Health | Per blob store | 1 min | 15 min | 6 | Availability, capacity, utilization, quota violations |
| Nexus_Repository_Status | Per repository | 1 min | 15 min | 2 | Online/offline, proxy auto-block detection |
| Nexus_HTTP_Metrics | 1 (server) | 1 min | 15 min | 6 | HTTP 2xx/4xx/5xx rates, 401/403 counts, request rate |
| Nexus_System_Resources | 1 (server) | 1 min | 15 min | 8 | JVM heap, threads, CPU cores, disk utilization |
| Nexus_Task_Health | Per task | 1 min | 15 min | 2 | Task state, last run result |

**Total: 6 DataSources, 27 datapoints.**

- **appliesTo**: `hasCategory("Nexus")`
- **Group**: `Nexus`
- **ILP grouping**: blob stores by type, repositories by format, tasks by type

### Customer Use Case: Image Pull Failure Diagnosis

| Failure Cause | DataSource | Datapoint | Threshold |
|---|---|---|---|
| Nexus is down | Nexus_System_Health | `status` | < 1 |
| Nexus frozen/read-only | Nexus_System_Health | `writable`, `frozen` | writable < 1, frozen > 0 |
| Blob store full/quota hit | Nexus_Blob_Store_Health | `quota_violation`, `used_percent` | > 0, > 85% |
| Repository offline | Nexus_Repository_Status | `online` | < 1 |
| Upstream registry blocked | Nexus_Repository_Status | `proxy_blocked` | > 0 |
| Auth failures spiking | Nexus_HTTP_Metrics | `response_401_count`, `response_403_count` | delta trend |
| HTTP errors spiking | Nexus_HTTP_Metrics | `response_5xx_rate` | > 5/sec |
| Disk full | Nexus_System_Resources | `disk_used_pct` | > 90% |
| JVM out of memory | Nexus_System_Resources | `heap_used_pct` | > 90% |
| Cleanup tasks stuck | Nexus_Task_Health | `last_run_result` | > 0 |

### Nexus API Endpoints Used

| Endpoint | Auth Required | DS |
|----------|---------------|-----|
| `GET /service/rest/v1/status` | No | System Health, PropertySource |
| `GET /service/rest/v1/status/writable` | No | System Health |
| `GET /service/rest/v1/read-only` | Yes | System Health |
| `GET /service/rest/v1/blobstores` | Yes | Blob Store Health |
| `GET /service/rest/v1/blobstores/{name}/quota-status` | Yes | Blob Store Health |
| `GET /service/rest/v1/repositories` | Yes | Repository Status |
| `GET /service/rest/v1/repositories/{format}/proxy/{name}` | Yes | Repository Status |
| `GET /service/rest/metrics/data` | Yes (`nx-metrics-all`) | HTTP Metrics |
| `GET /service/rest/atlas/system-information` | Yes (`nx-atlas-all`) | System Resources |
| `GET /service/rest/v1/tasks` | Yes | Task Health |

### Nexus Setup

1. Create a monitoring service account in Nexus with `nx-metrics-all` and `nx-atlas-all` privileges
2. Add the LM device representing the Nexus server
3. Set device properties: `nexus.host`, `nexus.user`, `nexus.pass` (and optionally `nexus.port`, `nexus.ssl`)
4. The PropertySource will auto-detect Nexus and set the `Nexus` category
5. All 6 DataSources will begin collecting once the category is applied

## License

GPL v3
