#!/usr/bin/env python3
"""Product verification used by the simulate-transfer skill (SK-3, SK-8).

Starts the packaged server against a disposable database and drives the public
API end to end as SK-3 requires: two users with their own sessions, one wallet
each, admin funding, a transfer and its idempotent replay, two concurrent
distinct-key transfers that can only both fit if the ledger overdraws (exactly
one must succeed), and admin reconciliation. It also checks key reuse,
ownership, a fractional amount, a reversal and logout. Every step reports PASS or
FAIL and an overall result is printed. Exit code 0 means every step passed.

Credentials come from LEDGER_SIM_ADMIN_KEY and LEDGER_SIM_PASSWORD when set and
are generated otherwise. They are never printed or written to disk.

Uses only the Python standard library.
"""

import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "target" / "financial-ledger-api.jar"
BASE = "/api/v1"
ADMIN_PRINCIPAL = "sim-admin"


class Report:
    def __init__(self):
        self.results = []

    def check(self, step, passed, detail=""):
        self.results.append((step, passed))
        status = "PASS" if passed else "FAIL"
        line = f"[{status}] {step}"
        if detail:
            line += f" - {detail}"
        print(line, flush=True)
        return passed

    def overall(self):
        return bool(self.results) and all(passed for _, passed in self.results)


class Api:
    def __init__(self, port):
        self.root = f"http://127.0.0.1:{port}{BASE}"

    def call(self, method, path, body=None, token=None, admin_key=None, key=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.root + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        if admin_key:
            request.add_header("X-API-Key", admin_key)
        if key:
            request.add_header("Idempotency-Key", key)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, dict(response.headers), _json(response.read())
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), _json(error.read())


def _json(raw):
    try:
        return json.loads(raw.decode("utf-8")) if raw else {}
    except ValueError:
        return {}


def code_of(payload):
    return payload.get("error", {}).get("code")


def new_key():
    return "sim-" + secrets.token_hex(12)


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def build_jar():
    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [str(wrapper), "-B", "-q", "package", "-DskipTests", "-Djacoco.skip=true"]
    return subprocess.run(command, cwd=ROOT).returncode == 0 and JAR.exists()


def wait_healthy(api, process, timeout_s):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return False
        try:
            status, _, body = api.call("GET", "/health")
            if status == 200 and body.get("status") == "OK":
                return True
        except OSError:
            pass
        time.sleep(0.25)
    return False


def stop_server(process, log_path, timeout_s=15):
    """Stops the server and reports whether the stop was graceful (FR-6.3).

    On POSIX the termination signal runs the JVM shutdown hook, and a graceful stop is one that
    exits in time and logs the "stopped" message. Windows has no termination signal for a console child, so
    terminate() is a hard kill; that run cannot observe a graceful stop and says so.
    """
    if process.poll() is not None:
        return False, f"server had already exited with code {process.returncode}"
    process.terminate()
    try:
        process.wait(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=timeout_s)
        return False, "had to be killed"
    if os.name == "nt":
        return True, "forced stop (Windows); graceful shutdown is checked on Linux"
    text = Path(log_path).read_text(encoding="utf-8", errors="replace")
    if '"message":"stopped"' in text:
        return True, "graceful"
    return False, "exited without the shutdown log line"


def person(api, report, login, password):
    status, _, body = api.call(
        "POST", "/users", {"displayName": login.title(), "loginName": login, "password": password}
    )
    report.check(f"register {login}", status == 201, f"HTTP {status}")
    user_id = body.get("userId")
    status, headers, session = api.call(
        "POST", "/auth/login", {"loginName": login, "password": password}
    )
    token = session.get("accessToken")
    report.check(
        f"login {login}",
        status == 200 and bool(token) and headers.get("Cache-Control") == "no-store",
        f"HTTP {status}",
    )
    status, _, wallet = api.call(
        "POST", f"/users/{user_id}/wallets", {"currency": "INR"}, token=token
    )
    report.check(f"create INR wallet for {login}", status == 201, f"HTTP {status}")
    return user_id, token, wallet.get("walletId")


def balance(api, token, wallet_id):
    status, _, body = api.call("GET", f"/wallets/{wallet_id}", token=token)
    return body.get("balance") if status == 200 else None


def entry_count(api, token, wallet_id):
    status, _, body = api.call("GET", f"/wallets/{wallet_id}/entries?limit=100", token=token)
    return len(body.get("items", [])) if status == 200 else None


