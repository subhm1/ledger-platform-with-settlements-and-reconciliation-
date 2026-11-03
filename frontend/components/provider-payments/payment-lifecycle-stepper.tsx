"use client";

import { AlertCircle, AlertTriangle, CheckCircle2, Circle, Clock, ExternalLink, HelpCircle, LoaderCircle, RefreshCw, XCircle } from "lucide-react";
import type { ProviderAttempt, ProviderAttemptDetail, ProviderAttemptEvent } from "@/lib/api/types";
import { formatMinorUnits } from "@/lib/format";

export type ClientLifecycleStatus = "idle" | "submitting" | "checkout_open" | "checkout_dismissed" | "verifying";

export interface LifecycleStage {
  id: string;
  stageNumber: number;
  label: string;
  status: "completed" | "current" | "waiting" | "warning" | "failed";
  badgeText: string;
  icon: React.ReactNode;
  timestamp?: string;
  rawTimestamp?: string;
  detail?: string;
  connectorStatus: "completed" | "active" | "waiting" | "warning" | "failed";
}

interface PaymentLifecycleStepperProps {
  attempt: ProviderAttempt;
  detail: ProviderAttemptDetail | null;
  clientStage: ClientLifecycleStatus;
  checkoutKeyId?: string;
  busy?: boolean;
  onOpenCheckout?: (attempt: ProviderAttempt) => void;
  onRecoverOrder?: (attemptId: string) => void;
  onClose?: () => void;
}

function formatTimeOnly(isoString?: string): string | undefined {
  if (!isoString) return undefined;
  try {
    const date = new Date(isoString);
    if (isNaN(date.getTime())) return undefined;
    return date.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: true });
  } catch {
    return undefined;
  }
}

