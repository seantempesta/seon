#!/usr/bin/env python3
"""Official SWE-bench grading of mini-swe-agent predictions on arm64.

The swebench 4.1.0 package's own `run_evaluation` hardcodes arch="x86_64" and
passes `--platform linux/x86_64` to `containers.create`, which the Docker
daemon rejects for the arm64 epoch instance images this host uses (same images
the Seon arm runs). This script reproduces `run_instance`'s EXACT official
sequence — swebench's own `make_test_spec` eval script + `GIT_APPLY_CMDS` patch
apply + `swebench.harness.grading.get_eval_report` FAIL_TO_PASS/PASS_TO_PASS
oracle — but drives the arm64 container via the docker CLI with NO platform
flag (as mini-swe-agent itself does). The verdict is produced by swebench's
unmodified grader; only the container orchestration differs.

Run: tmp/slice2-venv/bin/python score_official.py
"""
from __future__ import annotations
import concurrent.futures as cf
import json, subprocess, sys, tempfile, time
from pathlib import Path

from datasets import load_dataset
from swebench.harness.test_spec.test_spec import make_test_spec
from swebench.harness.grading import get_eval_report
from swebench.harness.constants import (
    DOCKER_PATCH, DOCKER_WORKDIR, DOCKER_USER,
    KEY_INSTANCE_ID, KEY_PREDICTION, KEY_MODEL,
)

# swebench run_evaluation.py:64 — the exact apply ladder, in order.
GIT_APPLY_CMDS = [
    "git apply --verbose",
    "git apply --verbose --reject",
    "patch --batch --fuzz=5 -p1 -i",
]

RUN = Path(__file__).resolve().parent
PREDS = json.loads((RUN / "preds" / "preds.json").read_text())
IDS = [l.strip() for l in (RUN.parent / "2026-07-05-slice4-dev-pass" / "dev-ids.txt")
       .read_text().splitlines() if l.strip()]
EVAL_TIMEOUT_S = 1800  # swebench default per-instance test timeout
LOGDIR = RUN / "eval-report" / "arm64-official"
LOGDIR.mkdir(parents=True, exist_ok=True)


def arm64_image(iid: str) -> str:
    return f"ghcr.io/epoch-research/swe-bench.eval.arm64.{iid}:latest"


def sh(argv, timeout=120):
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout)


def grade_one(instance: dict) -> dict:
    iid = instance["instance_id"]
    spec = make_test_spec(instance)  # swebench's own test spec (eval_script, F2P/P2P)
    cname = f"baseline-score-{iid}"
    idir = LOGDIR / iid
    idir.mkdir(parents=True, exist_ok=True)
    pred = PREDS[iid]
    rec = {"instance_id": iid, "resolved": False, "apply_failed": False,
           "error": None, "runtime_s": None}
    t0 = time.monotonic()
    try:
        sh(["docker", "rm", "-f", cname])
        sh(["docker", "run", "-d", "--name", cname, "-w", DOCKER_WORKDIR,
            arm64_image(iid), "sleep", "7200"], timeout=300)
        # apply the model patch, swebench's ladder
        with tempfile.NamedTemporaryFile("w", suffix=".diff", delete=False) as pf:
            pf.write(pred.get(KEY_PREDICTION) or "")
            patch_host = pf.name
        sh(["docker", "cp", patch_host, f"{cname}:{DOCKER_PATCH}"])
        applied = False
        apply_log = ""
        for cmd in GIT_APPLY_CMDS:
            r = sh(["docker", "exec", "-w", DOCKER_WORKDIR, "-u", DOCKER_USER, cname,
                    "bash", "-c", f"{cmd} {DOCKER_PATCH}"])
            apply_log += f"$ {cmd}\n{r.stdout}\n{r.stderr}\n"
            if r.returncode == 0:
                applied = True
                break
        (idir / "apply.log").write_text(apply_log)
        if not applied:
            rec["apply_failed"] = True  # bad patch → unresolved (official verdict path)
        # write + run the OFFICIAL eval script (ordered stream, as inspect/upstream do)
        (idir / "eval.sh").write_text(spec.eval_script)
        sh(["docker", "cp", str(idir / "eval.sh"), f"{cname}:/eval.sh"])
        sh(["docker", "exec", cname, "bash", "-c",
            "chmod +x /eval.sh; /bin/bash /eval.sh > /eval_output 2>&1"],
           timeout=EVAL_TIMEOUT_S)
        sh(["docker", "cp", f"{cname}:/eval_output", str(idir / "test_output.txt")])
        # OFFICIAL grader — swebench's own FAIL_TO_PASS/PASS_TO_PASS report
        prediction = {KEY_INSTANCE_ID: iid, KEY_PREDICTION: pred.get(KEY_PREDICTION) or "",
                      KEY_MODEL: pred.get(KEY_MODEL, "openai/deepseek-v4-pro")}
        report = get_eval_report(test_spec=spec, prediction=prediction,
                                 test_log_path=str(idir / "test_output.txt"),
                                 include_tests_status=True)
        (idir / "report.json").write_text(json.dumps(report, indent=2))
        rec["resolved"] = bool(report[iid]["resolved"])
        rec["report"] = report[iid]
    except subprocess.TimeoutExpired:
        rec["error"] = f"eval_timeout>{EVAL_TIMEOUT_S}s"
    except Exception as e:  # noqa: BLE001 — record, do not fabricate
        rec["error"] = f"{type(e).__name__}: {e}"
    finally:
        sh(["docker", "rm", "-f", cname])
        rec["runtime_s"] = round(time.monotonic() - t0, 1)
    return rec


def main():
    ds = load_dataset("princeton-nlp/SWE-Bench_Verified", split="test")
    by_id = {r["instance_id"]: r for r in ds if r["instance_id"] in IDS}
    assert set(by_id) == set(IDS), sorted(set(IDS) - set(by_id))
    results = {}
    with cf.ThreadPoolExecutor(max_workers=3) as ex:
        futs = {ex.submit(grade_one, by_id[i]): i for i in IDS}
        for fut in cf.as_completed(futs):
            r = fut.result()
            results[r["instance_id"]] = r
            print(f"{r['instance_id']:32s} resolved={r['resolved']} "
                  f"apply_failed={r['apply_failed']} err={r['error']} "
                  f"{r['runtime_s']}s", flush=True)
    out = RUN / "eval-report" / "arm64-official-results.json"
    resolved = sum(1 for r in results.values() if r["resolved"])
    summary = {"n": len(IDS), "resolved": resolved,
               "mean": resolved / len(IDS),
               "per_instance": {i: {k: results[i][k] for k in
                                    ("resolved", "apply_failed", "error", "runtime_s")}
                                for i in IDS}}
    out.write_text(json.dumps({"summary": summary, "results": results}, indent=2))
    print("\n=== SUMMARY ===")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
