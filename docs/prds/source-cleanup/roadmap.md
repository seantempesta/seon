---
type: prd
status: active
tags: [prd, architecture, database, agent, web]
---

# Source cleanup and vocabulary unification roadmap

This is the index of the source-cleanup PRD collection. Each domain PRD lays
out its problems, recommended solutions, acceptance, and the open owner
questions:

- [[async-facade]] — finish the async `seon.db` migration (B3-B5)
- [[config-through-aero]] — every knob through aero into database facts
- [[logging-unification]] — one line shape, agent-readable faults
- [[vocabulary-unification]] — pod retirement + remaining term rulings
- [[deletions-and-wiring]] — orphans: wire `ctx.usage`, delete the rest
- [[data-browser]] — one schema-aware rendering mechanism for every value

## Outcome

Finish the runtime-reliability refactor's deletion promise across the working
tree: every remaining synchronous consumer of the asynchronous `seon.db`
facade fixed in place, one logging surface per process, one config/default
owner per fact, the retired "pod" vocabulary gone from active source and
living docs, and dead namespaces deleted. No stage adds a mechanism; every
stage removes or unifies one, except the universal browser's required bounded
child-sampling protocol operation, which extends the existing execution IPC
rather than creating another value transport.

Evidence base (all dated 2026-07-20, committed):

- [[../database-authority-mesh/research/cleanup-audit-jvm-residue-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-duplicate-interfaces-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-logging-errors-2026-07-20]]
- [[../database-authority-mesh/research/cleanup-audit-vocabulary-2026-07-20]]
- [[../runtime-reliability/research/cleanup-audit-config-startup-2026-07-20]]
- [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]
- [[research/fresh-source-cleanup-gaps-2026-07-20]]

## Live bug ledger

Open correctness defects, ordered by risk. A bug leaves this table only with
a commit plus behavioral or live proof; intermittents leave only after a
clean loop of the owning gate.

| # | Bug | Owner file(s) | State |
|---|---|---|---|
| B1 | `later-run?` booleans a Promise (always true); whole ns reads async facade synchronously | `src/seon/runtime/recovery.cljs` | **CLOSED** `a2b0c815`: ns fully async, regression tests, live `.then` proof |
| B2 | Agent-loop failure reports bypass `seon.log`; `seon.log/tail` blind to loop faults | `src/seon/agent/loop.cljs` + report list | **CLOSED** `2cbd1892`+`b109266e`: 29 sites routed; events-log emission proven; full suite 1284/5817 green |
| B3 | `eval.cljs` "record-eval! tx FAILED" may print without persisting a fault datom (contract check in flight); plus 7 sync-read clusters | `src/seon/eval.cljs` | verify in B2 lane; sync reads stage 1 |
| B4 | `seon.warn` repair guidance names removed `seon.db/*conn*`; ~15 sync facade reads across check registry | `src/seon/warn.cljs:1064` | issue filed; stage 1 |
| B5 | Remaining sync facade reads: `render.cljs:684`, `agent/testrun.cljs:192,205`, `agent/web/internal.cljs:528-536`, `handlers/message.cljs:43`, `my/skills.cljs:324-331`, `my/canvas.cljs:149-153` | listed files | stage 1 |
| B6 | Stray repo-root `locks/` from `cli_test` fixture running real `state/with-lock` with nil process-dir | `script/seon/dev/state.clj`, `test/seon/dev/cli_test.clj` | **CLOSED** `3d4aee61` + `a850b343` (relative env coordinates absolutized after the guard exposed `bin/acme`) |
| B7 | MCP/dev CLJS REPL cannot use `await`/`^:async`; Promises returned unresolved | `bin/mcp-server-cljs` path | **CLOSED** `8116ba1c`: transport bridge mirrors agent auto-await; five-point live proof; MCP clients must restart |
| B10 | Default client crash-loops on reload rehost: `:seon.runtime.admission/status :publishing` -> `on-core-error :crash`; required a default cluster reset on 2026-07-20 | `seon.runtime.admission` / reload path | **CLOSED** `1098c061`: publication-scoped mark-unavailable + refusal-value prepare; reproduced twice pre-fix, reload-storm survived post-fix; issue archived |
| B12 | `seon.warn/check-record-errors` and `ctx/warnings.cljs:86` read `:seon.eval/record-error`, an attribute whose registration was deliberately deleted (`346e70fa`) — the check can never fire | `src/seon/warn.cljs`, `src/seon/agent/ctx/warnings.cljs` | OPEN — `docs/seon/issues/record-error-warning-check-reads-dead-attribute.md`; fold into stage 1 warn work |
| B13 | `bin/issues-index` blocked repo-wide: ~70 pre-existing notes carry illegal `status`/`severity` values (legal: blocker,friction,cleanup) | `docs/seon/issues/` | **CLOSED** `927d5b6e`+`9d638b57`: 115 notes normalized, 8 resolved notes archived, index regenerates clean |
| B11 | Operator intermittent: `contained-one-shot-drains-a-foreign-generation-without-overlap` fails order-dependently (containment-uncertain; leaked `sleep 300` workloads suspected via shared `tmp/seon-containment`) — 1 occurrence, green in isolation and on full rerun | `test/seon/dev/process_test.clj:503` | OPEN intermittent — track with B8 |
| B8 | Writer gate intermittents: `writer-integration` release path + `query-admission` injected-release (1 occurrence each, order-dependent) | `src/seon/db/writer.clj` tests | task chip filed |
| B9 | `test/seon/agent/ctx/canvas_test.cljs` calls `datahike.api` `create-database` directly (boundary violation) | that test | stage 5 |

