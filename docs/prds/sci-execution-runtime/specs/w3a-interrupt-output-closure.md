---
type: prd
status: active
tags: [prd, architecture, agent]
---

# W3a — interrupt/output closure on the JVM host

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. Report in your summary:
(a) a better seam if you find one; (b) the existing owners' exact terms.
**Stopping early to report is FREE.** If source contradicts this spec,
stop and report.

Read FIRST — the complete interface ledger with file:line for every
claim here:
`docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md`
§W3a. Typed interrupt classification already LANDED (structural, no
regexes in host scope) — this unit is the small closure it identified.

## Goal (three fixes + the proofs that pin the baseline)

1. **Late-interrupt metadata fix**: `finish-evaluation!`'s
   late-interrupt fallback (`host/eval.clj:95`) synthesizes `:interrupt`
   WITHOUT `:seon.error/kind :timeout`, while the ordinary marker path
   preserves it (`error/sci.clj:125`). Make the fallback carry the same
   timeout identity. Extend the forced post-return interrupt test
   (`host_conformance_writer_test.clj:518`) to assert both `:interrupt`
   and `:timeout`.
2. **Output-cap policy convergence**: the host's capture/persistence
   bound is a hard-coded 2,048-token cap (`host/eval.clj:17`,
   `host/record.clj:336`) while the child uses the configurable
   `database-edn-cap` (`config.cljs:1153`) — a duplicate-limit bug of
   exactly the q16 class. Move the host bound to the common
   configuration authority: a named config key/accessor threaded to the
   host (follow how the host already receives configuration-derived
   values — read `host/context.clj`'s existing acquisition; if the host
   genuinely has no path for this value today, STOP AND REPORT with
   options rather than inventing a side channel). PRESERVE the
   streaming-truncation behavior (the cap applies during capture, not
   after accumulation) — that discipline is the host's strength; do not
   copy the child's unbounded `swap! … str` accumulation.
3. **Concurrent-session output-bleed proof**: current attribution proof
   is sequential only. Add the two-concurrent-sessions test with
   distinct sentinels, asserting each eval row carries only its own
   output (grounding risk 3).

Regression guard: the print-flood containment test
(`host_hostile_battery_writer_test.clj:384`) must stay green, extended
to require a sibling session succeeds immediately after the flood.

NOT in scope: the diffusion worker's message-regex fallback (quarantined
tree), child read-error prose parsing (not interrupt classification),
embed retry classification (outside W3), W3b/c/d surfaces.

## Owned paths (touch nothing else)

- `src/seon/host/eval.clj`, `src/seon/host/record.clj` (the cap sites)
- the config key/accessor addition in the config authority (additive
  only — another lane owns `runtime/admission.cljs`; if your config
  edit would touch anything beyond adding one key family + accessor,
  stop and report)
- `src/seon/host/context.clj` ONLY if the existing config-threading
  path runs through it (read first)
- `test/seon/host_conformance_writer_test.clj`,
  `test/seon/host_hostile_battery_writer_test.clj`

Protected: everything else. No `bin/seon` lifecycle ops (other lanes
active; the default cluster stays up). No commits.

## Gates (run them; report honest results)

- Full `bin/test-writer` (baseline was 353/2652 + NS-0.5d-era counts —
  record before/after).
- The three new/extended tests above, individually named in the
  summary with their assertions.
- If the config key lands, `bin/seon test operator` once (the envelope/
  resolve tests may assert key sets).
