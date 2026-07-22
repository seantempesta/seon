---
type: prd
status: active
tags: [prd, architecture, agent]
---

# W3c2 — repair sub-loop and symbol preflight on the JVM host

## Grounding preamble (mandatory)

Read the actual source of every file you touch and every interface you
connect to before editing — including `reference-code/sci` for fork/
analysis semantics. Report: (a) a better seam if found; (b) the owners'
exact terms. **Stopping early to report is FREE.** If source
contradicts this spec, stop and report.

Read FIRST, both: the grounding
`docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md`
§W3c (repair/preflight half) and the accepted host semantics in
`docs/prds/sci-execution-runtime/research/error-quality-u6-w3-design-2026-07-21.md`
(:365-:396 region — receipt-first, unresolved-preflight terminal).

## Goal

Host eval batches gain the child's two repair capabilities under the
ACCEPTED host semantics (deliberately NOT child parity — the grounding
documents the differences as design):

1. **Delimiter repair**: read failures route through the pure
   `seon.repair` best-effort repair (`repair.cljc:149` — already
   portable; inject the host's read predicate); repaired entries
   reparse and redispatch through the ordinary path; the repaired form
   is what renders (owner ruling: show the corrected behavior;
   `:seon.repair/fixes` provenance on the envelope).
2. **Symbol preflight**: budgeted detect → candidate
   (`repair/candidates.cljc:106-143` — threshold/ranking/unique-winner
   contract as-is) → compile-only trial on a DISPOSABLE fork of the
   retained agent context (sci/fork copies the env atom; analyze there
   and discard — the retained context must remain unmutated) → fix or
   hint. HOST SEMANTICS: preflight runs AFTER receipt (receipt-first),
   and an unresolved/ambiguous preflight is TERMINAL for that form —
   no evaluation (unlike the child, which runs the original and
   annotates). New band owner `src/seon/host/preflight.clj`; the batch
   hook in `host/eval.clj` stays thin.
3. **Repair policy/config**: the repair accessors are `.cljs`-only
   today (`config.cljs:1395`) — JVM parity is NOT GROUNDED. Thread the
   policy through the SAME per-invocation configuration acquisition
   W3a extended (host.sample's policy query) — no second config path,
   no new keys unless a genuinely missing dial surfaces (then stop and
   report).

## Falsifiers (grounding risks — bake into tests)

- Disposable analysis: analyze a defn on the fork, prove the symbol
  stays unresolved in the retained context.
- Accepted-semantics guard: receipt exists before preflight runs; zero
  eval-form! calls on ambiguous/fatal resolution.
- Repaired-path equivalence: a repaired malformed form matches its
  already-correct twin through receipts, tee, namespace transitions,
  and output.
- Policy table: absent config, :off, class-disabled, max-fixes, budget
  — one table test, host tier.

## Owned paths (touch nothing else)

- new `src/seon/host/preflight.clj`; `src/seon/host/eval.clj` (thin
  hooks); `src/seon/host/sample.clj` ONLY if the policy acquisition
  extension needs it (read W3a's landed shape first)
- writer tests (new host_preflight_writer_test.clj expected +
  conformance touches — enumerate)

Protected: everything else — `seon.repair*` (consume, don't fork),
`config.cljs` (pod accessors stay), the child (read-only reference),
`host/instrument.clj` (landed — coordinate only through the existing
hooks). A B11 lane owns `test/seon/dev/process_test.clj`. No commits,
no lifecycle ops.

## Gates

Full `bin/test-writer` (baseline 362/2717 — record after). Honest
summary of any falsifier you could not prove.
