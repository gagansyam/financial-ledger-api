#!/usr/bin/env python3
"""Repository health check used by the local-check skill (SK-2).

Steps: build, run the automated tests, start the packaged server against a
disposable database, probe GET /api/v1/health, then stop the server and delete
the database. Every executable step reports PASS or FAIL and an overall result
is printed. Exit code 0 means every step passed.

Uses only the Python standard library.
"""

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "target" / "financial-ledger-api.jar"
HEALTH_PATH = "/api/v1/health"


class Report:
    def __init__(self):
        self.results = []

    def record(self, step, passed, detail=""):
        self.results.append((step, passed, detail))
        status = "PASS" if passed else "FAIL"
        suffix = f" - {detail}" if detail else ""
        print(f"[{status}] {step}{suffix}", flush=True)
        return passed

    def skip(self, step, reason):
        self.results.append((step, None, reason))
        print(f"[SKIP] {step} - {reason}", flush=True)

    def passed(self, step):
        return any(name == step and ok for name, ok, _ in self.results)

    def overall(self):
        return all(passed is not False for _, passed, _ in self.results) and any(
            passed for _, passed, _ in self.results
        )


def maven_command():
    wrapper = "mvnw.cmd" if os.name == "nt" else "mvnw"
    return [str(ROOT / wrapper), "-B", "-q"]


def run_maven(goals, log_path):
    with open(log_path, "w", encoding="utf-8") as log:
        completed = subprocess.run(
            maven_command() + goals, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT
        )
    return completed.returncode == 0


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def probe_health(port, timeout_s):
    url = f"http://127.0.0.1:{port}{HEALTH_PATH}"
    deadline = time.monotonic() + timeout_s
    last_error = "no response"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                body = json.loads(response.read().decode("utf-8"))
                request_id = response.headers.get("X-Request-Id", "")
                return response.status, body, request_id
        except urllib.error.HTTPError as e:
            body = json.loads(e.read().decode("utf-8") or "{}")
            return e.code, body, e.headers.get("X-Request-Id", "")
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            last_error = type(e).__name__
            time.sleep(0.5)
    raise TimeoutError(f"health endpoint did not answer within {timeout_s}s ({last_error})")


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


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--skip-build", action="store_true", help="reuse the existing jar and skip build and tests")
    parser.add_argument("--startup-timeout", type=int, default=60, help="seconds to wait for the health endpoint")
    args = parser.parse_args()

    report = Report()
    workdir = Path(tempfile.mkdtemp(prefix="ledger-local-check-"))
    database = workdir / "ledger.db"
    print(f"Disposable workspace: {workdir}")

    try:
        if args.skip_build:
            report.skip("build", "--skip-build given")
            report.skip("tests", "--skip-build given")
        else:
            built = report.record(
                "build",
                run_maven(["-DskipTests", "-Djacoco.skip=true", "package"], workdir / "build.log"),
                "" if JAR.exists() else "see build log",
            )
            if built:
                passed = run_maven(["verify"], workdir / "test.log")
                report.record("tests", passed, "unit, integration, system tests and quality gates")
                if not passed:
                    print((workdir / "test.log").read_text(encoding="utf-8")[-4000:])
            else:
                print((workdir / "build.log").read_text(encoding="utf-8")[-4000:])
                report.skip("tests", "build failed")

        if not JAR.exists():
            report.record("start server", False, f"{JAR.relative_to(ROOT)} not found")
            report.skip("health probe", "server not started")
        else:
            port = free_port()
            env = dict(os.environ)
            env.update({
                "LEDGER_LISTEN_ADDRESS": "127.0.0.1",
                "LEDGER_PORT": str(port),
                "LEDGER_DB_PATH": str(database),
            })
            server_log = open(workdir / "server.log", "w", encoding="utf-8")
            process = subprocess.Popen(
                ["java", "-jar", str(JAR)], cwd=workdir, env=env, stdout=server_log, stderr=subprocess.STDOUT
            )
            try:
                try:
                    status, body, request_id = probe_health(port, args.startup_timeout)
                    report.record("start server", True, f"listening on 127.0.0.1:{port}")
                    healthy = (
                        status == 200
                        and body.get("status") == "OK"
                        and body.get("database") == "reachable"
                        and bool(request_id)
                    )
                    report.record(
                        "health probe",
                        healthy,
                        f"HTTP {status} status={body.get('status')} database={body.get('database')}"
                        f" version={body.get('version')}",
                    )
                except TimeoutError as e:
                    report.record("start server", False, str(e))
                    report.skip("health probe", "server not reachable")
            finally:
                clean, stop_detail = stop_server(process, workdir / "server.log")
                server_log.close()
                if not report.passed("start server"):
                    print((workdir / "server.log").read_text(encoding="utf-8", errors="replace")[-4000:])
                report.record("stop server", clean, stop_detail)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
        report.record("discard database", not workdir.exists(), str(workdir))

    ok = report.overall()
    print(f"\nOVERALL: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
