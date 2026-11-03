"use client";

import * as Dialog from "@radix-ui/react-dialog";
import { ChevronLeft, ChevronRight, Search, X } from "lucide-react";
import { useEffect, useState } from "react";
import { apiGet, type Page } from "@/lib/api/client";
import type { TransactionDetail, TransactionSummary } from "@/lib/api/types";
import { formatDate, formatMinorUnits } from "@/lib/format";
import { Empty, ErrorState, Loading } from "@/components/ui/feedback";

export function TransactionExplorer() {
  const [page, setPage] = useState(0);
  const [reference, setReference] = useState("");
  const [currency, setCurrency] = useState("");
  const [queryReference, setQueryReference] = useState("");
  const [queryCurrency, setQueryCurrency] = useState("");
  const [data, setData] = useState<Page<TransactionSummary> | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const refresh = () => {
    setLoading(true);
    setError("");
    setRefreshKey((value) => value + 1);
  };

  useEffect(() => {
    const linkedTransactionId = new URLSearchParams(window.location.search).get("transactionId");
    // eslint-disable-next-line react-hooks/set-state-in-effect -- hydrate an explicit client-side receipt deep link after mount
    if (linkedTransactionId) setSelected(linkedTransactionId);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(page), size: "25" });
    if (queryReference) params.set("transactionReference", queryReference);
    if (queryCurrency) params.set("currencyCode", queryCurrency);
    apiGet<Page<TransactionSummary>>(`api/v1/transactions?${params}`, controller.signal)
      .then(setData).catch((reason: unknown) => {
        if (reason instanceof Error && reason.name === "AbortError") return;
        setError(reason instanceof Error ? reason.message : "Could not load transactions.");
      }).finally(() => setLoading(false));
    return () => controller.abort();
  }, [page, queryReference, queryCurrency, refreshKey]);

  const submitSearch = (event: React.FormEvent) => {
    event.preventDefault();
    const nextReference = reference.trim();
    const nextCurrency = currency.trim().toUpperCase();
    setLoading(true);
    setError("");
    setPage(0);
    setQueryReference(nextReference);
    setQueryCurrency(nextCurrency);
    const params = new URLSearchParams();
    if (nextReference) params.set("transactionReference", nextReference);
    if (nextCurrency) params.set("currencyCode", nextCurrency);
    window.history.replaceState(null, "", `/transactions${params.size ? `?${params}` : ""}`);
  };

  const openDetail = (id: string | null) => {
    setSelected(id);
    const url = new URL(window.location.href);
    if (id) url.searchParams.set("transactionId", id);
    else url.searchParams.delete("transactionId");
    window.history.replaceState(null, "", `${url.pathname}${url.search}`);
  };

  return <>
    <div className="page-heading"><div><div className="eyebrow">Ledger records</div><h1>Transactions</h1><p>Explore posted journal transactions and their immutable entry details.</p></div></div>
    <section className="surface-card" aria-label="Transaction records">
      <form className="toolbar" onSubmit={submitSearch}>
        <label className="search-field"><Search size={15} aria-hidden="true" /><span className="sr-only">Search transaction reference</span><input value={reference} onChange={(event) => setReference(event.target.value)} placeholder="Transaction reference" /></label>
        <label><span className="sr-only">Currency code</span><input className="field" value={currency} maxLength={3} onChange={(event) => setCurrency(event.target.value)} placeholder="Currency (e.g. USD)" /></label>
        <button className="button secondary" type="submit">Apply filters</button><span className="toolbar-spacer" />
        <span className="muted" style={{ fontSize: 11 }}>Posted records · newest first</span>
      </form>
      {loading ? <Loading label="Loading transactions" /> : error ? <ErrorState message={error} onRetry={refresh} /> : !data?.items.length ? <Empty title="No transactions found" detail="Try another reference or currency filter." /> : <>
        <div className="table-wrap"><table><thead><tr><th>Reference</th><th>Status</th><th>Currency</th><th className="amount">Debit total</th><th>Posted</th><th>Reversal</th></tr></thead><tbody>
          {data.items.map((transaction) => <tr key={transaction.transactionId}>
            <td><button className="row-button mono" onClick={() => openDetail(transaction.transactionId)}>{transaction.transactionReference}</button></td>
            <td><span className="badge good">{transaction.status.toLowerCase()}</span></td><td>{transaction.currencyCode}</td>
            <td className="amount">{formatMinorUnits(transaction.totalDebitMinor, transaction.currencyCode, transaction.minorUnitExponent)}</td>
            <td>{formatDate(transaction.postedAt)}</td><td className="mono muted">{transaction.reversalOfTransactionId ? transaction.reversalOfTransactionId.slice(0, 8) : "—"}</td>
          </tr>)}
        </tbody></table></div>
        <div className="table-footer"><span>{data.totalElements.toLocaleString()} transactions · page {data.page + 1} of {Math.max(1, data.totalPages)}</span><div className="pagination"><button aria-label="Previous page" disabled={data.page <= 0} onClick={() => { setLoading(true); setPage((p) => Math.max(0, p - 1)); }}><ChevronLeft size={16} /></button><button aria-label="Next page" disabled={data.page + 1 >= data.totalPages} onClick={() => { setLoading(true); setPage((p) => p + 1); }}><ChevronRight size={16} /></button></div></div>
      </>}
    </section>
    <TransactionDrawer key={selected ?? "closed"} transactionId={selected} onClose={() => openDetail(null)} />
  </>;
}

