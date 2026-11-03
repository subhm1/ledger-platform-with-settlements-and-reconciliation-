"use client";

import { ChevronDown, ChevronLeft, ChevronRight, FileUp, Play } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPost, type Page } from "@/lib/api/client";
import type { ReconciliationResult, ReconciliationRun, SettlementBatch, SettlementRecord } from "@/lib/api/types";
import { formatDate, formatMinorUnits } from "@/lib/format";
import { Empty, ErrorState, Loading } from "@/components/ui/feedback";
import { ProviderReconciliationView } from "./provider-reconciliation-view";

export function ReconciliationConsole() {
  const [activeTab, setActiveTab] = useState<"settlement" | "provider">("settlement");
  const [batches, setBatches] = useState<Page<SettlementBatch> | null>(null);
  const [selectedBatch, setSelectedBatch] = useState<string | null>(null);
  const [batchPage, setBatchPage] = useState(0);
  const [records, setRecords] = useState<Page<SettlementRecord> | null>(null);
  const [recordPage, setRecordPage] = useState(0);
  const [runs, setRuns] = useState<Page<ReconciliationRun> | null>(null);
  const [runPage, setRunPage] = useState(0);
  const [results, setResults] = useState<Page<ReconciliationResult> | null>(null);
  const [resultsPage, setResultsPage] = useState(0);
  const [selectedRun, setSelectedRun] = useState<string | null>(null);
  const [outcome, setOutcome] = useState("");
  const [loadingBatches, setLoadingBatches] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [loadingResults, setLoadingResults] = useState(false);
  const [error, setError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const [reconciling, setReconciling] = useState(false);
  const [actionMessage, setActionMessage] = useState("");

  const refreshBatches = useCallback(() => {
    setLoadingBatches(true);
    setError("");
    setRefreshKey((key) => key + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    apiGet<Page<SettlementBatch>>(`api/v1/settlement-batches?page=${batchPage}&size=25`, controller.signal)
      .then((response) => {
        setBatches(response);
        setSelectedBatch((current) => {
          const next = current && response.items.some((batch) => batch.batchId === current)
            ? current : response.items[0]?.batchId ?? null;
          if (next !== current && next) setLoadingDetail(true);
          return next;
        });
      }).catch((reason: unknown) => {
        if (reason instanceof Error && reason.name === "AbortError") return;
        setError(reason instanceof Error ? reason.message : "Could not load settlement batches.");
      }).finally(() => setLoadingBatches(false));
    return () => controller.abort();
  }, [refreshKey, batchPage]);

  useEffect(() => {
    if (!selectedBatch) return;
    const controller = new AbortController();
    const root = `api/v1/settlement-batches/${encodeURIComponent(selectedBatch)}`;
    Promise.all([
      apiGet<Page<SettlementRecord>>(`${root}/records?page=${recordPage}&size=25`, controller.signal),
      apiGet<Page<ReconciliationRun>>(`${root}/reconciliation-runs?page=${runPage}&size=25`, controller.signal),
    ]).then(([recordResponse, runResponse]) => {
      setRecords(recordResponse); setRuns(runResponse);
      if (!selectedRun || !runResponse.items.some((run) => run.runId === selectedRun)) {
        const nextRun = runResponse.items[0]?.runId ?? null;
        setSelectedRun(nextRun);
        setResults(null);
        setResultsPage(0);
        setLoadingResults(!!nextRun);
      }
    }).catch((reason: unknown) => {
      if (reason instanceof Error && reason.name === "AbortError") return;
      setError(reason instanceof Error ? reason.message : "Could not load batch details.");
    }).finally(() => setLoadingDetail(false));
    return () => controller.abort();
  }, [selectedBatch, recordPage, runPage, selectedRun, refreshKey]);

  useEffect(() => {
    if (!selectedRun) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(resultsPage), size: "25" });
    if (outcome) params.set("outcome", outcome);
    apiGet<Page<ReconciliationResult>>(`api/v1/reconciliation-runs/${encodeURIComponent(selectedRun)}/results?${params}`, controller.signal)
      .then(setResults).catch((reason: unknown) => {
        if (reason instanceof Error && reason.name === "AbortError") return;
        setError(reason instanceof Error ? reason.message : "Could not load reconciliation results.");
      }).finally(() => setLoadingResults(false));
    return () => controller.abort();
  }, [selectedRun, outcome, resultsPage]);

  const activeBatch = batches?.items.find((batch) => batch.batchId === selectedBatch);
  const activeRun = runs?.items.find((run) => run.runId === selectedRun);
  const selectRun = (runId: string) => {
    if (runId === selectedRun) return;
    setError("");
    setResults(null);
    setResultsPage(0);
    setLoadingResults(true);
    setSelectedRun(runId);
  };

  async function reconcileSelectedBatch() {
    if (!selectedBatch || reconciling) return;
    setReconciling(true);
    setActionMessage("");
    setError("");
    try {
      const response = await apiPost<{ runId: string; batchId: string }>(
        `api/v1/settlement-batches/${encodeURIComponent(selectedBatch)}/reconciliation-runs`);
      setRunPage(0);
      setResultsPage(0);
      setOutcome("");
      setResults(null);
      setLoadingDetail(true);
      setLoadingResults(true);
      setSelectedRun(response.runId);
      setRefreshKey((key) => key + 1);
      setActionMessage(`Reconciliation run ${response.runId} completed and saved.`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Reconciliation failed.");
    } finally {
      setReconciling(false);
    }
  }

  return <>
    <div className="page-heading">
      <div>
        <div className="eyebrow">External evidence</div>
        <h1>Reconciliation &amp; settlements</h1>
        <p>Review imported reports, validation outcomes, provider evidence, and persisted reconciliation history.</p>
      </div>
      {activeTab === "settlement" && (
        <UploadReport
          onImported={(batchId) => {
            setBatchPage(0);
            setSelectedBatch(batchId);
            setLoadingDetail(true);
            refreshBatches();
          }}
        />
      )}
    </div>

    <div style={{ display: "flex", gap: "8px", marginBottom: "18px" }}>
      <button
        type="button"
        className={`button ${activeTab === "settlement" ? "" : "secondary"}`}
        onClick={() => setActiveTab("settlement")}
      >
        Settlement batches
      </button>
      <button
        type="button"
        className={`button ${activeTab === "provider" ? "" : "secondary"}`}
        onClick={() => setActiveTab("provider")}
      >
        Provider payments (Razorpay)
      </button>
    </div>

    {activeTab === "provider" ? (
      <ProviderReconciliationView />
    ) : (
      <>
        <div className="summary-grid">
          <div className="summary-card"><span>Settlement batches</span><strong>{batches?.totalElements.toLocaleString() ?? "—"}</strong><p>Most recent imports first</p></div>
          <div className="summary-card"><span>Selected batch rows</span><strong>{activeBatch?.recordCount.toLocaleString() ?? "—"}</strong><p>{activeBatch ? `${activeBatch.validRecordCount} valid · ${activeBatch.invalidRecordCount} invalid` : "Select a batch to inspect rows"}</p></div>
          <div className="summary-card"><span>Reconciliation runs</span><strong>{runs?.totalElements.toLocaleString() ?? "—"}</strong><p>Each run is retained as history</p></div>
        </div>
        {error && <div style={{ marginBottom: 14 }}><ErrorState message={error} onRetry={refreshBatches} /></div>}
        {actionMessage && <p className="upload-message success" role="status">{actionMessage}</p>}
        <div className="split-layout">
          <section className="surface-card" aria-label="Settlement batches"><div className="toolbar"><strong>Imported reports</strong><span className="toolbar-spacer" /><span className="muted" style={{ fontSize: 11 }}>Select a batch</span></div>
            {loadingBatches ? <Loading label="Loading imports" /> : !batches?.items.length ? <Empty title="No settlement batches" detail="Import a CSV report to begin reviewing external evidence." /> : batches.items.map((batch) => <button key={batch.batchId} className={`batch-item ${selectedBatch === batch.batchId ? "selected" : ""}`} onClick={() => { setLoadingDetail(true); setError(""); setSelectedBatch(batch.batchId); setRecordPage(0); setRunPage(0); setResults(null); setSelectedRun(null); }} aria-pressed={selectedBatch === batch.batchId}>
              <span className="batch-main"><span>{batch.sourceCode} / {batch.reportId}</span><span className={`badge ${batch.status === "RECONCILED" ? "good" : ""}`}>{batch.status.toLowerCase()}</span></span>
              <span className="batch-sub">{batch.recordCount} rows · {formatDate(batch.createdAt)}</span><span className="batch-sub mono">{batch.batchId}</span>
            </button>)}
            {batches && <Pager page={batches.page} totalPages={batches.totalPages} onPage={(next) => { setLoadingBatches(true); setLoadingDetail(true); setError(""); setRecordPage(0); setRunPage(0); setResults(null); setSelectedRun(null); setBatchPage(next); }} label="Settlement batches" />}
          </section>
          <div className="detail-stack">
            <section className="section-card"><h2 className="section-title">Batch evidence <span className="muted">{activeBatch?.reportId ?? "—"}</span></h2>
              {!selectedBatch ? <p className="muted">Select an imported batch to view its stored rows.</p> : loadingDetail ? <Loading label="Loading batch evidence" /> : records?.items.length ? <><div className="table-wrap"><table><thead><tr><th>Row</th><th>Reference</th><th>Validation</th><th>Gross amount</th></tr></thead><tbody>{records.items.map((record) => <tr key={record.recordId}><td>{record.rowNumber}</td><td className="mono">{record.transactionReference ?? "—"}</td><td><span className={`badge ${record.validationStatus === "VALID" ? "good" : "bad"}`}>{record.validationStatus.toLowerCase()}</span>{record.validationErrors.length > 0 && <div className="muted" style={{ fontSize: 10, marginTop: 4 }}>{record.validationErrors.join("; ")}</div>}</td><td className="amount">{formatMinorUnits(record.grossAmountMinor, record.currencyCode, record.minorUnitExponent)}</td></tr>)}</tbody></table></div><Pager page={records.page} totalPages={records.totalPages} onPage={(next) => { setLoadingDetail(true); setRecordPage(next); }} label="Batch records" /></> : <Empty title="No stored rows" detail="This batch has no settlement row records." />}
            </section>
            <section className="section-card"><h2 className="section-title">Reconciliation runs <span className="muted">{runs?.totalElements ?? 0}</span><button className="button" type="button" disabled={!selectedBatch || reconciling} onClick={reconcileSelectedBatch}><Play size={14} />{reconciling ? "Reconciling…" : "Run reconciliation"}</button></h2>
              {!runs?.items.length ? <p className="muted">No reconciliation run is recorded for this batch.</p> : <><div className="table-wrap"><table><thead><tr><th>Run</th><th>Status</th><th>Rule</th><th>Started</th></tr></thead><tbody>{runs.items.map((run) => <tr key={run.runId} onClick={() => selectRun(run.runId)} style={{ cursor: "pointer", background: selectedRun === run.runId ? "var(--accent-soft)" : undefined }}><td><button className="row-button" onClick={() => selectRun(run.runId)}>#{run.runNumber}</button></td><td><span className={`badge ${run.status === "COMPLETED" ? "good" : run.status === "FAILED" ? "bad" : "warn"}`}>{run.status.toLowerCase()}</span></td><td className="mono">{run.ruleVersion}</td><td>{formatDate(run.startedAt)}</td></tr>)}</tbody></table></div><Pager page={runs.page} totalPages={runs.totalPages} onPage={(next) => { setLoadingDetail(true); setRunPage(next); }} label="Reconciliation runs" /></>}
            </section>
            <section className="section-card"><h2 className="section-title">Run results <span className="muted">{activeRun ? `Run #${activeRun.runNumber}` : "—"}</span><label className="sr-only" htmlFor="outcome">Filter by outcome</label><select id="outcome" className="field" style={{ minWidth: 145, height: 30 }} value={outcome} onChange={(event) => { setOutcome(event.target.value); setResultsPage(0); setResults(null); setLoadingResults(!!selectedRun); }}><option value="">All classifications</option>{["MATCHED", "EXTERNAL_ONLY", "INTERNAL_ONLY", "DUPLICATE_EXTERNAL", "AMOUNT_MISMATCH", "AMBIGUOUS", "INVALID"].map((item) => <option key={item} value={item}>{item.replaceAll("_", " ")}</option>)}</select></h2>
              {!selectedRun ? <p className="muted">Select a recorded run to inspect its classifications.</p> : loadingResults ? <Loading label="Loading reconciliation results" /> : results?.items.length ? <><div className="table-wrap"><table><thead><tr><th>Classification</th><th>Reference</th><th>Reported gross</th><th>Internal debit</th></tr></thead><tbody>{results.items.map((result) => <tr key={result.resultId}><td><span className={`badge ${result.outcome === "MATCHED" ? "good" : result.outcome === "INVALID" || result.outcome === "AMOUNT_MISMATCH" ? "bad" : "warn"}`}>{result.outcome.toLowerCase().replaceAll("_", " ")}</span></td><td className="mono">{result.transactionReference ?? "—"}</td><td className="amount">{formatMinorUnits(result.reportedGrossMinor, result.currencyCode, result.minorUnitExponent)}</td><td className="amount">{formatMinorUnits(result.internalDebitTotalMinor, result.currencyCode, result.minorUnitExponent)}</td></tr>)}</tbody></table></div><Pager page={results.page} totalPages={results.totalPages} onPage={(next) => { setLoadingResults(true); setResultsPage(next); }} label="Reconciliation results" /></> : <Empty title="No results for this filter" detail="This run has no persisted classifications matching the selected outcome." />}
            </section>
          </div>
        </div>
        <p className="notice" style={{ marginTop: 16 }}>Settlement evidence is external input. Reconciliation creates historical runs and does not change posted ledger transactions. Manual resolution and conflict replacement are not available.</p>
      </>
    )}
  </>;
}

function Pager({ page, totalPages, onPage, label }: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
  label: string;
}) {
  if (totalPages <= 1) return null;
  return <div className="table-footer">
    <span>Page {page + 1} of {totalPages}</span>
    <div className="pagination" aria-label={`${label} pagination`}>
      <button type="button" aria-label={`Previous ${label} page`} disabled={page <= 0} onClick={() => onPage(page - 1)}><ChevronLeft size={16} /></button>
      <button type="button" aria-label={`Next ${label} page`} disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}><ChevronRight size={16} /></button>
    </div>
  </div>;
}

function UploadReport({ onImported }: { onImported: (batchId: string) => void }) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  const [isError, setIsError] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setMessage(""); setIsError(false);
    const element = event.currentTarget;
    const form = new FormData(element);
    for (const name of ["reportingPeriodStart", "reportingPeriodEnd"]) {
      const localValue = String(form.get(name) ?? "");
      const date = new Date(localValue);
      if (Number.isNaN(date.valueOf())) {
        setPending(false); setIsError(true); setMessage("Enter a valid reporting period."); return;
      }
      form.set(name, date.toISOString());
    }
    try {
      const result = await apiPost<{ batchId: string; status: string }>("api/v1/settlement-batches", form);
      setMessage(`Backend confirmed import: ${result.status}. Batch ${result.batchId}.`);
      onImported(result.batchId);
      element.reset();
    } catch (reason) {
      setIsError(true); setMessage(reason instanceof Error ? reason.message : "Import failed.");
    } finally { setPending(false); }
  }

  return <details open={open} onToggle={(event) => setOpen((event.currentTarget as HTMLDetailsElement).open)} style={{ position: "relative" }}>
    <summary className="button" style={{ listStyle: "none" }}><FileUp size={15} /> Import report <ChevronDown size={14} /></summary>
    {open && <div className="section-card" style={{ position: "absolute", zIndex: 2, right: 0, top: 44, width: "min(480px,90vw)", boxShadow: "var(--shadow)" }}>
      <h2 className="section-title">Import settlement CSV</h2><form className="upload-grid" onSubmit={submit}>
        <label>Source code<input name="sourceCode" maxLength={80} required /></label><label>Report ID<input name="reportId" maxLength={150} required /></label>
        <label>Reporting period start<input name="reportingPeriodStart" type="datetime-local" required /></label><label>Reporting period end<input name="reportingPeriodEnd" type="datetime-local" required /></label>
        <label className="wide">CSV file<input name="file" type="file" accept=".csv,text/csv" required /></label>
        <div className="wide"><button className="button" type="submit" disabled={pending}>{pending ? "Submitting…" : "Submit import"}</button><p className={`upload-message ${isError ? "error" : message ? "success" : ""}`} role="status">{message || "The backend response confirms the import outcome."}</p></div>
      </form>
    </div>}
  </details>;
}
