---
type: reference
status: active
tags: [reference, prd]
---

# webassembly-agents — working state

Resume notes for the two parallel tracks. Read this first when picking
the project back up.

## Tracks

- **MVP track** (this session's owner): the agent eval surface — design
  in [[agent-repl-mvp]]. Currently in REPL-verification phase against
  the V0 CLJS pod (Node, not WASM yet).
- **Platform track**: the WASM-Tauri containment — design in
  [[platform]]. Owned by the platform agent.

## Where we are

### Spec status

[[agent-repl-mvp]] has a "Verified live in the V0 pod" section listing
what's been REPL-confirmed end-to-end. As of this checkpoint:

- ✅ rewrite-clj parses comments + forms as ordered nodes
- ✅ Bare-symbol unbound-var rejects loudly (via warning-handler set!)
- ✅ Unqualified core vars resolve (analyzer-cache load fixed)
- ✅ tx-meta = eval-id; eval entity AND tx entity coincide
- ✅ Topological replay = sort-by tx-id (no analyzer walk needed)

Dashboard has 10 open Ds (see [[agent-repl-mvp#decisions-pending]]).
Known Issues at the bottom of the spec list 6 implementation bugs that
need triage (KI-1 through KI-6).

### Next priorities for the MVP track

In rough order:

1. **D11 — per-agent ctx set on the agent record.** The agent record
   becomes the hub: `:seon.agent/ctx` is a cardinality-many vector
   of refs to that agent's `:seon.ctx` entities. Defaults point at
   `seon.agent/*` substrate fns; customization writes a fn in
   `seon.agent.<id>` and re-points refs. V1 has NO dynamic dispatch
   — symbols resolve at call time, period. V2 layers per-entity
   specificity dispatch on top.
2. **D5 — explicit remove-spec / remove-fn / remove-test** verbs.
   Small surface; high agent-utility; gates the "agent can curate
   without accumulating cruft" story.
3. **D4 — targeted test auto-run** wiring. Trigger on `:seon.fn`
   touches; query tests via `:seon.test/target`; stash full output
   via eval-id; surface failures as warnings. Verifies the whole
   reference-graph mechanic.
4. **D2 — per-kind redefinability rules**. Specs must be accretive
   when data exists; fns redefine freely; tests redefine freely.
   Implementation-only; spec already settled.
5. **D3 — `(def …)` detection via rewrite-clj AST**. No regex.
   Small, well-bounded.
6. **D7 — `<name>-example` test convention** + the "no-test-coverage"
   warning predicate.

Defer: D1 (older-DB upgrade), D8 (reference-graph attrs — confirm
shape once we actually populate refs), D9 (forgiving parse recovery —
edge case), D10 (bootstrap.edn emission — separate work item).

### Queued simplifications (not yet decisions)

Round-2 cuts I surfaced earlier in the session but haven't applied.
Each follows the same "use the primitive" pattern that paid off for
`:touches` → tx-meta:

- **Drop `:seon.test/last-passed-at` / `:last-failed-at` /
  `:last-failure`.** A test run IS an eval; tag the run's tx with
  `:seon.eval/test [:seon.test/sym "..."]` in tx-meta. "Latest pass/
  fail" becomes a history query. Three stored attrs collapse to zero.
- **Drop `:seon.fn/refs` extraction.** cljs.analyzer's compile-state
  ALREADY has the AST per `defn` with var references. We can query
  `(get-in @compile-state [:cljs.analyzer/namespaces ns :defs fn :body])`
  for free; no separate walk + storage needed.
- **Audit the Reversibility classifier table.** It existed to power a
  generic `undo`; we replaced that with explicit remove-* verbs. The
  classifier may now be dead code in the spec. Confirm or remove.

These should be promoted to D-decisions if they survive a closer look.

### Known Issues (need triage)

See [[agent-repl-mvp#known-issues]] for the full list. Quick summary:

- KI-1: `seon.db/transact!` invocation shape (wrong shape crashes Node).
- KI-2: `defonce !compile-state` holds pre-fix state across hot-reloads.
- KI-3: Eval error envelope is 4-levels-deep; promote useful keys.
- KI-4: Shadow watcher gets confused after ~3 Node restart cycles.
- KI-5: `start-agent!` and `dev-init!` race for `!compile-state`.
- KI-6: `ws` npm dep was missing for fresh checkout (now in package.json).

KI-1 may already be fixed by another agent's parallel work — check
when integrating.

## Cross-track touchpoints

The MVP and Platform tracks share infrastructure. Coordination points:

- **Eval surface contract.** [[agent-repl-mvp]]'s spec describes what
  `eval` returns; [[platform]] §"Eval surface" wires it into the WIT
  `eval-form` export. Changes to error envelope shape (KI-3) affect
  both.
- **tx-meta as eval-id pointer.** Verified in the V0 Node pod
  ([[agent-repl-mvp]]). Platform agent needs to confirm it still
  works under wasmtime + the WIT shim.
- **Analyzer-cache load.** V0 pod loads from `out/bootstrap/ana/`.
  Platform's WASM build needs the same caches packaged into the
  Component bundle (see [[research/m2-findings-2026-05-21]] for the
  bundle structure).

## Iteration surface

- Bring up the V0 pod: `clj -M:cljs watch client` (terminal 1) +
  `node out/client/main.js` (terminal 2). See
  [[../../seon/pod/REPL-WORKFLOW]].
- MCP tools: `mcp__seon_cljs__eval` for host-side eval (the
  substrate's `:client` runtime). `(seon.repl/dev-init!)` once per
  pod boot brings up `@!compile-state` and `@!conn`.
- WASM iteration: reserve for confidence runs. See
  [[research/m2-findings-2026-05-21]] §"Iteration cadence".

## Layout

```text
docs/prds/webassembly-agents/
├── STATUS.md           ← you are here
├── agent-repl-mvp.md   ← MVP track design
├── platform.md         ← Platform track design
└── research/
    ├── m2-findings-2026-05-21.md   (WASM landmines, owned by platform)
    ├── v0-state-2026-05-20.md      (V0 Node pod state snapshot)
    └── wasm-spike-2026-05-20.md    (earlier spike report)

```

The operational doc [[../../seon/pod/REPL-WORKFLOW]] stays under
`docs/seon/pod/` because it's substrate-wide (used by both tracks
and by anything else iterating against the V0 pod).
