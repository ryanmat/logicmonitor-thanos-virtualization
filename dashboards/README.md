# Thanos Suite Dashboards

LogicMonitor dashboards that visualize metrics and alerts from the Thanos DataSource Suite. Each dashboard is exported as JSON from a reference portal and can be imported into any customer portal that has deployed the matching DataSources and PropertySource.

## Dashboard Index

| File | Name | Covers | Status |
|------|------|--------|--------|
| `openshift_thanos_overview.json` | OpenShift Thanos Overview | Executive health snapshot across all 7 OpenShift suites | Available |
| `openshift_platform_health.json` | OpenShift Platform Health | OCP + Etcd deep dive (control plane, scheduler, etcd quorum + perf, certs) | Available |
| `openshift_storage.json` | OpenShift Storage | ODF (Ceph + NooBaa + Rook) + Portworx (cluster + KVDB + Autopilot) with capacity gauges | Available |
| `openshift_gitops_delivery.json` | OpenShift GitOps Delivery | ArgoCD: app inventory + sync + controller + repo + gRPC server + alerts | Available |
| `openshift_kubevirt_virtualization.json` | OpenShift Virtualization (KubeVirt) | KubeVirt cluster rollup + virt components + live migrations + alerts | Available |
| `multi_cluster_fleet.json` | Multi-Cluster Fleet (ACM) | ACM hub fleet + managed cluster availability + hub components + controllers + Thanos observability | Available |
| `active_alerts.json` | Active Alerts - OpenShift Thanos Suite | Cross-suite triage view scoped by severity (Critical, Error, Warning, All) | Available |

## Design Conventions

- All widgets use `*` glob matching on device name, device group, and instance name. Portability comes from the DataSource's `appliesTo` expression (`hasCategory("OpenShift_*")`), not from hardcoded device identifiers.
- Cluster-level health signals use the `_Overview` DataSources (one singleton instance per cluster) so fleet-wide aggregates resolve cleanly with `sum` / `max` aggregate functions.
- Dashboard group on the portal: `OpenShift Thanos Monitoring`.

## Prerequisites Before Import

1. Import the PropertySource `propertysources/addCategory_OpenShift_Thanos.json`. This sets the `system.categories` used by widget filters.
2. Import the relevant DataSource suite(s) from `datasources/`.
3. Set the Thanos connection properties on each target device or on a parent device group:
   - `openshift.thanos.host`
   - `openshift.thanos.pass` (service account bearer token)
   - `openshift.thanos.port` (default 443)
   - `openshift.thanos.ssl` (default true)
4. Wait one discovery interval so each `_Overview` DataSource registers its cluster-level instance.

## Importing a Dashboard

### Via LogicMonitor UI
Dashboards -> (group) -> Add -> Import Dashboard -> select the JSON file.

### Via LM REST API
```bash
curl -X POST "https://<portal>.logicmonitor.com/santaba/rest/dashboard/dashboards" \
  -H "Authorization: LMv1 <signed>" \
  -H "Content-Type: application/json" \
  --data @openshift_thanos_overview.json
```

### Via LM MCP Server
```
create_dashboard(
  name="OpenShift Thanos Overview",
  group_id=<target_group_id>,
  template=<contents_of_openshift_thanos_overview.json>
)
```

## After Import

- The `Active Alerts` widget filter defaults to `group: "*"`. On import, scope it to the customer's OpenShift device group for tenant isolation.
- Widgets render "No Data" for any suite the customer has not deployed. This is expected. Removing the widget is optional.
- Color thresholds are intentionally omitted on first import. Add them per customer preference after confirming baseline values.
