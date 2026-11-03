import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { PaymentLifecycleStepper } from "@/components/provider-payments/payment-lifecycle-stepper";
import type { ProviderAttempt, ProviderAttemptDetail } from "@/lib/api/types";

describe("PaymentLifecycleStepper UI", () => {
  afterEach(() => {
    cleanup();
  });

  const baseAttempt: ProviderAttempt = {
    attemptId: "11111111-2222-3333-4444-555555555555",
    receipt: "lp_11111111222233334444555555555555",
    amountMinor: "2500",
    currencyCode: "INR",
    purpose: "Platform test",
    businessReference: "inv-2026-999",
    state: "READY_FOR_CHECKOUT",
    providerOrderId: "order_AbCdEf123456",
    providerPaymentId: null,
    createdAt: "2026-10-30T00:10:00Z",
    updatedAt: "2026-10-30T00:10:01Z",
  };

  const baseDetail: ProviderAttemptDetail = {
    attempt: baseAttempt,
    accountingState: "ACCOUNTING_GATE_PENDING",
    events: [
      {
        eventId: "e1",
        eventType: "ATTEMPT_CREATED",
        previousState: null,
        newState: "ORDER_PENDING",
        safeDetail: "Request recorded.",
        occurredAt: "2026-10-30T00:10:00Z",
      },
      {
        eventId: "e2",
        eventType: "ORDER_CREATED",
        previousState: "ORDER_PENDING",
        newState: "READY_FOR_CHECKOUT",
        safeDetail: "Provider order identity and amount verified.",
        occurredAt: "2026-10-30T00:10:01Z",
      },
    ],
  };

  test("renders all 6 horizontal stages with metadata header and status message", () => {
    const { container } = render(
      <PaymentLifecycleStepper
        attempt={baseAttempt}
        detail={baseDetail}
        clientStage="idle"
        checkoutKeyId="rzp_test_key"
        onOpenCheckout={vi.fn()}
      />
    );

    // Metadata header
    expect(screen.getByText("11111111-2222-3333-4444-555555555555")).toBeInTheDocument();
    expect(screen.getByText("INR 25.00")).toBeInTheDocument();
    expect(screen.getByText("inv-2026-999")).toBeInTheDocument();

    // 6 horizontal stages
    expect(screen.getByText("Request Submitted")).toBeInTheDocument();
    expect(screen.getByText("Creating Order")).toBeInTheDocument();
    expect(screen.getByText("Order Ready")).toBeInTheDocument();
    expect(screen.getByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText("Verifying Payment")).toBeInTheDocument();
    expect(screen.getByText("Final Status")).toBeInTheDocument();

    const track = container.querySelector(".stepper-track");
    expect(track).toBeInTheDocument();
    const steps = container.querySelectorAll(".stepper-step");
    expect(steps).toHaveLength(6);

    // Verify connectors between steps (5 connectors for 6 steps)
    const connectors = container.querySelectorAll(".stepper-connector");
    expect(connectors).toHaveLength(5);

    // Status callout
    expect(screen.getByText(/Provider order is ready. Launch Razorpay Test Checkout/i)).toBeInTheDocument();

    // Open checkout action button visible for ready attempt
    expect(screen.getByRole("button", { name: /Open Test Checkout/i })).toBeInTheDocument();

    // Ensures NO vertical timeline exists
    expect(container.querySelector(".provider-timeline")).not.toBeInTheDocument();
  });

  test("displays completed lifecycle for CAPTURED_VERIFIED attempt", () => {
    const capturedAttempt: ProviderAttempt = {
      ...baseAttempt,
      state: "CAPTURED_VERIFIED",
      providerPaymentId: "pay_ZzYyXx987654",
    };
    const capturedDetail: ProviderAttemptDetail = {
      ...baseDetail,
      attempt: capturedAttempt,
      events: [
        ...baseDetail.events,
        {
          eventId: "e3",
          eventType: "PAYMENT_VERIFIED",
          previousState: "READY_FOR_CHECKOUT",
          newState: "CAPTURED_VERIFIED",
          safeDetail: "Server verification confirmed captured payment and fully paid order.",
          occurredAt: "2026-10-30T00:10:15Z",
        },
      ],
    };

    const { container } = render(
      <PaymentLifecycleStepper
        attempt={capturedAttempt}
        detail={capturedDetail}
        clientStage="idle"
      />
    );

    expect(screen.getByText("Captured & Verified")).toBeInTheDocument();
    expect(screen.getByText(/Stored under ACCOUNTING_GATE_PENDING/i)).toBeInTheDocument();

    const completedSteps = container.querySelectorAll(".stepper-step-completed");
    expect(completedSteps).toHaveLength(6);

    // Detail summary grid
    expect(screen.getByText("pay_ZzYyXx987654")).toBeInTheDocument();
  });

  test("handles ORDER_UNKNOWN with warning status and recovery action", () => {
    const unknownAttempt: ProviderAttempt = {
      ...baseAttempt,
      state: "ORDER_UNKNOWN",
      providerOrderId: null,
    };
    const unknownDetail: ProviderAttemptDetail = {
      ...baseDetail,
      attempt: unknownAttempt,
      events: [
        {
          eventId: "e1",
          eventType: "ATTEMPT_CREATED",
          previousState: null,
          newState: "ORDER_PENDING",
          safeDetail: "Request recorded.",
          occurredAt: "2026-10-30T00:10:00Z",
        },
        {
          eventId: "e2",
          eventType: "ORDER_OUTCOME_UNKNOWN",
          previousState: "ORDER_PENDING",
          newState: "ORDER_UNKNOWN",
          safeDetail: "Order creation outcome is unknown; do not create another order.",
          occurredAt: "2026-10-30T00:10:02Z",
        },
      ],
    };

    const recoverMock = vi.fn();
    render(
      <PaymentLifecycleStepper
        attempt={unknownAttempt}
        detail={unknownDetail}
        clientStage="idle"
        onRecoverOrder={recoverMock}
      />
    );

    expect(screen.getByText("Order Unknown")).toBeInTheDocument();
    expect(screen.getByText(/Order creation outcome is unknown.*Check existing order/i)).toBeInTheDocument();

    const recoverBtn = screen.getByRole("button", { name: /Check existing order/i });
    expect(recoverBtn).toBeInTheDocument();
    fireEvent.click(recoverBtn);
    expect(recoverMock).toHaveBeenCalledWith(unknownAttempt.attemptId);
  });

  test("handles FAILED status accurately", () => {
    const failedAttempt: ProviderAttempt = {
      ...baseAttempt,
      state: "FAILED",
      providerPaymentId: "pay_failed_123",
    };
    const failedDetail: ProviderAttemptDetail = {
      ...baseDetail,
      attempt: failedAttempt,
    };

    const { container } = render(
      <PaymentLifecycleStepper
        attempt={failedAttempt}
        detail={failedDetail}
        clientStage="idle"
      />
    );

    expect(screen.getByText("Payment Failed")).toBeInTheDocument();
    expect(screen.getByText(/Provider-confirmed failure: Razorpay confirmed the payment attempt failed/i)).toBeInTheDocument();
    expect(container.querySelector(".stepper-step-failed")).toBeInTheDocument();
  });

  test("handles checkout dismissal without fabricating state and displays reopen action", () => {
    const openMock = vi.fn();
    render(
      <PaymentLifecycleStepper
        attempt={baseAttempt}
        detail={baseDetail}
        clientStage="checkout_dismissed"
        checkoutKeyId="rzp_test_key"
        onOpenCheckout={openMock}
      />
    );

    expect(screen.getByText("Dismissed")).toBeInTheDocument();
    expect(screen.getByText(/Checkout dismissed: Closed without payment/i)).toBeInTheDocument();

    const reopenBtn = screen.getByRole("button", { name: /Reopen Test Checkout/i });
    expect(reopenBtn).toBeInTheDocument();
    fireEvent.click(reopenBtn);
    expect(openMock).toHaveBeenCalledWith(baseAttempt);
  });

  test("handles PAYMENT_PENDING with Provider confirmation pending label and warning status", () => {
    const pendingAttempt: ProviderAttempt = {
      ...baseAttempt,
      state: "PAYMENT_PENDING",
      providerPaymentId: "pay_pending_123",
    };
    const pendingDetail: ProviderAttemptDetail = {
      ...baseDetail,
      attempt: pendingAttempt,
    };

    render(
      <PaymentLifecycleStepper
        attempt={pendingAttempt}
        detail={pendingDetail}
        clientStage="idle"
      />
    );

    expect(screen.getByText("Confirmation Pending")).toBeInTheDocument();
    expect(screen.getAllByText(/Provider confirmation pending/i).length).toBeGreaterThan(0);
  });

  test("handles REVIEW_REQUIRED with warning status and review label", () => {
    const reviewAttempt: ProviderAttempt = {
      ...baseAttempt,
      state: "REVIEW_REQUIRED",
      providerPaymentId: "pay_review_123",
    };
    const reviewDetail: ProviderAttemptDetail = {
      ...baseDetail,
      attempt: reviewAttempt,
    };

    render(
      <PaymentLifecycleStepper
        attempt={reviewAttempt}
        detail={reviewDetail}
        clientStage="idle"
      />
    );

    expect(screen.getByText("Review Required")).toBeInTheDocument();
    expect(screen.getByText(/Review required: Provider order\/payment mismatch/i)).toBeInTheDocument();
  });

  test("displays client observations for checkout_open and verifying stages", () => {
    const { rerender } = render(
      <PaymentLifecycleStepper
        attempt={baseAttempt}
        detail={baseDetail}
        clientStage="checkout_open"
        checkoutKeyId="rzp_test_key"
      />
    );

    expect(screen.getByText("Opened")).toBeInTheDocument();
    expect(screen.getByText(/Checkout opened \(Client observation\)/i)).toBeInTheDocument();

    rerender(
      <PaymentLifecycleStepper
        attempt={baseAttempt}
        detail={baseDetail}
        clientStage="verifying"
        checkoutKeyId="rzp_test_key"
      />
    );

    expect(screen.getByText("Submitted")).toBeInTheDocument();
    expect(screen.getAllByText(/Payment submitted \(Client observation\)/i).length).toBeGreaterThan(0);
  });
});
