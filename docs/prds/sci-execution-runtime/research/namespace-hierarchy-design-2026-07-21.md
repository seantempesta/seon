---
type: research
status: active
tags: [research, architecture]
---

# Namespace hierarchy design — src/seon + src/my restructure and the host.clj decomposition

Decision-ready design for the owner-requested namespace refactor: an
annotated inventory of every top-level namespace with a keep/move/merge/
delete verdict, the proven internal seams of `src/seon/host.clj` (1,141
lines, read completely), the execution-contract naming pair, the diffusion
subsystem preservation boundary (owner directive, 2026-07-21), and a
dependency-safe staged work-package cut. Every fan-in number below was
computed from the actual `(:require …)` graph across `src/` (rg over the
ns forms, bracket-anchored so doc mentions don't count). Naming taste
questions are collected in §11 as recommendation + alternative; nothing
in this file authorizes a rename the owner has not approved.

## 0. Dependency ledger

| Dependency | Where read | What it settles |
|---|---|---|
| Program synthesis | `docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md` (whole file) | W5/U11 deletes the child-fleet + cljs.js bands; ruling 9 (dependency vocabulary), the key-namespaces discoverability ruling ("the key's namespace is where a reader expects the functions operating on that data"); in-flight lanes W0.4/WP-A/W0.6 own `src/seon/host.clj`, `src/seon/host/context.clj`, `src/seon/db/transport/uds.cljc` |
| Deletion inventory | `docs/prds/sci-execution-runtime/research/audit-deletion-inventory-2026-07-21.md` (summary table + rewiring points 1–12) | exact per-band verdicts for `eval.cljs` (5,389), `execution.cljs` (1,302), `execution/host.cljs` (1,239), `execution/runtime.cljs` (706); the `.cljc` promotion seam (`host.clj:102` "JVM projection of the `seon.execution` wire contract") |
| Source-cleanup roadmap | `docs/prds/source-cleanup/roadmap.md` (Stage 2 pod→client atomic rename; Stage 1.5 value-browser chain; bug ledger) | "pod" vocabulary is Stage-2-owned as ONE orchestrator freeze unit — this design must not interleave with it; `seon.client` is the sanctioned process name and is NOT renamed here |
| One-mechanism table | `src/seon/AGENTS.md` | `seon.reactive`, `seon.state`, `seon.render`, `seon.error`, `seon.log`, `seon.retry`, `seon.embed`, `seon.db` are named one-owner mechanisms — their NAMES are load-bearing documentation |
| Conventions | `docs/conventions.md` (`::` auto-resolved keys; keyword namespaces ARE code namespaces; the `.internal` pattern §70) | renaming a namespace renames every `::`-registered key it owns; persisted-attribute owners are rename-frozen without a data migration |
| host source | `src/seon/host.clj` (all 1,141 lines), `src/seon/host/{context,record,graduate}.clj` heads | the five real seams in §3, with the shared-private-fn evidence |
| Require graph | computed 2026-07-21 over `src/**/*.clj{,s,c}` (scratch: bracket-anchored rg per namespace) | every fan-in count and blast radius in this file |
| Owner directive (diffusion) | coordinator message 2026-07-21, superseding the "diffusion dies at U11" framing | diffusion is PRESERVED as a separated experimental subsystem; main `src/` must never require the diffusion tree; providers stay opt-in |
| Shadow builds | `shadow-cljs.edn` (`:client` :57, `:execution` :142, `:worker-validator` :208, `:worker-oracle-eval` :245, `:bootstrap` :324, `:execution-sci`/`:b2-driver`/`:u15-driver` :381-430) | which namespaces are build entry points (fan-in 0 but alive), and the W9 build-matrix-shrink interaction |

## 1. Annotated inventory — the top-level grab-bag

Everything under `src/seon/` that is not already inside a coherent family
directory (`db/`, `agent/`, `ai/`, `web/`, `render/`, `ui/`, `dev/`,
`host/`, `handlers/`, `runtime/`, `repl/`, `diffusion/`). Fan-in = number
of `src/` files whose ns form requires it (tests counted separately).

| Namespace | LOC | src fan-in | Family | Verdict |
|---|---:|---:|---|---|
| `seon.error` (.cljs) | 693 | 22 | error values | **KEEP** — owns `:seon.error/*` keys used in every tier and on the wire; the key-namespaces ruling anchors it. Rename-frozen. |
| `seon.error.instrument` (.cljc) | 363 | 6 | error values | **KEEP** — owns `:seon.error.malli/*` envelope; U6 host instrumentation consumes it as-is (error-quality design ledger). `.cljc` is justified *prospectively* by U6 (today all 6 consumers are `.cljs`). |
| `seon.repair` (.cljc) + `seon.repair.candidates` (.cljc) | 280+146 | 1 / 4 | eval repair | **KEEP** — `repair` is required only by death-row `eval.cljs` today, but W3 repair-parity moves the sub-loop host-side; `candidates` is already a host wrapper (`host/context.clj:631-636`). One boundary fix: `candidates.cljc:101` calls `seon.diffusion.grammar/levenshtein` — the pure fn moves INTO `seon.repair.candidates` (§7.4). |
| `seon.instrument` (.cljc) | 1,113 | 5 | instrumentation | **KEEP** — one-mechanism owner. `.cljc` currently has zero `.clj` consumers; justified prospectively by U6 (host instrumentation over sci vars). Re-check after U6: if the host grows its own wrapper instead, demote to `.cljs`. |
| `seon.warn` | 962 | 3 | derived context | **KEEP name** — registers 15 schemas incl. 5 entity-marked shapes (persisted warning facts → rename-frozen without migration). Family-wise it is agent-context derivation; a move to `seon.agent.warn` is possible but buys discoverability at the cost of orphaning `:seon.warn/*` datoms. Not recommended (§11 D6). |
| `seon.derive` | 547 | 9 | agent projections | **KEEP** — deliberately-below-the-cycle pure projection ns (its docstring documents the acyclicity contract: requires ONLY db+schema). The name is generic but the ns IS the documented cycle-breaker; renaming to `seon.agent.derive` would re-tempt the cycle it exists to break. §11 D7. |
| `seon.reactive` | 580 | 3 | reactive reads | **KEEP** — one-mechanism table row, name load-bearing. |
| `seon.state` | 576 | 1 | lifecycle | **MOVE candidate** → `seon.runtime.state` (§6). Zero entity-marked registrations, fan-in 1 (`seon.client`) — the cheapest move in the tree. §11 D3. |
| `seon.retry` (.cljc) | 195 | 2 | resilience | **KEEP** — the `again` port, sole retry authority; `.cljc` justified (JVM consumer `seon.embed.clj` since `84ab7097`). |
| `seon.log` | 459 | 20 | logging | **KEEP** — one logging surface (Stage 3 owns its convention); fan-in 20 makes any rename pure churn. |
| `seon.content-hash` (.cljc) | 27 | 6 | identity leaf | **KEEP** — both tiers require it (host/{context,record,graduate}.clj + pod). |
| `seon.time` (.cljc) | 10 | 2 | portable leaf | **KEEP** — consumers `my.plan.internal` + `host/context.clj` span tiers. Smell found: it requires `seon.schema` but registers nothing and calls nothing from it — apparently a dead require (verify before removing; `:malli/schema` metadata alone does not need the require). |
| `seon.code` (.cljc) | 34 | 3 | `#code` literal | **KEEP** — owns `:seon.code/*`; its docstring already reserves it for the foreign program-graph arc. |
| `seon.items` / `seon.result` | 20 / 10 | 3 / 2 | shared envelopes | **KEEP** — both docstrings state the key-namespaces rule verbatim; they are the pattern's exemplars. Flat top-level vocabulary namespaces are CORRECT under the discoverability ruling — depth is not a goal. |
| `seon.demo` | 14 | 0 (src) | downstream override fixture | **KEEP** — fan-in 0 in `src/` but alive: `shadow-cljs.edn` build override, `examples/third-party-override/`, `test/seon/eval/memory_safety_test.cljs`. The acme override-proof standing rule depends on it. NOT a deletion candidate. |
| `seon.indexing` (.clj) | 136 | 1 | client build macros | **MOVE candidate** → `seon.client.indexing` (§6). Compile-time macro ns consumed only via `(:require-macros [seon.indexing …])` at `client.cljs:227`. Blast radius: 1 src file + 2 test/doc mentions. §11 D4. |
| `seon.subprocess` | 259 | 4 | Bun process boundary | **KEEP** — shared by shell/search/recovery/autocomplete; survives U11 (deletion inventory). |
| `seon.platform` | 62 | 11 | env/artifact leaf | **KEEP** — documented leaf below `seon.config`; fan-in 11. |
| `seon.route` | 115 | 1 | routes-as-data | **KEEP** — registers the `:seon.route/*` ENTITY schema (`:seon.db/entity true`) — persisted attributes ⇒ rename-frozen. Its docstring already delineates the split with `seon.web.router` (schema+seed here, projection there). |
| `seon.analyzer-info` | 404 | 3 | cljs.js band | **FROZEN-UNTIL-W5** — analyzer-state projection; ruling 6 kills every production cljs.js reference at U11. Survivor callers are rewiring points: `client.cljs:1332` (`namespace-info-from-source`), `agent.cljs:71`, plus death-row `eval.cljs`. If the diffusion oracle needs any of it, that band moves into the diffusion tree at W5, not before. Zero rename effort. |
| `seon.launch` (.cljc) | 526 | 6 + 7 script files | process descriptors | **KEEP name** — 36 registrations; descriptor keys appear in persisted process records/restore intents and every `script/seon/dev/*.clj`. Rename-frozen in practice. Shrinks at W5 (execution-artifact fields leave, inventory row). |
| `seon.config` / `seon.schema` / `seon.db` | 1,668/968/1,422 | 30/125/67 | cores | **KEEP** — untouchable by fan-in alone; no proposal touches them. |
| `seon.client` (+ `client/schema.cljc`) | 2,882 | 0 (entry) | pod process entry | **KEEP** — sanctioned name (source-cleanup Stage 2 ruling); this design does not rename it. `client/schema.cljc` (12 lines) is correctly `.cljc` (required by `launch.cljc`). |
| `seon.repl` / `seon.repl.internal` (.cljc) / `seon.repl.autocomplete` | 124/1,517/809 | 1/11/1 | REPL + parser | `repl` is W5 decision-point 12 (dev self-host surface) — FROZEN until that ruling. `repl.internal` is misnamed: it is the ONE text→forms parser with 11 direct src consumers (incl. `my.plan.internal`, diffusion, worker-validator) — a de-facto public contract wearing the `.internal` (un-rendered, "free-form") label. **RENAME candidate** → `seon.repl.parse` at the W5 window when its consumer set shrinks. §11 D5. |
| `seon.eval` + `eval/internal` + `eval/bootstrap_cache`, `seon.worker-eval`, `seon.worker-validator`, `seon.execution*` | — | — | §4, §7 | SPLIT/frozen per deletion inventory + diffusion directive; see those sections. |
| `seon.embed` (.clj + .cljs + `embed/preflight.clj`) | 1,288/208/205 | 5 | semantic search | **KEEP** — one-mechanism row; the same-name two-file split is the standard platform pair (like `db/transport/uds`). |
| `seon.test.runner` | 823 | 2 | test infra | **KEEP** (recently unified on `find-ns-obj`, `8aeadd3d`). |
| Empty directories | — | — | cruft | **DELETE**: 16 empty dirs under `src/seon/` — `experimental/`, `ns/`, `claude/`, `phase2/`, `graph/`, `server/`, `system/`, `ctx/`, `store/internal/`, `flow/harness/`, `web/sse/`, `ai/claude/`, `ai/agent/`, `db/protocol/` (dir; the `.cljc` file sibling stays). Untracked by git; pure local hygiene, zero risk. |

`src/my/` verdict: **no structural change**. `my.*` names are the
agent-facing teaching surface — every rename is a context-teaching change,
not a refactor. The `.internal` pattern (`my.plan.internal`, 2,124 lines)
and platform pairs (`my.kb` + `my.kb.shared`, `my.blob` +
`my.blob.schema`) match `docs/conventions.md:70`. The only observation:
`my.plan` (1,854) + `my.plan.internal` (2,124) is the largest colocated
pair in the tree and is actively owned by the repl-autosuggest lane —
leave it alone.

## 2. Target hierarchy (sketch)

The finding of the inventory is that Seon's hierarchy is already mostly
right: the real problems are (a) one god-file (`host.clj`), (b) one
naming collision (`seon.host` vs `seon.execution.host`), (c) a handlers
subtree whose name doesn't say render-slot dispatch, (d) lifecycle
namespaces scattered at top level, and (e) an unmarked experimental
subsystem (diffusion). Flat top-level vocabulary namespaces
(`seon.error`, `seon.result`, `seon.items`, `seon.code`, `seon.route`)
are **correct** under the key-namespaces ruling and stay flat.

```text
src/seon/
  client.cljs  client/{schema,indexing}         ; pod process entry (indexing moved in)
  host.clj     host/{context,record,graduate,   ; JVM execution host (entry stays seon.host)
                     session,sample,eval,invoke} ;   ← the §3 decomposition
  execution.cljc                                 ; ONE wire contract (.cljc at W5)
  execution/host.cljs                            ; pod-side host-session dispatch (name: §5/§11 D2)
  db/…                                           ; unchanged
  agent/…  ai/…                                  ; typeahead stays core; diffusiongemma → diffusion tree via provider registry (§7.2b)
  runtime/{admission,recovery,lifecycle,state}   ; lifecycle family (state moved in)
  render/…  + render/handlers/{eval,fn,message,ns,schema,test}  ; §5 (owner decision D1)
  ui/…  web/…                                    ; unchanged this cycle (serve.cljs split deferred)
  repl/{parse,autocomplete} + repl.cljs          ; parse = renamed internal (W5 window, D5)
  eval.cljc (post-W5 survivor: :seon.eval/* vocabulary, budget, envelope renders, lookup-value)
  error, error/instrument, warn, derive, reactive, retry, log, config, schema,
  state→(runtime/), route, code, items, result, time, content_hash, platform,
  subprocess, launch, embed*, test/runner, demo   ; flat vocabulary + mechanism owners, unchanged
src-diffusion-cljs OR src/seon/diffusion (rg-fenced) ; §7 — the preserved experimental subsystem:
  diffusion/{grammar,retrieval,oracle,scaffold}, worker_eval, worker_validator
```

## 3. host.clj decomposition — the proven seams

`src/seon/host.clj` read completely. Five bands, with the shared private
functions that decide whether each seam is real:

**Band A — wire-contract projection (lines 88–177).** Message keyword
defs (`:91-100`) and `schema/register!` of
`::startup`/`::invoke`/`::cancel`/`::shutdown`/`::function-identity`
(`:102-154`). The file itself marks this "JVM projection of the
`seon.execution` wire contract (seam above)". **Do not extract — this
band is deleted by W5's `.cljc` promotion** (deletion-inventory rewiring
point 8). Any decomposition effort spent here is wasted. The
`::start-request`/`::host` schemas (`:155-175`) are host-owned and stay
in `seon.host`.

