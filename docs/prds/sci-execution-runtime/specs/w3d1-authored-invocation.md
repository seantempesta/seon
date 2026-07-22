---
type: prd
status: active
tags: [prd, architecture, agent]
---

# W3d1 — authored-function invocation on the JVM host tier

## Grounding preamble (mandatory)

Read the actual source of every file you touch and every interface you
connect to before editing — including `reference-code/sci` for
fork/with-ctx/resolve semantics. Report: (a) a better seam if found;
(b) the owners' exact terms. **Stopping early to report is FREE.** If
source contradicts this spec, stop and report.

Read FIRST:
`docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md`
§W3d — the interface ledger, the premise correction (the child does NOT
invoke through the U2 registry: it prepares a pinned source-digest
identity, acquires the program at one immutable database value, loads,
resolves, applies — execution.cljs:571-781), and the seven ranked
risks. This unit is risks 1/2/5/6; the cross-agent live-require gate
(risks 3/4) is W3d2 — OUT of this unit's scope.

## Goal

An authored-function invocation request served by the JVM host behaves
like the child's, composed from tonight's landed mechanisms:

1. **Pinned identity**: look up the function's source at the
   invocation's `:seon.db/db`, verify the request's source digest
   (reject absent/mismatched exactly as the child does,
   execution.cljs:638) — the request's pinned identity WINS over any
   newer mutable registry root (grounding risk 1: prepare at database
   A, redefine at B, invoke the A request → the A result, while
   retained/shared roots remain B).
2. **Version-correct materialization without mutation**: execute the
   pinned source in a DISPOSABLE fork of the agent's retained context
   when the pinned version differs from the live root (risk 2: pinned
   replay must never mutate a shared registry var or another context —
   sci/fork copies the env atom but preserves referenced objects;
   prove isolation). When the live var's identity already matches the
   pinned digest, invoke it directly (no pointless replay) — under
   `sci.ctx-store/with-ctx` of the originating context either way (the
   nursery test path idiom, graduate.clj:190). Both nursery
   (interpreted) and graduated (JVM root) tiers must work through the
   one path (risk 6), including an interpreted fn that resolves
   another SCI var.
3. **Composition**: the invocation runs under W3c1's run fence, W3b's
   instrumentation (a wrong-args authored call fails structurally),
   and the existing result bounding + settle! terminal — no new
   containment.
4. **Replace the refusal**: the `:core-bug` refusal for source-digest
   identities (host/invoke.clj:84) becomes the real invocation path;
   the refusal remains ONLY for shapes that are genuinely unservable
   (report what remains and why).
5. **Pod routing**: `execution/host.cljs:858-868` forces authored
   calls to the child lane; route them to the host session when the
   agent's host coordinate exists, exactly as eval batches already
   select (risk 5: a host-tier authored call proves JVM socket
   ownership and NO Bun child allocation). Keep the child path intact
   for agents without a host coordinate — this is routing, not child
   deletion (W5 owns that).
6. **Private-function policy** (risk 7, NOT GROUNDED in the child):
   match the child's observable behavior exactly — probe what the
   child DOES with a qualified private authored fn and mirror it;
   report the finding.

## Owned paths (touch nothing else)

- `src/seon/host/invoke.clj`, `src/seon/host/context.clj` (pinned
  lookup/materialization seams), `src/seon/host/eval.clj` ONLY if the
  batch/invoke seam genuinely requires it (read first)
- `src/seon/execution/host.cljs` (the routing branch only)
- writer tests (new host_authored_invocation_writer_test.clj expected)
  + the pod routing test that owns dispatch selection (enumerate)

Protected: everything else — `host/graduate.clj`, `host/record.clj`,
`host/instrument.clj` (compose through their public surfaces; if one
lacks a needed seam, stop and report), the child (read-only reference),
`seon.execution.cljs` contract vocabulary. No commits, no lifecycle
ops.

## Gates

Full `bin/test-writer` (baseline 368/2752) AND full `bin/test-cljs`
(baseline 1502/7246 — the routing edit). The end-to-end live authored
call through a real agent rides the coordinated checkpoint — note
pending, don't run it.