def transfer_body(source, destination, amount, reference=None):
    body = {
        "sourceWalletId": source,
        "destinationWalletId": destination,
        "amount": amount,
        "currency": "INR",
    }
    if reference is not None:
        body["reference"] = reference
    return body


def race(api, token, source, destination, amount):
    """Sends two distinct-key transfers from one source at the same moment."""
    results = []
    lock = threading.Lock()
    barrier = threading.Barrier(2)

    def worker():
        key = new_key()
        barrier.wait()
        status, _, body = api.call(
            "POST", "/transfers", transfer_body(source, destination, amount), token=token, key=key
        )
        with lock:
            results.append((status, code_of(body)))

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def scenario(api, report, admin_key, password):
    alice_id, alice, alice_wallet = person(api, report, "sim_alice", password)
    _, bob, bob_wallet = person(api, report, "sim_bob", password)

    funding = 10000
    status, _, _ = api.call(
        "POST", f"/admin/wallets/{alice_wallet}/funding", {"amount": funding, "currency": "INR"},
        admin_key=admin_key, key=new_key(),
    )
    report.check(f"admin funds alice with {funding}", status == 201, f"HTTP {status}")
    alice_balance = balance(api, alice, alice_wallet)
    bob_balance = balance(api, bob, bob_wallet)
    report.check(
        "initial balances recorded",
        alice_balance == funding and bob_balance == 0,
        f"alice {alice_balance}, bob {bob_balance}",
    )

    amount = 2500
    key = new_key()
    body = transfer_body(alice_wallet, bob_wallet, amount, "rent")
    status, _, first = api.call("POST", "/transfers", body, token=alice, key=key)
    report.check(f"transfer {amount} alice -> bob", status == 201, f"HTTP {status}")
    tx_id = first.get("transactionId")
    alice_balance -= amount
    bob_balance += amount
    entries_before = (entry_count(api, alice, alice_wallet), entry_count(api, bob, bob_wallet))

    replay_status, headers, replay = api.call("POST", "/transfers", body, token=alice, key=key)
    entries_after = (entry_count(api, alice, alice_wallet), entry_count(api, bob, bob_wallet))
    report.check(
        "replay returns the original status and body without new entries",
        replay_status == status and headers.get("Idempotency-Replayed") == "true"
        and replay == first and entries_after == entries_before
        and balance(api, alice, alice_wallet) == alice_balance,
        f"HTTP {replay_status}, entries {entries_before} -> {entries_after}",
    )

    changed = transfer_body(alice_wallet, bob_wallet, amount + 100, "rent")
    status, _, reuse = api.call("POST", "/transfers", changed, token=alice, key=key)
    report.check(
        "same key with a different payload is rejected",
        status == 422 and code_of(reuse) == "IDEMPOTENCY_KEY_REUSED",
        f"HTTP {status} {code_of(reuse)}",
    )
    status, _, err = api.call(
        "POST", "/transfers", transfer_body(bob_wallet, alice_wallet, 1), token=alice,
        key=new_key(),
    )
    report.check(
        "spending from another user's wallet is forbidden",
        status == 403 and code_of(err) == "FORBIDDEN", f"HTTP {status} {code_of(err)}",
    )
    status, _, err = api.call(
        "POST", "/transfers", transfer_body(alice_wallet, bob_wallet, 1.5), token=alice,
        key=new_key(),
    )
    report.check(
        "fractional amount is rejected", status == 400 and code_of(err) == "INVALID_AMOUNT",
        f"HTTP {status} {code_of(err)}",
    )

    remaining = balance(api, alice, alice_wallet)
    racing = remaining // 2 + 1
    report.check(
        "race amount is more than half and at most the remaining balance",
        remaining is not None and remaining >= 2 and remaining < 2 * racing <= 2 * remaining,
        f"remaining {remaining}, each transfer {racing}",
    )
    results = race(api, alice, alice_wallet, bob_wallet, racing)
    outcomes = sorted(results)
    report.check(
        "two concurrent transfers: exactly one succeeds, the other is INSUFFICIENT_FUNDS",
        outcomes == [(201, None), (422, "INSUFFICIENT_FUNDS")],
        ", ".join(f"HTTP {s}" + (f" {c}" if c else "") for s, c in outcomes),
    )
    if any(s == 201 for s, _ in results):
        alice_balance -= racing
        bob_balance += racing
    actual = (balance(api, alice, alice_wallet), balance(api, bob, bob_wallet))
    report.check(
        "balances equal the expected arithmetic",
        actual == (alice_balance, bob_balance),
        f"alice {actual[0]} (expected {alice_balance}), bob {actual[1]} (expected {bob_balance})",
    )

    status, _, reversal = api.call(
        "POST", f"/admin/transactions/{tx_id}/reversal", {"reason": "simulation"},
        admin_key=admin_key, key=new_key(),
    )
    report.check(
        "admin reverses the first transfer",
        status == 201 and reversal.get("reversalOf") == tx_id, f"HTTP {status}",
    )
    if status == 201:
        alice_balance += amount
        bob_balance -= amount
    status, _, again = api.call(
        "POST", f"/admin/transactions/{tx_id}/reversal", None, admin_key=admin_key, key=new_key()
    )
    report.check(
        "a second reversal is rejected", status == 409 and code_of(again) == "ALREADY_REVERSED",
        f"HTTP {status} {code_of(again)}",
    )
    actual = (balance(api, alice, alice_wallet), balance(api, bob, bob_wallet))
    report.check(
        "final balances equal the expected arithmetic",
        actual == (alice_balance, bob_balance),
        f"alice {actual[0]} (expected {alice_balance}), bob {actual[1]} (expected {bob_balance})",
    )

    status, _, recon = api.call("GET", "/admin/reconciliation", admin_key=admin_key)
    report.check(
        "reconciliation is CONSISTENT",
        status == 200 and recon.get("status") == "CONSISTENT" and not recon.get("discrepancies"),
        f"HTTP {status} {recon.get('status')}, {recon.get('transactionCount')} transactions",
    )

    status, _, _ = api.call("POST", "/auth/logout", None, token=alice)
    after, _, _ = api.call("GET", f"/users/{alice_id}", token=alice)
    report.check("logout revokes the session", status == 204 and after == 401, f"HTTP {after}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--build", action="store_true", help="package the jar first")
    parser.add_argument("--startup-timeout", type=int, default=60)
    args = parser.parse_args()

    report = Report()
    if args.build or not JAR.exists():
        if not report.check("build", build_jar()):
            print("OVERALL: FAIL")
            return 1

    admin_key = os.environ.get("LEDGER_SIM_ADMIN_KEY") or secrets.token_urlsafe(32)
    password = os.environ.get("LEDGER_SIM_PASSWORD") or secrets.token_urlsafe(18)
    workdir = Path(tempfile.mkdtemp(prefix="ledger-sim-"))
    port = free_port()
    env = dict(os.environ)
    env.pop("LEDGER_SIM_ADMIN_KEY", None)
    env.pop("LEDGER_SIM_PASSWORD", None)
    env.update(
        {
            "LEDGER_LISTEN_ADDRESS": "127.0.0.1",
            "LEDGER_PORT": str(port),
            "LEDGER_DB_PATH": str(workdir / "ledger.db"),
            "LEDGER_CURRENCIES": "INR:2",
            "LEDGER_ADMIN_KEYS": ADMIN_PRINCIPAL + "="
            + hashlib.sha256(admin_key.encode("utf-8")).hexdigest(),
        }
    )
    log_path = workdir / "server.log"
    api = Api(port)
    process = None
    try:
        with open(log_path, "wb") as log:
            process = subprocess.Popen(
                ["java", "-jar", str(JAR)], cwd=workdir, env=env, stdout=log,
                stderr=subprocess.STDOUT,
            )
            if report.check("start server", wait_healthy(api, process, args.startup_timeout)):
                scenario(api, report, admin_key, password)
            stopped, stop_detail = stop_server(process, log_path)
            report.check("stop server", stopped, stop_detail)
        leaked = admin_key in log_path.read_text(encoding="utf-8", errors="replace")
        leaked = leaked or password in log_path.read_text(encoding="utf-8", errors="replace")
        report.check("server log contains no credentials", not leaked)
    finally:
        if process is not None and process.poll() is None:
            process.kill()
        shutil.rmtree(workdir, ignore_errors=True)
    report.check("discard database", not workdir.exists())

    print("OVERALL: " + ("PASS" if report.overall() else "FAIL"))
    return 0 if report.overall() else 1


if __name__ == "__main__":
    sys.exit(main())
