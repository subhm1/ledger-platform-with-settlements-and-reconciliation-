"use client";

import { RefreshCw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { apiGet } from "@/lib/api/client";
import type { HealthDocument } from "@/lib/api/types";
import { ErrorState, Loading, StatePanel } from "@/components/ui/feedback";

type Probe = { state: "good" | "bad" | "unknown"; value: string; detail: string };
type HealthState = { application: Probe; liveness: Probe; readiness: Probe; database: Probe };

const unavailable = (detail: string): Probe => ({ state: "unknown", value: "Unavailable", detail });
const probe = (document: HealthDocument, label: string): Probe => {
  const status = document.status?.toUpperCase();
  if (!status) return unavailable(`${label} response did not include a health status.`);
  return { state: status === "UP" ? "good" : "bad", value: status, detail: `${label} endpoint response` };
};

export function HealthDashboard() {
  const [data, setData] = useState<HealthState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const refresh = useCallback(() => {
    setLoading(true);
    setError("");
    setRefreshKey((value) => value + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const paths = ["actuator/health", "actuator/health/liveness", "actuator/health/readiness"];
    Promise.allSettled(paths.map((path) => apiGet<HealthDocument>(path, controller.signal))).then((responses) => {
      const overall = responses[0].status === "fulfilled" ? responses[0].value : null;
      const liveness = responses[1].status === "fulfilled" ? probe(responses[1].value, "Liveness") : unavailable("Liveness endpoint could not be reached.");
      const readiness = responses[2].status === "fulfilled" ? probe(responses[2].value, "Readiness") : unavailable("Readiness endpoint could not be reached.");
      if (!overall && !controller.signal.aborted) setError("The backend health endpoint is unavailable.");
      const components = overall?.components ?? {};
      const databaseStatus = components.db?.status?.toUpperCase();
      const database: Probe = databaseStatus
        ? { state: databaseStatus === "UP" ? "good" : "bad", value: databaseStatus, detail: "Database component reported by aggregate health" }
        : unavailable("Database health is not exposed by the current aggregate endpoint.");
      setData({
        application: overall ? probe(overall, "Aggregate application health") : unavailable("Aggregate health endpoint could not be reached."),
        liveness, readiness, database,
      });
    }).catch(() => setError("Health data could not be loaded.")).finally(() => setLoading(false));
    return () => controller.abort();
  }, [refreshKey]);

  return <>
    <div className="page-heading"><div><div className="eyebrow">Runtime status</div><h1>System health</h1><p>Live health signals from the Spring Boot application. No synthetic service metrics are shown.</p></div><button className="button secondary" onClick={refresh} disabled={loading}><RefreshCw size={14} /> Refresh</button></div>
    {loading ? <section className="surface-card"><Loading label="Checking application health" /></section> : error && <div style={{ marginBottom: 14 }}><ErrorState message={error} onRetry={refresh} /></div>}
    {!loading && data && <div className="health-grid">
      <StatePanel title="Application" value={data.application.value} detail={data.application.detail} state={data.application.state} />
      <StatePanel title="Liveness" value={data.liveness.value} detail={data.liveness.detail} state={data.liveness.state} />
      <StatePanel title="Readiness" value={data.readiness.value} detail={data.readiness.detail} state={data.readiness.state} />
      <StatePanel title="Database" value={data.database.value} detail={data.database.detail} state={data.database.state} />
    </div>}
    <p className="notice" style={{ marginTop: 18 }}>This local view reports only current health endpoint responses. It does not provide uptime, latency, throughput, alert history, or production monitoring.</p>
  </>;
}
