# KubeVirt Thanos DataSource Implementation Plan

## Overview

This plan implements 7 LogicMonitor DataSources for monitoring OpenShift Virtualization VMs via Thanos Querier. The implementation is broken into small, iterative steps with comprehensive testing at each stage.

---

## Phase 1: Foundation and Infrastructure

### Step 1.1: Project Setup and Directory Structure

**Goal**: Create project structure and establish coding standards.

```text
Create the following directory structure for the KubeVirt Thanos DataSource project:

/home/claude/kubevirt-datasources/
  /datasources/          # Final JSON DataSource files for import
  /scripts/
    /discovery/          # Active Discovery Groovy scripts
    /collection/         # Data collection Groovy scripts  
    /lib/                # Shared library code
  /tests/                # Test files and mock data
  /docs/                 # Documentation

Create a README.md in the root with:
- Project description
- Directory structure explanation
- Setup requirements
- How to import DataSources into LogicMonitor

Use Groovy 4 syntax for all scripts (add "// v4" comment at top of each file).
```

---

### Step 1.2: Thanos Client Library

**Goal**: Create reusable Groovy library for Thanos API communication.

```text
Create a Thanos client library at /scripts/lib/thanos_client.groovy that:

1. Handles authentication with bearer token
2. Makes HTTP requests to Thanos Querier /api/v1/query endpoint
3. Parses Prometheus-format JSON responses
4. Provides error handling with meaningful messages
5. Supports both instant queries and rate calculations

Requirements:
- Use LogicMonitor's HTTP helper: com.santaba.agent.groovyapi.http.*
- Use JsonSlurper for response parsing
- Get credentials from hostProps:
  - kubevirt.thanos.host
  - kubevirt.thanos.token  
  - kubevirt.thanos.port (default: 443)
  - kubevirt.thanos.ssl (default: true)

Functions to implement:
- queryInstant(String promql) -> returns parsed result
- queryWithLabels(String promql) -> returns list of [labels, value] pairs
- buildUrl(String host, int port, boolean ssl) -> returns base URL

Include inline documentation and error handling for:
- Connection failures
- Authentication errors (401)
- Query errors (400)
- Empty results

Test the library by printing debug output when run standalone.
```

---

### Step 1.3: Test Mock Data

**Goal**: Create mock Thanos API responses for testing.

```text
Create mock Thanos API response files in /tests/mock_responses/ for testing:

1. vmi_phase_running.json - Response for kubevirt_vmi_phase_count query with 2 VMs:
   - demo-vms/fedora-demo (Running)
   - demo-vms/windows-server (Running)

2. vmi_cpu_metrics.json - Response with CPU metrics for both VMs

3. vmi_memory_metrics.json - Response with memory metrics for both VMs

4. vmi_network_metrics.json - Response with network metrics:
   - fedora-demo has interfaces: eth0, eth1
   - windows-server has interface: eth0

5. vmi_storage_metrics.json - Response with storage metrics:
   - fedora-demo has drives: vda, vdb
   - windows-server has drive: vda

Use realistic Prometheus response format:
{
  "status": "success",
  "data": {
    "resultType": "vector",
    "result": [
      {
        "metric": { "namespace": "...", "name": "...", ... },
        "value": [timestamp, "value"]
      }
    ]
  }
}

Include varied metric values to test calculations.
```

---

## Phase 2: Discovery DataSource

### Step 2.1: VMI Discovery Script

**Goal**: Create Active Discovery script that finds all running VMs.

```text
Create the VMI discovery script at /scripts/discovery/vmi_discovery.groovy that:

1. Uses the thanos_client library to query running VMIs
2. Returns instances in LogicMonitor format: wildvalue##wildalias##description

Query: kubevirt_vmi_phase_count{phase="Running"} == 1

For each VM found, output:
- wildvalue: {namespace}/{vmi_name}
- wildalias: {vmi_name}
- description: VM in namespace {namespace}

Also collect and output these instance properties:
- auto.kubevirt.namespace = {namespace}
- auto.kubevirt.vmi_name = {vmi_name}
- auto.kubevirt.node = {node} (from metric labels)
- auto.kubevirt.phase = Running

Output format per instance:
{namespace}/{vmi_name}##{vmi_name}##VM in namespace {namespace}####auto.kubevirt.namespace={namespace}&auto.kubevirt.vmi_name={vmi_name}&auto.kubevirt.node={node}

Handle edge cases:
- No VMs running (empty result)
- Connection errors
- Malformed responses

Return 0 on success, 1 on error.
```

