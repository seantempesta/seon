---
type: issue
status: open
severity: friction
tags: [issue, cljs, research]
---

# LoRA audit runner depends on a retired Shadow target and pinned checkout

## Problem

The LoRA data-audit runbook is bound to an isolated `:lora-audit` Shadow build,
scratch source outside the normal CLJS classpath, and a hard-coded
`/Users/sean/src/seon-pin` checkout. It cannot be reproduced from the current
checkout through the supported test/data-quality boundaries.

Autocomplete training remains paused; this runner is evidence to preserve and
migrate, not a reason to revive the pinned runtime or add another test path.

## Evidence

- `shadow-cljs.edn` still declares `:lora-audit`, whose namespace lives under
  `src-needle/audit/` rather than the ordinary test source roots.
- `docs/prds/repl-autosuggest/research/lora-data-audit-2026-07-12.md` instructs
  operators to copy that test into `/Users/sean/src/seon-pin/test/seon/`, run
  `clojure -M:cljs compile lora-audit` from the pin, then invoke
  `node out/lora-audit/test.js` with scratch manifest/output variables.
- The referenced pin is commit `93c8d8ad`, while the current runtime and eval
  contracts have moved on. The fair scorer also names that pin as its staged-
  world execution authority.
- The current Inspect integration report preserves the audit's 149/557
  hard-failure result but directs rebuilding it in the canonical data-quality
  pipeline rather than importing the scratch harness.

## Owner

The canonical `src-inspect-ai` data-quality path owns replay and classification;
the standard `bin/test-cljs` boundary owns CLJS correctness tests. The
repl-autosuggest lane owns preserving the old audit inputs/results until that
migration is verified.

## Acceptance

- The useful audit fixtures, classifications, and expected 149/557 historical
  result are represented as immutable inputs/evidence in the canonical
  data-quality path, with current-runtime replay tests for the important
  failure classes.
- Reproduction starts from the current checkout and consumes the versioned
  database-derived autocomplete artifact. No file copy, absolute checkout
  path, retired pin, private classpath mutation, or direct `out/lora-audit`
  invocation is required.
- Narrow CLJS correctness belongs to the existing test runner; trajectory
  replay and scoring belong to Inspect. No third evaluator or audit runner is
  introduced.
- After equivalent evidence is verified, `:lora-audit`, its stale runbook
  commands, and any now-unreferenced scratch harness are removed in the same
  unit. The ordinary CLJS suite and canonical data-quality acceptance cases
  pass.