export function computeLifecycleStages(
  attempt: ProviderAttempt,
  detail: ProviderAttemptDetail | null,
  clientStage: ClientLifecycleStatus
): LifecycleStage[] {
  const events: ProviderAttemptEvent[] = detail?.events ?? [];
  const createdEvent = events.find((e) => e.eventType === "ATTEMPT_CREATED");
  const orderEvent = events.find((e) => ["ORDER_CREATED", "ORDER_RECOVERED", "ORDER_OUTCOME_UNKNOWN", "ORDER_RESPONSE_REVIEW"].includes(e.eventType));
  const verifiedEvent = events.find((e) => ["PAYMENT_VERIFIED", "PAYMENT_FAILED", "PAYMENT_UNRESOLVED", "VERIFICATION_REVIEW"].includes(e.eventType));
  const dismissedEvent = events.find((e) => e.eventType === "CHECKOUT_DISMISSED");

  const isOrderUnknown = attempt.state === "ORDER_UNKNOWN";
  const isOrderPending = attempt.state === "ORDER_PENDING";
  const isReadyForCheckout = attempt.state === "READY_FOR_CHECKOUT";
  const hasProviderOrder = Boolean(attempt.providerOrderId && attempt.providerOrderId.startsWith("order_"));
  const hasPaymentVerified = Boolean(verifiedEvent || ["CAPTURED_VERIFIED", "AUTHORIZED", "FAILED", "REVIEW_REQUIRED"].includes(attempt.state));
  const hasProviderPayment = Boolean(attempt.providerPaymentId);

  // Stage 1: Request Submitted
  const s1Status: LifecycleStage["status"] = clientStage === "submitting" && !createdEvent ? "current" : "completed";
  const s1Time = createdEvent?.occurredAt ?? attempt.createdAt;

  // Stage 2: Creating Order
  let s2Status: LifecycleStage["status"] = "waiting";
  let s2Badge = "Waiting";
  let s2Detail = "Initiating order";
  let s2Icon: React.ReactNode = <Circle size={16} />;
  if (isOrderUnknown) {
    s2Status = "warning";
    s2Badge = "Unknown";
    s2Detail = "Outcome unknown";
    s2Icon = <AlertTriangle size={16} />;
  } else if (isOrderPending) {
    s2Status = "current";
    s2Badge = "In Progress";
    s2Detail = "Creating Razorpay order";
    s2Icon = <LoaderCircle className="spin" size={16} />;
  } else if (hasProviderOrder || orderEvent?.eventType === "ORDER_CREATED" || orderEvent?.eventType === "ORDER_RECOVERED") {
    s2Status = "completed";
    s2Badge = "Done";
    s2Detail = "Razorpay order created";
    s2Icon = <CheckCircle2 size={16} />;
  } else if (attempt.state === "REVIEW_REQUIRED" && !hasProviderOrder) {
    s2Status = "failed";
    s2Badge = "Mismatch";
    s2Detail = "Order creation mismatch";
    s2Icon = <XCircle size={16} />;
  }

  // Stage 3: Order Ready
  let s3Status: LifecycleStage["status"] = "waiting";
  let s3Badge = "Waiting";
  let s3Detail = "Awaiting order";
  let s3Icon: React.ReactNode = <Circle size={16} />;
  if (isOrderUnknown) {
    s3Status = "warning";
    s3Badge = "Blocked";
    s3Detail = "Recovery required";
    s3Icon = <AlertCircle size={16} />;
  } else if (hasProviderOrder) {
    s3Status = "completed";
    s3Badge = "Ready";
    s3Detail = attempt.providerOrderId ?? "Order ready";
    s3Icon = <CheckCircle2 size={16} />;
  }

  // Stage 4: Checkout
  let s4Status: LifecycleStage["status"] = "waiting";
  let s4Badge = "Waiting";
  let s4Detail = "Pending order ready";
  let s4Icon: React.ReactNode = <Circle size={16} />;
  if (hasPaymentVerified || hasProviderPayment || clientStage === "verifying") {
    s4Status = "completed";
    s4Badge = "Done";
    s4Detail = attempt.providerPaymentId ? `Payment ${attempt.providerPaymentId.slice(0, 14)}…` : "Checkout completed";
    s4Icon = <CheckCircle2 size={16} />;
  } else if (clientStage === "checkout_open") {
    s4Status = "current";
    s4Badge = "Opened";
    s4Detail = "Modal open (Client observation)";
    s4Icon = <ExternalLink className="pulse-icon" size={16} />;
  } else if (clientStage === "checkout_dismissed" || (dismissedEvent && isReadyForCheckout)) {
    s4Status = "warning";
    s4Badge = "Dismissed";
    s4Detail = dismissedEvent ? "Dismissal recorded on server" : "Closed without payment (Client observation)";
    s4Icon = <AlertCircle size={16} />;
  } else if (isReadyForCheckout) {
    s4Status = "current";
    s4Badge = "Awaiting";
    s4Detail = "Ready to launch Checkout";
    s4Icon = <Clock size={16} />;
  }

  // Stage 5: Verifying Payment
  let s5Status: LifecycleStage["status"] = "waiting";
  let s5Badge = "Waiting";
  let s5Detail = "Awaiting payment";
  let s5Icon: React.ReactNode = <Circle size={16} />;
  if (clientStage === "verifying") {
    s5Status = "current";
    s5Badge = "Submitted";
    s5Detail = "Payment submitted (Client observation)";
    s5Icon = <LoaderCircle className="spin" size={16} />;
  } else if (attempt.state === "PAYMENT_PENDING") {
    s5Status = "warning";
    s5Badge = "Pending";
    s5Detail = "Provider confirmation pending";
    s5Icon = <AlertTriangle size={16} />;
  } else if (hasPaymentVerified) {
    s5Status = "completed";
    s5Badge = "Verified";
    s5Detail = "HMAC & provider verified";
    s5Icon = <CheckCircle2 size={16} />;
  } else if (attempt.state === "REVIEW_REQUIRED") {
    s5Status = "warning";
    s5Badge = "Review";
    s5Detail = "Provider review required";
    s5Icon = <AlertTriangle size={16} />;
  }

  // Stage 6: Final Status
  let s6Label = "Final Status";
  let s6Status: LifecycleStage["status"] = "waiting";
  let s6Badge = "Waiting";
  let s6Detail = "Awaiting outcome";
  let s6Icon: React.ReactNode = <Circle size={16} />;
  if (attempt.state === "CAPTURED_VERIFIED") {
    s6Label = "Captured & Verified";
    s6Status = "completed";
    s6Badge = "Success";
    s6Detail = "Provider-confirmed success";
    s6Icon = <CheckCircle2 size={16} />;
  } else if (attempt.state === "FAILED") {
    s6Label = "Payment Failed";
    s6Status = "failed";
    s6Badge = "Failed";
    s6Detail = "Provider-confirmed failure";
    s6Icon = <XCircle size={16} />;
  } else if (attempt.state === "PAYMENT_PENDING") {
    s6Label = "Confirmation Pending";
    s6Status = "warning";
    s6Badge = "Pending";
    s6Detail = "Provider confirmation pending";
    s6Icon = <AlertTriangle size={16} />;
  } else if (attempt.state === "REVIEW_REQUIRED") {
    s6Label = "Review Required";
    s6Status = "warning";
    s6Badge = "Review";
    s6Detail = "Operator review required";
    s6Icon = <AlertTriangle size={16} />;
  } else if (attempt.state === "AUTHORIZED") {
    s6Label = "Authorized";
    s6Status = "warning";
    s6Badge = "Authorized";
    s6Detail = "Capture pending";
    s6Icon = <AlertTriangle size={16} />;
  } else if (attempt.state === "ORDER_UNKNOWN") {
    s6Label = "Order Unknown";
    s6Status = "warning";
    s6Badge = "Blocked";
    s6Detail = "Recovery required";
    s6Icon = <HelpCircle size={16} />;
  }

  const determineConnector = (from: LifecycleStage["status"], to: LifecycleStage["status"]): LifecycleStage["connectorStatus"] => {
    if (from === "failed" || to === "failed") return "failed";
    if (from === "warning" || to === "warning") return "warning";
    if (from === "completed" && to === "completed") return "completed";
    if (from === "completed" && to === "current") return "active";
    return "waiting";
  };

  const rawStages: Omit<LifecycleStage, "connectorStatus">[] = [
    {
      id: "stage-request-submitted",
      stageNumber: 1,
      label: "Request Submitted",
      status: s1Status,
      badgeText: s1Status === "completed" ? "Done" : "Active",
      icon: s1Status === "completed" ? <CheckCircle2 size={16} /> : <LoaderCircle className="spin" size={16} />,
      timestamp: formatTimeOnly(s1Time),
      rawTimestamp: s1Time,
      detail: "Validated & recorded",
    },
    {
      id: "stage-creating-order",
      stageNumber: 2,
      label: "Creating Order",
      status: s2Status,
      badgeText: s2Badge,
      icon: s2Icon,
      timestamp: formatTimeOnly(orderEvent?.occurredAt),
      rawTimestamp: orderEvent?.occurredAt,
      detail: s2Detail,
    },
    {
      id: "stage-order-ready",
      stageNumber: 3,
      label: "Order Ready",
      status: s3Status,
      badgeText: s3Badge,
      icon: s3Icon,
      timestamp: formatTimeOnly(orderEvent?.occurredAt ?? attempt.updatedAt),
      rawTimestamp: orderEvent?.occurredAt ?? attempt.updatedAt,
      detail: s3Detail,
    },
    {
      id: "stage-checkout",
      stageNumber: 4,
      label: "Checkout",
      status: s4Status,
      badgeText: s4Badge,
      icon: s4Icon,
      timestamp: hasProviderPayment ? formatTimeOnly(verifiedEvent?.occurredAt ?? attempt.updatedAt) : undefined,
      rawTimestamp: verifiedEvent?.occurredAt ?? attempt.updatedAt,
      detail: s4Detail,
    },
    {
      id: "stage-verifying-payment",
      stageNumber: 5,
      label: "Verifying Payment",
      status: s5Status,
      badgeText: s5Badge,
      icon: s5Icon,
      timestamp: formatTimeOnly(verifiedEvent?.occurredAt),
      rawTimestamp: verifiedEvent?.occurredAt,
      detail: s5Detail,
    },
    {
      id: "stage-final-status",
      stageNumber: 6,
      label: s6Label,
      status: s6Status,
      badgeText: s6Badge,
      icon: s6Icon,
      timestamp: ["CAPTURED_VERIFIED", "FAILED", "AUTHORIZED", "REVIEW_REQUIRED", "ORDER_UNKNOWN", "PAYMENT_PENDING"].includes(attempt.state)
        ? formatTimeOnly(verifiedEvent?.occurredAt ?? attempt.updatedAt)
        : undefined,
      rawTimestamp: verifiedEvent?.occurredAt ?? attempt.updatedAt,
      detail: s6Detail,
    },
  ];

  return rawStages.map((stage, i) => {
    const next = rawStages[i + 1];
    return {
      ...stage,
      connectorStatus: next ? determineConnector(stage.status, next.status) : "waiting",
    };
  });
}