**Band B — value retention + sampling (lines 179–195, 224–362,
457–523).** `drill-value` (:179), `sample-error-frame` (:224),
`unavailable-drill-result` (:233), `serve-value-sample!` (:248),
`valid-value-sample?` (:327), `safe-sample-correlation` (:355),
`admitted-retained-value` (:457), `retain-live-value!` (:462),
`retained-live-entry` (:479), `retained-live-value` (:491),
`sampling-policy-query` (:494), `acquire-sampling-policy!` (:507).
Cross-band edges: it reads session state (`::live-values`, `::active`,
`::startup`) and calls `send-frame!`; band D calls `retain-live-value!`
(:705) and band E calls `acquire-sampling-policy!` (:779);
`serve-session!` clears `::live-values` (:897, :1043). The seam is real:
every crossing is a named public-able function or a session-map read.
Target: **`seon.host.sample`** (Stage 1.5's Unit-1G vocabulary — this IS
the "bounded process-local managed-eval-id slot" that roadmap issue
ruled into "the existing JVM host session lifecycle").

**Band C — frame IO + error/result builders (lines 196–222, 364–395,
904–913).** `now-ms`, `error-value`, `error-frame`, `result-frame`,
`send-frame!` (write-lock discipline), `bounded-result`,
`invalid-message-frame`, `startup-error`. These are the shared privates
used by EVERY other band — which is precisely why they must become the
bottom leaf of the split rather than staying glued to any one consumer.
Target: **`seon.host.session`** together with session construction (the
map built at `serve-session!` :980-993) and the session-shape schema.
Decision: the session map's `::`-keys are today `:seon.host/*`; after
the split they become `:seon.host.session/*` — safe because the session
map never crosses the process boundary (verified: only frame CONTENTS
with `:seon.execution/*` keys are written to the socket), and correct
under the key-namespaces ruling (the operating functions move there).