---

### Step 2.2: VMI Discovery DataSource JSON

**Goal**: Create the importable DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_VMI_Discovery.json with:

DataSource Configuration:
- name: KubeVirt_VMI_Discovery
- displayName: KubeVirt VMI Discovery  
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: script
- collectInterval: 300 (5 minutes)
- multiInstance: true
- hasMultiInstances: true

Active Discovery:
- method: script
- scriptType: embeddedGroovyScript
- script: [contents of vmi_discovery.groovy]
- schedule: 900 (15 minutes)
- deleteInactiveInstances: true
- deleteInactiveInstancesTimeInMs: 1800000 (30 minutes)

Datapoints (status tracking):
1. phase_running
   - type: gauge
   - description: VM is in Running phase (1=yes, 0=no)
   - alertExpr: < 1
   
2. collection_status
   - type: gauge  
   - description: Data collection success (0=success)
   - source: auto (script exit code)

Graphs:
- VMI Status: Shows phase_running for all instances

Create valid JSON that can be imported via LogicMonitor UI or API.
Include the embedded Groovy script inline in the JSON.
```

---

## Phase 3: CPU DataSource

### Step 3.1: CPU Collection Script

**Goal**: Create BATCHSCRIPT collection for CPU metrics.

```text
Create the CPU collection script at /scripts/collection/cpu_collection.groovy that:

1. Uses thanos_client library
2. Queries multiple CPU metrics in batch for efficiency
3. Outputs in BATCHSCRIPT format: wildvalue.datapoint=value

Metrics to collect:
- vcpu_seconds_total: rate(kubevirt_vmi_vcpu_seconds_total[5m])
- vcpu_wait_seconds: rate(kubevirt_vmi_vcpu_wait_seconds_total[5m])  
- cpu_system_usage: rate(kubevirt_vmi_cpu_system_usage_seconds_total[5m])
- cpu_user_usage: rate(kubevirt_vmi_cpu_user_usage_seconds_total[5m])

Calculate derived metric:
- cpu_usage_percent: (vcpu_seconds_total / number_of_vcpus) * 100

Note: The rate() calculation is done by Prometheus, we just query the result.

For each VM, output:
demo-vms/fedora-demo.vcpu_seconds_total=0.45
demo-vms/fedora-demo.vcpu_wait_seconds=0.02
demo-vms/fedora-demo.cpu_system_usage=0.05
demo-vms/fedora-demo.cpu_user_usage=0.35
demo-vms/fedora-demo.cpu_usage_percent=45.0