export function getConciseStatusMessage(
  attempt: ProviderAttempt,
  detail: ProviderAttemptDetail | null,
  clientStage: ClientLifecycleStatus
): string {
  const events = detail?.events ?? [];
  const dismissedEvent = events.find((e) => e.eventType === "CHECKOUT_DISMISSED");

  if (clientStage === "submitting") {
    return "Submitting payment request to server and registering payment attempt…";
  }
  if (clientStage === "checkout_open") {
    return "Checkout opened (Client observation): Razorpay Test Mode Checkout is open in the modal. Complete or test payment using dummy payment credentials.";
  }
  if (clientStage === "checkout_dismissed" || (dismissedEvent && attempt.state === "READY_FOR_CHECKOUT")) {
    return "Checkout dismissed: Closed without payment. State remains Ready for Checkout. Click 'Reopen Test Checkout' to resume.";
  }
  if (clientStage === "verifying") {
    return "Payment submitted (Client observation): Submitting payment response to server for verification…";
  }
  if (attempt.state === "CAPTURED_VERIFIED") {
    return "Provider-confirmed success: Payment captured and verified by server. Stored under ACCOUNTING_GATE_PENDING (does not post to ledger).";
  }
  if (attempt.state === "FAILED") {
    return "Provider-confirmed failure: Razorpay confirmed the payment attempt failed. Inspect attempt details or create a new test payment.";
  }
  if (attempt.state === "PAYMENT_PENDING") {
    return "Provider confirmation pending: Payment created on provider but final outcome is not yet established. Stored unresolved.";
  }
  if (attempt.state === "REVIEW_REQUIRED") {
    return "Review required: Provider order/payment mismatch or unverified payment state requires operator investigation.";
  }
  if (attempt.state === "AUTHORIZED") {
    return "Payment authorized by provider; capture has not yet been confirmed.";
  }
  if (attempt.state === "ORDER_UNKNOWN") {
    return "Order creation outcome is unknown. Do not create a duplicate order—click 'Check existing order' to recover.";
  }
  if (attempt.state === "READY_FOR_CHECKOUT") {
    return "Provider order is ready. Launch Razorpay Test Checkout to complete payment.";
  }
  return `Provider state: ${attempt.state.replaceAll("_", " ")}.`;
}

