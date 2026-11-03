import { NextRequest, NextResponse } from "next/server";
import { createSession, credentialsMatch, isLoopbackOrigin, SESSION_COOKIE } from "@/lib/server-auth";

export async function POST(request: NextRequest) {
  if (!isLoopbackOrigin(request.headers.get("origin"))) {
    return NextResponse.json({ message: "Request origin is not allowed." }, { status: 403 });
  }
  let body: { username?: unknown; password?: unknown };
  try { body = await request.json(); } catch { return NextResponse.json({ message: "Invalid sign-in request." }, { status: 400 }); }
  if (typeof body.username !== "string" || typeof body.password !== "string"
      || !credentialsMatch(body.username, body.password)) {
    return NextResponse.json({ message: "Invalid username or password." }, { status: 401 });
  }
  const session = createSession();
  const response = NextResponse.json({ authenticated: true });
  response.cookies.set(SESSION_COOKIE, session.value, {
    httpOnly: true, sameSite: "strict", secure: false, path: "/", maxAge: session.maxAge,
  });
  return response;
}
