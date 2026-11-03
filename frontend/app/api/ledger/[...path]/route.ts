import { NextRequest, NextResponse } from "next/server";
import { hasValidSession, isLoopbackOrigin, SESSION_COOKIE } from "@/lib/server-auth";

const UUID = "[0-9a-fA-F-]{36}";
const allowedGet = [
  /^api\/v1\/wallets$/,

  /^api\/v1\/provider-payments$/,
  new RegExp(`^api/v1/provider-payments/${UUID}$`),
  new RegExp(`^api/v1/provider-payments/${UUID}/webhook-events$`),
  /^api\/v1\/provider-payments\/reconciliation-runs$/,
  new RegExp(`^api/v1/provider-payments/reconciliation-runs/${UUID}$`),
  new RegExp(`^api/v1/provider-payments/reconciliation-runs/${UUID}/results$`),
  /^api\/v1\/transactions$/,
  new RegExp(`^api/v1/transactions/${UUID}$`),
  /^api\/v1\/settlement-batches$/,
  new RegExp(`^api/v1/settlement-batches/${UUID}/records$`),
  new RegExp(`^api/v1/settlement-batches/${UUID}/reconciliation-runs$`),
  new RegExp(`^api/v1/reconciliation-runs/${UUID}/results$`),
  /^actuator\/health(?:\/(?:liveness|readiness))?$/,
];
const allowedPost = [
  /^api\/v1\/wallets\/transfers$/,
  /^api\/v1\/webhooks\/razorpay$/,

  /^api\/v1\/provider-payments$/,
  new RegExp(`^api/v1/provider-payments/${UUID}/(?:verify-checkout|recover-order)$`),
  new RegExp(`^api/v1/provider-payments/webhook-events/${UUID}/replay$`),
  /^api\/v1\/provider-payments\/reconciliation-runs$/,
  /^api\/v1\/settlement-batches$/,
  new RegExp(`^api/v1/settlement-batches/${UUID}/reconciliation-runs$`),
];

type Context = { params: Promise<{ path: string[] }> };

async function forward(request: NextRequest, context: Context, method: "GET" | "POST") {
  const { path } = await context.params;
  const targetPath = path.join("/");
  const allowed = method === "GET"
    ? allowedGet.some((pattern) => pattern.test(targetPath))
    : allowedPost.some((pattern) => pattern.test(targetPath));
  if (!allowed) return NextResponse.json({ code: "NOT_FOUND", message: "Endpoint unavailable." }, { status: 404 });
  const isWebhook = targetPath === "api/v1/webhooks/razorpay";
  if (!isWebhook && !hasValidSession(request.cookies.get(SESSION_COOKIE)?.value)) {
    return NextResponse.json({ code: "UNAUTHENTICATED", message: "Sign in to access ledger operations." }, { status: 401 });
  }
  if (method === "POST") {
    if (!isWebhook && !isLoopbackOrigin(request.headers.get("origin"))) {
      return NextResponse.json({ code: "INVALID_ORIGIN", message: "Request origin is not allowed." }, { status: 403 });
    }
    const length = Number(request.headers.get("content-length") ?? "0");
    if (length > 6 * 1024 * 1024) {
      return NextResponse.json({ code: "PAYLOAD_TOO_LARGE", message: "The upload exceeds the request limit." }, { status: 413 });
    }
  }

  const base = process.env.LEDGER_API_BASE_URL ?? "http://localhost:8080";
  let target: URL;
  try {
    target = new URL(`/${targetPath}`, base);
  } catch {
    return NextResponse.json({ code: "UPSTREAM_UNAVAILABLE", message: "Backend URL is invalid." }, { status: 503 });
  }
  request.nextUrl.searchParams.forEach((value, key) => target.searchParams.append(key, value));

  try {
    const headers = new Headers({ Accept: "application/json" });
    const username = process.env.LEDGER_OPERATOR_USERNAME ?? "";
    const password = process.env.LEDGER_OPERATOR_PASSWORD ?? "";
    if (!username || password.length < 24) {
      return NextResponse.json({ code: "AUTH_UNAVAILABLE", message: "Operator authentication is not configured." }, { status: 503 });
    }
    headers.set("authorization", `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`);
    const contentType = request.headers.get("content-type");
    if (method === "POST" && contentType) headers.set("content-type", contentType);
    if (method === "POST" && (targetPath === "api/v1/wallets/transfers" || targetPath === "api/v1/provider-payments")) {
      const idempotencyKey = request.headers.get("idempotency-key");
      if (idempotencyKey) headers.set("Idempotency-Key", idempotencyKey);
    }
    if (isWebhook) {
      const sig = request.headers.get("x-razorpay-signature");
      if (sig) headers.set("X-Razorpay-Signature", sig);
      const evtId = request.headers.get("x-razorpay-event-id");
      if (evtId) headers.set("X-Razorpay-Event-Id", evtId);
    }
    const response = await fetch(target, {
      method,
      headers,
      body: method === "POST" ? await request.arrayBuffer() : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(30_000),
    });
    const responseType = response.headers.get("content-type") ?? "application/json";
    return new NextResponse(await response.arrayBuffer(), {
      status: response.status,
      headers: { "content-type": responseType, "cache-control": "no-store" },
    });
  } catch {
    return NextResponse.json({ code: "BACKEND_UNAVAILABLE", message: "The ledger API is unavailable." }, { status: 503 });
  }
}

export async function GET(request: NextRequest, context: Context) {
  return forward(request, context, "GET");
}

export async function POST(request: NextRequest, context: Context) {
  return forward(request, context, "POST");
}





