"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Activity, ArrowLeftRight, CreditCard, Layers3, Moon, Sun, WalletCards } from "lucide-react";
import { useEffect, useState, useSyncExternalStore } from "react";

function subscribeToTheme(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  return () => observer.disconnect();
}

function currentTheme(): "light" | "dark" {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

function serverTheme(): "light" | "dark" {
  return "light";
}

const destinations = [
  { href: "/payments", label: "Payments", icon: CreditCard },
  { href: "/transactions", label: "Transactions", icon: ArrowLeftRight },
  { href: "/provider-payments", label: "Provider payments", icon: WalletCards },
  { href: "/reconciliation", label: "Reconciliation", icon: Layers3 },
  { href: "/system-health", label: "System health", icon: Activity },
];

export function AppShell({ children }: Readonly<{ children: React.ReactNode }>) {
  const path = usePathname();
  const theme = useSyncExternalStore(subscribeToTheme, currentTheme, serverTheme);
  const [auth, setAuth] = useState<"loading" | "signed-out" | "signed-in">("loading");
  const [authError, setAuthError] = useState("");

  useEffect(() => {
    const saved = localStorage.getItem("ledger-theme");
    const next = saved === "dark" ? "dark" : "light";
    document.documentElement.dataset.theme = next;
  }, []);

  useEffect(() => {
    const onExpired = () => setAuth("signed-out");
    window.addEventListener("ledger:session-expired", onExpired);
    return () => window.removeEventListener("ledger:session-expired", onExpired);
  }, []);

  useEffect(() => {
    fetch("/api/auth/session", { cache: "no-store" }).then((response) => {
      setAuth(response.ok ? "signed-in" : "signed-out");
    }).catch(() => setAuth("signed-out"));
  }, []);

  async function signIn(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setAuthError("");
    const form = new FormData(event.currentTarget);
    const response = await fetch("/api/auth/login", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ username: form.get("username"), password: form.get("password") }),
    });
    if (!response.ok) { setAuthError(response.status === 401 ? "Username or password is incorrect." : "Sign-in is unavailable."); return; }
    setAuth("signed-in");
  }

  if (auth !== "signed-in") return <main className="page-container" style={{ maxWidth: 420, margin: "12vh auto" }}>
    <section className="section-card"><h1>Ledger operator sign in</h1>
      {auth === "loading" ? <p role="status">Checking session…</p> : <form onSubmit={signIn} className="upload-grid" style={{ gridTemplateColumns: "1fr" }}>
        <label>Username<input name="username" autoComplete="username" required /></label>
        <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
        {authError && <p className="upload-message error" role="alert">{authError}</p>}
        <button className="button" type="submit">Sign in</button>
      </form>}
    </section>
  </main>;

  function toggleTheme() {
    const next = theme === "light" ? "dark" : "light";
    document.documentElement.dataset.theme = next;
    localStorage.setItem("ledger-theme", next);
  }

  return <div className="app-frame">
    <aside className="sidebar">
      <Link href="/transactions" className="brand"><span className="brand-mark">L</span><span>Ledger<span className="brand-muted"> / platform</span></span></Link>
      <div className="nav-caption">WORKSPACE</div>
      <nav aria-label="Primary navigation" className="primary-nav">
        {destinations.map(({ href, label, icon: Icon }) => <Link key={href} href={href} aria-current={path === href ? "page" : undefined} className={`nav-link ${path === href ? "active" : ""}`}>
          <Icon size={17} strokeWidth={1.8} /><span>{label}</span>
        </Link>)}
      </nav>
      <div className="sidebar-bottom"><span className="local-dot" /> Local operator
        <button className="theme-button" type="button" onClick={async () => { await fetch("/api/auth/logout", { method: "POST" }); setAuth("signed-out"); }}>Sign out</button></div>
    </aside>
    <main className="main-area">
      <header className="topbar"><div className="breadcrumb">Operations <span>/</span> <strong>{destinations.find((item) => item.href === path)?.label ?? "Ledger"}</strong></div>
        <button className="theme-button" type="button" onClick={toggleTheme} aria-label={`Switch to ${theme === "light" ? "dark" : "light"} theme`}>
          {theme === "light" ? <Moon size={16} /> : <Sun size={16} />}<span>{theme === "light" ? "Dark" : "Light"} theme</span>
        </button>
      </header>
      <div className="page-container">{children}</div>
    </main>
  </div>;
}




