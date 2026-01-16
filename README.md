# KubeVirt Thanos DataSources for LogicMonitor

LogicMonitor DataSources for monitoring OpenShift Virtualization (KubeVirt) VMs by querying Thanos Querier directly.

## Overview

This project provides a suite of 7 LogicMonitor DataSources to monitor KubeVirt virtual machines running on OpenShift. It queries the Thanos Querier API to collect VM metrics, providing an alternative to the OpenMetrics module.

## Directory Structure

```
/datasources/          # Final JSON DataSource files for import into LogicMonitor
/scripts/
  /discovery/          # Active Discovery Groovy scripts
  /collection/         # Data collection Groovy scripts
  /lib/                # Shared library code (Thanos client)
/tests/
  /mock_responses/     # Mock Thanos API responses for testing
/docs/                 # Internal planning and specification documents
```

## DataSources Included

| DataSource | Type | Description |
|------------|------|-------------|
| KubeVirt_VMI_Discovery | Multi-Instance | Discovers running VMs, sets properties |
| KubeVirt_VMI_CPU | Multi-Instance | CPU metrics per VM |
| KubeVirt_VMI_Memory | Multi-Instance | Memory metrics per VM |
| KubeVirt_VMI_Network | Multi-Instance | Network metrics per interface per VM |
| KubeVirt_VMI_Storage | Multi-Instance | Storage metrics per disk per VM |
| KubeVirt_VMI_Migration | Multi-Instance | Live migration tracking per VM |
| KubeVirt_Cluster_Overview | Single-Instance | Cluster-wide KubeVirt statistics |

## Prerequisites

- OpenShift 4.x with OpenShift Virtualization installed
- LogicMonitor Collector with HTTPS access to OpenShift
- ServiceAccount with `cluster-monitoring-view` ClusterRole

## Device Properties Required

| Property | Description | Example |
|----------|-------------|---------|
| `kubevirt.thanos.host` | Thanos Querier route hostname | `thanos-querier-openshift-monitoring.apps.cluster.example.com` |
| `kubevirt.thanos.token` | ServiceAccount bearer token | `eyJhbGciOiJSUzI1NiIs...` |
| `kubevirt.thanos.port` | Port (optional, default 443) | `443` |
| `kubevirt.thanos.ssl` | Use SSL (optional, default true) | `true` |

## Setup

1. Create a ServiceAccount in OpenShift with monitoring permissions
2. Extract the bearer token
3. Add a device in LogicMonitor representing the OpenShift cluster
4. Set the required device properties
5. Import the DataSource JSON files

See `docs/setup_guide.md` for detailed instructions (created in Phase 8).

## vSphere Parity

These DataSources provide approximately 85% feature parity with vSphere VM monitoring:
- CPU usage and ready time
- Memory active, usage, and swap
- Disk read/write throughput, IOPS, and latency
- Network throughput, packets, errors, and drops
- Power state and migration tracking

## License

Apache 2.0
