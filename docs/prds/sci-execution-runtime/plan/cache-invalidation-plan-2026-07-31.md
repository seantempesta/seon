---
type: prd
status: active
tags: [prd, render, caching, flow]
---

# W5/W6 — call-grain cache, attribute-revision invalidation, per-agent render proc

The performance layer UNDER the context system. The MVP
(`context-mvp-2026-07-31.md`) deliberately ships uncached — context derives
fresh per turn, which is correct under ruling (16) FRESHNESS OUTRANKS CACHE
(`README.md:1199`) and merely slow. This plan is what lands after the MVP
proves the shape: waves W5 and W6 of the sealed contract
(`context-render-data-model-spec.md:266-272`).

Nothing here is new machinery. Every unit is flow procs
(`src/seon/flow.clj:83-115`), the one guarded door
(`src/seon/flow.clj:481-523`), or Datahike's own revision facts carried on the
database value (`reference-code/datahike/src/datahike/query.cljc:2568-2589`).

## 0. Dependency ledger

| dependency / mechanism | selected source | what we depend on |
|---|---|---|
| Datahike (Seon's fork) | `reference-code/datahike` submodule `9b3be9d5` (`0.8.1729-98-g9b3be9d5`), `deps.edn:21-25` | `query.cljc:2568-2589` `advance-query-cache-context`; `:2906-2944` `dependency-plan-attributes` / `query-attribute-dependencies`; `:2963-2975` `source-context-unchanged?`; `pull_api.cljc:18-69` `pull-dependency-plan`; `db.cljc:400-415` committed cache identity + `clear-cache-context`; `versioning.cljc:69-100` derived-value contexts; `index/persistent_set.cljc:432-444` the blocking `k/get … :sync? true` |
| core.async flow | `1.10.874-alpha3`, `deps.edn:17-20` | `flow/impl.clj:166-167,263,323` (init runs inline on the `flow/start` CALLER's thread, before `run` is submitted); `:243-263` workload = executor selection only; `:209-217` transition fires only on a real status change |
| Seon walk / render | tree at `ae0bf5704` | `src/seon/render/walk.clj:226-255` (two-step probe → family selector), `:279-331` (per-family named reverse pull), `:416-423` (derived edges), `:473-618` (`neighborhood`); `src/seon/render.clj:166-235` (resolution chain, var-backed invoke) |
| Seon render production | same | `src/seon/render/web.clj:244-274` `page-of`, `:413-437` `changed`, `:467-519` `render-pass`, `:521-641` `render-step`; `src/seon/render/block.clj:192,431,523` |
| Seon flow/graph wiring | same | `src/seon/cluster.clj:1002-1019` cluster graph definition, `:1041-1151` `arm-agents!` (where every process-local render resource is created), `:1153-1210` `disarm-agents!`; `src/seon/cluster/agent.clj:251-275` the ONE agent blueprint, `:348-410` `arm!` |
| Seon wake | same | `src/seon/cluster/wake.cljc:12-33` the two absolute listener prohibitions, `:163-228` `route!`, `:212` the unconditional render wake |
| Seon schema projection | same | `src/seon/schema.cljc:1993-2007` `activate-projection!` (the ONE publication), `:2009` `activate!`, `:2457` `matching-shapes` |
| Seon corpus install | same | `src/seon/sci/eval.clj:533+` `install-program-row!` |
| research (baselines, not re-derived) | — | `research/render-invalidation-caching-2026-07-31.md`, `render-invalidation-falsification-2026-07-31.md`, `render-scheduling-design-2026-07-31.md`, `flow-control-protocol-2026-07-31.md`, `agent-flow-render-falsification-2026-07-29.md`, `render-pipeline-design-2026-07-29.md` |
| probes (committed, reproducible) | `tmp/render-invalidation/{dependency_plan,falsify,family_pull,union_selector,reverse_cost,selector_sweep}_probe.clj` | run `clojure -M:dev <path>`; they ARE the regression baselines (§8) |

## 1. Inherited constraints — read before designing anything local

1. **Staleness is three fail-closed terms, all `not=`** (spec `:150-164`, B1/B2):
   `∃ a ∈ deps : not= revisions[a] current[a]` ∨ conservative-revision moved ∨
   the process-local code revision moved. Revisions are **commit-id UUIDs**
   (`falsify_probe.clj` D), so `<` throws and ordering is meaningless.
2. **Deps come from the READ FORM**, `query-attribute-dependencies` /
   `pull-dependency-plan` at 3.75 µs — never `q-with-evidence` (+52%,
   +110 µs/query). The evidence pass is diagnosis only.
3. **The check is defined only on a COMMITTED database value.** `d/with`
   carries `:cache-context nil`; `as-of`/`history` carry no revisions, and two
   as-of values compare equal — so a derived value is unconditionally stale
   (falsification R2).
4. **`:db/txInstant ∈ deps ⇒ always stale`** — it is `disj`'d from revisions by
   construction (`query.cljc:2575`), so a renderer displaying transaction time
   would be permanently fresh (R1).
5. **The cache is per FUNCTION CALL** — `(renderer-fn × explicit args) → bytes`
   (ruling #7(1), `README.md:1370-1377`). Hidden walk state may never be an
   invisible argument (the falsified viewer leak).
6. **Cluster-global, digest-deduped, process-local, losable** (spec `:172-174`;
   ruling 21(d) `README.md:1065-1066`). Never a second truth.
7. **Cross-branch sharing is structurally impossible** and that is correct:
   the outer cache key embeds `connection-id` = `[store-id branch]`
   (`db.cljc:400-406`, `store.cljc:50-61`), and inheritance compares
   `(subvec member 0 2)` (`query.cljc:2951-2961`). Do not design for it.
8. **Renders pin `:io`, never `:compute`** (scheduling C1): a cold index node
   restore is a synchronous konserve read on the calling thread
   (`persistent_set.cljc:432-444`).
9. **`compute-timeout-ms` bounds nothing** — it reports a fault and never
   cancels the `FutureTask` (`flow/impl.clj:29-36,301-316`). Only the sci
   `:interrupt-fn` + `time-limit` bounds a renderer.
10. **State discipline** (owner correction, `README.md:1457-1461`): durable
    state is a database fact; a genuinely required atom is acquired in the
    proc's **init** and unwound in the **`::flow/stop` transition**. And init
    runs inline on the `flow/start` caller's thread
    (`flow/impl.clj:166-167,263,323`) — **a cache holder's init must be an
    assoc, never an allocation that blocks**.
11. **The wake stays unconditional** (`wake.cljc:180-186`); selectivity moves
    into the woken pass. The listener may never park or throw (`:12-33`).

## 2. What the MVP's landed shape constrains — named, not guessed

State as of `ae0bf5704`: **the MVP has not landed in `src/`.** The last source
commits are W1/W2 work (`b4b3f0f5a` walk requires-as-refs, `64ea0a5ba`
transcript projection, `d6399b4b8` the single HTML floor, `c189a3d12` cluster
instruction facts); `08c79976c` scopes the MVP in docs only. Every item below
is therefore a constraint to VERIFY against the MVP diff before dispatching
the unit it touches — not a settled fact.

| MVP element (`context-mvp-2026-07-31.md`) | how it constrains W5/W6 |
|---|---|
| §In(2) assembly = the walk; block composition deleted from `seon.cluster.prompt` | W5 caches at `seon.render/render` call grain **inside** `walk/neighborhood` (`walk.clj:562-596`). If the MVP leaves any assembly-level composition in `prompt.cljc`, W5 must NOT add a second cache there — one grain only. |
| §In(2) "the REPL state line is the LAST line — the deliberate cache boundary" | That is the **provider's** prompt-cache boundary, not ours. Do not conflate: our cache is per render call and invisible (ruling #13, `README.md:1535-1537`). Naming them alike in code would be the classic second-mechanism error. |
| Ruling #13 clarification: exactly ONE walk per turn, re-derived fresh at that turn's basis (`README.md:1538-1549`) | The cache may never serve a stale byte to a turn; it is a derivation optimization only. P8 (walk purity, cache transparent) is the gate. |
| §In(1) grouped last-changed order, ties clustered by branch | W5's `changed-at` (U5) **replaces the MVP's ordering input**; it must feed the MVP's existing ordering function, never introduce a second sorter. If the MVP orders by anything derived from `:max-tx` or a fact timestamp, that is a defect to fix in place (R3: `:max-tx` moves on no-op re-asserts). |
| §In(4) transcript branch = `seon.render.transcript` inside the walk | Its reads must be concrete-selector reads through the U1 seam or the whole agent branch registers `:all`. Highest-value single narrowing target after `walk.clj`. |
| §In(5) `:seon.cluster/toolkit` computed membership (a corpus query) | Its deps are corpus attributes (`:seon.fn/*`, `:seon.ns/*`); a corpus commit therefore legitimately invalidates the toolkit card block. Expected churn, not a bug. |
| §Out "per-agent render proc, call-grain cache wiring, attribute-revision invalidation" | This document IS that deferred work. The MVP's uncached per-turn walk cost is the **pre-cache baseline** for §8 — capture it from the MVP's drive harness (§In(8)) before U2 lands. |
| §Out "HTML page membership inversion + floor checkbox (W4-html follows the MVP)" | **Sequencing hazard.** U7 (per-agent render proc) moves `page-of` out of the cluster proc. `page-of` still composes `block/surfaces` (`web.clj:268-274`, `block.clj:431`). If U7 lands before W4-html, the per-agent proc inherits the block-composed page and W4-html then rewrites it. **Order W4-html before U7**, or accept one rewrite and say so. |

Two contradictions to watch for in the MVP diff, each of which invalidates a
unit below rather than being worked around:

- if the MVP threads any per-walk state (a visited set, a viewer map) into
  renderer arguments, the call cache's key is no longer `(fn × explicit args)`
  and U2 is blocked until that state is removed (spec `:132-138`);
- if the MVP's walk holds more than one database value per pass, the staleness
  check has no single basis to capture and U1's collector is unsound. The walk
  today pins one `db` in the request (`walk.clj:500`); keep it.

## 3. The units, dependency-ordered

Each unit: owner files → mechanism → falsifiable acceptance → what test lands.

### U0 (gate) — the sci interrupt guard, and R1's docstring reconciliation

**Blocking dependency, not our work.** Spec `:190-201`: `arm` cancels its
scheduled timer, leaving previously acquired fns holding a permanently
disarmed flag; `interrupted?` reads only top-level ex-data and misses the
marker on the CAUSE. Three edit sites (`src/seon/sci/eval.clj` arm + assoc,
`src/seon/cluster/loop.cljc` run fork). **Gates U8 only** — U1–U7 do not
depend on it, so it must not gate the wave.

R1 (minutes, unblocks everyone): write the proc-vs-work reconciliation into
`src/seon/flow.clj:83-111`'s docstring and the flow skill — ruling #3's
`:mixed` fail-closed classifies WORK; procs stay `:io`/`:compute`. R8 rides
along: `src/seon/flow.clj:164-186` states that `compute-timeout-ms`'s 5 s
default is a fault report, not a cancellation.

Acceptance: a reader can state, from the docstring alone, why `:mixed` is both
ruled and refused. Test: none (documentation); the skill's independent
verification pass is the gate (`README.md:1467-1477`).

### U1 — the read seam: capture the dependency plan from the read FORM

**Owner:** new `seon.render.read` (small, one mechanism) + call-site
conversion in `src/seon/render/walk.clj:226-255,279-331,385-423`,
`src/seon/render/transcript.clj`, `src/seon/render/ns.clj`,
`src/seon/render/root.clj`, `src/seon/render/agent.clj:220,340`,
`src/seon/render/block.clj:226,498`.

**Mechanism.** One function per read shape — `pull`, `q` — that (a) performs
the read, (b) reduces the FORM through `d/pull-dependency-plan` /
`d/query-attribute-dependencies` (`query.cljc:2935-2944`), (c) conj's the
attribute set into an invocation-local collector. The collector is a
`volatile!` bound by the cache owner (U2) for the duration of one cached call
— write-only, never readable by a renderer, so it cannot become an invisible
argument (spec `:132-138`). The pure reduction is memoized on the form value
itself (forms are compile-time constants; `query-cache` already proves
form-keyed memoization is sound, `query.cljc:2413`), so warm cost is a map
lookup, not 3.75 µs.

Two soundness rules, both historically load-bearing
(`render-invalidation-caching-2026-07-31.md:275-285`): **fail open to `:all`**
when a read form is dynamic or a render throws; **empty means static**, and
"no evidence collected" must widen to `:all`, never collapse to `#{}`.

`walk.clj` already did most of R5 in W1 (`071ca1e50`): `family-probe` →
`concrete-entity` two-step and the per-family named reverse pull. Remaining
`'[*]` render-path sites are `agent.clj:220,340` and `block.clj:226,498`.
`web.clj`'s `generic-entity` (`:301-349`) is the **deliberate exception** —
the debug drill must show family-less attributes — so it registers `:all` and
its blocks are therefore never cached. State that in its docstring rather than
narrowing it.

**Acceptance (falsifiable).** For a generated agent walked at depth 2:
`deps ≠ :all`, and `deps` equals the union of the concrete family selectors'
attributes intersected with `(:schema db)`. `rg 'q-with-evidence' src/`
returns nothing on the render path. Capture overhead ≤ 5% of the uncached walk
measured against the MVP's baseline (§8), versus the +52% the evidence pass
costs.

**Tests.** P3 (staleness soundness) becomes assertable here in its
conservative direction. Plus one unit test per fail-open case: dynamic
selector → `:all`; throwing render → `:all`; uninstalled attribute never
enters a selector (a pull naming one **throws**, `union_selector_probe.clj`
§2).

### U2 — the cached call value and the staleness predicate (pure)

**Owner:** new `seon.render.cache`, pure functions only — no holder yet.

**Mechanism.** The entry is spec §3's map verbatim: `bytes`, `digest`, `deps`,
`revisions` (`{attribute commit-id}`), `changed-at`. `stale?` implements §1's
three terms with `not=`, plus the two rules the spec derives from R1/R2:
`:db/txInstant ∈ deps ⇒ stale`; a database value with no `:cache-context`
(speculative/`as-of`/`history`) ⇒ stale unconditionally. `deps = :all` ⇒ stale.

**Acceptance.** The sweep for 100 blocks × 1,702 deps stays **< 100 µs per
commit** (measured baseline 66.31 µs, `falsify_probe.clj` §F). At 1,000 blocks
it stays sub-millisecond.

**Tests.** One regression per revision-semantics row of falsification §R —
retraction-only advances; `retractEntity` advances; tx-meta datoms advance;
schema tx moves only conservative; merge and empty-`tx-data` move only
conservative; no-op re-assert moves nothing. These are the failure CLASS, and
the class is dead when the predicate is total over that table.

### U3 — the code-revision scalar: owner and bump sites

**Owner:** `seon.render.cache/code-revision`, one process-local `defonce`
counter compared by `not=` (process-local ⇒ a counter is honest; do not mint
UUIDs to look like Datahike's).

**Bump sites (all real publication owners, none a hand list):**

1. `seon.schema/activate-projection!` (`src/seon/schema.cljc:1993-2007`) — the
   ONE atomic projection publication, covering B3 case 3 (an agent registers a
   schema whose Datahike attributes already exist, so no conservative bump
   occurs yet `matching-shapes` picks a different winner);
2. `seon.sci.eval/install-program-row!` (`src/seon/sci/eval.clj:533+`) —
   covering B3 case 2 (an agent publishes a renderer: `:seon.fn/*` datoms move
   a user attribute's revision but bump nothing conservative).

**The residual, stated rather than papered over.** B3 case 1 — a plain REPL
redefinition of a first-party renderer Var — commits nothing and has no hook,
so no bump site exists. Two candidate covers, both cheap:

- **(a, recommended) derive it.** The entry retains the resolved projection
  symbol AND the Var's current value; `stale?` adds
  `(not (identical? f @var))`. Zero bump sites, exactly covers redefinition of
  the winner, one `identical?` per entry. This preserves `render.clj:215-219`'s
  deliberate "invoke the VAR, never a cached fn value" guarantee, which the
  cache would otherwise cancel.
- **(b) re-resolve per check.** Ruling #7(3) says override resolution IS a
  corpus query through the same query cache (`README.md:1383-1387`), so
  re-running `render/resolve-unit` also covers the one case (a) misses: a NEW
  Var interned in a governing namespace changes the winner while the old Var's
  value is untouched.

**Owner gate (§9(a)):** (a) alone leaves the new-Var-interned case false-fresh;
(b) costs a resolution per check whose price is unmeasured. Recommendation:
land (a) + the two bump sites, measure (b)'s resolution cost in the same bench,
and adopt (b) only if it is under the sweep's own budget.

**Acceptance.** `family_pull_probe.clj` §6 as a regression: define a renderer,
render, redefine it, render again with **zero transactions** — the second
render returns the new bytes. And: register a schema whose Datahike attributes
already exist → the affected block re-renders.

### U4 — the cluster-global cache holder, under the state discipline

**Owner:** `src/seon/cluster.clj:1041-1151` `arm-agents!` (creation) and
`:1153-1210` `disarm-agents!` (release); consumed through the handle by every
render proc.

**Mechanism.** The holder is created **exactly where `:seon.render.web/
registration` and the render/pages channels already are** (`cluster.clj:1054-
1067`) — process-local, cluster-scoped, free to lose — and dropped where those
channels are closed (`:1207-1208`). It is NOT created in a proc's init,
because it is shared by N per-agent render procs and the cluster delivery
tier; a proc-local holder would break P5 (two agents referencing one
instruction row must produce byte-identical prefixes) and ruling #7(1)'s
cross-agent byte identity by construction. Each render proc's `init` only
`assoc`s the already-open holder into its state — the cheap-init law
(`flow-control-protocol-2026-07-31.md:467-471`), and the same shape
`render-step`'s init already uses for its ports (`web.clj:569-598`).

Two maps in one holder, per ruling 21(d) "each output stored AT MOST ONCE":
`{call-key → entry}` and `{digest → bytes}`; the entry carries the digest, not
the bytes. Bound: derive from the existing `:seon.sci.admit/caps` size dials
rather than inventing a number; if a genuinely new dial is needed it is one
config fact beside `:seon.config.render/coalesce-ms`
(`config/default.edn:114`), never a literal.

**Acceptance.** Steady-state heap delta at 1,000 cached blocks is measured and
recorded (no target invented — the measurement IS the acceptance, compared
against the +7.3–9.2 KB/agent proc budget). Stopping and restarting the
cluster graph leaves no cache and one re-derivation restores every byte
(losable-by-construction proof). P5 holds across two agents.

**Tests.** P5 (prefix sharing) and a dedupe assertion: N agents sharing one
instruction row store its bytes once.

### U5 — the ordering key: `changed-at` from digest transitions

**Owner:** `seon.render.cache` (derivation) + whatever the MVP's walk ordering
function is (`seon.render.walk`, consumption).

**Mechanism.** On recompute: if the new digest differs from the retained
digest, `changed-at` := the current committed value's basis `t`; if it is
unchanged, the previous `changed-at` is retained. A never-before-computed
entry takes the current basis. **Never a fact** — the spec falsified both
fact-side derivations (`:max-tx` moves on no-op re-asserts; a pull returns no
tx, spec `:165-171`, falsification R3). The state lives in the cache entry and
nowhere else.

Display order = dumb last-changed across all units regardless of tree position,
ties clustered by branch (ruling #7(2)). W5 supplies the key; it must not add a
sorter beside the MVP's.

**Acceptance.** A no-op re-assert of an identical value reorders nothing
(today's `:max-tx`-based derivation would reorder everything). A transaction
touching one block moves that block tailward and leaves every other relative
order intact (P4).

**Known and accepted:** on a cold process every entry's first `changed-at` is
the same basis, so order degenerates to branch clustering until the first few
commits differentiate it. That is the price of process-local derived memory
(spec `:172-174`); measure how many commits it takes to settle and record it
rather than seeding a fake history.

**Tests.** P4 (ordering stability).

### U6 — the staleness sweep's home: the wake path

**Owner:** the woken pass — `web.clj:467-519` `render-pass` until U7 lands,
then the per-agent `::renders` transform.

**Mechanism.** The listener stays exactly as it is: one unconditional
payload-free render wake per report (`wake.cljc:212`), because matching there
would be a hand list and would bill Datahike's writer loop (`:12-33,180-186`).
The sweep is the **first thing** the woken pass does: for each registered
entry, `stale?` against the db value the pass already pinned. Only stale
entries recompute.

**Acceptance.** p99 < 50 µs per registration set (scheduling report §3 —
"else it is not a check, it is a render"). Worst case C falsifier: commit one
hot attribute 100×; total renderer invocations ≈ the number of genuinely stale
blocks, not 100 × blocks; concurrent submissions ≤ the launcher's permit count;
no growth in platform threads.

**Tests.** P3, asserted on a live cluster pass rather than in isolation.

### U7 — the per-agent render proc (ruling 21 / scheduling R2)

**Owner:** `src/seon/cluster/agent.clj:251-275` (the ONE blueprint gains
`::renders`), a new `render-step` beside `mailbox-step`/`turn-step`;
`src/seon/cluster.clj:1002-1019` (the cluster proc loses production, keeps
delivery); `src/seon/cluster/wake.cljc` (fan-out, below).

**What moves out of the cluster-wide proc:** per-agent page derivation —
`page-of` and its `block/surfaces` composition (`web.clj:498-512,244-274`).
**What stays in the cluster tier:** delivery — the pages `mult`
(`cluster.clj:1148-1150`), per-tab taps and their sliding-1 buffers, the
`registration` atom, per-tab `changed` suppression (`web.clj:413-437`), and
one serialization per package.

**Ports and buffers** (transport law, scheduling §4.3):

| port | direction | buffer | why |
|---|---|---|---|
| `::interest` | in | `(sliding-buffer 1)` | a wake says only "look"; the pass derives from the newest db value |
| `::stream` | in | `(sliding-buffer 1)` | newest partial supersedes; the terminal fact supersedes all. **Wiring change:** today one cluster stream channel is keyed by agent-id (`web.clj:614-618`, `cluster.clj:1044`); per-agent procs want their own edge |
| `::page` | out | `(sliding-buffer 1)` per agent | the newest page repairs itself; the delivery mult must never be backpressured by one agent |

**The wake fan-out.** Per-agent interest means N deliveries per report. Doing
that N-way `offer!` inside the listener bills Datahike's writer loop, which
`wake.cljc:22-28` forbids. So: the listener keeps its ONE `offer!` into the
cluster render channel, and a small cluster-graph `::render-fanout` proc (`:io`)
offers into each armed agent's `::interest` channel, read from the routing
entry (`cluster.agent/channels`, `agent.clj:326-331`, extended with an
interest-channel map alongside `::channels`). One extra proc, no new mechanism,
and the O(N) is off the writer thread.

**The coalesce floor moves with production.** `:seon.config.render/coalesce-ms`
(`config/default.edn:114`, honored at `web.clj:622-631`) becomes per agent —
one derivation per agent per window instead of one per cluster per window.
That is the intended fairness change and must be stated in the docstring, not
discovered.

**Acceptance (worst case A falsifier, scheduling §4.2).** One agent with a
200 ms renderer plus 20 idle watched agents: an idle agent's commit→package
p95 is **unchanged by A's presence** (today it grows by ~200 ms). Cost budget
already measured: +7.3–9.2 KB/agent, +19 µs/agent, **0 new platform threads**
at 100 agents (`agent-flow-render-falsification-2026-07-29.md` §2) — and C4:
the agent graph must keep passing only `:procs`/`:conns` to `create-flow`
(`agent.clj:258-275`); handing it `root-executors` turns every proc into a
platform thread and evaporates that measurement.

**Tests.** The A falsifier as a live proof at a reset boundary (a fixture
cannot see this class). Plus D's falsifier preserved: SIGSTOP a tab's reader,
other tabs' delivery p95 unchanged, `::passes` still advancing.

### U8 — agent-authored renderers through the one door (scheduling R3)

**Gated by U0.** **Owner:** the resolution seam in `src/seon/render/block.clj`
(`:891-911` region, first-party vars only today) + U7's `::renders` transform.

**Mechanism.** First-party renderers stay inline on the `:io` proc (trusted,
bounded, and they read the database — same blocking argument, C1). Every
**agent-authored** renderer goes through `seon.flow/submit!!`
(`src/seon/flow.clj:481-523`), armed once per render PASS and invoking the
installed sci fn VALUES directly — never `requiring-resolve`, which throws on
corpus fns (spec `:182-189`; measured armed invoke 3.2 µs vs full `evaluate`
100.8 µs; per-entrance interrupt check 7.8 ns). **One submission at a time per
agent**, so the "1 permit per agent" property that evals have structurally
(`loop.cljc:304-320`) extends to renders (worst case E).

One new dial, `:seon.config.render/time-limit-ms`, beside
`:seon.config.eval/time-limit-ms` (`config/default.edn:39`) — same door, same
dial family, shorter default because a renderer that needs 30 s is a bug
(ruling #6, `README.md:1359-1369`). No render-specific door.

**Acceptance (worst cases B and E).** `(loop [] (recur))` as a block's
`:seon.render/html` resolves to a bounded flat error value within the limit;
the proc's `::passes` still advances; every other agent's page is unaffected.
No agent holds more than one permit while 17 other agents each need an eval.
Second falsifier: a renderer blocked in a HOST call — the known interrupt
ceiling — must be bounded by permit release, not by the interrupt.

**Tests.** P7 (door totality). Catch sites must mirror `admit`'s interrupt
pass-through; a bare `catch Throwable` swallowing the uncatchable interrupt is
itself the defect (spec `:202-205`).

### U9 — keyframe-serve for a new tab (scheduling R6 / F3)

**Owner:** `src/seon/render/web.clj:569-580` (the per-tab initial paint calls
`page-of` today, i.e. one full derivation per tab open).

**Mechanism.** Serve the delivery tier's latest produced page instead of
deriving. This is the cheap half of the packages design and needs none of it.

**Acceptance.** Opening 50 tabs performs **one** derivation. Measured
alternative: 0.872–1.171 ms p95 shared versus 31.783–42.479 ms p50 per-tab
(`render-pipeline-design-2026-07-29.md:259-268`).

### U10 — launcher repairs that this wave makes live (separate lane)

Not render-owned; sequence them beside the wave so they do not conflict with
render files (`src/seon/flow.clj` only).

- **R7 — bound `submit!!`'s injection wait** (`flow.clj:500`: `.get` with no
  timeout; the `time-limit-ms` deref starts only after the item enters the
  fixed queue, `:501`). Render submissions make queue contention real, so land
  R7 **before** U8. Acceptance: with the queue full, a submitting caller settles
  with a `:seon.flow/time-limit` value carrying its wait rather than parking
  without a clock.
- **R4 — the lifetime-vs-CPU permit split** (`flow.clj:314-318`). Per C1 the
  first blocking leaf already exists (a cold index restore inside submitted
  work), so this is live today. Acceptance: M=72, C=18, L=100 ms ⇒ ≈100 ms
  versus today's ≈400 ms (`workload-scheduling-truth-2026-07-29.md:410-418`).
  **Does not gate this wave**; U8's per-agent serial submission is the local
  mitigation.

## 4. Fairness table — which of R3–R8 land here

| rec | lands in this wave? | where |
|---|---|---|
| R1 proc-vs-work reconciliation | yes, first | U0 |
| R2 per-agent render proc | yes | U7 |
| R3 renderers through `submit!!` | yes, gated by U0 | U8 |
| R4 CPU-permit split | **no** — separate launcher lane, named not deferred-silently | U10 |
| R5 name the pull patterns | yes, the remainder | U1 |
| R6 keyframe-serve a new tab | yes | U9 |
| R7 bound the injection wait | yes, sequenced before U8 | U10 |
| R8 document `compute-timeout-ms` | yes, free | U0 |

## 5. Dependency order

```
U0(R1,R8) ─┬─> U1 ─> U2 ─> U3 ─> U4 ─> U5
           │                     └─> U6 ─> U7 ─> U9
           └─> [U0 sci guard] ──────────────> U8
                              U10(R7) ──────────┘
W4-html ──────────────────────────────> U7   (sequencing hazard, §2)
```

U1→U2→U4 is the spine: no cache without a sound dependency set, no holder
without a value shape. U5 and U6 are independent of each other. U7 needs U6
(a per-agent proc with no sweep is just relocation, not simplification — the
conversion test, `CLAUDE.md`). U8 needs U7's proc and U0's guard. U9 is
delivery-side and conflicts with nothing.

## 6. The properties (spec §6) and the units that make them assertable

| property | unit |
|---|---|
| P1 membership completeness | already the walk's (MVP); W5 must not change membership — assert cache transparency instead (P8) |
| P2 resolution determinism | U3 (the code revision is exactly the resolution-changed signal) |
| P3 staleness soundness (conservative direction only) | U1 + U2 + U6 |
| P4 ordering stability | U5 |
| P5 prefix sharing | U4 |
| P6 elision loudness | unchanged from W1/W2 |
| P7 door totality | U8 |
| P8 walk purity — same db value in, identical bytes out, cache transparent | U2 + U4, and it is the single most important regression of this wave |

Every proof must be claimed by a recurring surface. The µs-level numbers stay
committed benches re-run at checkpoints; the CLASS assertions (P3 soundness,
P8 transparency, the U1 `q-with-evidence` absence gate, the U2 revision-
semantics table) land in `bin/test`.

## 7. Explicitly deferred — with the reason, not by omission

- **Packages / keyframes delivery** (revisioned packages, delta fragments, the
  serialize-once keyframe multed to all tabs). Spec `:270-272`: stays TARGET
  until after the walk lands. U9 takes only its cheap half.
- **Entity- or value-level narrowing (E/A/V).** 100% precise but obliges a
  `db-before` read per candidate on the writer thread, which `wake.cljc:22-28`
  forbids; at 66 µs for a whole 100-block sweep there is no measured reason.
  `src-old/seon/db/writer.clj:2756-3205` stays quarry for a **measured** hot
  attribute only (ruling #2(1), `README.md:1281-1286`).
- **Cross-cluster / cross-branch cache sharing.** Structurally unrepresentable
  (§1.7). Parsing and planning are already process-global and free.
- **A `seon.db` facade.** The evidence-capture pass it existed to host is
  falsified (+52%); U1 captures from the read form instead. Revisit only if a
  different consumer needs it.
- **The lifecycle/pause surface, oversight facts.** Rulings #9 correction and
  #10; nothing in this wave stores a status.
- **The `:my/*` rename.** Awaits the owner's explicit ruling.

## 8. The measurement suite — the reports' numbers become the baselines

| measurement | baseline | source | becomes |
|---|---|---|---|
| staleness sweep, 100 blocks / 1,702 deps | 66.31 µs/commit | `falsify_probe.clj` §F | U2 gate: < 100 µs |
| dependency capture, pure | 3.75 µs | same | U1 mechanism |
| `q-with-evidence` | 320.85 µs vs 210.57 µs plain (+52%) | same | U1 prohibition (grep gate) |
| two-step forward read | 47.86 µs (24 concrete deps) vs `'[*]` 6.95 µs (`:all`) | `selector_sweep_probe.clj` §2 | accepted 7× — the price of selectivity |
| reverse: derived 26-entry pull | 270.66 µs vs generic 11.33 µs | `reverse_cost_probe.clj` | why the walk narrows per family (~35 µs for 3 ref attributes), not universally |
| sci fork | 72 ns | sci-door report | U8 ctx sharing |
| armed direct invoke vs `evaluate` | 3.2 µs vs 100.8 µs; interrupt check 7.8 ns | same | U8 mechanism |
| once+mult at 50 tabs | 0.872–1.171 ms p95 vs 31.783–42.479 ms p50 per-tab | `render-pipeline-design-2026-07-29.md:259-268` | U9 gate |
| per-agent proc cost | +7.3–9.2 KB, +19 µs, 0 new platform threads @100 agents | `agent-flow-render-falsification-2026-07-29.md` §2 | U7 budget |
| lifetime vs CPU permit | 425.9 ms vs 102.7 ms (M=72, C=18, L=100 ms) | `workload-scheduling-truth-2026-07-29.md:410-418` | U10/R4 |
| **uncached per-turn walk cost** | **TO CAPTURE from the MVP drive harness** | `context-mvp-2026-07-31.md` §In(8) | the whole wave's before/after |

New benches are committed code with their numbers recorded in
`research/` — an unreproducible number is an anecdote
(`CLAUDE.md`, operating rules).

## 9. Owner-gate questions (ask before the units that depend on them)

a. **The code revision's third case.** Derived var-value identity (a), or
   re-resolve per check (b) which also covers a newly interned Var changing the
   winner? Recommendation: land (a) + the two bump sites, measure (b), adopt
   (b) only if it fits the sweep budget. Blocks U3's exit, not its start.
b. **The cache bound.** Derive from `:seon.sci.admit/caps` size dials, or add
   one `:seon.config.render/*` fact? Recommendation: derive first; add a dial
   only when a measurement demands it. Blocks U4's exit.
c. **Per-agent coalescing.** U7 turns "one derivation per cluster per 16 ms"
   into "one per agent per 16 ms" — intended, but it is a semantic change to a
   config fact's meaning. Confirm.
d. **Cold-start ordering degeneracy** (U5): accept the branch-clustered
   first-pass order, or is a settle-time bound wanted? Recommendation: accept
   and measure.
e. **The debug view's generic read** (`web.clj:301-349`) registers `:all` by
   design and is therefore uncacheable. Confirm it stays uncached rather than
   being narrowed (narrowing it would delete the drill's whole purpose).