export function PaymentLifecycleStepper({
  attempt,
  detail,
  clientStage,
  checkoutKeyId,
  busy = false,
  onOpenCheckout,
  onRecoverOrder,
  onClose,
}: PaymentLifecycleStepperProps) {
  const stages = computeLifecycleStages(attempt, detail, clientStage);
  const statusMessage = getConciseStatusMessage(attempt, detail, clientStage);

  return (
    <section className="section-card provider-stepper-card" aria-label="Payment lifecycle progress" aria-live="polite">
      <div className="stepper-header">
        <div className="stepper-title-group">
          <span className="stepper-badge">LIVE LIFECYCLE</span>
          <h3 className="stepper-title">
            Attempt <code>{attempt.attemptId}</code>
          </h3>
        </div>
        <div className="stepper-meta">
          <span className="stepper-amount">{formatMinorUnits(attempt.amountMinor, attempt.currencyCode, 2)}</span>
          <span className="stepper-ref" title={attempt.businessReference}>
            {attempt.businessReference}
          </span>
          {onClose && (
            <button type="button" className="button secondary small stepper-close-btn" onClick={onClose} aria-label="Close lifecycle tracker">
              Close
            </button>
          )}
        </div>
      </div>

      <div className="stepper-track-wrap" role="region" aria-label="Payment lifecycle stages" tabIndex={0}>
        <div className="stepper-track">
          {stages.map((stage, index) => (
            <div
              key={stage.id}
              className={`stepper-step stepper-step-${stage.status}`}
              aria-current={stage.status === "current" ? "step" : undefined}
            >
              <div className="stepper-step-indicator">
                <div className="stepper-icon-node" aria-hidden="true">
                  {stage.icon}
                </div>
                {index < stages.length - 1 && (
                  <div
                    className={`stepper-connector stepper-connector-${stage.connectorStatus}`}
                    aria-hidden="true"
                  />
                )}
              </div>
              <div className="stepper-step-content">
                <div className="stepper-step-label-row">
                  <span className="stepper-step-label">{stage.label}</span>
                  <span className={`stepper-step-badge stepper-badge-${stage.status}`}>
                    {stage.badgeText}
                  </span>
                </div>
                {stage.timestamp && (
                  <time className="stepper-step-time" dateTime={stage.rawTimestamp}>
                    {stage.timestamp}
                  </time>
                )}
                {stage.detail && (
                  <span className="stepper-step-detail" title={stage.detail}>
                    {stage.detail}
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="stepper-footer">
        <div className="stepper-status-callout" role="status">
          {attempt.state === "CAPTURED_VERIFIED" ? (
            <CheckCircle2 size={16} className="stepper-callout-icon success" />
          ) : attempt.state === "FAILED" ? (
            <XCircle size={16} className="stepper-callout-icon failed" />
          ) : ["ORDER_UNKNOWN", "REVIEW_REQUIRED", "PAYMENT_PENDING"].includes(attempt.state) || clientStage === "checkout_dismissed" ? (
            <AlertTriangle size={16} className="stepper-callout-icon warning" />
          ) : clientStage === "checkout_open" || clientStage === "verifying" ? (
            <LoaderCircle size={16} className="stepper-callout-icon spin" />
          ) : (
            <Clock size={16} className="stepper-callout-icon info" />
          )}
          <span className="stepper-status-text">{statusMessage}</span>
        </div>

        <div className="stepper-actions">
          {attempt.state === "READY_FOR_CHECKOUT" && checkoutKeyId && onOpenCheckout && (
            <button
              className="button small stepper-action-btn"
              type="button"
              disabled={busy || clientStage === "checkout_open"}
              onClick={() => onOpenCheckout(attempt)}
            >
              <ExternalLink size={14} />
              {clientStage === "checkout_dismissed" ? "Reopen Test Checkout" : "Open Test Checkout"}
            </button>
          )}

          {(attempt.state === "ORDER_UNKNOWN" || attempt.state === "ORDER_PENDING") && onRecoverOrder && (
            <button
              className="button secondary small stepper-action-btn"
              type="button"
              disabled={busy}
              onClick={() => onRecoverOrder(attempt.attemptId)}
            >
              <RefreshCw size={14} className={busy ? "spin" : ""} />
              Check existing order
            </button>
          )}
        </div>
      </div>

      {detail && (
        <div className="stepper-detail-summary">
          <div className="provider-detail-grid">
            <div>
              <small>State</small>
              <strong>{detail.attempt.state.replaceAll("_", " ")}</strong>
            </div>
            <div>
              <small>Accounting</small>
              <strong>{detail.accountingState.replaceAll("_", " ")}</strong>
            </div>
            <div>
              <small>Provider payment</small>
              <code>{detail.attempt.providerPaymentId ?? "—"}</code>
            </div>
            <div>
              <small>Receipt</small>
              <code>{detail.attempt.receipt}</code>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
