---
type: prd
status: active
tags: [prd, agent, flow, schema, database]
---

# CLJS-finish + CLJ-pivot plan (2026-06-09)

## Decision

The V0 CLJS pod proved the agent loop end-to-end (read a doc → register hard
nested schemas → store data — verified 2026-06-08). But single-process Node is a
poor host for heavy compute and a central store, and the **CLJ side already has
mature infra** we built: JVM datahike on-disk LMDB + the flow, code-graph indexing
(`graph/ingest`, `graph/query`), the richer Malli-walk compliance checker
(`seon.dev.compliance`), runtime instrumentation. So (user, 2026-06-09): **finish
the CLJS side to its best achievable state, then PIVOT the store + heavy analysis
to a centralized CLJ subsystem the pod talks to.** This is the provisional pivot
the architecture pre-authorized (memory `project_provisional_choices`) —
transition, don't keep bending CLJS to do what it wasn't designed for.

## Principles carried in from this design conversation (user)

- **Keep per-form emissions** (don't disable) — but make them CONCISE + VERY CLEAR.
- **Eval results stay per-eval, REPL-style** — even across many forms in one turn it
  reads like a real REPL: `(+ 1 1) ; 2` then the next form, one result per eval.
- **Warnings are where we EXPAND** — the rich teaching surface. CLUSTER by kind: ONE
  complete explanation + how-to-fix example per cluster, then "Affecting: a, b, c (N).
  Please correct before moving on." NEVER repeat the same explanation per-fn.
- **Warnings are SPECIFIC, never "one of these things."** Name exactly what is wrong
  and WHERE, with a concise concrete example. NOT "fn foo has an incomplete spec
  (could be :any/:maybe/missing)" — instead "fn foo's RETURN is `:any`" or "fn foo's
  arg `:bar` is `:any`". So each defect is its OWN check/kind (return-is-any,
  arg-is-any, uses-maybe, no-return-spec, no-malli-schema, …), and the cluster names
  the precise issue + a targeted fix example.
- **Warnings are COMPOSITIONAL** — each check is a separate, independently-testable
  function; `warnings-section` composes a registry of them.
- **Optional namespace scope** — a check takes an optional ns-scope arg; default to
  the CURRENT agent's ns so an agent isn't confused by warnings about other
  namespaces. Unscoped = whole-substrate overview.
- Warnings render at context-assembly (start of next turn), so they're naturally
  WHOLE-TURN and self-healing — a fn + its following-form test are both in the
  corpus by the time warnings render. No per-form "missing test" false alarm.

## Track A — finish the CLJS side (NOW)

### A1. Persist the pod DB — file/LMDB-backed, gitignored session dirs

- Flip the pod conn from `:backend :memory` (`client.cljs:184/210/374`) to a
  persistent datahike backend. File or LMDB (`reference-code/konserve-lmdb`) —
  whichever is easier; it's temporary (the CLJ central store supersedes it).
- Store under a gitignored base `data/seon-pod/<run-id>/` (one dir per pod-run /
  agent-conn = a "session directory"); persisted, NOT wiped, so past runs are
  reviewable. Add `data/seon-pod/` to `.gitignore` (do NOT fill the repo with DB
  files). Use a human-readable run-id (timestamp-based), not a bare random uuid.
- Ground in `reference-code/datahike` store config + `konserve-lmdb`.
- Verify: data survives `bin/seon restart pod` (a stored entity is still queryable),
  or at minimum the prior run's dir persists and is openable. Fixes "only one run".
- NOTE: the conn is per-pod (one shared conn), NOT per-`:seon.session`. True
  per-session logical partitioning → pinned to the CLJ central store (B1).

### A2. Compositional, clustered warnings

- Each check = a separate fn, e.g. `(check-<kind> {:seon.db/db db :seon.warn/ns <ns|nil>})
  → {:seon.warn/kind k :seon.warn/affected [{:sym s :where "return"} …]
     :seon.warn/explain <rich str> :seon.warn/example <concise fix>}` (nil/empty
  when clean). Independently unit-tested. The `:affected` entries carry the SPECIFIC
  location (which arg / the return) so the cluster can say exactly where.
- `warnings-section` composes a registry of checks and renders CLUSTERED per kind:
  the precise explanation + ONE targeted fix example, then the affected list (with
  locations) + "Please correct before moving on."