Non-bugs recorded to prevent re-diagnosis: default Meta-compatible provider
returns HTTP 402 (external credential state, not a runtime regression);
`:seon.error/kind` / `:seon.repl/kind` are closed value enums, not entity
taxonomies; konserve "store" and cljs.test `:type` keys are correct seam
names.

## Stages

Dependency-ordered; each stage is one coherent commit series with its own
gate, and stages 2-5 are safe to interleave with other program lanes only at
the named boundaries. One stage in progress at a time at the top level.

### Stage 0 — integrate the initial cleanup lanes (graduated)

B1, B2, B6, B7, B10, and B13 are integrated with their recorded proofs. B3's
remaining transaction-fault verification and sync reads belong to Stage 1.

### Stage 1 — finish the async-facade migration (B3-B5)

Fix every remaining synchronous consumer from the duplicate-interfaces
report per [[async-facade]]'s two-idiom split: async-plane consumers get
`^:async`/`await`; sync-render-plane sites (`seon.warn` checks,
`handlers.message` renderers) are fixed acquisition-side — no `^:async`
may escape into `seon.render/render` or `seon.warn/run-checks`. While in
`seon.warn`, collapse its dual acquisition path to the pre-acquired
`::data` branch and rewrite the `warn.cljs:1064` and `warn.cljs:720`
guidance to the current facade idiom. Reachability-gate every site first:
the caller-less superseded `seon.eval` cluster, `seon.render:684`, and
`testrun/latest-run` are deleted, not asyncified. Gate: full CLJS suite
plus one live cluster proof that a warn check, a render, and an eval each
round-trip through the authority; the report's inventory rechecked to
zero, including zero `^:async` fns reachable from `seon.render/render` or
`seon.warn/run-checks`.

### Stage 1.5 — universal data browser contract and transport

Implement [[data-browser]] after the stage-1 render/schema overlap is closed:
generic bounded rendering remains available when no schema matches; structural
diagnostic candidates are distinct from validated custom-render matches; live
eval drill requests address the owning execution child over the existing
Transit ordinary-data IPC; `/data` samples an acquired database value through
the same projection. Gate: invalid missing-key and wrong-type probes, ambiguous
valid matches, a no-schema value, a large child-owned value with paging, route
ownership refusal, and honest unavailable rendering after child retirement.

### Stage 2 — pod-term retirement (atomic rename)

