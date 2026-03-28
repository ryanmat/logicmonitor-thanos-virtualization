<!-- Description: Customer setup guide for OpenShift Thanos DataSources for LogicMonitor. -->
<!-- Description: Covers OpenShift prerequisites, token generation, LM portal configuration, and troubleshooting. -->

# OpenShift Thanos DataSources - Setup Guide

This guide walks through the complete setup of LogicMonitor monitoring for
OpenShift environments via the Thanos Querier API. The DataSources in this
project query Thanos for KubeVirt VM metrics, OCP platform health (etcd cluster
health, operator status, API server performance, pod health, image pull health,
ingress latency), and custom PromQL queries, allowing any LogicMonitor Collector
with HTTPS access to the cluster to monitor OpenShift without requiring an
in-cluster collector.

For architecture details and metric documentation, see the project
[README](../README.md).

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [OpenShift Configuration](#2-openshift-configuration)
3. [LogicMonitor Portal Configuration](#3-logicmonitor-portal-configuration)
4. [Token Rotation and Maintenance](#4-token-rotation-and-maintenance)
5. [Monitoring Multiple Clusters](#5-monitoring-multiple-clusters)
6. [Troubleshooting](#6-troubleshooting)
7. [Appendix A: DataSource Reference](#appendix-a-datasource-reference)
8. [Appendix B: Instance Level Properties](#appendix-b-instance-level-properties)

---

## 1. Prerequisites

### OpenShift Cluster (All Suites)

These are required regardless of which DataSource suites you deploy:

- OpenShift 4.12 or later (including ROSA, ARO, and self-managed)
- `oc` CLI installed and authenticated with cluster-admin or equivalent privileges

### KubeVirt Suite (Additional)

These are required only if deploying the KubeVirt DataSources:

- OpenShift Virtualization (CNV) operator installed and healthy
- At least one VirtualMachineInstance (VMI) running

### OCP Suite

No additional prerequisites beyond the base requirements. The OCP DataSources
monitor core OpenShift components (etcd, ClusterOperators, API server, pods,
CRI-O image pulls, HAProxy routes) that are present on every OpenShift cluster.

### LogicMonitor

- Active LogicMonitor portal with admin access
- At least one LogicMonitor Collector deployed
- The Collector must have outbound HTTPS access (port 443) to the OpenShift
  cluster's Thanos Querier route

### Network

- The Collector host must be able to resolve the Thanos Querier route hostname
  via DNS
- Port 443 must be open between the Collector and the OpenShift cluster's
  ingress (router)
- The Collector does **not** need to be inside the cluster -- any external
  Collector with network access works

---

## 2. OpenShift Configuration

### 2.1 Verify OpenShift Virtualization is Installed

> **Note:** This step is only required for the KubeVirt DataSource suite. Skip
> this step if you are only deploying the OCP or PromQL suites.

Confirm the CNV operator is installed and the KubeVirt custom resource is
healthy:

```bash
# Check that the CNV operator CSV is available
oc get csv -n openshift-cnv | grep kubevirt

# Confirm the KubeVirt CR is deployed
oc get kubevirt -n openshift-cnv
```

The KubeVirt CR should show phase `Deployed`.

Verify that at least one VMI is running (the DataSources discover VMIs, so at
least one must exist to see data):

```bash
oc get vmi -A
```

### 2.2 Get the Thanos Querier Route

The Thanos Querier is part of the OpenShift monitoring stack and provides a
unified PromQL API across all Prometheus instances in the cluster.

```bash
# Get the Thanos Querier route hostname
oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}'
```

Save this value. It becomes the `openshift.thanos.host` device property in
LogicMonitor (shared by all DataSource suites). The output looks like:

```
thanos-querier-openshift-monitoring.apps.<cluster-name>.<base-domain>
```

Quick smoke test to confirm the route is responding (expect HTTP 401 without a
token -- that is correct):

```bash
THANOS_HOST=$(oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}')
curl -sk -o /dev/null -w "%{http_code}" "https://${THANOS_HOST}/api/v1/query?query=up"
# Expected output: 401
```

### 2.3 Create a Dedicated Service Account

Create a service account specifically for LogicMonitor. Using a dedicated
account (rather than the built-in `prometheus-k8s` SA) provides better
isolation, audit trail, and allows token revocation without affecting cluster
monitoring.

```bash
# Create the service account
oc create serviceaccount logicmonitor-thanos-reader -n openshift-monitoring
```

### 2.4 Grant Monitoring Read Access

Grant the `cluster-monitoring-view` ClusterRole. This is a built-in OpenShift
role that provides read-only access to all monitoring metrics via the Thanos
Querier API. It does not grant write access or access to non-monitoring
resources.

```bash
oc adm policy add-cluster-role-to-user cluster-monitoring-view \
  -z logicmonitor-thanos-reader -n openshift-monitoring
```

### 2.5 Generate a Bearer Token

Generate a long-lived token for the service account. The token is used by
LogicMonitor to authenticate against the Thanos Querier API.

```bash
# Generate a token valid for 1 year (8760 hours)
oc create token logicmonitor-thanos-reader \
  -n openshift-monitoring \
  --duration=8760h
```

The token is printed to stdout. Copy it immediately and store it securely.

**Notes:**
- The `--duration` flag creates a time-bound token via the TokenRequest API.
  If `8760h` is rejected by your cluster policy, try a shorter duration.
- Record the creation date and set a calendar reminder to rotate before
  expiration (see [Section 4](#4-token-rotation-and-maintenance)).

### 2.6 Test the Thanos Endpoint

Verify end-to-end connectivity and authentication by querying Thanos metrics.
Which queries to test depends on the suites you plan to deploy.

```bash
THANOS_HOST=$(oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}')

# Use the token generated in the previous step
TOKEN="<paste-your-token-here>"
```

**KubeVirt suite test** (skip if not deploying KubeVirt DataSources):

```bash
curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "https://${THANOS_HOST}/api/v1/query?query=kubevirt_vmi_info" | python3 -m json.tool
```

Expected output: JSON with `"status": "success"` and a `data.result` array
containing one entry per running VMI. Each entry includes labels such as
`name`, `namespace`, `node`, and `phase`.

If the result array is empty, confirm VMIs are running with `oc get vmi -A`.

**OCP suite tests:**

```bash
# Test OCP operator metrics
curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "https://${THANOS_HOST}/api/v1/query?query=cluster_operator_conditions" | python3 -m json.tool

# Test etcd metrics
curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "https://${THANOS_HOST}/api/v1/query?query=etcd_server_has_leader" | python3 -m json.tool
```

Expected output for both: JSON with `"status": "success"` and a non-empty
`data.result` array. The `cluster_operator_conditions` query returns one entry
per operator/condition combination. The `etcd_server_has_leader` query returns
one entry per etcd member (typically 3).

**Optional:** Verify additional cluster-level metrics exist:

```bash
curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "https://${THANOS_HOST}/api/v1/query?query=kubevirt_virt_api_up" | python3 -m json.tool
```

---

## 3. LogicMonitor Portal Configuration

### 3.1 Import DataSource JSON Files

Import DataSource files from the `datasources/` subdirectories in this
repository. Import the suites relevant to your environment. Import order does
not matter.

**KubeVirt Suite** (`datasources/kubevirt/`):

| File | Description |
|------|-------------|
| `KubeVirt_Cluster_Overview.json` | Cluster-wide health, VM counts, migration status (11 datapoints) |
| `KubeVirt_VMI_Discovery.json` | VMI discovery and status tracking (1 datapoint) |
| `KubeVirt_VMI_CPU.json` | Per-VMI CPU utilization and vCPU metrics (6 datapoints) |
| `KubeVirt_VMI_Memory.json` | Per-VMI memory usage, swap, and page faults (10 datapoints) |
| `KubeVirt_VMI_Network.json` | Per-VMI network throughput, packets, errors (8 datapoints) |
| `KubeVirt_VMI_Storage.json` | Per-VMI storage throughput, IOPS, latency (8 datapoints) |

**OCP Suite** (`datasources/ocp/`):

| File | Description |
|------|-------------|
| `Etcd_Cluster_Overview.json` | Cluster-wide etcd health, member counts, consensus metrics (8 datapoints) |
| `Etcd_Member_Discovery.json` | Etcd member discovery and leader status (2 datapoints) |
| `Etcd_Member_Performance.json` | Per-member disk latency, proposals, db size, peer network (13 datapoints) |
| `OCP_Operator_Health.json` | Per-operator Available, Degraded, Progressing, Upgradeable (4 datapoints) |
| `OCP_Operator_Overview.json` | Cluster-wide operator degraded/unavailable counts (3 datapoints) |
| `OCP_API_Server_Performance.json` | API server request rate, p99 latency, inflight, 429s (7 datapoints) |
| `OCP_Pod_Health.json` | Cluster-wide pod failure counts by reason (7 datapoints) |
| `OCP_Image_Pull_Health.json` | Per-node CRI-O pull rates and cluster pull failures (5 datapoints) |
| `OCP_Ingress_Latency.json` | Per-route HAProxy request rate, latency, errors (6 datapoints) |

**PromQL Collector** (`datasources/promql/`):

| File | Description |
|------|-------------|
| `Thanos_PromQL_Collector.json` | Generic PromQL query executor (2 datapoints per query) |

**Steps:**

1. Log in to your LogicMonitor portal
2. Navigate to **Settings > DataSources**
3. Click **Add > Import**
4. Select a JSON file and click **Import**
5. Repeat for each file in the suites you are deploying (6 for KubeVirt, 9 for
   OCP, 1 for PromQL)

KubeVirt DataSources appear under the **KubeVirt** group, OCP DataSources
appear under the **OCP** and **Etcd** groups, and the PromQL Collector appears
under the **PromQL** group in the DataSource list.

### 3.2 Create the Monitoring Device

1. Navigate to **Resources > Add > Device**
2. Configure the device:
   - **Name / Hostname**: The Thanos Querier route hostname from
     [Section 2.2](#22-get-the-thanos-querier-route)
     (e.g., `thanos-querier-openshift-monitoring.apps.cluster.example.com`)
   - **Display Name**: A descriptive name such as `OCP-Prod-Thanos` or
     `<cluster-name>-thanos`
   - **Collector**: Select the Collector that has network access to the Thanos
     route
   - **Host Group**: Place in an appropriate resource group

### 3.3 Set Device Properties

Add the following custom properties on the device. Navigate to the device page,
go to the **Info** tab, scroll to **Properties**, and click **Add** for each
property.

| Property | Required | Default | Value |
|----------|----------|---------|-------|
| `openshift.thanos.host` | Yes | -- | Thanos Querier route hostname from Section 2.2 |
| `openshift.thanos.pass` | Yes | -- | Bearer token from Section 2.5 (full JWT string) |
| `openshift.thanos.port` | No | `443` | Only set if using a non-standard port |
| `openshift.thanos.ssl` | No | `true` | Set to `false` only if Thanos is not behind TLS |

Once both required properties are set, all DataSources automatically attach
to the device via the `appliesTo` expression:
`openshift.thanos.host && openshift.thanos.pass`.

### 3.4 Verify Active Discovery

Active Discovery runs every 15 minutes. To trigger it immediately:

1. Navigate to the device in LogicMonitor
2. Under the **DataSources** tab, you should see DataSources listed for each
   suite you imported
3. Click on any DataSource (e.g., **KubeVirt VMI Discovery** or **OCP Operator
   Health**)
4. Click the gear icon and select **Run Active Discovery**

Within 1-2 minutes, instances appear:

**KubeVirt DataSources:**
- **VMI DataSources**: One instance per running VMI, grouped by namespace
- **Cluster Overview**: A single `cluster` instance

**OCP DataSources:**
- **OCP_Operator_Health**: One instance per ClusterOperator (~30-35 operators on
  a standard OpenShift cluster)
- **OCP_API_Server_Performance**: A single static `apiserver` instance
- **OCP_Operator_Overview**: A single static `cluster` instance
- **OCP_Pod_Health**: A single static `cluster` instance
- **OCP_Image_Pull_Health**: One instance per node plus a `cluster` aggregate
  instance
- **OCP_Ingress_Latency**: One instance per HAProxy route, grouped by namespace
- **Etcd_Member_Discovery / Etcd_Member_Performance**: One instance per etcd
  member (typically 3)
- **Etcd_Cluster_Overview**: A single `etcd-cluster` instance

### 3.5 Verify Data Collection

All DataSources collect every 1 minute. After Active Discovery completes:

1. Click on a discovered VMI instance
2. Check the **Raw Data** tab -- datapoints should populate within 1-2 collection
   cycles
3. Check the **Graphs** tab for visual confirmation

If datapoints show `NaN` or no data after 5 minutes, see
[Section 6: Troubleshooting](#6-troubleshooting).

---

## 4. Token Rotation and Maintenance

### Token Lifetime

| Duration Flag | Token Lifetime |
|--------------|----------------|
| `--duration=8760h` | 1 year |
| `--duration=4380h` | 6 months |
| `--duration=720h` | 30 days |

### Check Token Expiration

Decode the JWT to inspect the expiration timestamp:

```bash
echo "<YOUR_TOKEN>" | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool
```

Look for the `exp` field, which is a Unix epoch timestamp.

### Rotate the Token

```bash
# Generate a new token
oc create token logicmonitor-thanos-reader \
  -n openshift-monitoring \
  --duration=8760h
```

Then update the `openshift.thanos.pass` property on the device in LogicMonitor.

The Collector caches device properties, so there is a 5-10 minute delay before
the new token takes effect. Data collection will resume automatically once the
cache refreshes.

### Recommended Schedule

- Set a calendar reminder 30 days before token expiration
- After rotating, verify data collection resumes within 10 minutes
- In high-security environments, consider shorter token durations (e.g., 6
  months) with more frequent rotation

---

## 5. Monitoring Multiple Clusters

Each OpenShift cluster requires its own device in LogicMonitor with its own
Thanos host and token properties. The same DataSources apply to all devices --
there is no need to import them more than once.

**Recommended naming convention:**

| Cluster | Device Display Name | openshift.thanos.host |
|---------|--------------------|-----------------------|
| Production | `ocp-prod-thanos` | `thanos-querier-openshift-monitoring.apps.prod.example.com` |
| Staging | `ocp-staging-thanos` | `thanos-querier-openshift-monitoring.apps.staging.example.com` |
| Development | `ocp-dev-thanos` | `thanos-querier-openshift-monitoring.apps.dev.example.com` |

Each device uses its own service account token. Follow Sections 2.3-2.5 for
each cluster.

---

## 6. Troubleshooting

### No DataSources Appearing on Device

- Verify both `openshift.thanos.host` **and** `openshift.thanos.pass` are set
  as device properties
- Property names are case-sensitive -- double-check the exact spelling
- Both properties must be non-empty for the `appliesTo` expression to match

### Active Discovery Returns No Instances

Test the discovery query from the command line:

```bash
curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "https://${THANOS_HOST}/api/v1/query?query=kubevirt_vmi_info"
```

| Symptom | Cause | Fix |
|---------|-------|-----|
| Empty result array | No VMIs running | Verify with `oc get vmi -A` |
| HTTP 401 | Token invalid or expired | Generate a new token (Section 2.5) |
| HTTP 403 | Missing ClusterRole | Re-run `oc adm policy add-cluster-role-to-user cluster-monitoring-view -z logicmonitor-thanos-reader -n openshift-monitoring` |
| Connection refused / timeout | Collector cannot reach Thanos route | Check DNS resolution and firewall rules from the Collector host |

### Data Shows All Zeros

- **CPU metrics**: These use `rate()` over a 5-minute window. If a VMI was
  recently started, wait 5 minutes for the rate calculation to have sufficient
  data points.
- **After token rotation**: The Collector caches properties for 5-10 minutes.
  Wait and check again.
- **Memory unused_bytes always 0**: The guest VM may not have the `virtio`
  memory balloon driver installed. Without the balloon driver, the guest cannot
  report memory statistics to the hypervisor.

### Cluster-Level Metrics Return Zero

Some cluster-level metrics depend on specific KubeVirt components:

| Metric | Required Component |
|--------|--------------------|
| `kubevirt_hco_system_health_status` | HyperConverged Operator (HCO) |
| `kubevirt_allocatable_nodes` | KubeVirt 0.49+ |
| `kubevirt_nodes_with_kvm` | KubeVirt 0.49+ |

On standalone KubeVirt installations without HCO, the `system_health` datapoint
returns 0. This is expected behavior -- the DataSource handles missing metrics
gracefully using `or vector(0)` fallbacks.

### No OCP Operators Discovered

The `cluster_operator_conditions` metric is OpenShift-specific. The
OCP_Operator_Health DataSource does not work on vanilla Kubernetes or managed
K8s services (EKS, AKS, GKE) without the OpenShift control plane.

### Image Pull Health Shows 0 Instances

The `container_runtime_crio_image_pulls_success_total` metric requires CRI-O as
the container runtime. Clusters using containerd (common on non-OpenShift
distributions) will not have this metric. All standard OpenShift clusters use
CRI-O.

### Ingress Latency Shows 0 Instances

The `haproxy_server_up` metric is specific to the OpenShift HAProxy router.
Clusters using nginx-ingress or other ingress controllers will not expose
HAProxy metrics via Thanos. The default OpenShift router uses HAProxy, so this
DataSource works on standard OpenShift installations.

### Pod Health Shows All Zeros

This is normal on a healthy cluster. The OCP_Pod_Health DataSource counts pods
in error states (CrashLoopBackOff, ImagePullBackOff, OOMKilled, etc.). Zero
values mean no pods are currently in those states.

### SSL/TLS Errors

The DataSource scripts accept all TLS certificates to accommodate self-signed
certificates on OpenShift routes. If TLS errors still occur, verify that:

1. The Collector host can resolve the route hostname
2. Port 443 is open between the Collector and the OpenShift ingress
3. No TLS-intercepting proxy is altering the certificate chain in an
   incompatible way

### Collector Debug Logging

To enable debug logging for the Groovy collection scripts:

1. Navigate to **Settings > Collectors > [Your Collector] > Support**
2. Enable script debug logging
3. Check the Collector logs for Groovy script output and error messages

---

## Appendix A: DataSource Reference

**KubeVirt Suite:**

| File | Display Name | Collection | Discovery | Instances | Datapoints |
|------|-------------|------------|-----------|-----------|------------|
| `KubeVirt_Cluster_Overview.json` | KubeVirt Cluster Overview | 1 min | 15 min | 1 (cluster) | 11 |
| `KubeVirt_VMI_Discovery.json` | KubeVirt VMI Discovery | 1 min | 15 min | Per VMI | 1 |
| `KubeVirt_VMI_CPU.json` | KubeVirt VMI CPU | 1 min | 15 min | Per VMI | 6 |
| `KubeVirt_VMI_Memory.json` | KubeVirt VMI Memory | 1 min | 15 min | Per VMI | 10 |
| `KubeVirt_VMI_Network.json` | KubeVirt VMI Network | 1 min | 15 min | Per VMI | 8 |
| `KubeVirt_VMI_Storage.json` | KubeVirt VMI Storage | 1 min | 15 min | Per VMI | 8 |

**OCP Suite:**

| File | Display Name | Collection | Discovery | Instances | Datapoints |
|------|-------------|------------|-----------|-----------|------------|
| `Etcd_Cluster_Overview.json` | Etcd Cluster Overview | 1 min | 15 min | 1 (cluster) | 8 |
| `Etcd_Member_Discovery.json` | Etcd Member Discovery | 1 min | 15 min | Per member | 2 |
| `Etcd_Member_Performance.json` | Etcd Member Performance | 1 min | 15 min | Per member | 13 |
| `OCP_Operator_Health.json` | OCP Operator Health | 1 min | 15 min | Per operator | 4 |
| `OCP_Operator_Overview.json` | OCP Operator Overview | 1 min | 15 min | 1 (cluster) | 3 |
| `OCP_API_Server_Performance.json` | OCP API Server Performance | 1 min | 15 min | 1 (apiserver) | 7 |
| `OCP_Pod_Health.json` | OCP Pod Health | 1 min | 15 min | 1 (cluster) | 7 |
| `OCP_Image_Pull_Health.json` | OCP Image Pull Health | 1 min | 15 min | Per node + 1 | 5 |
| `OCP_Ingress_Latency.json` | OCP Ingress Latency | 1 min | 15 min | Per route | 6 |

**PromQL Collector:**

| File | Display Name | Collection | Discovery | Instances | Datapoints |
|------|-------------|------------|-----------|-----------|------------|
| `Thanos_PromQL_Collector.json` | Thanos PromQL Collector | 1 min | 15 min | Per query | 2 |

**Total: 99 datapoints across 16 DataSources** (44 KubeVirt + 55 OCP)

All DataSources use:
- `appliesTo`: `openshift.thanos.host && openshift.thanos.pass`
- `collectionMethod`: `script` (Groovy)
- `group`: `KubeVirt` (KubeVirt suite), `OCP` or `Etcd` (OCP suite), `PromQL`
  (PromQL Collector)
- Instance Level Property (ILP) grouping varies by suite (see Appendix B)

---

## Appendix B: Instance Level Properties

### KubeVirt Instance Properties

Each VMI instance discovered by Active Discovery receives the following
auto-populated properties:

| Property | Description | Example |
|----------|-------------|---------|
| `auto.vmi.name` | VM instance name | `my-rhel-vm` |
| `auto.vmi.namespace` | Kubernetes namespace (used for ILP grouping) | `default` |
| `auto.vmi.node` | Node where the VMI is scheduled | `worker-0.ocp.example.com` |
| `auto.vmi.phase` | VMI lifecycle phase (Discovery DS only) | `running` |

### OCP Instance Properties

OCP DataSource instances receive the following auto-populated properties
depending on the DataSource:

| Property | Description | Example |
|----------|-------------|---------|
| `auto.operator.name` | ClusterOperator name | `authentication` |
| `auto.etcd.pod` | Etcd pod name | `etcd-master-0` |
| `auto.etcd.instance` | Etcd endpoint (IP:port) | `10.0.1.5:9979` |
| `auto.etcd.namespace` | Etcd namespace | `openshift-etcd` |
| `auto.node.instance` | CRI-O node instance endpoint | `10.1.14.10:9637` |
| `auto.node.name` | Node hostname | `ip-10-55-117-247.us-west-2.compute.internal` |
| `auto.route.name` | HAProxy route name | `console` |
| `auto.route.namespace` | Route namespace (used for ILP grouping) | `openshift-console` |

These properties can be used in LogicMonitor for filtering, alerting, and
dashboard widgets.
