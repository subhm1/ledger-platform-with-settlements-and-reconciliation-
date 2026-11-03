"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, ExternalLink, LoaderCircle, RefreshCw, RotateCcw, ShieldCheck } from "lucide-react";
import { ClientLifecycleStatus, PaymentLifecycleStepper } from "./payment-lifecycle-stepper";
import { ApiClientError, apiGet, apiPostJson } from "@/lib/api/client";
import type { ProviderAttempt, ProviderAttemptDetail, ProviderAttemptPage, ProviderCreateResponse, ProviderWebhookEvent, ProviderWebhookEventPage } from "@/lib/api/types";
import { formatDate, formatMinorUnits } from "@/lib/format";
import { ErrorState, Loading } from "@/components/ui/feedback";

type CheckoutResponse = { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature: string };
type CheckoutFailure = { error?: { metadata?: { payment_id?: string; order_id?: string } } };
type CheckoutOptions = {
  key: string;
  order_id: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  handler: (response: CheckoutResponse) => void;
  modal: { ondismiss: () => void };
};
type CheckoutInstance = {
  open: () => void;
  on: (event: "payment.failed", handler: (response: CheckoutFailure) => void) => void;
};
declare global {
  interface Window {
    Razorpay?: new (options: CheckoutOptions) => CheckoutInstance;
  }
}

const STORAGE_KEY_SELECTED = "ledger-provider-selected-attempt";

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError && error.status === 401) {
    window.dispatchEvent(new Event("ledger:session-expired"));
    return "Your operator session expired. Sign in again to continue.";
  }
  if (error instanceof ApiClientError && error.code === "PROVIDER_NOT_CONFIGURED") {
    return "Razorpay Test Mode is not configured on the backend.";
  }
  return error instanceof Error ? error.message : "The provider payment request could not be completed.";
}

function amountInPaise(value: string): number | null {
  const text = value.trim();
  if (!/^[0-9]+(?:\.[0-9]{1,2})?$/.test(text)) return null;
  const [rupees, fraction = ""] = text.split(".");
  const amount = BigInt(rupees) * 100n + BigInt((fraction + "00").slice(0, 2));
  return amount > 0n && amount <= 100_000_000n ? Number(amount) : null;
}