**Band D — eval-batch serving (lines 397–723).** `agent-home-ns` (:399,
documented mirror of pod `seon.agent.home/home-ns` — a `.cljc`
promotion candidate for that one derivation), `built-in-var-refusal?`
(:407), `eval-error-value` (:425), `entry-source`, `wire-safe-value`
(:440), `eval-form!` (:525), `read-error-envelope`, `batch-summary`,
`declared-next-ns` (:564), `eval-batch-result` (:582, the 138-line
receipt→eval→terminal→tee loop), `interrupted-batch?`. Cross-band
edges: `retain-live-value!` (band B, one call), `error-value` (band C),
`context/*` + `record/*` + `schema/*` (existing siblings). This is the
band W3 GROWS (repl-form dispatch parity, preflight repair, print
capture, typed interrupt classification — deletion-inventory hard
blockers), which is the argument for splitting BEFORE W3: new parity
code lands in a 330-line owner, not a 1,141-line one. Target:
**`seon.host.eval`** (it serves evals; the name pairs with
`seon.eval`'s surviving receipt vocabulary that both tiers write).

**Band E — invocation lifecycle (lines 727–900).** `settle!` (:727,
the CAS terminal), `run-invocation!` (:735, watchdog + W0.3
token-identity checks), `begin-invocation!` (:818), `cancel-active!`
(:865), `shutdown-session!` (:887). Cross-band edges: bands B
(`acquire-sampling-policy!`), C (frames), D (`eval-batch-result`,
`interrupted-batch?`). `settle!` is used only inside this band — clean.
This is also where W3's authored-function invocation lands (the
`:core-bug` refusal at :769-776 is the recorded U2 seam). Target:
**`seon.host.invoke`**.

