---
type: prd
status: active
tags: [prd, architecture, agent]
---

# W3b — instrumentation over SCI vars on the JVM host

## Grounding preamble (mandatory)

Read the actual source of every file you touch and every interface you
connect to before editing — including `reference-code/sci/` and
`reference-code/malli/` for the seams named below. Report: (a) a better
seam if found; (b) the owners' exact terms. **Stopping early to report
is FREE.** If source contradicts this spec, stop and report.

Read FIRST:
`docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md`
§W3b — the complete interface ledger. Its corrections are binding:
use `m/-instrument` (malli/core.cljc:3110) — NEVER one manual
`m/-function-info` call (nil for multi-arity `:function` schemas);
W3b must cover BOTH shared registry vars and live context-private vars
(agent defs replay into private forks; only registry vars are linked).

## Goal

Wrong calls to specced functions fail with the structured
`error.instrument` envelope on the host tier, exactly as they do in the
pod — reconciled from the same committed projection, surviving hot
redefinition.

1. **New `seon.host.instrument`** (src/seon/host/instrument.clj): wrap
   target SCI vars with `m/-instrument` (contract + projection
   registry + the existing `error/instrument.cljc` report-fn),
   preserving original roots + a projection fingerprint in metadata;
   install via `sci/alter-var-root` (the public privileged seam,
   sci/core.cljc:249); a guarded `bindRoot` watch (sci/lang.cljc:97)
   rewraps after defn/registry upgrade/graduation and UNWRAPS when a
   contract is removed (grounding risk 5). Maintain an apply ledger
   (what is wrapped, at which fingerprint).
2. **Cold hook**: `seon.host/start!` applies instrumentation AFTER
   `graduate/rebuild!` (host.clj:210-223 — projection acquisition
   precedes corpus registry vars).
3. **Hot hook**: `seon.host.eval` reconciles synchronously after a
   successful committed-projection refresh, before advancing the batch
   (host/eval.clj — the refresh site the grounding cites at :284,
   re-derive current lines).
4. **Var discovery**: resolve targets through the registry AND the
   evaluating agent's own context (private fork vars) — until W3d makes
   authored functions shared, both populations must be covered; the
   grounding's risk-2 falsifier (define specced fn in a batch, invalid
   call next form AND next batch fails structurally) is the acceptance
   test for the private population.
5. **Envelope portability**: `error/instrument.cljc`'s coercion hints
   are JS-specific (js/parseInt, js/Date at :158) — make them
   reader-conditional/portable WITHOUT changing pod behavior (pod
   gates must stay green). The envelope itself works in-process
   (host_error_sci_writer_test.clj:116 proves preservation + SCI
   class).
6. **Wire safety**: grounding risk 3 — leaf error maps can carry live
   Malli schema objects and the UDS codec has no unknown-object
   handler. The envelope that reaches a FRAME must be wire-safe: prove
   it (encode/decode a classified bad-input envelope through the uds
   codec in a test). If wire-safening requires bounding/serializing at
   the existing wire-safe-value seam, strengthen THAT seam — no new
   sanitizer path.

## Falsifiers (bake into tests — grounding risks 1-5)

- Multi-arity: a two-arity specced SCI fn — valid 0/1-arity calls pass;
  an invalid call fails structurally per arity.
- New private corpus var: risk-2 as above.
- Wire round-trip: risk-3 as above.
- Publish/apply barrier: two sessions racing projection refresh see
  either old-projection-old-wrappers or new-new, never mixed (risk 4).
- Contract removal: specced → schema removed → redefined → old-contract
  call is UNinstrumented (risk 5).

## Owned paths (touch nothing else)

- new `src/seon/host/instrument.clj`; `src/seon/host.clj` (cold hook);
  `src/seon/host/eval.clj` (hot hook); `src/seon/host/context.clj`
  ONLY if the apply/reconcile threading requires it (read first);
  `src/seon/error/instrument.cljc` (portable coercion hints only)
- writer tests (a new host_instrument_writer_test.clj is expected) +
  any pod test touched by the cljc edit (enumerate)

Protected: everything else — `seon.instrument.cljc` (the pod
implementation is the SEMANTIC reference, not a shared implementation;
do not promote it in this unit), graduate.clj, record.clj. A read-only
investigation lane is active; no lifecycle ops, no commits.

## Gates

Full `bin/test-writer` (baseline 356/2686 — record after) AND full
`bin/test-cljs` (the cljc edit; baseline 1501/7244). Honest summary of
anything the falsifiers could not prove.