function TransactionDrawer({ transactionId, onClose }: { transactionId: string | null; onClose: () => void }) {
  const [detail, setDetail] = useState<TransactionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!transactionId) return;
    const controller = new AbortController();
    apiGet<TransactionDetail>(`api/v1/transactions/${encodeURIComponent(transactionId)}`, controller.signal)
      .then(setDetail).catch((reason: unknown) => {
        if (reason instanceof Error && reason.name === "AbortError") return;
        setError(reason instanceof Error ? reason.message : "Could not load transaction details.");
      }).finally(() => setLoading(false));
    return () => controller.abort();
  }, [transactionId]);

  return <Dialog.Root open={!!transactionId} onOpenChange={(open) => { if (!open) onClose(); }}>
    <Dialog.Portal><Dialog.Overlay className="drawer-overlay" /><Dialog.Content className="drawer" aria-describedby={undefined}>
      <div className="drawer-header"><div><Dialog.Title asChild><h2>Transaction details</h2></Dialog.Title><Dialog.Description>{detail?.transactionReference ?? "Journal entry context"}</Dialog.Description></div><Dialog.Close asChild><button className="icon-button" aria-label="Close transaction details"><X size={17} /></button></Dialog.Close></div>
      <div className="drawer-body">{loading ? <Loading label="Loading transaction" /> : error ? <ErrorState message={error} /> : detail ? <>
        <div className="detail-grid"><DetailField label="Status" value={detail.status} /><DetailField label="Currency" value={detail.currencyCode} /><DetailField label="Posted at" value={formatDate(detail.postedAt)} /><DetailField label="Description" value={detail.description || "—"} /><DetailField label="Reversal of" value={detail.reversalOfTransactionId ?? "—"} /></div>
        <JournalSummary entries={detail.entries} currencyCode={detail.currencyCode} minorUnitExponent={detail.minorUnitExponent} />
        <WalletTransferFlow detail={detail} />
        <div className="section-card journal-entry-card"><h3 className="section-title">Journal entries <span className="muted">{detail.entries.length}</span></h3><div className="table-wrap"><table className="journal-entry-table"><thead><tr><th>Account</th><th>Direction</th><th className="amount">Amount</th></tr></thead><tbody>{detail.entries.map((entry) => <tr key={entry.entryId}><td className="journal-entry-account"><strong>{entry.accountDisplayName}</strong><div className="muted journal-entry-account-code">{entry.accountCode} · {entry.accountRole}</div></td><td><span className={`badge ${entry.direction === "DEBIT" ? "warn" : "good"}`}>{entry.direction.toLowerCase()}</span></td><td className="amount">{formatMinorUnits(entry.amountMinor, entry.currencyCode, entry.minorUnitExponent)}</td></tr>)}</tbody></table></div></div>
        <p className="notice" style={{ marginTop: 14 }}>Posted records are read-only in this console. No edit or delete action is available.</p>
      </> : null}</div>
    </Dialog.Content></Dialog.Portal>
  </Dialog.Root>;
}

