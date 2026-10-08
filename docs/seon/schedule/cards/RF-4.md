---
title: "Card RF-4 The edit hook sends changed forms to the lane's branch"
type: reference
status: active
created: 2026-10-08
---

# RF-4 The edit hook is the lane's REPL entrance

Design: `docs/research/agent-platform/2026-10-08/repl-first/repl-first.md` §4. Lane: Sonnet. Depends on RF-2 (its
gate consumes the evidence) and F4a (the automatic test step).

`bin/seon-hook` already reaches the live root through `seon.operator/live-root-value!` (schema admission at
`bin/seon-hook:500`, contract compilation at `:608`) and degrades to `:unavailable` with a reason.

## Functions

- ADD to `bin/seon-hook` one step after the clj-kondo pass for a `.clj` under `src/` or `test/`:
  1. Lane: `SEON_LANE`, else the lane whose `bin/lane status` claim holds the path, else skip ("no lane: lint only").
  2. Forms: the file's top-level forms (clj-kondo analysis the hook already has: var definitions with spans) whose
     text differs from the branch's recorded source for that identity; one prepl call asks the root for the
     branch's sources by identity (`my.program/source` semantics).
  3. Host-bound ones (RF-3) are not sent: print "host-bound: throwaway check at commit".
  4. Send `(in-ns 'ns)` plus those forms as one `seon.mcp/call` `eval_clj` request with `agent` = the lane; print
     the reply text as the value printer made it.
  5. No live root, or the root answers not ready: "default unavailable: lint only".

## Proof

`seon.dev.hook-test/a-clojure-edit-evaluates-its-changed-defn-on-the-lane-branch` (new): with a live test root, an
edit to one defn sends exactly that form and the reply carries its Var render; with no root the hook answers lint
only with the reason.

## Cost

Measured from Babashka 2026-10-08: prepl round trip 6-200 ms; a one-function defn evaluates in 6 ms. Target under
1 s for a one-function edit when no reaching test executes.
