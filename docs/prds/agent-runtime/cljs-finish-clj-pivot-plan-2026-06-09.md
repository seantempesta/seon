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

Current ordered plan lives in **"RESUME HERE → Next steps"** below (it supersedes
this section as work lands). Track B's first slice (Node UDS transport) parallelizes
with Track A since most of B already exists. Methodology: implement (`seon-agent`) →
seon-verifier → commit; the live pod is the oracle.
`v2-context-render-prd-2026-06-08.md` is the demo source of truth; this doc owns the
finish-CLJS/pivot-CLJ plan.

---

## RESUME HERE — fresh-context handoff (2026-06-09)

**THIS doc is the source of truth.** Methodology: implement-agent → seon-verifier →
commit only when verified (`git add <specific files>`, never `-A`); the live pod is
the oracle; commit working code incrementally. Relevant memories:
`project_soul_and_two_track`, `feedback_prds_are_source_of_truth`,
`feedback_specific_actionable_feedback`, `feedback_agents_read_core_source`.

### Live system state

- **Pod** (`:client`, Node) PERSISTS to disk now (A1): konserve `:file`,
  `data/seon-pod/<run-id>/`. Restart via `bin/seon restart pod`.
- **Dev JVM** raised to `-Xmx4g` + heap-dump-on-OOM (was OOMing the in-process
  clj-kondo lint at 2g/99.9%). `bin/seon restart jvm`. Healthy (244/4096 used).
- **wire-server** (separate process, pid was 51224) = the CLJ central store
  (file-backed datahike over UDS/Transit at `data/clusters/default/store`) — Track 2's
  target. Verified working in scoping.
- **deepseek model** = `deepseek-v4-pro` (per user; if API rejects, a live call surfaces it).

### Gotchas (cost real time)

- MCP isolated JVM sessions are BROKEN (`mcp__seon__eval` clone fails) — the code
  references the retired `seon.orchestrator.session`. Use session `"orchestrator"`.
- The CLJS MCP `"default"` session wedges (`Compiler.currentNS()` null) and survives
  pod restart — use `mcp__seon_cljs__create_session` for a fresh sid.
- Dev-JVM Integrant logs `No such namespace: seon.flow` / `:seon.flow/pool` at boot
  (dangling retired-ns refs; system starts anyway) — see the surgical-fix doc below.

### Status (live)

- **ALL open agents DONE + committed; working tree clean.** (a) **second-boot fix** —
  `index-substrate!` idempotent across boots; a 2nd `start-agent-with-deepseek!`
  boots clean → **fresh-agent-same-conn works, two-scenario test UNBLOCKED**; live in
  the pod. (b) **flow/pool fix** — 4 dead keys removed from `system.edn`, **ACTIVE
  after the dev-JVM restart**. (c) `-Xmx` 2g→4g + heap-dump-on-OOM; runaway old JVM
  killed. (d) **Track 2** — thin Node UDS transport (`:wire-node` build, outside
  `:client`) + both sync layers wired into the wire-server (raw tx feed + reactive
  query-subscriptions), verified live; the `:client` db-path integration is the
  deferred handoff.
- **Instrumentation is now ON.** Post-restart the running system has 8 keys incl.
  `:seon.dev/instrumentation` + `:seon.db.schema/consistency-check` + `:seon.web/caddy`
  (Phase 2 had been aborting on the flow error, so these were OFF). Turned on with NO
  surfaced schema errors — existing fn schemas are consistent.
- **SOUL-pull correction DONE** (done the SIMPLE way — compile-time bake, NOT runtime
  pull): `src/seon/soul.clj` `(defmacro soul-md [] (slurp "SOUL.md"))`;
  `deepseek/default-system-prompt` = `(str (soul-md) "\n\n" <operational layer>)`.
  `SOUL.md` is the single source; the inline copied prose is gone. Caveat: editing
  `SOUL.md` needs a forced recompile (shadow doesn't track it as a dep).

### Next steps (ordered)

1. **Drive the two-scenario test** (same persistent DB, fresh-context agents,
   back-to-back): (1) first work question → WORK-DIRECTED schema design + store (no
   index step); (2) similar question → fresh agent SEES + reuses stored schemas + fns
   (schema-catalog + functions-catalog). Observe COMPACTLY; append to
   `research/e2e-demo-findings-2026-06-08.md`.
2. Track 2 `:client` integration handoff (route the pod db path through the Node UDS
   transport); then A2 warnings (specific/clustered/compositional/ns-scoped).

### Standing principles (don't relearn)

- **SOUL.md = the agent's identity, PULLED not copied** (single source).
- **Work-directed**: model from the human's question, no "store whatever" index step.
- **Identity is OPTIONAL** on entities — never force/warn a natural key.
- **Feedback is SPECIFIC** (exact defect + location + concise example; cluster by kind).
- **Thin Node wrapper, NOT WASM** (swappable later).
- Two tracks by build: Track 1 = pod `:client`; Track 2 = JVM `seon.server.*` +
  guest/transport — keep concurrent edits on DIFFERENT builds.