Handle:
- VMs with no CPU metrics (skip with warning)
- Rate returning 0 (valid, don't skip)
- Multiple vCPUs (aggregate or per-CPU based on labels)

Return 0 on success, 1 on error.
```

---

### Step 3.2: CPU DataSource JSON

**Goal**: Create CPU DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_VMI_CPU.json with:

DataSource Configuration:
- name: KubeVirt_VMI_CPU
- displayName: KubeVirt VMI CPU
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: batchscript
- collectInterval: 60 (1 minute)
- multiInstance: true
- hasMultiInstances: true
- useWildValueAsUniqueIdentifier: true

Active Discovery:
- Use same script as VMI_Discovery (discovers same instances)
- schedule: 900 (15 minutes)

Datapoints:
1. vcpu_seconds_total
   - type: gauge
   - description: CPU time used per second (rate)
   - unit: seconds/sec
   
2. vcpu_wait_seconds  
   - type: gauge
   - description: CPU wait time per second (like vSphere ready time)
   - unit: seconds/sec
   - alertExpr: > 0.1 (more than 10% wait)

3. cpu_system_usage
   - type: gauge
   - description: System CPU usage rate
   - unit: seconds/sec

4. cpu_user_usage
   - type: gauge
   - description: User CPU usage rate
   - unit: seconds/sec

5. cpu_usage_percent
   - type: gauge
   - description: Overall CPU utilization percentage
   - unit: percent
   - alertExpr: > 90

Graphs:
1. CPU Usage: vcpu_seconds_total, cpu_user_usage, cpu_system_usage (stacked)
2. CPU Wait Time: vcpu_wait_seconds
3. CPU Utilization %: cpu_usage_percent

Include embedded collection script in JSON.
```

---

## Phase 4: Memory DataSource

### Step 4.1: Memory Collection Script

**Goal**: Create BATCHSCRIPT collection for memory metrics.

```text
Create the Memory collection script at /scripts/collection/memory_collection.groovy that:

1. Uses thanos_client library
2. Collects memory metrics for all VMs in batch

Metrics to collect (instant values, not rates):
- memory_available_bytes: kubevirt_vmi_memory_available_bytes
- memory_resident_bytes: kubevirt_vmi_memory_resident_bytes (like mem.active)
- memory_domain_total_bytes: kubevirt_vmi_memory_domain_total_bytes
- memory_balloon_current_bytes: kubevirt_vmi_memory_balloon_current_bytes

Rate metrics:
- memory_swap_in_bytes: rate(kubevirt_vmi_memory_swap_in_traffic_bytes_total[5m])
- memory_swap_out_bytes: rate(kubevirt_vmi_memory_swap_out_traffic_bytes_total[5m])

Calculated metrics:
- memory_used_bytes: domain_total - available
- memory_usage_percent: (memory_used_bytes / domain_total) * 100

Output format:
demo-vms/fedora-demo.memory_available_bytes=1073741824
demo-vms/fedora-demo.memory_resident_bytes=536870912
demo-vms/fedora-demo.memory_domain_total_bytes=2147483648
demo-vms/fedora-demo.memory_usage_percent=50.0

Handle edge cases:
- Balloon driver not active (balloon_current may be 0 or missing)
- No swap configured (swap metrics may not exist)

Return 0 on success, 1 on error.
```

---

### Step 4.2: Memory DataSource JSON

**Goal**: Create Memory DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_VMI_Memory.json with:

DataSource Configuration:
- name: KubeVirt_VMI_Memory
- displayName: KubeVirt VMI Memory
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: batchscript
- collectInterval: 60 (1 minute)
- multiInstance: true
- useWildValueAsUniqueIdentifier: true

Datapoints:
1. memory_available_bytes
   - type: gauge
   - unit: bytes
   
2. memory_resident_bytes
   - type: gauge
   - description: Active memory (like vSphere mem.active)
   - unit: bytes

3. memory_domain_total_bytes
   - type: gauge
   - unit: bytes

4. memory_balloon_current_bytes
   - type: gauge
   - description: Memory returned via balloon driver
   - unit: bytes

5. memory_used_bytes
   - type: gauge
   - unit: bytes

6. memory_usage_percent
   - type: gauge
   - unit: percent
   - alertExpr: > 90

7. memory_swap_in_bytes
   - type: gauge
   - description: Swap in rate
   - unit: bytes/sec
   - alertExpr: > 1048576 (1MB/sec sustained swap is concerning)

8. memory_swap_out_bytes
   - type: gauge
   - description: Swap out rate
   - unit: bytes/sec

Graphs:
1. Memory Usage: available, used (stacked to show total)
2. Memory Utilization %: usage_percent
3. Swap Activity: swap_in, swap_out
4. Balloon: balloon_current_bytes

Include embedded collection script.
```

---

## Phase 5: Network DataSource (Multi-Instance per Interface)

### Step 5.1: Network Discovery Script

**Goal**: Create discovery that finds all interfaces per VM.

```text
Create the Network discovery script at /scripts/discovery/network_discovery.groovy that:

1. Queries network metrics to discover all VM/interface combinations
2. Returns sub-instances in format: {namespace}/{vmi_name}/{interface}

Query: kubevirt_vmi_network_receive_bytes_total
This metric has labels: namespace, name, interface

For each unique combination, output:
- wildvalue: {namespace}/{vmi_name}/{interface}
- wildalias: {vmi_name}/{interface}
- description: Interface {interface} on VM {vmi_name}

Instance properties:
- auto.kubevirt.namespace
- auto.kubevirt.vmi_name
- auto.kubevirt.interface

Example output:
demo-vms/fedora-demo/eth0##fedora-demo/eth0##Interface eth0 on VM fedora-demo####auto.kubevirt.namespace=demo-vms&auto.kubevirt.vmi_name=fedora-demo&auto.kubevirt.interface=eth0
demo-vms/fedora-demo/eth1##fedora-demo/eth1##Interface eth1 on VM fedora-demo####auto.kubevirt.namespace=demo-vms&auto.kubevirt.vmi_name=fedora-demo&auto.kubevirt.interface=eth1

Handle:
- VMs with no network interfaces (skip)
- Interface changes (hot-add/remove detected by discovery)
```

---

### Step 5.2: Network Collection Script

**Goal**: Create BATCHSCRIPT collection for network metrics.

```text
Create the Network collection script at /scripts/collection/network_collection.groovy that:

1. Collects network metrics grouped by namespace/name/interface

Metrics (all rates):
- rx_bytes: rate(kubevirt_vmi_network_receive_bytes_total[5m])
- tx_bytes: rate(kubevirt_vmi_network_transmit_bytes_total[5m])
- rx_packets: rate(kubevirt_vmi_network_receive_packets_total[5m])
- tx_packets: rate(kubevirt_vmi_network_transmit_packets_total[5m])
- rx_errors: rate(kubevirt_vmi_network_receive_errors_total[5m])
- tx_errors: rate(kubevirt_vmi_network_transmit_errors_total[5m])
- rx_dropped: rate(kubevirt_vmi_network_receive_packets_dropped_total[5m])
- tx_dropped: rate(kubevirt_vmi_network_transmit_packets_dropped_total[5m])

Output format (note 3-part wildvalue):
demo-vms/fedora-demo/eth0.rx_bytes=1048576
demo-vms/fedora-demo/eth0.tx_bytes=524288
demo-vms/fedora-demo/eth0.rx_packets=1000
demo-vms/fedora-demo/eth0.tx_packets=500
demo-vms/fedora-demo/eth0.rx_errors=0
demo-vms/fedora-demo/eth0.tx_errors=0
demo-vms/fedora-demo/eth0.rx_dropped=0
demo-vms/fedora-demo/eth0.tx_dropped=0

Handle:
- Interfaces that disappear (will have no data)
- New interfaces (discovered on next AD cycle)
```

---

### Step 5.3: Network DataSource JSON

**Goal**: Create Network DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_VMI_Network.json with:

DataSource Configuration:
- name: KubeVirt_VMI_Network
- displayName: KubeVirt VMI Network
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: batchscript
- collectInterval: 60 (1 minute)
- multiInstance: true
- useWildValueAsUniqueIdentifier: true

Active Discovery:
- Use network_discovery.groovy (discovers interfaces)
- schedule: 900 (15 minutes)

Datapoints:
1. rx_bytes - unit: bytes/sec
2. tx_bytes - unit: bytes/sec
3. rx_packets - unit: packets/sec
4. tx_packets - unit: packets/sec
5. rx_errors - unit: errors/sec, alertExpr: > 0
6. tx_errors - unit: errors/sec, alertExpr: > 0
7. rx_dropped - unit: packets/sec, alertExpr: > 10
8. tx_dropped - unit: packets/sec, alertExpr: > 10

Graphs:
1. Network Throughput: rx_bytes, tx_bytes
2. Packet Rate: rx_packets, tx_packets
3. Errors and Drops: rx_errors, tx_errors, rx_dropped, tx_dropped
```

---

## Phase 6: Storage DataSource (Multi-Instance per Drive)

### Step 6.1: Storage Discovery Script

**Goal**: Create discovery that finds all drives per VM.

```text
Create the Storage discovery script at /scripts/discovery/storage_discovery.groovy that:

1. Queries storage metrics to discover all VM/drive combinations
2. Returns sub-instances in format: {namespace}/{vmi_name}/{drive}

Query: kubevirt_vmi_storage_read_traffic_bytes_total
This metric has labels: namespace, name, drive

For each unique combination, output:
- wildvalue: {namespace}/{vmi_name}/{drive}
- wildalias: {vmi_name}/{drive}
- description: Drive {drive} on VM {vmi_name}

Instance properties:
- auto.kubevirt.namespace
- auto.kubevirt.vmi_name
- auto.kubevirt.drive

Example output:
demo-vms/fedora-demo/vda##fedora-demo/vda##Drive vda on VM fedora-demo####auto.kubevirt.namespace=demo-vms&auto.kubevirt.vmi_name=fedora-demo&auto.kubevirt.drive=vda
demo-vms/fedora-demo/vdb##fedora-demo/vdb##Drive vdb on VM fedora-demo####auto.kubevirt.namespace=demo-vms&auto.kubevirt.vmi_name=fedora-demo&auto.kubevirt.drive=vdb

Handle:
- VMs with no storage metrics (skip)
- Disk hot-add detection
```

---

### Step 6.2: Storage Collection Script

**Goal**: Create BATCHSCRIPT collection for storage metrics.

```text
Create the Storage collection script at /scripts/collection/storage_collection.groovy that:

1. Collects storage metrics grouped by namespace/name/drive

Metrics (all rates):
- read_bytes: rate(kubevirt_vmi_storage_read_traffic_bytes_total[5m])
- write_bytes: rate(kubevirt_vmi_storage_write_traffic_bytes_total[5m])
- read_iops: rate(kubevirt_vmi_storage_iops_read_total[5m])
- write_iops: rate(kubevirt_vmi_storage_iops_write_total[5m])
- read_time: rate(kubevirt_vmi_storage_read_times_seconds_total[5m])
- write_time: rate(kubevirt_vmi_storage_write_times_seconds_total[5m])

Calculated metrics:
- read_latency_ms: (read_time / read_iops) * 1000 (if read_iops > 0)
- write_latency_ms: (write_time / write_iops) * 1000 (if write_iops > 0)

Output format:
demo-vms/fedora-demo/vda.read_bytes=10485760
demo-vms/fedora-demo/vda.write_bytes=5242880
demo-vms/fedora-demo/vda.read_iops=100
demo-vms/fedora-demo/vda.write_iops=50
demo-vms/fedora-demo/vda.read_latency_ms=0.5
demo-vms/fedora-demo/vda.write_latency_ms=1.0

Handle:
- Zero IOPS (don't divide by zero for latency)
- Missing time metrics
```

---

### Step 6.3: Storage DataSource JSON

**Goal**: Create Storage DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_VMI_Storage.json with:

DataSource Configuration:
- name: KubeVirt_VMI_Storage
- displayName: KubeVirt VMI Storage
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: batchscript
- collectInterval: 60 (1 minute)
- multiInstance: true
- useWildValueAsUniqueIdentifier: true

Active Discovery:
- Use storage_discovery.groovy
- schedule: 900 (15 minutes)

Datapoints:
1. read_bytes - unit: bytes/sec
2. write_bytes - unit: bytes/sec
3. read_iops - unit: ops/sec
4. write_iops - unit: ops/sec
5. read_latency_ms - unit: ms, alertExpr: > 20
6. write_latency_ms - unit: ms, alertExpr: > 20

Graphs:
1. Throughput: read_bytes, write_bytes
2. IOPS: read_iops, write_iops
3. Latency: read_latency_ms, write_latency_ms
```

---

## Phase 7: Migration and Cluster Overview DataSources

### Step 7.1: Migration Collection Script

**Goal**: Create collection for VM migration metrics.

```text
Create the Migration collection script at /scripts/collection/migration_collection.groovy that:

1. Uses same instances as VMI Discovery (namespace/vmi_name)
2. Collects migration-specific metrics

Metrics:
- migration_succeeded: kubevirt_vmi_migration_succeeded (counter - use last value)
- migration_failed: kubevirt_vmi_migration_failed (counter - use last value)
- migration_data_processed: kubevirt_vmi_migration_data_processed_bytes
- migration_data_remaining: kubevirt_vmi_migration_data_remaining_bytes
- migration_dirty_memory_rate: kubevirt_vmi_migration_dirty_memory_rate_bytes
- migration_memory_transfer_rate: kubevirt_vmi_migration_memory_transfer_rate_bytes

Note: Many of these metrics only exist during active migrations.
Handle missing metrics by outputting nothing (LogicMonitor will NaN them).

Output format:
demo-vms/fedora-demo.migration_succeeded=5
demo-vms/fedora-demo.migration_failed=0
```

---

### Step 7.2: Migration DataSource JSON

**Goal**: Create Migration DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_VMI_Migration.json with:

DataSource Configuration:
- name: KubeVirt_VMI_Migration
- displayName: KubeVirt VMI Migration
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: batchscript
- collectInterval: 300 (5 minutes - migrations are not frequent)
- multiInstance: true
- useWildValueAsUniqueIdentifier: true

Active Discovery:
- Use vmi_discovery.groovy (same as CPU/Memory)

Datapoints:
1. migration_succeeded - type: gauge, description: Total successful migrations
2. migration_failed - type: gauge, alertExpr: > 0, description: Total failed migrations
3. migration_data_processed - type: gauge, unit: bytes
4. migration_data_remaining - type: gauge, unit: bytes
5. migration_dirty_memory_rate - type: gauge, unit: bytes/sec
6. migration_memory_transfer_rate - type: gauge, unit: bytes/sec

Graphs:
1. Migration Counts: succeeded, failed
2. Migration Progress: data_processed, data_remaining (during active migration)
3. Migration Performance: dirty_memory_rate, memory_transfer_rate
```

---

### Step 7.3: Cluster Overview Collection Script

**Goal**: Create single-instance DataSource for cluster-wide stats.

```text
Create the Cluster Overview script at /scripts/collection/cluster_overview_collection.groovy that:

1. This is a SINGLE INSTANCE DataSource (no discovery needed)
2. Collects cluster-wide aggregated metrics

Metrics:
- total_vmis_running: count(kubevirt_vmi_phase_count{phase="Running"} == 1)
- total_vmis_pending: count(kubevirt_vmi_phase_count{phase="Pending"} == 1)
- total_vmis_failed: count(kubevirt_vmi_phase_count{phase="Failed"} == 1)
- total_vmis_succeeded: count(kubevirt_vmi_phase_count{phase="Succeeded"} == 1)
- virt_api_up: up{job=~".*virt-api.*"}
- virt_controller_up: up{job=~".*virt-controller.*"}
- virt_handler_count: count(up{job=~".*virt-handler.*"} == 1)
- migrations_in_progress: kubevirt_vmi_migration_data_remaining_bytes > 0 (count)

Output format (no wildvalue prefix for single instance):
total_vmis_running=10
total_vmis_pending=2
total_vmis_failed=0
virt_api_up=1
virt_controller_up=1
virt_handler_count=3
```

---

### Step 7.4: Cluster Overview DataSource JSON

**Goal**: Create Cluster Overview DataSource definition.

```text
Create the DataSource JSON file at /datasources/KubeVirt_Cluster_Overview.json with:

DataSource Configuration:
- name: KubeVirt_Cluster_Overview
- displayName: KubeVirt Cluster Overview
- group: KubeVirt
- appliesTo: kubevirt.thanos.host && kubevirt.thanos.token
- collectMethod: script (not batchscript - single instance)
- collectInterval: 60 (1 minute)
- multiInstance: false
- hasMultiInstances: false

No Active Discovery needed (single instance).

Datapoints:
1. total_vmis_running - type: gauge
2. total_vmis_pending - type: gauge, alertExpr: > 10
3. total_vmis_failed - type: gauge, alertExpr: > 0
4. total_vmis_succeeded - type: gauge
5. virt_api_up - type: gauge, alertExpr: < 1
6. virt_controller_up - type: gauge, alertExpr: < 1
7. virt_handler_count - type: gauge, alertExpr: < 1
8. migrations_in_progress - type: gauge

Graphs:
1. VM States: running, pending, failed (stacked)
2. Component Health: virt_api_up, virt_controller_up
3. Node Coverage: virt_handler_count
```

---

## Phase 8: Integration and Packaging

### Step 8.1: Validate All JSON Files

**Goal**: Ensure all DataSource JSONs are valid and importable.

```text
Create a validation script at /tests/validate_json.groovy that:

1. Reads each JSON file in /datasources/
2. Validates JSON syntax
3. Checks required fields exist:
   - name
   - displayName
   - appliesTo
   - dataPoints (array)
   - Each datapoint has: name, type

4. Validates embedded scripts have:
   - // v4 version comment
   - return statement
   - No syntax errors (basic check)

5. Reports any issues found

Run: groovy /tests/validate_json.groovy
Output: List of files checked and any validation errors.
```

---

### Step 8.2: Setup Documentation

**Goal**: Create customer-facing setup guide.

```text
Create /docs/setup_guide.md with:

# KubeVirt Thanos DataSource Setup Guide

## Prerequisites
- OpenShift 4.x with OpenShift Virtualization
- LogicMonitor Collector with HTTPS access to OpenShift
- User with cluster-admin or monitoring permissions

## Step 1: Create ServiceAccount for LogicMonitor

Provide the commands to:
1. Create ServiceAccount in openshift-monitoring namespace
2. Bind cluster-monitoring-view role
3. Extract bearer token

## Step 2: Get Thanos Querier Route

Show how to:
1. Get the thanos-querier route hostname
2. Test connectivity with curl

## Step 3: Configure LogicMonitor Device

Explain:
1. Add device representing OpenShift cluster
2. Set device properties:
   - kubevirt.thanos.host
   - kubevirt.thanos.token
   - kubevirt.thanos.port (optional)
   - kubevirt.thanos.ssl (optional)

## Step 4: Import DataSources

Instructions for:
1. Download JSON files
2. Import via Settings > LogicModules > Import
3. Verify DataSources apply to device

## Step 5: Verify Data Collection

How to:
1. Check Active Discovery ran
2. Verify instances appear
3. Confirm metrics are collecting

## Troubleshooting
Common issues and solutions.
```

---

### Step 8.3: Final Package

**Goal**: Create release-ready package.

```text
Create a release script at /package_release.sh that:

1. Creates /release/ directory
2. Copies all JSON DataSource files
3. Creates combined documentation
4. Generates SHA256 checksums
5. Creates ZIP archive

Final package structure:
kubevirt-datasources-v1.0.zip
  /datasources/
    KubeVirt_VMI_Discovery.json
    KubeVirt_VMI_CPU.json
    KubeVirt_VMI_Memory.json
    KubeVirt_VMI_Network.json
    KubeVirt_VMI_Storage.json
    KubeVirt_VMI_Migration.json
    KubeVirt_Cluster_Overview.json
  /docs/
    setup_guide.md
    troubleshooting.md
  README.md
  CHECKSUMS.txt
```

---

## Testing Checkpoints

### After Phase 2:
- [ ] Thanos client can connect and authenticate
- [ ] Discovery finds test VMs
- [ ] Instances appear in LogicMonitor

### After Phases 3-4:
- [ ] CPU metrics collecting
- [ ] Memory metrics collecting
- [ ] Calculated metrics (percentages) correct

### After Phases 5-6:
- [ ] Network sub-instances (per interface) discovered
- [ ] Storage sub-instances (per drive) discovered
- [ ] All throughput/latency metrics collecting

### After Phase 7:
- [ ] Migration metrics visible during live migration
- [ ] Cluster overview shows correct totals

### After Phase 8:
- [ ] All JSONs import without error
- [ ] Documentation complete
- [ ] Package ready for customer delivery

---

## Rollback Points

Each phase can be deployed independently. If issues occur:

1. Remove problematic DataSource via API/UI
2. Debug collection scripts using Collector Debug Facility
3. Re-import fixed DataSource

DataSources do not affect each other - removing one does not impact others.
