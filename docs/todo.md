# KubeVirt Thanos DataSource - Project State Tracker

## Status: IN PROGRESS

**Last Updated**: 2026-01-16
**Current Phase**: 1 - Foundation

---

## Phase 1: Foundation and Infrastructure

### Step 1.1: Project Setup
- [ ] Create directory structure
- [ ] Create README.md
- [ ] Establish coding standards

### Step 1.2: Thanos Client Library
- [ ] Create thanos_client.groovy
- [ ] Implement queryInstant()
- [ ] Implement queryWithLabels()
- [ ] Implement error handling
- [ ] Test with mock data

### Step 1.3: Test Mock Data
- [ ] Create vmi_phase_running.json
- [ ] Create vmi_cpu_metrics.json
- [ ] Create vmi_memory_metrics.json
- [ ] Create vmi_network_metrics.json
- [ ] Create vmi_storage_metrics.json

---

## Phase 2: Discovery DataSource

### Step 2.1: VMI Discovery Script
- [ ] Create vmi_discovery.groovy
- [ ] Test discovery output format
- [ ] Verify instance properties

### Step 2.2: VMI Discovery DataSource JSON
- [ ] Create KubeVirt_VMI_Discovery.json
- [ ] Embed discovery script
- [ ] Define status datapoints
- [ ] Create graphs

---

## Phase 3: CPU DataSource

### Step 3.1: CPU Collection Script
- [ ] Create cpu_collection.groovy
- [ ] Test BATCHSCRIPT output
- [ ] Verify rate calculations

### Step 3.2: CPU DataSource JSON
- [ ] Create KubeVirt_VMI_CPU.json
- [ ] Embed collection script
- [ ] Define all datapoints
- [ ] Set alert thresholds
- [ ] Create graphs

---

## Phase 4: Memory DataSource

### Step 4.1: Memory Collection Script
- [ ] Create memory_collection.groovy
- [ ] Test memory calculations
- [ ] Handle balloon metrics

### Step 4.2: Memory DataSource JSON
- [ ] Create KubeVirt_VMI_Memory.json
- [ ] Embed collection script
- [ ] Define all datapoints
- [ ] Set alert thresholds
- [ ] Create graphs

---

## Phase 5: Network DataSource

### Step 5.1: Network Discovery Script
- [ ] Create network_discovery.groovy
- [ ] Test interface discovery
- [ ] Verify 3-part wildvalue format

### Step 5.2: Network Collection Script
- [ ] Create network_collection.groovy
- [ ] Test per-interface output

### Step 5.3: Network DataSource JSON
- [ ] Create KubeVirt_VMI_Network.json
- [ ] Embed both scripts
- [ ] Define all datapoints
- [ ] Set alert thresholds
- [ ] Create graphs

---

## Phase 6: Storage DataSource

### Step 6.1: Storage Discovery Script
- [ ] Create storage_discovery.groovy
- [ ] Test drive discovery
- [ ] Verify 3-part wildvalue format

### Step 6.2: Storage Collection Script
- [ ] Create storage_collection.groovy
- [ ] Test latency calculations
- [ ] Handle divide-by-zero

### Step 6.3: Storage DataSource JSON
- [ ] Create KubeVirt_VMI_Storage.json
- [ ] Embed both scripts
- [ ] Define all datapoints
- [ ] Set alert thresholds
- [ ] Create graphs

---

## Phase 7: Migration and Cluster Overview

### Step 7.1: Migration Collection Script
- [ ] Create migration_collection.groovy
- [ ] Handle missing metrics

### Step 7.2: Migration DataSource JSON
- [ ] Create KubeVirt_VMI_Migration.json

### Step 7.3: Cluster Overview Script
- [ ] Create cluster_overview_collection.groovy
- [ ] Test aggregation queries

### Step 7.4: Cluster Overview DataSource JSON
- [ ] Create KubeVirt_Cluster_Overview.json

---

## Phase 8: Integration and Packaging

### Step 8.1: Validate All JSON Files
- [ ] Create validate_json.groovy
- [ ] Run validation
- [ ] Fix any issues

### Step 8.2: Setup Documentation
- [ ] Create setup_guide.md
- [ ] Create troubleshooting.md

### Step 8.3: Final Package
- [ ] Create release script
- [ ] Generate checksums
- [ ] Create ZIP archive

---

## Testing Checkpoints

### Integration Test - Phase 2
- [ ] Thanos client can connect
- [ ] Discovery finds test VMs
- [ ] Instances appear in LogicMonitor

### Integration Test - Phases 3-4
- [ ] CPU metrics collecting
- [ ] Memory metrics collecting
- [ ] Calculated percentages correct

### Integration Test - Phases 5-6
- [ ] Network sub-instances discovered
- [ ] Storage sub-instances discovered
- [ ] All metrics collecting

### Integration Test - Phase 7
- [ ] Migration metrics visible
- [ ] Cluster overview correct

### Final Validation - Phase 8
- [ ] All JSONs import successfully
- [ ] Documentation complete
- [ ] Package ready

---

## Notes and Decisions

### 2026-01-16
- Project initialized
- Spec created based on previous brainstorm sessions
- Hybrid multi-instance approach confirmed (Option C)
- Target: 7 DataSources total

### OpenShift Test Environment
- 2 test VMs available
- Namespaces: TBD
- Thanos route: TBD

---

## Blockers

None currently.

---

## Reference Links

- [KubeVirt Metrics Documentation](http://kubevirt.io/monitoring/metrics.html)
- [OpenShift Thanos API Access](https://docs.openshift.com/container-platform/4.16/observability/monitoring/accessing-third-party-monitoring-apis.html)
- [LogicMonitor Groovy Scripting](https://www.logicmonitor.com/support/terminology-syntax/scripting-support/embedded-groovy-scripting)
- [LogicMonitor Active Discovery](https://www.logicmonitor.com/support/active-discovery)
