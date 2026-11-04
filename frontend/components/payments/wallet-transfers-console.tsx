"use client";

import { ArrowRight, Check, Copy, LoaderCircle, RefreshCw, RotateCcw, ShieldAlert } from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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

type ActionFeedback = {
  type: "info" | "success";
  message: string;
};

export function WalletTransfersConsole() {
  const [wallets, setWallets] = useState<DemoWallet[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [actionFeedback, setActionFeedback] = useState<ActionFeedback | null>(null);
  const feedbackTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
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

  const showFeedback = useCallback((type: "info" | "success", message: string, durationMs = 4500) => {
    if (feedbackTimeout.current) clearTimeout(feedbackTimeout.current);
    setActionFeedback({ type, message });
    feedbackTimeout.current = setTimeout(() => {
      setActionFeedback(null);
      feedbackTimeout.current = null;
    }, durationMs);
  }, []);

  const applyWallets = useCallback((currentWallets: DemoWallet[]) => {
    setWallets(currentWallets);
    const nextSourceId = selectedSourceId.current && currentWallets.some((wallet) => wallet.walletId === selectedSourceId.current)
      ? selectedSourceId.current : currentWallets[0]?.walletId ?? "";
    selectedSourceId.current = nextSourceId;
    setSourceId(nextSourceId);
    setDestinationId((current) => current && current !== nextSourceId && currentWallets.some((wallet) => wallet.walletId === current)
      ? current : currentWallets.find((wallet) => wallet.walletId !== nextSourceId)?.walletId ?? "");
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    apiGet<DemoWallet[]>("api/v1/wallets", controller.signal)
      .then((currentWallets) => {
        applyWallets(currentWallets);
      })
      .catch((reason: unknown) => {
        if (!(reason instanceof Error && reason.name === "AbortError")) setLoadError(errorText(reason));
      })
      .finally(() => {
        setLoading(false);
      });
    return () => {
      controller.abort();
      if (feedbackTimeout.current) clearTimeout(feedbackTimeout.current);
    };
  }, [applyWallets]);

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

  async function refreshWallets() {
    setReceipt(null);
    setFormError("");
    setLoadError("");
    setRefreshing(true);
    const start = Date.now();
    try {
      const currentWallets = await apiGet<DemoWallet[]>("api/v1/wallets");
      const elapsed = Date.now() - start;
      if (elapsed < 500) {
        await new Promise((resolve) => setTimeout(resolve, 500 - elapsed));
      }
      applyWallets(currentWallets);
      const time = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
      showFeedback("info", `Balances refreshed from ledger at ${time}.`);
    } catch (reason: unknown) {
      setLoadError(errorText(reason));
    } finally {
      setRefreshing(false);
    }
  }

  async function resetDemoBalances() {
    setReceipt(null);
    setFormError("");
    setResetting(true);
    const targetPaise = 10000;
    try {
      const currentWallets = await apiGet<DemoWallet[]>("api/v1/wallets");
      applyWallets(currentWallets);
      const needsReset = currentWallets.some((w) => Number(w.balanceMinor) !== targetPaise);
      if (!needsReset) {
        showFeedback("info", "All 5 demo wallets are already at INR 100.00.");
        setResetting(false);
        return;
      }

      type BalanceDiff = { walletId: string; displayName: string; amount: number };
      const surpluses: BalanceDiff[] = [];
      const deficits: BalanceDiff[] = [];

      for (const w of currentWallets) {
        const bal = Number(w.balanceMinor);
        if (bal > targetPaise) {
          surpluses.push({ walletId: w.walletId, displayName: w.displayName, amount: bal - targetPaise });
        } else if (bal < targetPaise) {
          deficits.push({ walletId: w.walletId, displayName: w.displayName, amount: targetPaise - bal });
        }
      }

      while (surpluses.length > 0 && deficits.length > 0) {
        const s = surpluses[0];
        const d = deficits[0];
        const transferAmount = Math.min(s.amount, d.amount);
        const ref = "XFER-RESET-" + window.crypto.randomUUID();
        const idempotencyKey = window.crypto.randomUUID();

        await apiPostJson<WalletTransferReceipt>(
          "api/v1/wallets/transfers",
          {
            sourceWalletId: s.walletId,
            destinationWalletId: d.walletId,
            amountMinor: transferAmount,
            transactionReference: ref,
            reference: "Demo balance reset to INR 100.00",
          },
          { "Idempotency-Key": idempotencyKey },
        );

        s.amount -= transferAmount;
        d.amount -= transferAmount;
        if (s.amount <= 0) surpluses.shift();
        if (d.amount <= 0) deficits.shift();
      }

      const refreshed = await apiGet<DemoWallet[]>("api/v1/wallets");
      applyWallets(refreshed);
      showFeedback("success", "All 5 demo wallets restored to INR 100.00 via balanced ledger transactions.", 6000);
    } catch (reason: unknown) {
      setFormError(errorText(reason));
    } finally {
      setResetting(false);
    }
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
      void apiGet<DemoWallet[]>("api/v1/wallets").then(applyWallets).catch(() => {});
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
      <div className="page-heading-actions">
        <button
          className="button secondary"
          type="button"
          onClick={() => void resetDemoBalances()}
          disabled={loading || refreshing || resetting || submitting}
          title="Restore all five demo wallets back to INR 100.00"
        >
          {resetting ? <LoaderCircle className="spin" size={14} /> : <RotateCcw size={14} />} Reset demo balances
        </button>
        <button
          className="button secondary"
          type="button"
          onClick={() => void refreshWallets()}
          disabled={loading || refreshing || resetting || submitting}
          title="Fetch latest balances from the ledger"
        >
          {refreshing ? <LoaderCircle className="spin" size={14} /> : <RefreshCw size={14} />} Refresh balances
        </button>
      </div>
    </div>

    {actionFeedback && (
      <div className={`notice wallet-action-feedback ${actionFeedback.type}`} role="status">
        {actionFeedback.type === "success" ? <Check size={16} aria-hidden="true" /> : <RefreshCw size={14} aria-hidden="true" />}
        <span>{actionFeedback.message}</span>
      </div>
    )}

    <div className="notice wallet-simulation-notice"><ShieldAlert size={16} aria-hidden="true" />
      <span>Demo balances only. No real bank account or external payment service is connected to this internal transfer.</span>
    </div>

    {loadError ? <ErrorState message={loadError} onRetry={() => { setLoading(true); void refreshWallets(); }} /> : loading
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

      <div className="receipt-brand-card" aria-label="State Bank of India">
        <Image
          src="/brands/sbi.png"
          alt="State Bank of India logo"
          width={120}
          height={80}
          style={{ height: 42, width: "auto", objectFit: "contain", display: "block" }}
          priority
        />
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