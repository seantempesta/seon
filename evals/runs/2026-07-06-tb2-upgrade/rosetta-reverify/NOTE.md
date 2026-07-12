---
type: research
status: completed
tags: [research, agent]
---

# TB-2 oracle re-verify after Docker Desktop Rosetta enabled

Narrow re-verify: does enabling Docker Desktop Rosetta fix the qemu SIGSEGV that
made the Terminal Bench 2.0 (Harbor) oracle smoke on `fix-git` score reward-0?

## Verdict

**YES — Rosetta unblocks faithful TB-2 scoring on this host.** The `fix-git`
oracle smoke now scores **reward 1** (was **reward 0**). The pytest verifier ran
to completion under amd64 emulation with **no SIGSEGV**.

## Rosetta active? — YES

- `~/Library/Group Containers/group.com.docker/settings-store.json`:
  `"UseVirtualizationFrameworkRosetta": true` (also `settings.json`
  `"useVirtualizationFrameworkRosetta": true`).
- Docker Desktop 4.80.0 / Engine 29.6.1 (linux/arm64) running.
- amd64 smoke: `docker run --rm --platform linux/amd64 alexgshaw/fix-git:20251031`
  → `uname -m` = `x86_64`, `python3 -c 'print(1+1)'` = `2` (ran, no crash).
- Definitive engagement proof is the verifier itself (below): amd64-emulated
  pytest completing instead of segfaulting is exactly the qemu→Rosetta delta.

## Result — reward 0 → 1

Command (reused verbatim from the prior unit's README):

```bash
tmp/tb2-venv/bin/harbor run -d terminal-bench/terminal-bench-2 \
  -i terminal-bench/fix-git -a oracle -n 1 -k 1 -y -o tmp/tb2-jobs-rosetta
```

| Signal | Before (no Rosetta) | After (Rosetta on) |
|---|---|---|
| harness end-to-end | proven | proven |
| oracle solve | ran (git recovery+merge) | ran |
| verifier `test.sh` (apt/uv/pytest) | **SIGSEGV under qemu** | **ran to completion** |
| pytest | `qemu: uncaught target signal 11` | `2 passed in 0.06s` (py3.13, pytest-8.4.1) |
| **fix-git oracle reward** | **0** | **1** |
| trials / exceptions | 1 / — | 1 / 0 |

`result.json`: `oracle__terminal-bench/terminal-bench-2` mean **1.0**, 1
completed trial, 0 errored, reward `1.0` → `fix-git__p2ZfDb7`. Total runtime 11s.
Grep for `segmentation|signal 11|qemu|target signal` across the whole job dir →
none.

## One-liner

Rosetta ON turns the TB-2 verifier's amd64-emulated pytest from a qemu SIGSEGV
false-negative into a clean pass — the gold solution now scores the correct
oracle reward 1, so faithful TB-2 scoring is unblocked on this arm64 host.

## Evidence in this dir

- `rosetta-active-proof.md` — settings flag + docker version + amd64 smoke
- `result.json` — job result (mean 1.0)
- `verifier-reward.txt` — `1`
- `verifier-test-stdout.tail.txt` — pytest `2 passed`, no crash
- `harbor-console.log` — the run's console (Reward 1.0 / Count 1)

Full job: `tmp/tb2-jobs-rosetta/2026-07-06__19-28-46/` (not committed).
