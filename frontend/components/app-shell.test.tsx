import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "./app-shell";

describe("AppShell", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true })); });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
    delete document.documentElement.dataset.theme;
  });

  it("shows workspace destinations and persists the dark theme", async () => {
    render(<AppShell><p>Ledger content</p></AppShell>);
    const navigation = await screen.findByRole("navigation", { name: "Primary navigation" });
    expect(navigation.querySelectorAll("a")).toHaveLength(5);
    expect(Array.from(navigation.querySelectorAll("a")).map((link) => link.textContent?.trim()))
      .toEqual(["Payments", "Transactions", "Provider payments", "Reconciliation", "System health"]);
    expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute("href", "/transactions");
    expect(screen.getByRole("link", { name: "Payments" })).toHaveAttribute("href", "/payments");
    expect(screen.getByRole("link", { name: "Provider payments" })).toHaveAttribute("href", "/provider-payments");
    expect(screen.getByRole("link", { name: "Reconciliation" })).toHaveAttribute("href", "/reconciliation");
    expect(screen.getByRole("link", { name: "System health" })).toHaveAttribute("href", "/system-health");

    fireEvent.click(screen.getByRole("button", { name: "Switch to dark theme" }));
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));
    expect(localStorage.getItem("ledger-theme")).toBe("dark");
  });

  it("keeps protected pages behind operator sign-in", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false }));
    render(<AppShell><p>Ledger content</p></AppShell>);
    expect(await screen.findByRole("heading", { name: "Ledger operator sign in" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("navigation", { name: "Primary navigation" })).not.toBeInTheDocument());
  });

  it("returns to operator sign-in after a session-expired event", async () => {
    render(<AppShell><p>Ledger content</p></AppShell>);
    await screen.findByRole("navigation", { name: "Primary navigation" });
    fireEvent(window, new Event("ledger:session-expired"));
    expect(await screen.findByRole("heading", { name: "Ledger operator sign in" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("navigation", { name: "Primary navigation" })).not.toBeInTheDocument());
  });
});
