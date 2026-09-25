---
type: issue
status: open
severity: performance
created: 2026-09-25
tags: [issue, sci, evaluation, allocation, one-repl-path]
---

# A trivial SCI evaluation allocates half a gigabyte

Measured by lane delete-mcp-bridge-opus on default (pid 37460, 2026-09-25), SCI mode on a fresh branch through the
agent evaluation path:

| Form | Allocated | Time |
|---|---:|---:|
| `(lane-mcp-probe 1)` (one-arg call) | 515,823,848 B | 199 ms |
| `(defn lane-mcp-probe {:malli/schema …} [x] (+ x 41))` | 653,931,880 B | — |
| `(vec (range 300))` | 457 MB | 205 ms |
| first evaluation on a fresh branch | — | 789 ms (later 24 ms) |

Allocation is ~10⁴× the value. Suspects to probe (not verified): per-evaluation projection/registry work, call-preparation
snapshot derivation, program-change detection (`call-detecting-program-change`, sci/eval.clj), read-evidence digest walks,
rendering the shown result. Fix at the owner that allocates; the one pipeline design (S2) must keep per-evaluation work
proportional to the form. Evidence: docs/prds/agent-platform/landing/lane-delete-mcp-bridge-2026-09-25.md.
