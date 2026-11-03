import { NextRequest, NextResponse } from "next/server";
import { hasValidSession, SESSION_COOKIE } from "@/lib/server-auth";

export async function GET(request: NextRequest) {
  const authenticated = hasValidSession(request.cookies.get(SESSION_COOKIE)?.value);
  return NextResponse.json({ authenticated }, { status: authenticated ? 200 : 401 });
}
