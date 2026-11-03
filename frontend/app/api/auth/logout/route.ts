import { NextRequest, NextResponse } from "next/server";
import { isLoopbackOrigin, SESSION_COOKIE } from "@/lib/server-auth";

export async function POST(request: NextRequest) {
  if (!isLoopbackOrigin(request.headers.get("origin"))) {
    return NextResponse.json({ message: "Request origin is not allowed." }, { status: 403 });
  }
  const response = NextResponse.json({ authenticated: false });
  response.cookies.set(SESSION_COOKIE, "", { httpOnly: true, sameSite: "strict", secure: false, path: "/", maxAge: 0 });
  return response;
}
