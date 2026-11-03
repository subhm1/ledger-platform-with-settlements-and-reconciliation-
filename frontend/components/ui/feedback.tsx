import { AlertCircle, LoaderCircle, SearchX } from "lucide-react";

export function Loading({ label = "Loading records" }: { label?: string }) {
  return <div className="feedback" role="status"><LoaderCircle className="spin" size={18} />{label}…</div>;
}

export function Empty({ title, detail }: { title: string; detail: string }) {
  return <div className="empty-state"><span className="empty-icon"><SearchX size={18} /></span><strong>{title}</strong><span>{detail}</span></div>;
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <div className="error-state" role="alert"><AlertCircle size={18} /><span>{message}</span>{onRetry && <button className="button secondary" onClick={onRetry}>Retry</button>}</div>;
}

export function StatePanel({ title, value, detail, state }: { title: string; value: string; detail: string; state: "good" | "bad" | "unknown" }) {
  return <article className="health-card"><div className="health-card-top"><span>{title}</span><span className={`health-dot ${state}`} /></div><strong>{value}</strong><p>{detail}</p></article>;
}