**Band F — session loop + assembly (lines 902–1141).**
`accept-startup!` (:915, context fork + corpus replay + ready frame),
`serve-session!` (:975, the message loop W0.6 hardens), `start!`
(:1046, writer session + projection + base + graduation + pools +
acceptor), `stop!` (:1110), `-main` (:1122). Stays in **`seon.host`**:
the entry namespace keeps `::start-request` keys stable
(`:seon.host/socket-path` appears in launch EDN — `bin/test-writer`,
operator config), matching `seon.client` as the process-entry idiom.

Resulting require DAG (acyclic, verified against the call sites above):

```text
seon.host ──► seon.host.invoke ──► seon.host.eval ──► seon.host.sample ──► seon.host.session
   │               │                    │                                        ▲
   │               └────────────────────┴────────────────────────────────────────┘
   └──► seon.host.context / .record / .graduate (unchanged siblings)
```

Sizes after split (estimate): host ≈ 340, invoke ≈ 200, eval ≈ 330,
sample ≈ 220, session ≈ 120; band A (~90) stays in host until W5
deletes it.

**Sequencing (hard):** the split may start only after W0.4 (writer
pool: owns `host/context.clj` + `host.clj` wiring), W0.6 (escape
hardening: owns exactly the band-C/F frame-write and accept-catch
sites), and W0.7 (battery) land — and SHOULD land before W3 so parity
code lands in the split files. The split itself must not change
behavior: same gate (`bin/test-writer` host conformance/pool/graduate
suites + the W0.7 battery) green before and after, zero diff in frames
on the wire.

## 4. seon.eval after W5 — where the KEEP bands land