- ns-scope optional; default = current agent's ns.
- CLJS-doable checks (derive from the `:seon.fn`/`:seon.test`/`:seon.schema` corpus,
  which already carries `:seon.fn/spec` etc.). Each is SPECIFIC — names the exact
  defect + location, not a generic "incomplete":
  - `no-malli-schema` — public fn with no `:malli/schema` at all.
  - `return-is-any` — the fn's RETURN spec is `:any` (says so; "type the return, e.g.
    `::foo-response`"). (`:any` is allowed only at third-party boundaries.)
  - `arg-is-any` — a specific ARG is `:any` (names the arg).
  - `uses-maybe` — schema uses `[:maybe X]` (use `{:optional true}` / a concrete type).
  - `no-return-spec` / `no-input-spec` — the `:=>`/`:function` is missing its output
    or input.
  - `missing-test` — `:seon.fn` with no associated `:seon.test`.
  - `bad-ref` — a lookup-ref whose target attr isn't registered or lacks identity
    (names the attr + the real fix), where detectable in CLJS.
  - NO `missing-identity` check — identity is OPTIONAL. Entities do not need a natural
    key (bulk data, value-ish rows, and component children legitimately have none).
    Don't warn on it; don't force it.
- Keep the existing `failed-evals` warning; fold into the same clustered renderer.

### A3. Per-form emissions — concise + clear (keep)

- Keep per-form step emissions. Tighten the legible `:seon.eval/error` (T12) so each
  is short + unambiguous. Preserve REPL-style per-eval result display in the transcript.

### A4. Clear ref/schema feedback + envelope contract

- Translate the cryptic datahike ref error ("Lookup ref attribute should be marked
  as :db/unique") into a guiding one (target needs `:seon.db/identity`; or the
  referenced entity doesn't exist — transact it first / use a tempid).
- Validation failures return the `{:seon.db/ok? false :seon.db/error …}` envelope
  consistently (currently they REJECT the promise past transact!'s sync try).
- Teach "errors are values — `(result :id)` holds the full error data; inspect + adapt."

## Track B — the CLJ pivot (mostly ALREADY BUILT — see scoping)

**Scoping 2026-06-09 (`clj-pivot-scoping-2026-06-09.md`) found Track B is far closer
than this section assumed — most of it EXISTS and WORKS:**

- **B1. Central store + wire — DONE + RUNNING.** The `wire-server` process (pid
  51224) is a file-backed on-disk JVM datahike conn answering
  ping/q/transact/pull/schema/entity-pull/batch over a Unix-domain socket
  (Transit-JSON), verified live (register→transact→query roundtrip vs
  `data/clusters/default/store`). The CLJS guest client mirroring `datahike.api`
  (`seon.client-runtime.db`) + Transit codec + multi-DB registry (agent-id→db-name)
  are all written. THIS is the pivot's central store; the main JVM `:seon.db/flow`
  is `:memory`/empty and is NOT it.
- **B2. Heavy analysis — DONE, JVM-resident.** `graph/analyzer`+`scanner`+`ingest`+
  `query` and `dev/compliance` (Malli-walk) work in-process; expose over the wire.
- **THE ONE MISSING PIECE — a plain-Node UDS transport.** The guest client routes
  through WASM-host (WIT) imports; there is no `node:net` UDS client, and the V0 pod
  is plain Node embedding datahike-cljs in-process. `wit.cljs` has a `js/require`
  fallback branch to hook.
- **B-FIRST-SLICE:** route ONE pod op (`transact!` then `q`) to the running
  wire-server over a Node UDS socket, single ambient conn (the back-compat path that
  already works). Oracle: same entity readable from the pod AND the wire-server REPL.
  This is the highest-leverage next step and the ONLY genuinely new code.
- **B3. Specific checks as a wire op.** The A2 checks (return-is-any/arg-is-any/
  uses-maybe/…) are a location-aware enhancement of `compliance.clj`'s existing
  parsed-schema walk (one fn, NOT a v2), exposed as a `handle-op` returning the
  clustered-warning shape the pod renders. So A2 (CLJS) is the stopgap; this is the
  real home.
- **B4. Session-browser UI** reading the central store (sessions → turns →
  prompt-text + messages + evals/errors). Blocked by: reactive engine is built but
  UNWIRED into the wire-server (no subscribe ops / `::reactive` hook), which the
  guest `listen!` loop already targets — wire it up.

Persistent-backend gotchas (from A1 + scoping) the central store must honor: mkdir
the base, branch on `database-exists?` (no create-or-connect), stable RFC-UUID `:id`.

## Sequence

A1 (persist) **SHIPPED (eb7a500)** → A2 (warnings framework + specific CLJS-doable
checks) → A3/A4 (emissions + feedback). The B pivot is now scoped + tractable: its
high-leverage first slice (Node UDS transport → route one pod op to the running
wire-server) can run in PARALLEL with A2/A3/A4 once the live-validation run frees
the pod, since most of B already exists.
Each step: implement (`seon-agent`) → seon-verifier → commit. The live pod is the
oracle. PRD `v2-context-render-prd-2026-06-08.md` remains the demo source of truth;
this doc owns the finish-CLJS/pivot-CLJ plan.
