"use client";

import { ArrowRight, Check, Copy, LoaderCircle, RefreshCw, ShieldAlert } from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { ErrorState, Loading } from "@/components/ui/feedback";
import { ApiClientError, apiGet, apiPostJson } from "@/lib/api/client";
import type { DemoWallet, WalletTransferReceipt } from "@/lib/api/types";
import { formatDate, formatMinorUnits } from "@/lib/format";

function parseInrPaise(value: string): number | null {
  const normalized = value.trim();
  if (!/^[0-9]+(?:\.[0-9]{1,2})?$/.test(normalized)) return null;
  const [rupees, fraction = ""] = normalized.split(".");
  const paise = BigInt(rupees) * 100n + BigInt((fraction + "00").slice(0, 2));
  if (paise <= 0n || paise > BigInt(Number.MAX_SAFE_INTEGER)) return null;
  return Number(paise);
}

function errorText(reason: unknown): string {
  if (reason instanceof ApiClientError && reason.status === 401) {
    if (typeof window !== "undefined") window.dispatchEvent(new Event("ledger:session-expired"));
    return "Your operator session expired. Sign in again to continue.";
  }
  return reason instanceof Error ? reason.message : "The simulated transfer could not be completed.";
}

type Submission = {
  fingerprint: string;
  idempotencyKey: string;
  transactionReference: string;
  sourceWalletId: string;
  destinationWalletId: string;
  amount: string;
  reference: string;
};