Not a new proposal — a naming ruling for the deletion inventory's KEEP
bands so W5 has a target: the surviving ~630 LOC (schemas :1-126,
budget/race-timeout :127-289, `lookup-value` resolver band :484-655,
`cap-edn`/render band :2761-2956) stay in **`seon.eval`**, which keeps
its name because `:seon.eval/*` is the durable receipt vocabulary both
tiers write (key-namespaces ruling). Promote to `.cljc` exactly the
slices `seon.host.record` currently mirrors (receipt envelope + schema
registrations — the inventory's own recommendation), leaving the
DOM-walking `lookup-value` band `.cljs`. No `eval2`, no `eval.core`.

## 5. Layer names — execution pair, render/handlers, web/ui

**The execution naming collision.** `seon.host` (JVM, serves the
contract) vs `seon.execution.host` (pod `.cljs`, the CLIENT that
supervises children today and host sessions tomorrow). Both files'
docstrings already describe one contract owned by `seon.execution`
("speaking the SAME message semantics… the pod cannot tell hosts from
children"). After W5: `seon.execution` promotes to `.cljc` and is THE
contract namespace both sides require; `execution.host`'s child lane
deletes and it collapses to host-session dispatch (~890 LOC KEEP band).
The discoverable pair is then: contract `seon.execution` (.cljc) ↔
server `seon.host` ↔ client `seon.execution.host`. Recommendation:
**keep both names**. The rename that suggests itself
(`seon.execution.host` → `seon.execution.dispatch`, after the
one-dispatch law) is blocked by data: `::eval-socket-path` is a
PERSISTED agent attribute `:seon.execution.host/eval-socket-path` and
is now also the runtime tier-acquisition probe (W4a, 4th acquisition
member) — renaming the ns orphans a stored attribute and a live query.
§11 D2 records the alternative.

**render / handlers / ui / web — the real current division.**
`seon.ui.*` = pure hiccup primitives (html, markdown, clojure,
header, agent_view; no IO). `seon.render` + `render/*` = the ONE
guarded walker + slot registration (`render/schema.cljs`, 30 lines) +
surface kinds (canvas/chat/surface/system) + the portable bounded
sampler (`render/value.cljc`). `seon.handlers.*` = six per-entity-family
`render-ai`/`render-html` pairs (eval, fn, message, ns, schema, test)
that the slots dispatch to — the program-synthesis addenda name
`seon.handlers.eval` as "the first-party idiom" of the ONE render-slot
dispatch. `seon.web.*` = HTTP/SSE (serve, datastar, router, debug,
brand, value, view_unit, reactive/{call,transform}).

The `handlers` name is the one that doesn't say render-slot dispatch
(it reads as HTTP handlers next to `seon.web`). Proposal (owner
decision D1): move the six files to **`seon.render.handlers.*`** —
total blast radius 6 moved files + 3 requiring files (`client.cljs`
requires five of them for registration, `agent/ctx/transcript.cljs` +
`agent/ctx.cljs` one each) + their 6 test files. None of the six
registers schemas (verified: 0 `register!` calls), so the rename is
pure require-editing. The flatter alternative (`seon.render.eval` etc.)
collides with the existing `seon.render.schema` slot-registration ns
and is rejected. Two adjacent smells recorded, not scheduled:
`seon.web.serve` (2,113 lines) is the tree's second god-file;
`seon.web.view-unit` is consumed only by `seon.render`/`render.surface`
(a web→render layering inversion — move to `seon.render.view-unit`
is a 3-file edit, folded into the same package if D1 is approved).

## 6. Startup/lifecycle grouping (seon.client untouched)

Current scatter: `seon.client` (entry), `seon.launch` (.cljc,
descriptors, rename-frozen), `seon.runtime.{admission,recovery,
lifecycle}`, `seon.state` (reconcile!), `seon.indexing` (client build
macros), `seon.platform`/`seon.log` (leaves). Proposal — make
`seon.runtime.*` the process-lifecycle family without touching frozen
names:

- `seon.state` → **`seon.runtime.state`** (fan-in 1: `client.cljs`;
  10 registrations, none entity-marked — registered fn-boundary
  schemas rename with the ns safely). D3.
- `seon.indexing` → **`seon.client.indexing`** (fan-in 1 via
  `:require-macros`; it enumerates the CLIENT build's program-graph
  surface — the family is the client artifact, not dev tooling). D4.
- `seon.agent.runtime` (134 lines; resume/unhost of process-local agent
  resources) collides verbally with both `seon.runtime.*` and the
  (deleting) `seon.execution.runtime`. Its 11 registrations are
  fn-boundary shapes (0 entity-marked). Recommendation: **merge into
  `seon.agent.lifecycle`** (398 lines, the adjacent concern; combined
  ~530 lines) — one fewer "runtime" meaning. Fan-in 2 (`client.cljs`,
  `web/serve.cljs`). D8.
- `seon.launch`, `seon.log`, `seon.platform`, `seon.runtime.lifecycle`
  keep names/locations. `runtime/lifecycle.cljc`'s `.cljc` is justified
  (consumed by `script/seon/dev/process.clj`).

## 7. Diffusion subsystem — preserved, fenced (owner directive 2026-07-21)

Supersedes the death-row framing for diffusion: the subsystem survives
indefinitely as a separated experimental tree; the main system must be
able to evolve without dragging it.

### 7.1 Membership (owner verdict 2026-07-21: typeahead is CORE)

**Diffusion tree (fenced):** `seon.diffusion.grammar` (.cljc, 92),
`seon.diffusion.retrieval` (678), `seon.diffusion.oracle` (233),
`seon.diffusion.scaffold` (180), `seon.worker-eval` (762, the
`:worker-oracle-eval` build entry — cljs.js self-host eval beside the
worker), `seon.worker-validator` (196, the `:worker-validator` build
entry — parse tier beside the worker), and — per the owner verdict —
**`seon.ai.diffusiongemma`** (656, the diffusion-worker provider
internals; relocation mechanics in 7.2b). Plus the Python side
(`src-diffusion/`, already separate) and the `out/bootstrap` artifact.

**Main system:**
- `seon.ai.typeahead` (1,182) — **core by owner ruling**: the active
  repl-autosuggest step-loop feature, implementable over other
  providers/mechanisms. Its core mechanics (step loop, offer/policy
  derivation from the one `seon.agent.ctx.menu` acquisition, persisted
  `:seon.typeahead/*` step projections, reply assembly) stay in
  `seon.ai.*`. Today it is diffusion-COUPLED in code: it statically
  requires `seon.ai.diffusiongemma` (`typeahead.cljs:54`) and speaks
  `::dg/*` wire keys directly (`typeahead.cljs:927-934` — `dg/mode`,
  `dg/prompt`, `dg/policy`, `dg/complete`). 7.2b decouples that at the
  provider boundary.
- `seon.ai.dispatch` (84) — the provider boundary. Honest finding: it
  is NOT registry-based today — it is a static `case` over
  `:seon.ai/provider` with static requires of every provider ns
  (`dispatch.cljs:8-15,44-67`). The owner-directed "config/registry-
  based, not a static require" indirection therefore has to be BUILT by
  strengthening this one dispatch in place (7.2b); there is no existing
  late-binding provider mechanism to reuse as-is (`seon.eval/
  lookup-value` late-binds symbols but cannot conjure code absent from
  the compiled artifact).
- `seon.agent.ctx.typeahead-steps` (542) — renders persisted
  `:seon.typeahead/*` step datoms into context; a ctx block, no
  diffusion requires.
- `seon.eval.bootstrap-cache` (63) — the deliberate LEAF cache loader.
  Shared today by `seon.worker-eval` (diffusion) and `seon.eval`
  (death-row engine) / `seon.repl/dev-init!` (W5 decision point 12). If
  decision 12 retires the dev self-host surface, this file's only
  consumer is diffusion and it MOVES into the tree; until then it stays
  in main as a leaf both sides may require (main→leaf is legal; the
  fence forbids main→diffusion, not diffusion→main).

### 7.2 Boundary rule and isolation mechanism

Rule (rg-enforceable, mirrors ruling 6's zero-`src/`-requires posture):
**no file outside the diffusion tree may require
`seon.diffusion.*`, `seon.worker-eval`, or `seon.worker-validator`;
the diffusion tree MAY require main** (it does today: `seon.db`,
`seon.embed`, `seon.repl.internal`, `seon.schema`). Providers stay
opt-in via explicit config only.

### 7.2b Provider registry — how diffusiongemma leaves main without losing the live path

The constraint triangle: (a) main never requires the diffusion tree;
(b) the diffusion-backed typeahead/diffusiongemma paths keep working in
the live pod (no feature loss — the research is preserved and active);
(c) one mechanism, no parallel dispatch. A pure classpath wall breaks
(b): the typeahead step loop executes inside the pod's turn loop, so
whatever backs it must be compiled into the running client artifact.
The resolution is a **provider registry in `seon.ai.dispatch`, filled
by provider self-registration at namespace load**, with the set of
loaded provider namespaces being a build/entry decision — the exact
late-bound-override posture `seon.demo` already proves for downstream
builds:

1. `seon.ai.dispatch` replaces its static `case` with one registry
   (process-local atom is legitimate here — compiler/process wiring,
   not durable state): provider id keyword → adapter descriptor
   (`agent-adapter` fn, `configured?` fn). ALL providers register —
   anthropic, openai-compat, stub, typeahead, diffusiongemma — so the
   registry is the one mechanism, not a diffusion special case.
   Unregistered-or-unconfigured selection falls to the stub exactly as
   the missing-credentials branch does today (`dispatch.cljs:47-67`).
   The closed provider enum at `ai.cljs:71` becomes registry-derived
   (no hand-maintained list).
2. `seon.ai.typeahead` gains a **step-backing contract in its own
   vocabulary**: it defines `:seon.ai.typeahead/*` step request/
   response keys and calls a registered step-backing fn instead of
   `dg/complete` with `::dg/*` keys (today: `typeahead.cljs:927-934`).
   The diffusion provider translates typeahead terms ↔ its worker wire
   terms at its own boundary (the producer/consumer translation rule —
   no third umbrella noun). This is what makes typeahead honestly
   "implementable without diffusion": another step backing registers
   under the same contract.
3. `seon.ai.diffusiongemma` moves into the diffusion tree (rename to
   **`seon.diffusion.gemma`** so the name-based fence covers it; its
   wire keys follow the ns per the key-namespaces ruling — safe: they
   are transient wire data, never persisted datoms; the persisted
   `:seon.typeahead/*` step rows are typeahead-owned and unaffected).
   It self-registers its agent adapter and the typeahead step backing
   on load. Provider IDs in config (`:diffusiongemma` /
   `:typeahead`, `SEON_AI_PROVIDER`, `SEON_DG_ENDPOINT`) are registry
   keys and env names — they do NOT rename with the namespace.
4. Loading: the default client build entry does not require the
   diffusion tree. A one-line opt-in entry require (build override /
   preload — the same shadow build override `seon.demo`
   demonstrates, or the diffusion flavor's own build) loads
   `seon.diffusion.gemma`, which registers itself. Fence intact: no
   main NAMESPACE requires diffusion; the build config edge is the
   sanctioned opt-in configuration and is explicit config, honoring the
   "never activate as a side effect" standing rule.

Sequencing caution: `seon.ai.typeahead` and the step-loop surface are
actively owned by the repl-autosuggest lane (separate checkout,
`seon-stable`) — coordinate the typeahead-side edits (item 2) with that
lane before dispatch; items 1/3/4 don't touch its hot files but ride
the same package.

### 7.2c Isolation of the rest of the tree

Mechanism — recommendation: **keep the `seon.diffusion.*` names and
current file locations; enforce the fence with a checked-in rg gate**
(a `bin/test-cljs`-adjacent conformance test or `bin/seon test`-visible
script asserting zero fence-crossing requires). Rationale: shadow-cljs
`:source-paths` is global across builds, so a separate source root does
NOT keep diffusion out of the main build's classpath by itself — only
the require closure does, and the closure is exactly what the rg gate
checks. A separate root (`src-diffusion-cljs/`) would require either a
second shadow config (the `.shadow-cljs-b2` pattern W9 is deleting) or
deps-alias gymnastics, for no additional isolation. Escalate to a
separate root + config only if diffusion measurably burdens main-system
builds; today it does not (see 7.3). Alternative recorded as D9.

### 7.3 Build accounting vs the W9 shrink

Diffusion currently owns: `:worker-oracle-eval`, `:worker-validator`,
`:bootstrap` (shared with `seon.repl/dev-init!` until decision 12), and
plausibly `:lora-audit` (node-test; confirm membership during the WP).
The W9 shrink targets are unrelated bands: `:execution-sci`,
`:b2-driver`, `:u15-driver` (+ `out-b2/`, `.shadow-cljs-b2/`), and W5
deletes `:execution`/`:acme-execution`/`:execution-integration-client`.
Net: preserving diffusion costs ZERO additional builds — its builds
already exist and stay; the matrix still shrinks by ~6. Diffusion's
gate (its worker bundle smokes + `:lora-audit`) runs in its own lane,
not in the three main surfaces' program gates.

### 7.4 Fence-violating edges today, and their resolutions

| Edge (main → diffusion) | Site | Resolution |
|---|---|---|
| `seon.eval` → `seon.diffusion.grammar` (`malformed-def?`) | `eval.cljs:3990-3996` | dies with the eval engine band at W5; until then the rg gate carries a dated allowlist entry for this one file (frozen band, zero rename effort) |
| `seon.repair.candidates` → `seon.diffusion.grammar` (`levenshtein`) | `candidates.cljc:15,101` | **move `levenshtein` into `seon.repair.candidates`** (pure fn, its only main consumer); `seon.diffusion.grammar` then requires it back (legal direction). Survives W3's host repair parity cleanly |
| `seon.ai.dispatch` → `seon.ai.diffusiongemma` | `dispatch.cljs:11` | becomes a fence violation once diffusiongemma joins the tree — resolved by the 7.2b provider registry (dispatch stops requiring any provider statically beyond the registry defaults it hosts) |
| `seon.ai.typeahead` → `seon.ai.diffusiongemma` | `typeahead.cljs:54,927-934` | resolved by the 7.2b step-backing contract (typeahead speaks its own keys; the diffusion provider translates and self-registers) |
| `seon.ai.dispatch` → `seon.ai.typeahead` | `dispatch.cljs:14` | not a violation — typeahead is core by owner ruling; it also self-registers under the same registry for uniformity |

No other main→diffusion edge exists in the graph.

## 8. cljc/clj/cljs placement flags

| File | Flag | Disposition |
|---|---|---|
| `seon.instrument.cljc` | zero `.clj` consumers today | keep (U6 target); re-check after U6 |
| `seon.error/instrument.cljc` | zero `.clj` consumers today | keep (U6 reuses it verbatim per the error-quality ledger) |
| `seon.repair.cljc` | only consumer is `.cljs` (`eval.cljs`, death row) | keep (W3 host repair parity is the `.clj` consumer) |
| `seon.runtime.lifecycle.cljc` | looks one-platform from src | justified — `script/seon/dev/process.clj` consumes it |
| `seon.time.cljc` | dead `seon.schema` require (registers/calls nothing) | remove the require in the hygiene WP after verifying no load-order dependency |
| `seon.db.transport.uds` `.cljc`+`.cljs` | same-ns platform pair | correct and intentional (CLJ reads `.cljc`, CLJS prefers `.cljs`); owned by the in-flight UDS lane — hands off |
| `my.canvas.cljc`, `my.kb.cljc`, `my.plan.cljc`, `my.skills.cljc` | portable toolkit | correct — the host loads the portable pure slice (`host/context.clj` docstring) |

## 9. Deletion candidates found

- **16 empty directories** under `src/seon/` (§1 last row) — delete.
- **Zero-fan-in namespaces that are NOT deletions:** `seon.demo`
  (build-override fixture), `seon.host`/`seon.client`/
  `seon.execution.runtime`/`seon.worker-*` (build/process entries),
  `seon.diffusion.oracle`/`scaffold` (diffusion tree, preserved by
  directive; driven from the REPL/worker side).
- Everything else on death row is already inventoried by W5
  (`audit-deletion-inventory-2026-07-21.md`) — this design adds no new
  deletion and spends zero rename effort on any W5 DELETE band
  (`eval.cljs` engine bands, `execution.cljs` child bands,
  `execution/host.cljs` child lane, `execution/runtime.cljs` compose,
  `analyzer_info` production references, `repl_parity` tests).
- Smells reported (not scheduled): dead `seon.schema` require in
  `time.cljc`; `seon.web.serve` at 2,113 lines; `seon.web.view-unit`
  layering inversion (§5).

## 10. Work-package cut (dependency order)

Rules applied: every move is a real move with all callers updated in the
same path-limited commit (no `-v2`, no compat ns); each package is one
atomic coherent move set executed by the orchestrator during a quiet
window for its owned paths; no package depends on a later one; W5-band
files get zero effort; anything touching `host.clj`/`host/context.clj`/
`uds.cljc` waits for W0.4 + WP-A + W0.6; nothing interleaves with
source-cleanup Stage 2's atomic pod→client freeze.

**NS-0 — hygiene sweep** (independent, now; ~4 files + 16 rmdirs).
Delete the 16 empty dirs; remove `time.cljc`'s dead require (verify
load-order first). Gate: `bin/test-cljs` focused time/plan selectors.
Recipe: `find src/seon -type d -empty -delete`; rg confirms no
`seon.time` schema use.

**NS-1a — diffusion fence, mechanical half** (independent; after W0.4
lands to avoid `host/context.clj`-adjacent churn; ~5 files).
Move `levenshtein` from `diffusion/grammar.cljc` into
`repair/candidates.cljc`; flip the require direction in `grammar`;
add the fence gate (rg assertion: no require of `seon.diffusion.*`,
`seon.worker-eval`, or `seon.worker-validator` outside
`src/seon/diffusion/`, `worker_eval.cljs`, `worker_validator.cljs` —
with two dated allowlist rows: `eval.cljs` (dies at W5) and
`dispatch.cljs`/`typeahead.cljs` (die at NS-1b)); confirm `:lora-audit`
membership; record the subsystem contract in a localized
`src/seon/diffusion/AGENTS.md`. Owned:
`src/seon/repair/candidates.cljc`, `src/seon/diffusion/grammar.cljc`,
the gate file, the new AGENTS.md. Gate: focused repair/diffusion suites +
both worker bundle compiles (`:worker-validator`, `:worker-oracle-eval`).
Preserves all behavior; no feature loss.

**NS-1b — provider registry + diffusiongemma relocation** (after NS-1a;
coordinate the typeahead edits with the repl-autosuggest lane owner
before dispatch; ~8 files: `ai/dispatch.cljs`, `ai.cljs` (registry-
derived provider set), `ai/typeahead.cljs` (step-backing contract),
`ai/diffusiongemma.cljs` → `diffusion/gemma.cljs` (translate + self-
register), opt-in entry require in the diffusion/build-override door,
plus dispatch/typeahead/gemma tests).
Execute §7.2b. Behavior proof: with the opt-in load, one live
`SEON_AI_PROVIDER=typeahead` step-loop round-trip and one
`:diffusiongemma` adapter call behave byte-identically at the wire;
without it, both selections fall to the stub with an honest steering
value naming the missing provider registration. Removes the NS-1a
dispatch/typeahead allowlist rows; the fence gate then has only the
W5-dated `eval.cljs` row.

**NS-2 — lifecycle grouping** (independent; needs owner sign-off on
D3/D4/D8; ~6 moved/merged files + 4 requiring files + tests ≈ 14).
`seon.state`→`seon.runtime.state`; `seon.indexing`→
`seon.client.indexing`; merge `seon.agent.runtime` into
`seon.agent.lifecycle`. Recipe per move: `rg -l 'seon\.state'
src test` → edit requires/aliases; registered keys follow the ns
(`::` stays auto-resolved); no entity attributes involved (verified §1).
Gate: `bin/test-cljs` full (the moves cross agent/loop/client seams) +
one `bin/seon up` boot proof.

**NS-3 — render-slot subtree** (independent; needs D1; 6 moved + 3
requiring + `seon.web.view-unit` fold-in + ~7 test files ≈ 17 files).
`seon.handlers.{eval,fn,message,ns,schema,test}` →
`seon.render.handlers.*`; `seon.web.view-unit` →
`seon.render.view-unit`. Recipe: `rg -l 'seon\.handlers\.'` /
`'seon\.web\.view-unit'`; zero schema registrations move (verified).
Gate: focused render/handlers/transcript suites + one live agent-page
render proof (transcript + technical eval blocks visible).

**NS-4 — host decomposition** (after W0.4 + W0.6 + W0.7; before W3;
5 new files + host.clj + ~3 writer-test files ≈ 9).
Execute §3: extract `seon.host.session` (band C + session shape), then
`seon.host.sample` (band B), `seon.host.eval` (band D),
`seon.host.invoke` (band E); `seon.host` keeps band F + band A (band A
frozen for W5). Behavior-identical: no frame, receipt, or error-value
change. Session keys renamespace to `:seon.host.session/*` (process-
local only, §3 evidence). `::start-request` keys unchanged. Gate:
`bin/test-writer` full (host conformance/registry/graduate/pool 1,551+
LOC suites) + the W0.7 battery, green before and after, plus one live
`bin/seon up` host session round-trip.

**NS-5 — W5-window renames** (rides the W5 unit, not before; sized
inside W5). `seon.repl.internal` → `seon.repl.parse` (11 consumers
today; several die with W5 — recount at execution); the surviving
`seon.eval` band consolidation + `.cljc` promotion (§4); band A deletion
from `seon.host`; diffusion allowlist row removal (NS-1's gate goes to
zero exceptions); `analyzer_info` disposition (delete or move remnant
into the diffusion tree per ruling 6).

Explicitly NOT scheduled here: Stage 2's pod→client rename (its own
freeze unit), ruling 9's get-in/path renames (Stage 1.5 boundary/W5),
`seon.web.serve` split (record as a W10 candidate), any `my.*` rename.

## 11. Open owner decisions

Every naming call that is taste rather than mechanics. Recommendation
first, alternative second; nothing executes without a ruling.

- **D1 — handlers subtree.** Recommend `seon.handlers.*` →
  `seon.render.handlers.*` (+ `web.view-unit` → `render.view-unit`) so
  the tree says render-slot dispatch (NS-3, ~17 files). Alternative:
  keep `seon.handlers.*` (it is short, and the AGENTS.md one-mechanism
  table already points at rendering) — zero cost.
- **D2 — the execution pair.** Recommend keeping `seon.host` ↔
  `seon.execution.host` ↔ contract `seon.execution` (.cljc at W5); the
  double-"host" reading is resolved by the contract ns sitting between
  them, and the pod ns owns the persisted
  `:seon.execution.host/eval-socket-path` attribute (rename = data
  migration). Alternative: `seon.execution.host` →
  `seon.execution.dispatch` at W5 WITH an attribute migration folded
  into the cutover's cluster resets — honest cost: one migration +
  ~7 files; buys a name that matches the one-dispatch law.
- **D3 — `seon.state` → `seon.runtime.state`.** Recommend yes (fan-in
  1, zero entity attrs). Alternative: keep flat — `seon.state` is a
  one-mechanism table name and the table can simply keep teaching it.
- **D4 — `seon.indexing` → `seon.client.indexing`.** Recommend yes
  (it is the client artifact's compile-time index; 1 require site).
  Alternative: `seon.dev.indexing` if the owner reads it as tooling —
  but it runs in the production build's macroexpansion, so `client` is
  the honest family.
- **D5 — `seon.repl.internal` → `seon.repl.parse`.** Recommend yes at
  the W5 window (the `.internal` label promises "not a public
  contract" while 11 namespaces require it; `parse` names what it
  owns). Alternative: keep `.internal` and accept the convention
  stretch (the conventions doc does allow big internals — the issue is
  only that this one is a cross-tree contract).
- **D6 — `seon.warn` placement.** Recommend keep (5 persisted
  entity-marked shapes make it rename-frozen without migration).
  Alternative: `seon.agent.warn` + attribute migration if the owner
  wants the agent-context family complete under `seon.agent.*`.
- **D7 — `seon.derive` name.** Recommend keep (the generic name is the
  point: it is the below-everything pure projection layer and its
  docstring is the cycle-breaking contract). Alternative:
  `seon.agent.derive` reads better in the tree but re-invites the
  `agent → ctx → render` cycle pressure it was built to break.
- **D8 — `seon.agent.runtime` merge into `seon.agent.lifecycle`.**
  Recommend merge (one fewer "runtime"; adjacent concern; ~530-line
  result). Alternative: rename to `seon.agent.hosting` if the owner
  wants resume/unhost separate from pause/terminate lifecycle.
- **D9 — diffusion isolation mechanism.** Recommend in-place
  `seon.diffusion.*` + rg fence gate (zero build cost, W9-compatible).
  Alternative: separate source root + own shadow config for a hard
  classpath wall — costs a second config file of exactly the
  `.shadow-cljs-b2` shape W9 is deleting; choose only if diffusion
  later burdens main builds.
- **D11 — relocated provider name.** Recommend
  `seon.ai.diffusiongemma` → `seon.diffusion.gemma` (the name-based
  fence then covers it; wire keys follow the ns; provider id
  `:diffusiongemma` and `SEON_DG_*` env names unchanged). Alternative:
  `seon.diffusion.diffusiongemma` keeps the exact model name in the
  segment at the cost of stutter; or keep the file under `src/seon/ai/`
  with a path-based fence exception — rejected as a standing exception
  list (computed-rule preference).
- **D12 — default-build opt-in configuration for the diffusion provider.**
  Recommend the build-override entry require (the `seon.demo`-proven
  door) so the DEFAULT client artifact carries zero diffusion code and
  the diffusion flavor/dev build carries the provider. Alternative:
  keep the provider in the default artifact via one sanctioned entry
  require in the client build config (capability always live, config
  gate alone prevents activation) — smaller operational change, weaker
  separation; the owner's "own build if it burdens the main system"
  language suggests the first.
- **D10 — `agent-home-ns` duplication.** `seon.host.eval` will carry
  the JVM mirror of `seon.agent.home/home-ns` (`host.clj:399` documents
  the mirror). Recommend promoting the one derivation to a tiny `.cljc`
  slice of `seon.agent.home` during NS-4. Alternative: leave the
  documented two-line mirror (cost of the `.cljc` split may exceed the
  duplication).
