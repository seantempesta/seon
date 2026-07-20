---
type: reference
status: active
tags: [prd, architecture]
---

# Source-cleanup chunk runbook

`roadmap.md` is the ledger: live bug table plus five dependency-ordered
stages. Work top stage only; one in-progress critical item at a time.

Rules of engagement:

- every fix strengthens the existing owner in place — no v2 names, no
  wrapper layers, no second logging/config/eval path;
- bug rows close only with a commit plus behavioral or live proof;
- stage 2 (pod retirement) is an atomic orchestrator-owned rename under a
  lane freeze — never run it concurrently with source-editing lanes;
- evidence lives in the six dated 2026-07-20 audit reports linked from the
  roadmap; do not re-audit before reading them;
- gates are the three existing suites plus the live proofs named per stage.
