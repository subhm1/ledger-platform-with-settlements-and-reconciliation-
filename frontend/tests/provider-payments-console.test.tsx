import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ProviderPaymentsConsole } from "@/components/provider-payments/provider-payments-console";
import * as api from "@/lib/api/client";
import type { ProviderAttempt, ProviderAttemptDetail, ProviderAttemptPage } from "@/lib/api/types";

vi.mock("@/lib/api/client");

const emptyPage: ProviderAttemptPage = {
  items: [],
  checkoutConfigured: true,
  checkoutKeyId: "rzp_test_123",
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
};

const unconfiguredPage: ProviderAttemptPage = {
  items: [],
  checkoutConfigured: false,
  checkoutKeyId: "",
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
};

describe("ProviderPaymentsConsole UI", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
  });

  afterEach(() => {
    cleanup();
    window.sessionStorage.clear();
  });

  test("renders form and validates amount field", async () => {
    vi.mocked(api.apiGet).mockResolvedValueOnce(emptyPage);
    render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByLabelText(/Amount \(INR\)/i)).toBeInTheDocument());
    const amountInput = screen.getByLabelText(/Amount \(INR\)/i);
    const refInput = screen.getByLabelText(/Business reference/i);
    fireEvent.change(amountInput, { target: { value: "abc" } });
    fireEvent.change(refInput, { target: { value: "ref-test" } });
    const submit = screen.getByRole("button", { name: /Create test payment/i });
    fireEvent.submit(submit.closest("form")!);
    expect(screen.getAllByText(/Enter an amount greater than INR 0/i).length).toBeGreaterThan(0);
  });

  test("prevents duplicate submissions while busy", async () => {
    vi.mocked(api.apiGet).mockResolvedValueOnce(emptyPage);
    vi.mocked(api.apiPostJson).mockImplementation(() => new Promise(() => {}));
    render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByLabelText(/Amount \(INR\)/i)).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText(/Amount \(INR\)/i), { target: { value: "10.00" } });
    fireEvent.change(screen.getByLabelText(/Purpose/i), { target: { value: "Test" } });
    fireEvent.change(screen.getByLabelText(/Business reference/i), { target: { value: "ref-001" } });
    const submit = screen.getByRole("button", { name: /Create test payment/i });
    fireEvent.submit(submit.closest("form")!);
    await waitFor(() => expect(screen.getByRole("button", { name: /Working/i })).toBeDisabled());
  });

  test("checkout button disabled when not configured", async () => {
    vi.mocked(api.apiGet).mockResolvedValueOnce(unconfiguredPage);
    render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByText(/Razorpay Test Mode is not configured on the backend/i)).toBeInTheDocument());
    const submit = screen.getByRole("button", { name: /Create test payment/i });
    expect(submit).toBeDisabled();
  });

  test("renders layout structure with top row cards and payment attempts section", async () => {
    vi.mocked(api.apiGet).mockResolvedValueOnce(emptyPage);
    const { container } = render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByText(/Create a provider payment/i)).toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Lifecycle boundary" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Payment attempts" })).toBeInTheDocument();

    const providerLayout = container.querySelector(".provider-layout");
    expect(providerLayout).toBeInTheDocument();
    expect(providerLayout?.children.length).toBe(2);

    const attemptsSection = container.querySelector(".provider-attempts");
    expect(attemptsSection).toBeInTheDocument();
  });

  test("renders all six table columns and displays action button for ready attempt", async () => {
    const mockAttempt: ProviderAttempt = {
      attemptId: "e3c78165-87d1-4ce3-b3f1-83901b4b4edd",
      receipt: "lpe3c7816587d14ce3b3f183901b4b4edd",
      amountMinor: "5000",
      currencyCode: "INR",
      purpose: "Collection",
      businessReference: "inv-2026-001",
      state: "READY_FOR_CHECKOUT",
      providerOrderId: "order_Tt1Qvb0GuxIsXV",
      providerPaymentId: null,
      createdAt: "2026-10-27T17:54:26.912500Z",
      updatedAt: "2026-10-27T17:54:27.737630Z",
    };
    const populatedPage: ProviderAttemptPage = {
      items: [mockAttempt],
      checkoutConfigured: true,
      checkoutKeyId: "rzp_test_123",
      totalElements: 1,
      totalPages: 1,
      page: 0,
      size: 25,
    };
    vi.mocked(api.apiGet).mockResolvedValueOnce(populatedPage);

    const { container } = render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByRole("region", { name: /Payment attempts table/i })).toBeInTheDocument());

    const tableHeaders = container.querySelectorAll(".provider-table th");
    expect(tableHeaders).toHaveLength(6);
    const headerTexts = Array.from(tableHeaders).map((th) => th.textContent?.trim());
    expect(headerTexts).toEqual([
      "Attempt / reference",
      "State",
      "Amount",
      "Provider order",
      "Created",
      "Action",
    ]);

    expect(screen.getByText("READY FOR CHECKOUT")).toBeInTheDocument();
    expect(screen.getByText("order_Tt1Qvb0GuxIsXV")).toBeInTheDocument();

    const checkoutBtn = screen.getByRole("button", { name: /Open Test Checkout/i });
    expect(checkoutBtn).toBeInTheDocument();
    expect(checkoutBtn).not.toBeDisabled();
  });

  test("creating test payment shows horizontal live lifecycle stepper", async () => {
    const mockAttempt: ProviderAttempt = {
      attemptId: "e3c78165-87d1-4ce3-b3f1-83901b4b4edd",
      receipt: "lpe3c7816587d14ce3b3f183901b4b4edd",
      amountMinor: "2500",
      currencyCode: "INR",
      purpose: "Collection test",
      businessReference: "inv-2026-stepper",
      state: "READY_FOR_CHECKOUT",
      providerOrderId: "order_Tt1Qvb0GuxIsXV",
      providerPaymentId: null,
      createdAt: "2026-10-27T17:54:26.912500Z",
      updatedAt: "2026-10-27T17:54:27.737630Z",
    };

    const mockDetail: ProviderAttemptDetail = {
      attempt: mockAttempt,
      accountingState: "ACCOUNTING_GATE_PENDING",
      events: [
        {
          eventId: "ev-1",
          eventType: "ATTEMPT_CREATED",
          previousState: null,
          newState: "ORDER_PENDING",
          safeDetail: "Request recorded.",
          occurredAt: "2026-10-27T17:54:26.912500Z",
        },
        {
          eventId: "ev-2",
          eventType: "ORDER_CREATED",
          previousState: "ORDER_PENDING",
          newState: "READY_FOR_CHECKOUT",
          safeDetail: "Provider order identity and amount verified.",
          occurredAt: "2026-10-27T17:54:27.737630Z",
        },
      ],
    };

    vi.mocked(api.apiGet).mockResolvedValueOnce(emptyPage);
    vi.mocked(api.apiPostJson).mockResolvedValueOnce({
      attempt: mockAttempt,
      checkout: null,
    });
    vi.mocked(api.apiGet).mockResolvedValueOnce(mockDetail);

    const { container } = render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByLabelText(/Amount \(INR\)/i)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Amount \(INR\)/i), { target: { value: "25.00" } });
    fireEvent.change(screen.getByLabelText(/Purpose/i), { target: { value: "Collection test" } });
    fireEvent.change(screen.getByLabelText(/Business reference/i), { target: { value: "inv-2026-stepper" } });

    const submit = screen.getByRole("button", { name: /Create test payment/i });
    fireEvent.submit(submit.closest("form")!);

    await waitFor(() => expect(screen.getByRole("region", { name: /Payment lifecycle stages/i })).toBeInTheDocument());

    expect(screen.getByText("LIVE LIFECYCLE")).toBeInTheDocument();
    expect(screen.getByText("Request Submitted")).toBeInTheDocument();
    expect(screen.getByText("Creating Order")).toBeInTheDocument();
    expect(screen.getByText("Order Ready")).toBeInTheDocument();
    expect(screen.getByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText("Verifying Payment")).toBeInTheDocument();
    expect(screen.getByText("Final Status")).toBeInTheDocument();

    // Verify stored attempt in sessionStorage
    expect(window.sessionStorage.getItem("ledger-provider-selected-attempt")).toBe(mockAttempt.attemptId);

    // Verify no vertical timeline in DOM
    expect(container.querySelector(".provider-timeline")).not.toBeInTheDocument();
  });

  test("dispatches verify-checkout on checkout dismissal and handles failure callbacks", async () => {
    const mockAttempt: ProviderAttempt = {
      attemptId: "e3c78165-87d1-4ce3-b3f1-83901b4b4edd",
      receipt: "lpe3c7816587d14ce3b3f183901b4b4edd",
      amountMinor: "2500",
      currencyCode: "INR",
      purpose: "Dismissal test",
      businessReference: "inv-dismiss",
      state: "READY_FOR_CHECKOUT",
      providerOrderId: "order_Tt1Qvb0GuxIsXV",
      providerPaymentId: null,
      createdAt: "2026-10-27T17:54:26.912500Z",
      updatedAt: "2026-10-27T17:54:27.737630Z",
    };

    const mockDetail: ProviderAttemptDetail = {
      attempt: mockAttempt,
      accountingState: "ACCOUNTING_GATE_PENDING",
      events: [
        {
          eventId: "ev-1",
          eventType: "CHECKOUT_DISMISSED",
          previousState: "READY_FOR_CHECKOUT",
          newState: "READY_FOR_CHECKOUT",
          safeDetail: "Checkout dismissed without payment.",
          occurredAt: "2026-10-27T17:55:00.000000Z",
        },
      ],
    };

    interface RazorpayFailureEvent {
      error?: {
        metadata?: {
          payment_id?: string;
          order_id?: string;
        };
      };
    }
    interface MockOptions {
      modal?: {
        ondismiss?: () => void;
      };
    }

    let rzpOptions: MockOptions | undefined;
    let failureCallback: ((response: RazorpayFailureEvent) => void) | undefined;
    const mockRazorpay = vi.fn().mockImplementation(function (opts: MockOptions) {
      rzpOptions = opts;
      return {
        open: vi.fn(),
        on: vi.fn().mockImplementation((evt: string, cb: (response: RazorpayFailureEvent) => void) => {
          if (evt === "payment.failed") failureCallback = cb;
        }),
      };
    });
    window.Razorpay = mockRazorpay as unknown as typeof window.Razorpay;

    vi.mocked(api.apiGet).mockResolvedValueOnce({
      items: [mockAttempt],
      checkoutConfigured: true,
      checkoutKeyId: "rzp_test_123",
      totalElements: 1,
      totalPages: 1,
      page: 0,
      size: 25,
    });
    vi.mocked(api.apiPostJson).mockResolvedValue(mockDetail);

    render(<ProviderPaymentsConsole />);
    await waitFor(() => expect(screen.getByRole("button", { name: /Open Test Checkout/i })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /Open Test Checkout/i }));
    await waitFor(() => expect(mockRazorpay).toHaveBeenCalled());

    // Trigger modal dismissal
    rzpOptions?.modal?.ondismiss?.();
    await waitFor(() => {
      expect(api.apiPostJson).toHaveBeenCalledWith(
        "api/v1/provider-payments/e3c78165-87d1-4ce3-b3f1-83901b4b4edd/verify-checkout",
        {
          paymentId: null,
          orderId: "order_Tt1Qvb0GuxIsXV",
          signature: null,
        }
      );
    });

    // Trigger payment.failed
    if (failureCallback) {
      await failureCallback({
        error: {
          metadata: {
            payment_id: "pay_failed_xyz",
            order_id: "order_Tt1Qvb0GuxIsXV",
          },
        },
      });
      expect(api.apiPostJson).toHaveBeenCalledWith(
        "api/v1/provider-payments/e3c78165-87d1-4ce3-b3f1-83901b4b4edd/verify-checkout",
        {
          paymentId: "pay_failed_xyz",
          orderId: "order_Tt1Qvb0GuxIsXV",
          signature: null,
        }
      );
    }
  });
});