export function WalletTransfersConsole() {
  const [wallets, setWallets] = useState<DemoWallet[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [sourceId, setSourceId] = useState("");
  const [destinationId, setDestinationId] = useState("");
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [formError, setFormError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [receipt, setReceipt] = useState<WalletTransferReceipt | null>(null);
  const [copied, setCopied] = useState(false);
  const submission = useRef<Submission | null>(null);
  const inFlight = useRef(false);
  const selectedSourceId = useRef("");

  useEffect(() => {
    const controller = new AbortController();
    apiGet<DemoWallet[]>("api/v1/wallets", controller.signal)
      .then((currentWallets) => {
        setWallets(currentWallets);
        const nextSourceId = selectedSourceId.current && currentWallets.some((wallet) => wallet.walletId === selectedSourceId.current)
          ? selectedSourceId.current : currentWallets[0]?.walletId ?? "";
        selectedSourceId.current = nextSourceId;
        setSourceId(nextSourceId);
        setDestinationId((current) => current && current !== nextSourceId && currentWallets.some((wallet) => wallet.walletId === current)
          ? current : currentWallets.find((wallet) => wallet.walletId !== nextSourceId)?.walletId ?? "");
      })
      .catch((reason: unknown) => {
        if (!(reason instanceof Error && reason.name === "AbortError")) setLoadError(errorText(reason));
      })
      .finally(() => {
        setLoading(false);
        setRefreshing(false);
      });
    return () => controller.abort();
  }, [reloadKey]);

  useEffect(() => {
    try {
      const raw = window.sessionStorage.getItem("ledger-wallet-transfer-pending");
      if (!raw) return;
      const saved = JSON.parse(raw) as Submission;
      const amountMinor = parseInrPaise(saved.amount);
      const fingerprint = JSON.stringify({
        sourceId: saved.sourceWalletId,
        destinationId: saved.destinationWalletId,
        amountMinor,
        reference: saved.reference,
      });
      if (typeof saved.idempotencyKey !== "string" || typeof saved.transactionReference !== "string"
          || typeof saved.sourceWalletId !== "string" || typeof saved.destinationWalletId !== "string"
          || typeof saved.amount !== "string" || typeof saved.reference !== "string"
          || amountMinor === null || saved.fingerprint !== fingerprint) return;
      submission.current = saved;
      selectedSourceId.current = saved.sourceWalletId;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- restore unresolved request after tab refresh
      setSourceId(saved.sourceWalletId);
      setDestinationId(saved.destinationWalletId);
      setAmount(saved.amount);
      setReference(saved.reference);
    } catch {
      submission.current = null;
    }
  }, []);

  function refreshWallets() {
    setLoadError("");
    setRefreshing(true);
    setReloadKey((current) => current + 1);
  }

  const source = wallets.find((wallet) => wallet.walletId === sourceId);
  const destinations = useMemo(() => wallets.filter((wallet) => wallet.walletId !== sourceId), [wallets, sourceId]);
  const destination = destinations.find((wallet) => wallet.walletId === destinationId);

  function changeSource(walletId: string) {
    selectedSourceId.current = walletId;
    setSourceId(walletId);
    if (walletId === destinationId) {
      setDestinationId(wallets.find((wallet) => wallet.walletId !== walletId)?.walletId ?? "");
    }
    submission.current = null;
  }

  async function submitTransfer(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;
    setFormError("");
    if (!source || !destination) {
      setFormError("Choose an active sender and recipient wallet.");
      return;
    }
    const amountMinor = parseInrPaise(amount);
    if (amountMinor === null) {
      setFormError("Enter a positive INR amount with no more than two decimal places.");
      return;
    }
    const normalizedReference = reference.trim();
    if (normalizedReference.length > 120) {
      setFormError("The optional reference must be at most 120 characters.");
      return;
    }
    const fingerprint = JSON.stringify({ sourceId, destinationId, amountMinor, reference: normalizedReference });
    if (!submission.current || submission.current.fingerprint !== fingerprint) {
      const transactionReference = "XFER-" + window.crypto.randomUUID();
      submission.current = {
        fingerprint,
        idempotencyKey: window.crypto.randomUUID(),
        transactionReference,
        sourceWalletId: source.walletId,
        destinationWalletId: destination.walletId,
        amount,
        reference: normalizedReference,
      };
    }
    const attempt = submission.current;
    if (!attempt) {
      setFormError("A safe retry key could not be created. Please submit again.");
      return;
    }
    try { window.sessionStorage.setItem("ledger-wallet-transfer-pending", JSON.stringify(attempt)); }
    catch { /* Keep the same idempotency key in memory when session storage is unavailable. */ }
    inFlight.current = true;
    setSubmitting(true);
    try {
      const result = await apiPostJson<WalletTransferReceipt>("api/v1/wallets/transfers", {
        sourceWalletId: source.walletId,
        destinationWalletId: destination.walletId,
        amountMinor,
        transactionReference: attempt.transactionReference,
        reference: normalizedReference,
      }, { "Idempotency-Key": attempt.idempotencyKey });
      setReceipt(result);
      setCopied(false);
      setAmount("");
      setReference("");
      submission.current = null;
      try { window.sessionStorage.removeItem("ledger-wallet-transfer-pending"); }
      catch { /* The committed result is already confirmed; storage cleanup is best effort. */ }
      setRefreshing(true);
      setReloadKey((current) => current + 1);
    } catch (reason) {
      setFormError(errorText(reason) + " Retrying unchanged values reuses the same idempotency key.");
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  async function copyReference() {
    if (!receipt) return;
    try {
      await navigator.clipboard.writeText(receipt.transactionReference);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  function startAnother() {
    setReceipt(null);
    setFormError("");
    submission.current = null;
  }

  return <>
    <div className="page-heading">
      <div>
        <div className="eyebrow">Simulated wallet transfer</div>
        <h1>Payments</h1>
        <p>Move demo INR between the five seeded wallets and see the exact balanced ledger posting.</p>
      </div>
      <button className="button secondary" type="button" onClick={refreshWallets} disabled={loading || refreshing}>
        {refreshing ? <LoaderCircle className="spin" size={14} /> : <RefreshCw size={14} />} Refresh balances
      </button>
    </div>

    <div className="notice wallet-simulation-notice"><ShieldAlert size={16} aria-hidden="true" />
      <span>Demo balances only. No real bank account or external payment service is connected to this internal transfer.</span>
    </div>

    {loadError ? <ErrorState message={loadError} onRetry={() => { setLoading(true); refreshWallets(); }} /> : loading
      ? <Loading label="Loading demo wallet balances" />
      : receipt ? <Receipt receipt={receipt} copied={copied} onCopy={() => void copyReference()} onNew={startAnother} />
        : <div className="wallet-transfer-layout">
          <section className="section-card">
            <h2 className="section-title">Send a simulated payment</h2>
            <p className="muted">Choose the demo wallet to debit and one to receive the transfer. Both ledger entries post atomically.</p>
            {wallets.length === 0 ? <p className="upload-message error" role="alert">No demo wallets are provisioned in this environment.</p>
              : <form className="upload-grid wallet-transfer-form" onSubmit={submitTransfer} noValidate>
                <label>From wallet
                  <select value={sourceId} onChange={(event) => changeSource(event.target.value)} required>
                    {wallets.map((wallet) => <option key={wallet.walletId} value={wallet.walletId} disabled={wallet.status !== "ACTIVE"}>
                      {wallet.displayName} · {formatMinorUnits(wallet.balanceMinor, wallet.currencyCode, wallet.minorUnitExponent)}
                    </option>)}
                  </select>
                </label>
                <label>To recipient
                  <select value={destinationId} onChange={(event) => { setDestinationId(event.target.value); submission.current = null; }} required>
                    {destinations.map((wallet) => <option key={wallet.walletId} value={wallet.walletId} disabled={wallet.status !== "ACTIVE"}>
                      {wallet.displayName} · {formatMinorUnits(wallet.balanceMinor, wallet.currencyCode, wallet.minorUnitExponent)}
                    </option>)}
                  </select>
                </label>
                <label>Amount (INR)
                  <input inputMode="decimal" autoComplete="off" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="e.g. 25.00" required />
                  <span className="muted wallet-help">Stored as integer paise. Available sender balance: {source ? formatMinorUnits(source.balanceMinor, source.currencyCode, source.minorUnitExponent) : "—"}.</span>
                </label>
                <label>Reference (optional)
                  <input value={reference} maxLength={120} onChange={(event) => setReference(event.target.value)} placeholder="e.g. lunch split" />
                </label>
                {formError && <p className="upload-message error wide" role="alert">{formError}</p>}
                <div className="wide">
                  <button className="button" type="submit" disabled={submitting || wallets.length < 2 || source?.status !== "ACTIVE" || destination?.status !== "ACTIVE"}>
                    {submitting ? <><LoaderCircle className="spin" size={14} /> Posting…</> : "Pay"}
                  </button>
                  <p className="muted wallet-help">A successful transfer creates one immutable balanced ledger transaction. Failed requests create no partial debit.</p>
                </div>
              </form>}
          </section>

          <section className="section-card wallet-balance-card">
            <h2 className="section-title">Demo wallet balances</h2>
            <p className="muted">Balances are derived from posted journal entries.</p>
            <ul>{wallets.map((wallet) => <li key={wallet.walletId}>
              <span><strong>{wallet.displayName}</strong><small>{wallet.status === "ACTIVE" ? "INR wallet" : "Unavailable"}</small></span>
              <b>{formatMinorUnits(wallet.balanceMinor, wallet.currencyCode, wallet.minorUnitExponent)}</b>
            </li>)}</ul>
          </section>
        </div>}
  </>;
}

function Receipt({ receipt, copied, onCopy, onNew }: {
  receipt: WalletTransferReceipt;
  copied: boolean;
  onCopy: () => void;
  onNew: () => void;
}) {
  const amount = formatMinorUnits(receipt.amountMinor, receipt.currencyCode, receipt.minorUnitExponent);
  return <article className="wallet-receipt" aria-labelledby="transfer-success-title">
    <header className="wallet-receipt-header">
      <span className="receipt-check"><Check size={19} aria-hidden="true" /></span>
      <div><h2 id="transfer-success-title">Transfer successful</h2><p>{formatDate(receipt.postedAt)}</p></div>
      <span className="receipt-status">SIMULATED</span>
    </header>
    <div className="wallet-receipt-body">
      <div className="receipt-amount">{amount}<span aria-label="posted"> ✓</span></div>
      <p className="receipt-recipient-label">Paid to</p>
      <strong className="receipt-recipient">{receipt.destinationName}</strong>
      <p className="receipt-sender">From {receipt.sourceName}&apos;s demo wallet</p>
      <div className="receipt-ref"><span>Transfer reference</span><code>{receipt.transactionReference}</code>
        <button className="icon-button" type="button" onClick={onCopy} aria-label="Copy transfer reference"><Copy size={15} /></button>
        {copied && <span role="status" className="muted">Copied</span>}
      </div>

      <div className="receipt-illustration" aria-label="Illustrative SBI branding, not a connected bank">
        <div><span className="receipt-recipient-label">Illustrative bank image</span>
          <Image src="/brands/sbi.svg" alt="State Bank of India logo, shown for illustration only" width={154} height={53} />
        </div>
        <strong>Not an SBI transaction</strong>
        <p>No SBI account is connected; this app moved simulated balances only.</p>
      </div>

      <section className="receipt-ledger" aria-labelledby="receipt-ledger-title">
        <h3 id="receipt-ledger-title">Where the transfer went</h3>
        <p>Both entries were recorded together in the same balanced journal transaction.</p>
        <ol>
          <li><span className="receipt-step-icon"><Check size={13} /></span><div><strong>Transfer posted</strong><small>{receipt.transactionReference}</small></div></li>
          <li><span className="receipt-step-icon"><Check size={13} /></span><div><strong>{amount} debited from {receipt.sourceName}&apos;s demo wallet</strong><small>Debit · customer wallet liability</small></div></li>
          <li><span className="receipt-step-icon"><Check size={13} /></span><div><strong>{amount} credited to {receipt.destinationName}&apos;s demo wallet</strong><small>Credit · customer wallet liability</small></div></li>
          <li><span className="receipt-step-icon"><Check size={13} /></span><div><strong>Transfer complete</strong><small>Ledger transaction {receipt.transactionId}</small></div></li>
        </ol>
      </section>
      <Link className="button secondary" href={`/transactions?transactionId=${encodeURIComponent(receipt.transactionId)}`}>
        View ledger transaction
      </Link>
      <button className="button secondary" type="button" onClick={onNew}><ArrowRight size={14} /> Make another transfer</button>
    </div>
  </article>;
}
