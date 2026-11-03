import hmac
import hashlib
import json
import time
import urllib.request
import urllib.error
import http.cookiejar
import base64
import sys

BASE_URL = "http://127.0.0.1:3000"
WEBHOOK_SECRET = "local-review-webhook-secret-phase63"
OPERATOR_USER = "ledger-operator"
OPERATOR_PASS = "76abed094c75faf607f6c5a9512758597d6de6882f47ebaae83e906ef0ca122"

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def log(msg):
    print(f"[ACCEPTANCE] {msg}")

def login():
    log("Logging in as operator...")
    login_data = json.dumps({"username": OPERATOR_USER, "password": OPERATOR_PASS}).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/api/auth/login",
        data=login_data,
        headers={"Content-Type": "application/json", "Origin": BASE_URL}
    )
    res = opener.open(req)
    assert res.status == 200, f"Login failed: {res.status}"
    body = json.loads(res.read().decode("utf-8"))
    log(f"Login success: {body}")

def calculate_sig(body_bytes):
    return hmac.new(WEBHOOK_SECRET.encode("utf-8"), body_bytes, hashlib.sha256).hexdigest()

def test_webhook_intake():
    log("\n--- TEST 1: Missing signature should be rejected with 400 ---")
    payload = json.dumps({"event": "payment.captured"}).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/webhooks/razorpay",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    try:
        opener.open(req)
        assert False, "Should have failed with 400"
    except urllib.error.HTTPError as e:
        log(f"Received expected HTTP error: {e.code}")
        assert e.code == 400, f"Expected 400, got {e.code}"

    log("\n--- TEST 2: Invalid signature should be rejected with 400 ---")
    req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/webhooks/razorpay",
        data=payload,
        headers={
            "Content-Type": "application/json",
            "X-Razorpay-Signature": "invalid_hex_signature_here"
        }
    )
    try:
        opener.open(req)
        assert False, "Should have failed with 400"
    except urllib.error.HTTPError as e:
        log(f"Received expected HTTP error: {e.code}")
        assert e.code == 400, f"Expected 400, got {e.code}"

    log("\n--- TEST 3: Valid payment.captured webhook delivery ---")
    event_id = f"evt_test_phase63_{int(time.time())}"
    # Target existing payment attempt: fafaf68a-3657-4cbd-b737-0a50e8521452
    webhook_data = {
        "event": "payment.captured",
        "payload": {
            "payment": {
                "entity": {
                    "id": "pay_TtqOCC5sc03OvW",
                    "order_id": "order_TtqKiYiDL5SPoK",
                    "amount": 1000,
                    "currency": "INR",
                    "status": "captured"
                }
            },
            "order": {
                "entity": {
                    "id": "order_TtqKiYiDL5SPoK",
                    "receipt": "rcpt_fafaf68a3657",
                    "amount": 1000,
                    "currency": "INR",
                    "status": "paid"
                }
            }
        }
    }
    body_bytes = json.dumps(webhook_data).encode("utf-8")
    sig = calculate_sig(body_bytes)

    req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/webhooks/razorpay",
        data=body_bytes,
        headers={
            "Content-Type": "application/json",
            "X-Razorpay-Signature": sig,
            "X-Razorpay-Event-Id": event_id
        }
    )
    res = opener.open(req)
    assert res.status == 200, f"Expected 200, got {res.status}"
    ack = json.loads(res.read().decode("utf-8"))
    log(f"Webhook accepted successfully: {ack}")
    assert ack["status"] == "OK"

    log("\n--- TEST 4: Deduplication - Re-delivering exact same webhook ---")
    req_duplicate = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/webhooks/razorpay",
        data=body_bytes,
        headers={
            "Content-Type": "application/json",
            "X-Razorpay-Signature": sig,
            "X-Razorpay-Event-Id": event_id
        }
    )
    res_duplicate = opener.open(req_duplicate)
    assert res_duplicate.status == 200, f"Expected 200 on dedup, got {res_duplicate.status}"
    ack_dup = json.loads(res_duplicate.read().decode("utf-8"))
    log(f"Duplicate webhook deduplicated idempotently: {ack_dup}")
    assert ack_dup["status"] == "OK"

    log("\n--- TEST 5: Out-of-order event - Late payment.authorized event ---")
    out_of_order_event_id = f"evt_ooo_{int(time.time())}"
    ooo_data = {
        "event": "payment.authorized",
        "payload": {
            "payment": {
                "entity": {
                    "id": "pay_TtqOCC5sc03OvW",
                    "order_id": "order_TtqKiYiDL5SPoK",
                    "amount": 1000,
                    "currency": "INR",
                    "status": "authorized"
                }
            },
            "order": {
                "entity": {
                    "id": "order_TtqKiYiDL5SPoK",
                    "receipt": "rcpt_fafaf68a3657",
                    "amount": 1000,
                    "currency": "INR",
                    "status": "paid"
                }
            }
        }
    }
    ooo_bytes = json.dumps(ooo_data).encode("utf-8")
    ooo_sig = calculate_sig(ooo_bytes)
    req_ooo = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/webhooks/razorpay",
        data=ooo_bytes,
        headers={
            "Content-Type": "application/json",
            "X-Razorpay-Signature": ooo_sig,
            "X-Razorpay-Event-Id": out_of_order_event_id
        }
    )
    res_ooo = opener.open(req_ooo)
    assert res_ooo.status == 200, f"Expected 200, got {res_ooo.status}"
    log(f"Late payment.authorized handled safely: {res_ooo.read().decode('utf-8')}")

    return event_id