export function ProviderPaymentsConsole() {
  const [page, setPage] = useState<ProviderAttemptPage | null>(null);
  const [detail, setDetail] = useState<ProviderAttemptDetail | null>(null);
  const [webhookEvents, setWebhookEvents] = useState<ProviderWebhookEventPage | null>(null);
  const [loadingWebhooks, setLoadingWebhooks] = useState(false);
  const [replayingEventId, setReplayingEventId] = useState<string | null>(null);
  const [webhookMessage, setWebhookMessage] = useState("");
  const [selected, setSelected] = useState<string | null>(() => {
    if (typeof window === "undefined") return null;
    try {
      return window.sessionStorage.getItem(STORAGE_KEY_SELECTED);
    } catch {
      return null;
    }
  });
  const [clientStage, setClientStage] = useState<ClientLifecycleStatus>("idle");
  const [amount, setAmount] = useState("");
  const [purpose, setPurpose] = useState("Collection");
  const [reference, setReference] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const requestKey = useRef<{ fingerprint: string; key: string } | null>(null);

  const refresh = useCallback(async (showSpinner = true) => {
    if (showSpinner) setLoading(true);
    setError("");
    try {
      const result = await apiGet<ProviderAttemptPage>("api/v1/provider-payments?page=0&size=25");
      setPage(result);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }, []);

  const showDetail = useCallback((attemptId: string) => {
    setSelected(attemptId);
    setError("");
    setClientStage("idle");
    try {
      window.sessionStorage.setItem(STORAGE_KEY_SELECTED, attemptId);
    } catch {
      /* ignore storage error */
    }
  }, []);

  const loadWebhookEvents = useCallback(async (attemptId: string) => {
    setLoadingWebhooks(true);
    try {
      const res = await apiGet<ProviderWebhookEventPage>(
        `api/v1/provider-payments/${encodeURIComponent(attemptId)}/webhook-events?page=0&size=25`
      );
      setWebhookEvents(res);
    } catch {
      setWebhookEvents(null);
    } finally {
      setLoadingWebhooks(false);
    }
  }, []);

  async function replayWebhook(event: ProviderWebhookEvent) {
    if (!selected || replayingEventId) return;
    setReplayingEventId(event.id);
    setWebhookMessage("");
    try {
      const ack = await apiPostJson<{ eventId: string; status: string }>(
        `api/v1/provider-payments/webhook-events/${encodeURIComponent(event.id)}/replay`,
        {}
      );
      setWebhookMessage(`Replay completed: ${ack.status} (${ack.eventId})`);
      await loadWebhookEvents(selected);
      const updatedDetail = await apiGet<ProviderAttemptDetail>(`api/v1/provider-payments/${selected}`);
      setDetail(updatedDetail);
      await refresh(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Webhook replay failed.");
    } finally {
      setReplayingEventId(null);
    }
  }

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void refresh();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [refresh]);

  useEffect(() => {
    if (!selected) return;
    if (detail?.attempt.attemptId === selected) return;
    let cancelled = false;
    const request = apiGet<ProviderAttemptDetail>(`api/v1/provider-payments/${selected}`);
    if (request && typeof request.then === "function") {
      request
        .then((data) => {
          if (!cancelled && data) {
            setDetail(data);
            void loadWebhookEvents(selected);
          }
        })
        .catch((reason) => {
          if (!cancelled) setError(errorMessage(reason));
        });
    }
    return () => {
      cancelled = true;
    };
  }, [selected, detail?.attempt?.attemptId, loadWebhookEvents]);

  async function loadCheckoutScript(): Promise<boolean> {
    if (window.Razorpay) return true;
    const existing = document.querySelector<HTMLScriptElement>('script[data-razorpay-checkout="true"]');
    const script = existing ?? document.createElement("script");
    if (!existing) {
      script.src = "https://checkout.razorpay.com/v1/checkout.js";
      script.async = true;
      script.dataset.razorpayCheckout = "true";
    }
    return new Promise((resolve) => {
      if (window.Razorpay) return resolve(true);
      let finished = false;
      const finish = (loaded: boolean) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        resolve(loaded);
      };
      const timer = window.setTimeout(() => finish(Boolean(window.Razorpay)), 10_000);
      script.addEventListener("load", () => finish(Boolean(window.Razorpay)), { once: true });
      script.addEventListener("error", () => finish(false), { once: true });
      if (!existing) document.body.appendChild(script);
    });
  }

  async function openCheckout(attempt: ProviderAttempt, keyId: string) {
    if (!attempt.providerOrderId || !keyId) return;
    setBusy(true);
    setError("");
    setMessage("Opening Razorpay Test Checkout…");
    setClientStage("checkout_open");

    const loaded = await loadCheckoutScript();
    if (!loaded || !window.Razorpay) {
      setBusy(false);
      setClientStage("checkout_dismissed");
      setError("Razorpay Checkout could not be loaded. The existing order remains available; refresh before retrying.");
      return;
    }

    const checkout = new window.Razorpay({
      key: keyId,
      order_id: attempt.providerOrderId,
      amount: Number(attempt.amountMinor),
      currency: attempt.currencyCode,
      name: "Ledger Platform",
      description: attempt.purpose,
      handler: async (response) => {
        setClientStage("verifying");
        setMessage("Verifying payment with the backend…");
        try {
          const result = await apiPostJson<ProviderAttemptDetail>(`api/v1/provider-payments/${attempt.attemptId}/verify-checkout`, {
            paymentId: response.razorpay_payment_id,
            orderId: response.razorpay_order_id,
            signature: response.razorpay_signature,
          });
          setDetail(result);
          setSelected(attempt.attemptId);
          setClientStage("idle");
          setMessage(
            result.attempt.state === "CAPTURED_VERIFIED"
              ? "Payment verified by the backend. Ledger posting remains gated."
              : `Backend state: ${result.attempt.state}.`
          );
          await refresh(false);
        } catch (reason) {
          setClientStage("idle");
          setError(errorMessage(reason));
          setMessage("Checkout response received; backend verification is still required.");
          await refresh(false);
        } finally {
          setBusy(false);
        }
      },
      modal: {
        ondismiss: () => {
          setBusy(false);
          setClientStage("checkout_dismissed");
          setMessage("Checkout closed. You may reopen Checkout or check the attempt state.");
          void apiPostJson<ProviderAttemptDetail>(`api/v1/provider-payments/${attempt.attemptId}/verify-checkout`, {
            paymentId: null,
            orderId: attempt.providerOrderId,
            signature: null,
          })
            .then((result) => {
              setDetail(result);
              void refresh(false);
            })
            .catch(() => {
              void refresh(false);
            });
        },
      },
    });

    checkout.on("payment.failed", async (response) => {
      const paymentId = response.error?.metadata?.payment_id;
      if (!paymentId) {
        setBusy(false);
        setClientStage("idle");
        setMessage("Razorpay reported a failed payment. Refresh the attempt state before retrying.");
        await refresh(false);
        return;
      }
      setClientStage("verifying");
      try {
        const result = await apiPostJson<ProviderAttemptDetail>(`api/v1/provider-payments/${attempt.attemptId}/verify-checkout`, {
          paymentId,
          orderId: response.error?.metadata?.order_id ?? attempt.providerOrderId,
          signature: null,
        });
        setDetail(result);
        setSelected(attempt.attemptId);
        setClientStage("idle");
        setMessage(`Provider state: ${result.attempt.state}.`);
        await refresh(false);
      } catch (reason) {
        setClientStage("idle");
        setError(errorMessage(reason));
      } finally {
        setBusy(false);
      }
    });

    checkout.open();
  }

  async function createAttempt(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setError("");
    setMessage("");
    const amountMinor = amountInPaise(amount);
    if (!amountMinor) {
      setError("Enter an amount greater than INR 0 and no more than INR 1,000,000.");
      return;
    }
    const normalizedPurpose = purpose.trim();
    const normalizedReference = reference.trim();
    if (!normalizedPurpose || !normalizedReference) {
      setError("Purpose and business reference are required.");
      return;
    }
    const fingerprint = JSON.stringify({ amountMinor, currencyCode: "INR", purpose: normalizedPurpose, businessReference: normalizedReference });
    if (!requestKey.current || requestKey.current.fingerprint !== fingerprint) {
      let restored: { fingerprint?: string; key?: string } | null = null;
      try {
        restored = JSON.parse(window.sessionStorage.getItem("ledger-provider-payment-pending") ?? "null");
      } catch {
        restored = null;
      }
      requestKey.current =
        restored?.fingerprint === fingerprint && restored.key
          ? { fingerprint, key: restored.key }
          : { fingerprint, key: window.crypto.randomUUID() };
      try {
        window.sessionStorage.setItem("ledger-provider-payment-pending", JSON.stringify(requestKey.current));
      } catch {
        /* Keep retry identity in memory. */
      }
    }
    const idempotencyKey = requestKey.current.key;
    setBusy(true);
    setClientStage("submitting");

    try {
      const result = await apiPostJson<ProviderCreateResponse>(
        "api/v1/provider-payments",
        {
          amountMinor,
          currencyCode: "INR",
          purpose: normalizedPurpose,
          businessReference: normalizedReference,
        },
        { "Idempotency-Key": idempotencyKey }
      );

      setSelected(result.attempt.attemptId);
      try {
        window.sessionStorage.setItem(STORAGE_KEY_SELECTED, result.attempt.attemptId);
      } catch {
        /* ignore */
      }

      const fetchedDetail = await apiGet<ProviderAttemptDetail>(`api/v1/provider-payments/${result.attempt.attemptId}`);
      setDetail(fetchedDetail);
      setClientStage("idle");
      setMessage(`Attempt recorded as ${result.attempt.state}.`);
      await refresh(false);

      if (result.checkout) {
        await openCheckout(result.attempt, result.checkout.keyId);
      }
    } catch (reason) {
      setClientStage("idle");
      setError(errorMessage(reason) + " Retrying the unchanged request reuses its idempotency key.");
    } finally {
      setBusy(false);
    }
  }

  async function recover(attemptId: string) {
    setBusy(true);
    setError("");
    setMessage("Checking Razorpay for the existing order by its unique receipt…");
    try {
      const result = await apiPostJson<ProviderAttemptDetail>(`api/v1/provider-payments/${attemptId}/recover-order`, {});
      setDetail(result);
      setSelected(attemptId);
      setMessage(`Recovery completed with state ${result.attempt.state}.`);
      await refresh(false);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }

  const attempts = page?.items ?? [];
  const activeAttempt = detail?.attempt ?? attempts.find((item) => item.attemptId === selected);

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">Razorpay · Test Mode</div>
          <h1>Provider Payments</h1>
          <p>Create a customer-to-platform test collection and verify its provider state.</p>
        </div>
        <button className="button secondary" type="button" onClick={() => void refresh()} disabled={loading || busy}>
          {loading ? <LoaderCircle className="spin" size={15} /> : <RefreshCw size={15} />} Refresh attempts
        </button>
      </div>

      <div className="notice provider-boundary">
        <ShieldCheck size={17} aria-hidden="true" />
        <span>
          Test Mode only. This collects to the platform merchant account; it does not pay a recipient or charge real money.
          Verified captures remain <strong>ACCOUNTING_GATE_PENDING</strong> and do not post to the ledger.
        </span>
      </div>

      <div className="provider-layout">
        <section className="section-card">
          <h2 className="section-title">Create a provider payment</h2>
          <p className="muted">
            Razorpay Checkout handles payment-method entry. This app stores only the request and provider references.
          </p>
          {page && !page.checkoutConfigured && !loading && (
            <p className="provider-config-warning" role="status">
              <AlertTriangle size={16} /> Razorpay Test Mode is not configured on the backend.
            </p>
          )}
          <form className="upload-grid provider-form" onSubmit={createAttempt}>
            <label>
              Amount (INR)
              <input
                inputMode="decimal"
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
                placeholder="e.g. 10.00"
                required
              />
            </label>
            <label>
              Purpose
              <input value={purpose} maxLength={100} onChange={(event) => setPurpose(event.target.value)} required />
            </label>
            <label className="wide">
              Business reference
              <input
                value={reference}
                maxLength={150}
                onChange={(event) => setReference(event.target.value)}
                placeholder="e.g. invoice-2026-104"
                required
              />
            </label>
            {error && <p className="upload-message error wide" role="alert">{error}</p>}
            {message && <p className="upload-message wide" role="status">{message}</p>}
            <div className="wide">
              <button className="button" type="submit" disabled={busy || loading || !page?.checkoutConfigured}>
                {busy ? <><LoaderCircle className="spin" size={15} /> Working…</> : "Create test payment"}
              </button>
            </div>
          </form>
        </section>

        <section className="section-card provider-boundary-card">
          <h2 className="section-title">Lifecycle boundary</h2>
          <p className="muted">
            Server creates the order, verifies Checkout, and records confirmed state. Reconciliation will compare provider
            records with ledger postings and settlement reports in its own workflow.
          </p>
        </section>
      </div>

      {activeAttempt && (
        <PaymentLifecycleStepper
          attempt={activeAttempt}
          detail={detail}
          clientStage={clientStage}
          checkoutKeyId={page?.checkoutKeyId}
          busy={busy}
          onOpenCheckout={(att) => page?.checkoutKeyId && void openCheckout(att, page.checkoutKeyId)}
          onRecoverOrder={(attId) => void recover(attId)}
          onClose={() => {
            setSelected(null);
            setDetail(null);
            setWebhookEvents(null);
            setWebhookMessage("");
            setClientStage("idle");
            try {
              window.sessionStorage.removeItem(STORAGE_KEY_SELECTED);
            } catch {
              /* ignore */
            }
          }}
        />
      )}

      {activeAttempt && (
        <section className="section-card provider-webhooks" style={{ marginTop: 14 }}>
          <div className="toolbar">
            <h2 className="section-title" style={{ margin: 0 }}>
              Webhook evidence
              <span className="muted" style={{ marginLeft: 8, fontSize: 13, fontWeight: "normal" }}>
                {webhookEvents?.totalElements ?? 0} recorded
              </span>
            </h2>
            <span className="toolbar-spacer" />
            <button
              className="button secondary small"
              type="button"
              disabled={loadingWebhooks || busy}
              onClick={() => activeAttempt && void loadWebhookEvents(activeAttempt.attemptId)}
            >
              {loadingWebhooks ? <LoaderCircle className="spin" size={13} /> : <RefreshCw size={13} />} Refresh
            </button>
          </div>

          {webhookMessage && (
            <p className="upload-message success" role="status" style={{ margin: "10px 16px" }}>
              {webhookMessage}
            </p>
          )}

          {loadingWebhooks && !webhookEvents ? (
            <Loading label="Loading webhook evidence" />
          ) : !webhookEvents?.items.length ? (
            <p className="muted" style={{ padding: "16px 20px", margin: 0 }}>
              No webhook events recorded for this payment attempt. Events are captured asynchronously upon receipt from Razorpay.
            </p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Event ID</th>
                    <th>Type</th>
                    <th>State</th>
                    <th>Attempts</th>
                    <th>Received</th>
                    <th>Processed</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {webhookEvents.items.map((evt) => (
                    <tr key={evt.id}>
                      <td className="mono" title={evt.providerEventId}>
                        {evt.providerEventId ? evt.providerEventId.slice(0, 16) + "…" : "—"}
                      </td>
                      <td>
                        <span className="badge">{evt.eventType}</span>
                      </td>
                      <td>
                        <span
                          className={`badge ${
                            evt.processingState === "PROCESSED"
                              ? "good"
                              : evt.processingState === "REVIEW_REQUIRED"
                              ? "bad"
                              : "warn"
                          }`}
                        >
                          {evt.processingState.toLowerCase().replaceAll("_", " ")}
                        </span>
                        {evt.safeErrorCode && (
                          <div className="muted" style={{ fontSize: 10, marginTop: 2 }}>
                            {evt.safeErrorCode}
                          </div>
                        )}
                      </td>
                      <td>{evt.attemptCount}</td>
                      <td>{formatDate(evt.receivedAt)}</td>
                      <td>{evt.processedAt ? formatDate(evt.processedAt) : "—"}</td>
                      <td>
                        <button
                          className="button secondary small"
                          type="button"
                          disabled={replayingEventId === evt.id || busy}
                          onClick={() => void replayWebhook(evt)}
                        >
                          {replayingEventId === evt.id ? (
                            <LoaderCircle className="spin" size={12} />
                          ) : (
                            <RotateCcw size={12} />
                          )}
                          Replay
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {error && !loading && <ErrorState message={error} onRetry={() => void refresh()} />}

      <section className="section-card provider-attempts">
        <div className="provider-section-heading">
          <div>
            <h2 className="section-title">Payment attempts</h2>
            <p className="muted">Provider responses are verified server-side before being shown as confirmed.</p>
          </div>
        </div>
        {loading ? (
          <Loading label="Loading provider payment attempts" />
        ) : attempts.length === 0 ? (
          <p className="empty-state">No provider payment attempts yet.</p>
        ) : (
          <div className="provider-table-wrap" role="region" aria-label="Payment attempts table" tabIndex={0}>
            <table className="provider-table">
              <thead>
                <tr>
                  <th scope="col">Attempt / reference</th>
                  <th scope="col">State</th>
                  <th scope="col">Amount</th>
                  <th scope="col">Provider order</th>
                  <th scope="col">Created</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {attempts.map((attempt) => (
                  <tr key={attempt.attemptId} className={selected === attempt.attemptId ? "row-selected" : undefined}>
                    <td>
                      <button className="provider-link" type="button" onClick={() => void showDetail(attempt.attemptId)}>
                        {attempt.attemptId.slice(0, 8)}
                      </button>
                      <small title={attempt.businessReference}>{attempt.businessReference}</small>
                    </td>
                    <td>
                      <span className={`state-pill state-${attempt.state.toLowerCase().replaceAll("_", "-")}`}>
                        {attempt.state.replaceAll("_", " ")}
                      </span>
                    </td>
                    <td>
                      <strong>{formatMinorUnits(attempt.amountMinor, attempt.currencyCode, 2)}</strong>
                    </td>
                    <td>
                      <code>{attempt.providerOrderId ?? "—"}</code>
                    </td>
                    <td>{formatDate(attempt.createdAt)}</td>
                    <td className="provider-actions">
                      {attempt.state === "READY_FOR_CHECKOUT" && page?.checkoutKeyId && (
                        <button
                          className="button small"
                          type="button"
                          disabled={busy}
                          onClick={() => void openCheckout(attempt, page.checkoutKeyId)}
                        >
                          <ExternalLink size={14} /> Open Test Checkout
                        </button>
                      )}
                      {(attempt.state === "ORDER_UNKNOWN" || attempt.state === "ORDER_PENDING") && (
                        <button
                          className="button secondary small"
                          type="button"
                          disabled={busy}
                          onClick={() => void recover(attempt.attemptId)}
                        >
                          Check existing order
                        </button>
                      )}
                      {attempt.state !== "READY_FOR_CHECKOUT" && attempt.state !== "ORDER_UNKNOWN" && (
                        <button
                          className="button secondary small"
                          type="button"
                          onClick={() => void showDetail(attempt.attemptId)}
                        >
                          View lifecycle
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {page && <p className="muted provider-count">{page.totalElements} attempts</p>}
      </section>
    </>
  );
}