function WalletTransferFlow({ detail }: { detail: TransactionDetail }) {
  const senderDebit = detail.entries.find((entry) => entry.direction === "DEBIT");
  const recipientCredit = detail.entries.find((entry) => entry.direction === "CREDIT");
  const isWalletTransfer = detail.entries.length === 2
    && detail.entries.every((entry) => entry.accountRole === "CUSTOMER_WALLET")
    && senderDebit !== undefined && recipientCredit !== undefined
    && BigInt(senderDebit.amountMinor) === BigInt(recipientCredit.amountMinor);
  if (!isWalletTransfer || !senderDebit || !recipientCredit) return null;

  const amount = formatMinorUnits(senderDebit.amountMinor, senderDebit.currencyCode, senderDebit.minorUnitExponent);
  return <section className="receipt-ledger" aria-labelledby="transaction-flow-title">
    <h3 id="transaction-flow-title">Transfer flow</h3>
    <p>Simulated wallet movement. Both journal entries committed atomically at {formatDate(detail.postedAt)}.</p>
    <ol>
      <li><span className="receipt-step-icon"><span aria-hidden="true">1</span></span><div><strong>Transfer posted</strong><small>{detail.transactionReference}</small></div></li>
      <li><span className="receipt-step-icon"><span aria-hidden="true">2</span></span><div><strong>{amount} debited from {senderDebit.accountDisplayName || senderDebit.accountCode}</strong><small>Debit · {senderDebit.accountRole}</small></div></li>
      <li><span className="receipt-step-icon"><span aria-hidden="true">3</span></span><div><strong>{amount} credited to {recipientCredit.accountDisplayName || recipientCredit.accountCode}</strong><small>Credit · {recipientCredit.accountRole}</small></div></li>
      <li><span className="receipt-step-icon"><span aria-hidden="true">✓</span></span><div><strong>Balanced ledger transaction</strong><small>No bank or external payment service was involved.</small></div></li>
    </ol>
  </section>;
}

function JournalSummary({ entries, currencyCode, minorUnitExponent }: {
  entries: TransactionDetail["entries"];
  currencyCode: string;
  minorUnitExponent: number;
}) {
  const debit = entries.filter((entry) => entry.direction === "DEBIT")
    .reduce((total, entry) => total + BigInt(entry.amountMinor), 0n);
  const credit = entries.filter((entry) => entry.direction === "CREDIT")
    .reduce((total, entry) => total + BigInt(entry.amountMinor), 0n);
  const balanced = entries.length > 0 && debit === credit;
  return <div className="detail-grid" aria-label="Journal balance">
    <DetailField label="Debit total" value={formatMinorUnits(debit.toString(), currencyCode, minorUnitExponent)} />
    <DetailField label="Credit total" value={formatMinorUnits(credit.toString(), currencyCode, minorUnitExponent)} />
    <DetailField label="Balance check" value={entries.length ? (balanced ? "Balanced" : "Out of balance") : "No entries"} />
  </div>;
}

function DetailField({ label, value }: { label: string; value: string }) {
  return <div className="detail-field"><span>{label}</span><strong>{value}</strong></div>;
}
