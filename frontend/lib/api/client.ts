import type { ApiErrorBody, Page } from "./types";

export class ApiClientError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ApiClientError";
  }
}

export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/ledger/${path.replace(/^\/+/, "")}`, {
    method: "GET",
    cache: "no-store",
    signal,
    headers: { Accept: "application/json" },
  });
  return parseResponse<T>(response);
}

export async function apiPost<T>(path: string, body?: FormData): Promise<T> {
  const response = await fetch(`/api/ledger/${path.replace(/^\/+/, "")}`, {
    method: "POST",
    body,
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  return parseResponse<T>(response);
}

export async function apiPostJson<T>(
  path: string,
  body: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  const response = await fetch("/api/ledger/" + path.replace(/^\/+/, ""), {
    method: "POST",
    body: JSON.stringify(body),
    cache: "no-store",
    headers: { Accept: "application/json", "Content-Type": "application/json", ...headers },
  });
  return parseResponse<T>(response);
}

async function parseResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get("content-type") ?? "";
  const text = await response.text();
  let value: unknown;
  if (text && contentType.includes("application/json")) {
    try {
      value = JSON.parse(text);
    } catch {
      throw new ApiClientError("The server returned invalid JSON.", response.status);
    }
  }
  if (!response.ok) {
    const error = (value ?? {}) as ApiErrorBody;
    throw new ApiClientError(
      error.message || `The request failed (${response.status}).`,
      response.status,
      error.code,
    );
  }
  if (value === undefined) {
    throw new ApiClientError("The server returned an empty response.", response.status);
  }
  return value as T;
}

export function pagePath(path: string, page: number, size = 25): string {
  const separator = path.includes("?") ? "&" : "?";
  return `${path}${separator}page=${page}&size=${size}`;
}

export type { Page };
