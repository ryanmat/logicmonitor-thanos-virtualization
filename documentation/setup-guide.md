<!-- Description: Customer setup guide for KubeVirt Thanos DataSources for LogicMonitor. -->
<!-- Description: Covers OpenShift prerequisites, token generation, LM portal configuration, and troubleshooting. -->

# OpenShift Thanos DataSources - Setup Guide

This guide walks through the complete setup of LogicMonitor monitoring for
OpenShift environments via the Thanos Querier API. The DataSources in this
project query Thanos for KubeVirt VM metrics, etcd cluster health, and custom
PromQL queries, allowing any LogicMonitor Collector with HTTPS access to the
cluster to monitor OpenShift without requiring an in-cluster collector.

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

### OpenShift Cluster

- OpenShift 4.12 or later (including ROSA, ARO, and self-managed)
- OpenShift Virtualization (CNV) operator installed and healthy
- At least one VirtualMachineInstance (VMI) running
- `oc` CLI installed and authenticated with cluster-admin or equivalent privileges

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

Verify end-to-end connectivity and authentication by querying the
`kubevirt_vmi_info` metric (the same metric the DataSources use for Active
Discovery):

```bash
THANOS_HOST=$(oc get route thanos-querier -n openshift-monitoring -o jsonpath='{.spec.host}')

# Use the token generated in the previous step
TOKEN="<paste-your-token-here>"

curl -sk -H "Authorization: Bearer ${TOKEN}" \
  "https://${THANOS_HOST}/api/v1/query?query=kubevirt_vmi_info" | python3 -m json.tool
```

**Expected output:** JSON with `"status": "success"` and a `data.result` array
containing one entry per running VMI. Each entry includes labels such as
`name`, `namespace`, `node`, and `phase`.

If the result array is empty, confirm VMIs are running with `oc get vmi -A`.

Optional: verify cluster-level metrics exist:

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

**PromQL Collector** (`datasources/promql/`):

| File | Description |
|------|-------------|
| `Thanos_PromQL_Collector.json` | Generic PromQL query executor (2 datapoints per query) |

**Steps:**

1. Log in to your LogicMonitor portal
2. Navigate to **Settings > DataSources**
3. Click **Add > Import**
4. Select a JSON file and click **Import**
5. Repeat for all 6 files

All DataSources will appear under the **KubeVirt** group in the DataSource
list.

### 3.2 Create the Monitoring Device

1. Navigate to **Resources > Add > Device**
2. Configure the device:
   - **Name / Hostname**: The Thanos Querier route hostname from
     [Section 2.2](#22-get-the-thanos-querier-route)
     (e.g., `thanos-querier-openshift-monitoring.apps.cluster.example.com`)
   - **Display Name**: A descriptive name such as `OCP-Prod-KubeVirt` or
     `<cluster-name>-kubevirt`
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

Once both required properties are set, all 6 DataSources automatically attach
to the device via the `appliesTo` expression:
`openshift.thanos.host && openshift.thanos.pass`.

### 3.4 Verify Active Discovery

Active Discovery runs every 15 minutes. To trigger it immediately:

1. Navigate to the device in LogicMonitor
2. Under the **DataSources** tab, you should see 6 KubeVirt DataSources listed
3. Click on any DataSource (e.g., **KubeVirt VMI Discovery**)
4. Click the gear icon and select **Run Active Discovery**

Within 1-2 minutes, instances appear:
- **VMI DataSources**: One instance per running VMI, grouped by namespace
- **Cluster Overview**: A single `cluster` instance

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

| File | Display Name | Collection | Discovery | Instances | Datapoints |
|------|-------------|------------|-----------|-----------|------------|
| `KubeVirt_Cluster_Overview.json` | KubeVirt Cluster Overview | 1 min | 15 min | 1 (cluster) | 11 |
| `KubeVirt_VMI_Discovery.json` | KubeVirt VMI Discovery | 1 min | 15 min | Per VMI | 1 |
| `KubeVirt_VMI_CPU.json` | KubeVirt VMI CPU | 1 min | 15 min | Per VMI | 6 |
| `KubeVirt_VMI_Memory.json` | KubeVirt VMI Memory | 1 min | 15 min | Per VMI | 10 |
| `KubeVirt_VMI_Network.json` | KubeVirt VMI Network | 1 min | 15 min | Per VMI | 8 |
| `KubeVirt_VMI_Storage.json` | KubeVirt VMI Storage | 1 min | 15 min | Per VMI | 8 |

**Total: 44 datapoints across 6 DataSources**

All DataSources use:
- `appliesTo`: `openshift.thanos.host && openshift.thanos.pass`
- `collectionMethod`: `script` (Groovy)
- `group`: `KubeVirt`
- Instance Level Property (ILP) grouping by `auto.vmi.namespace`

---

## Appendix B: Instance Level Properties

Each VMI instance discovered by Active Discovery receives the following
auto-populated properties:

| Property | Description | Example |
|----------|-------------|---------|
| `auto.vmi.name` | VM instance name | `my-rhel-vm` |
| `auto.vmi.namespace` | Kubernetes namespace (used for ILP grouping) | `default` |
| `auto.vmi.node` | Node where the VMI is scheduled | `worker-0.ocp.example.com` |
| `auto.vmi.phase` | VMI lifecycle phase (Discovery DS only) | `running` |

These properties can be used in LogicMonitor for filtering, alerting, and
dashboard widgets.