Execute [[../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]]
steps 1-4 as one orchestrator-owned unit during a lane freeze: code
identities (`client`/`cluster` mapping, `pod.js` -> `client.js`,
`pod-events.log` -> `client-events.log`, `:seon.dev.process/pod` ->
`/client`), then `acme`/`src-inspect-ai`, then living docs, localized
`AGENTS.md` authorities, and skills (resync adapters), then the sweep.
Prerequisite (plan's freeze gate 3): quiesce both clusters under PRE-rename
code (`bin/seon down`, `bin/acme down`) with recorded absence evidence —
the rename cannot cross persisted `:seon.dev.process/pod` records, restore
intents, or pre-rename release manifests. Gate: three suites, `bin/seon up`
from the quiesced state (never `restart` across the rename) with live
status/web-UI proof, one MCP `eval_cljs` round-trip after restarting the
MCP client, and a vendor-excluded sweep (`rg -vi runpod` — RunPod vendor
tokens are frozen) returning only `pod-host/`, dated research/history, and
dependency-owned terminology. No active authority may continue teaching
“pod” as the current process name.

### Stage 3 — one logging convention

From the logging report's remaining plan: adopt the `seon.log/console!`
line shape on the JVM writer via a timbre output-fn; route the residual
non-agent console sites; decide the two value->throw->value round-trips
(`turn.cljs:622,931`, `ctx/canvas.cljs:342`) with the errors-as-values
contract; prune stray bench/probe/`.eval` files from `logs/` and gitignore
their patterns. Gate: writer + CLJS suites; one log line from each process
shows the same shape; `seon.log/tail` shows a loop fault end-to-end.

### Stage 4 — config single-owner collapse and reactive fold-in

From the config report: collapse duplicated defaults (7890, port files,
cluster dir) to one declaration consumed by `config.clj`, `launch.cljc`,
and `db/server.clj`; migrate runtime env gates (`SEON_WEB`, `SEON_SHELL`,
`SEON_RENDER_STRICT`, `SEON_BRAND_*`, `my/blob.cljs:200`,
`db/transport/uds.cljs:28`) to database facts or the launch descriptor;
deduplicate the `SEON_EMBED` scrub with `bin/acme`; absolutize env dir
coordinates in `config/load!`. Gate: operator suite, `bin/seon up` from a
clean checkout, acme cluster boot, config-apply idempotence proof. Before the
ambient configuration refresh is selected, prove that two already-created
independent async fibers observe the update; otherwise keep live acquisition
at the owning operation/session boundary. Collapse the second product-route
catalog in `seon.web.router/static-supplement`: route facts own product,
lifecycle, debug, and data-browser routes; launch capabilities own optional
operator doors; only proven pre-database assets/readiness may remain static.

### Stage 5 — deletions and small unifications

Additional stage-5 items from
[[research/bespoke-reactive-sweep-2026-07-20]] and
[[research/envelope-symbol-conformance-2026-07-20]]: replace the
`serve.cljs:1223-1290` 1500 ms run poll with a request-scoped registration
(preserve the done predicate and `:superseded` timeout close); after the
stage-4 router collapse lands, replace the `client.cljs:344-539`
advertisement machinery with one `observe!` over resumable agent ids and
call the never-called `reactive/close!` from `drain-runtime-owners!`;
converge the failure-payload key on `:seon.error/message` and the
unresolved-symbol semantics (one warning derivation; fix render.cljs
silent nil-vanish); decide the ok?-discriminator ruling
(recommended: bless message-presence for concise domain results).

Collapse-hunt items (adversarial review 2026-07-20):

- `src/seon/embed.clj:611-679` hand-rolls the complete `seon.retry`
  strategy stack (exponential base, jitter, cap, max-retries) with a
  drifted curve (embed jitters a post-cap value, so it can exceed its own
  30 s cap by 50%) and no `max-duration` bound. Fix: rename
  `src/seon/retry.cljs` -> `retry.cljc` (jitter via reader conditionals;
  keep the `^:async` `sleep!`/`with-retry!` executor CLJS-only); embed
  builds its delay seq from the shared combinators in turn.cljs's exact
  composition order plus `(retry/max-duration 60000)`, walking it in its
  existing `Thread/sleep` loop with the interrupt handling preserved
  verbatim; ground the JVM driver shape against
  `reference-code/again/src` before writing it. Verify: `bin/test-cljs`
  stays green (turn.cljs + diffusiongemma consume the promoted `.cljc`
  unchanged), `bin/test-writer` for embed, one writer REPL probe that the
  strategy seq realizes (~500/1000/2000/4000/8000 within jitter bounds).
- `seon.render/value-leaf` (render.cljs:414-443) and `pruned-marker`
  (:496-510) hand-mirror `seon.render.value/emit-leaf`'s marker token
  strings and have already drifted (leading-space `" ⟨"` vs `"⟨"`). Fix:
  extract the four emit-leaf branches into pure formatters
  (datom/opaque/clipped-string/pruned token fns) in `seon.render.value`;
  the html view wraps the exact returned strings in its styled spans; the
  compact no-leading-space `"⟨"` form is canonical (the ai token budget's
  shape) with the html gap restored via CSS; one render test pins that the
  html leaf's flattened text equals the corresponding emit-leaf string.
- The stored-rows -> schema-projection decode is duplicated between
  `seon.runtime.admission/committed-projection`
  (admission.cljs:209-232) and the execution-child load path
  (eval.cljs:1019-1026), with a third single-form site at eval.cljs:2709.
  Fix: `seon.schema` gains one private `read-stored-form` (the single
  reader-table/decode-error policy for stored `:seon.schema/form` and
  `:seon.fn/spec` strings) and one public `rows->projection`; all three
  sites call it; query/transport stays with each caller — only the pure
  decode+build collapses. `typeahead.cljs:795` may follow (lowest
  priority, try-wrapped best-effort site).

Retain and wire `src/seon/agent/ctx/usage.cljs` into the debug turn projection
and compact agent-page usage, with validated non-negative provider counts and
diagnostics for malformed/unknown shapes. Delete `src/seon/ui/components.cljc`
(dead parallel UI layer); fix B9 to go through
`seon.db`; extract the two namespace predicates `seon/dev/docstring.clj:193`
duplicates into the owning `.cljc`; rename test-only "tile"/"verbs" fixture
strings; resolve
[[../../seon/issues/deprecated-skill-render-functions-indexed]] by removing
false deprecation claims from canonical live render functions and deleting any
actually retired function after caller migration; delete
`dev/storage-shootout.js`, remove the `reference-code/integrant`
submodule and its `.gitmodules` entry, and archive `docs/prds/namespace-ui/` as
already ruled; downstream `bin/acme` gym naming remains downstream-owned. Gate:
three suites; require-graph re-scan shows no orphan regressions, and no
deprecated function remains eligible for the callable program index.

## Graduation

All ledger rows closed with proof; every evidence report's fix plan either
executed or explicitly moved to a successor PRD; three suites green
twice consecutively (intermittents B8 included); one live cluster session
demonstrating: a warn check, a recovery decision on a real interrupted run,
an MCP `await` round-trip, and same-shape log lines from both processes.