def test_api_and_replay(target_attempt_id):
    log(f"\n--- TEST 6: Fetch attempt webhook events for {target_attempt_id} ---")
    req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/provider-payments/{target_attempt_id}/webhook-events",
        headers={"Accept": "application/json"}
    )
    res = opener.open(req)
    assert res.status == 200
    events_page = json.loads(res.read().decode("utf-8"))
    log(f"Found {events_page['totalElements']} webhook events for attempt.")
    assert len(events_page["items"]) > 0
    first_event = events_page["items"][0]
    log(f"Sample webhook event: id={first_event['id']}, type={first_event['eventType']}, state={first_event['processingState']}")

    log(f"\n--- TEST 7: Safe Replay of Webhook Event {first_event['id']} ---")
    replay_req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/provider-payments/webhook-events/{first_event['id']}/replay",
        data=b"{}",
        headers={"Content-Type": "application/json", "Origin": BASE_URL}
    )
    replay_res = opener.open(replay_req)
    assert replay_res.status == 200
    replay_ack = json.loads(replay_res.read().decode("utf-8"))
    log(f"Replay response: {replay_ack}")
    assert replay_ack["status"] in ("OK", "REPLAYED", "ALREADY_PROCESSED")

def test_reconciliation():
    log("\n--- TEST 8: Trigger Provider Payment Reconciliation Run ---")
    rec_req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/provider-payments/reconciliation-runs",
        data=b"{}",
        headers={"Content-Type": "application/json", "Origin": BASE_URL}
    )
    rec_res = opener.open(rec_req)
    assert rec_res.status in (200, 201), f"Expected 200/201, got {rec_res.status}"
    run_summary = json.loads(rec_res.read().decode("utf-8"))
    log(f"Reconciliation run created: Run #{run_summary['runNumber']} (id={run_summary['id']})")
    log(f"Matched: {run_summary['matchedCount']}, Internal: {run_summary['totalInternalRecords']}, Provider: {run_summary['totalProviderRecords']}")
    assert run_summary["status"] == "COMPLETED"

    log("\n--- TEST 9: Fetch Reconciliation Results ---")
    res_req = urllib.request.Request(
        f"{BASE_URL}/api/ledger/api/v1/provider-payments/reconciliation-runs/{run_summary['id']}/results",
        headers={"Accept": "application/json"}
    )
    res_res = opener.open(res_req)
    assert res_res.status == 200
    results_page = json.loads(res_res.read().decode("utf-8"))
    log(f"Results page: total {results_page['totalElements']} classifications.")
    for item in results_page["items"][:5]:
        log(f"  Outcome: {item['outcome']} | Order: {item.get('providerOrderId')} | Payment: {item.get('providerPaymentId')} | Details: {item.get('detailsJson', '')}")

    return run_summary['id']

def test_ui_routes():
    log("\n--- TEST 10: Verify all 5 UI Routes return HTTP 200 ---")
    routes = [
        "/transactions",
        "/payments",
        "/provider-payments",
        "/reconciliation",
        "/system-health"
    ]
    for r in routes:
        req = urllib.request.Request(f"{BASE_URL}{r}")
        res = opener.open(req)
        assert res.status == 200, f"Route {r} returned {res.status}"
        log(f"Route {r} -> HTTP {res.status} OK")

if __name__ == "__main__":
    try:
        login()
        test_webhook_intake()
        test_api_and_replay("fafaf68a-3657-4cbd-b737-0a50e8521452")
        test_reconciliation()
        test_ui_routes()
        log("\n>>> ALL PHASE 6.3 ACCEPTANCE INTEGRATION CHECKS PASSED SUCCESSFULLY! <<<")
    except Exception as e:
        log(f"\nFAILED with exception: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
