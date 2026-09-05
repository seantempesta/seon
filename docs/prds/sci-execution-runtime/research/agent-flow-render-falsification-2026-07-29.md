---
type: research
status: active
tags: [research, flow, render, agent]
---

# Agent-flow render falsification — the third proc

Falsification of plan ruling 21 ("rendering moves into the agent's flow"),
run in the REPL against the live fresh tree on 2026-07-29. No `src/` file
changed.

The prototypes are committed as evidence, because an unreproducible number is
an anecdote:

- `research/scripts/agent-flow-render-interest-2026-07-29.clj` — the old
  system's interest mechanism, adapted (§1);
- `research/scripts/agent-flow-render-falsify-2026-07-29.clj` — the four
  experiments.

Reproduce (the interest namespace must load first — `falsify` requires it):

```bash
clojure -M:dev:test -e '
(load-file "docs/prds/sci-execution-runtime/research/scripts/agent-flow-render-interest-2026-07-29.clj")
(load-file "docs/prds/sci-execution-runtime/research/scripts/agent-flow-render-falsify-2026-07-29.clj")
(falsify/-main)'
```

**NOT PRODUCTION CODE.** These prototypes exist to break the design before a
contract is sealed; nothing in them may be copied into `src/` as-is (see §6.6).

## Verdict

**The third proc survives falsification.** All four targets pass, and the one
mechanism the owner asked for — ONE registration shape serving html blocks and
the agent's own ai context pieces — is not a new mechanism at all: the block
row already carries `:seon.render/ai` and `:seon.render/html` side by side, and
`seon.render.block/surface` already renders either. The proc adds memory and a
wake; it adds no vocabulary.

| target | verdict | headline number |
|---|---|---|
| 1. wake-router render-interest delivery | **holds**, reusing the old writer's index | 1 of 1,001 interests addressed, 0.49 µs per report |
| 2. proc cost at 100 parked agents | **holds** | +8.9 KB heap/agent, **0 new platform threads**, +19 µs arm |
| 3. one mechanism for all render kinds | **holds** | 12 registrations (8 ai + 4 html), one memory, one pass |
| 4. cross-namespace production | **holds** | A's proc rendered B's entity on A's virtual thread; B has no proc |

Two honest breaks, both about interest NARROWNESS rather than the proc:

- **Wildcard pull is the widener.** `(d/pull db '[*] eid)` yields a dependency
  plan of `:all`. The `:focus` html block and the `:namespace` ai walk both
  wildcard-pull unconditionally, so they can never narrow as written. Narrow
  waking is earned by naming a pull pattern; `'[*]` is the opt-out.
- **Captured plans are data-dependent.** An unexecuted branch contributes no
  attributes, so a fresh agent's `:transcript` block registers 7 attributes and
  the same block after one message registers `:all`. This is a one-render lag,
  **not a missed wake** — proven in E1b, argument in §1.4.

## Dependency ledger

| dependency or mechanism | revision | source read |
|---|---|---|
| the old interest mechanism (the thing being reused) | tree `src-old/` | `seon/db/writer.clj:2756-3205`, `seon/reactive.cljc:120-349`, `seon/db.cljc:320-348` |
| Datahike dependency-plan API | pinned fork `reference-code/datahike` | `src/datahike/api/specification.cljc:460-475,523-549,595-622` (`q-with-evidence`, `query-dependency-plan`, `dependency-plan-attributes`, `pull-dependency-plan`, `pull-with-evidence`) |
| the wake router being extended | current tree | `src/seon/cluster/wake.cljc:98-175` |
| the agent graph being extended | current tree | `src/seon/cluster/agent.clj:246-282` (`graph-definition`), `:283-380` (`arm!`/`disarm!`) |
| proc construction function | current tree | `src/seon/flow.clj:83-127` (`var-process`) |
| the registration shape already in the tree | current tree | `src/seon/render/agent.clj:440-499` (`blocks`), `src/seon/render/block.clj:266-292` (`membership`), `:362-438` (`surface`) |
| the delivery tier this feeds | current plan | `research/render-pipeline-design-2026-07-29.md` (composite package, per-tab sliding-1) |
| parked-proc baseline being compared against | 2026-07-28 | `research/flow-mechanics-2026-07-28.md` §1 (~8.5 KB/parked proc) |
| the falsified alternative | 2026-07-29 | `research/query-invalidation-2026-07-29.md` §Read-tracing feasibility |

## 1. Wake routing — what was kept from the old system

Owner directive: reuse, do not redesign. The prototype
(`tmp/agent-render-falsify/interest.clj`) is the old writer's mechanism
translated from the pod↔writer wire protocol to an in-process per-agent proc.

### 1.1 Kept

| old owner | what it does | kept as |
|---|---|---|
| `writer.clj:2774-2780` `interest-attributes` | `:all` or a set; falls back to the patterns' attributes | `interest/interest-attributes`, verbatim |
| `writer.clj:2782-2810` `add-/remove-interest-to-entry` | the reverse candidate index `{::all #{ref} ::by-attribute {attr #{ref}}}` | `interest/add-interest`, `interest/remove-interest`, verbatim in shape |
| `writer.clj:3174-3190` `candidate-interests` | union of `::all` with every attribute bucket the report's datoms touch | `interest/candidate-interests` |
| `writer.clj:2984-3002` `datom-matches-pattern?` | exact E/A/V/added? matching | `interest/datom-matches-pattern?`, translated to raw Datahike datoms |
| `writer.clj:3004-3014` `matching-datoms` | the confirm stage after the index narrows | `interest/matching-datoms` |
| `writer.clj:2847-2850` `merge-read-dependencies` | `:all` absorbs; sets union | `interest/merge-read-dependencies`, verbatim |
| `writer.clj:2864-2899` `evidence-dependencies`/`listen-interest` | reduce captured plans to dependency ATTRIBUTES via `d/dependency-plan-attributes` | `interest/evidence-dependencies` |
| `db.cljc:320-348` `record-query-evidence!` | capture the plan the READ returned, never rebuild `(e,a)` from rows | `interest/with-read-evidence` |
| `reactive.cljc:120-128` `evidence-signature` | re-install only when the plan signature changes | `interest/install!`'s same-signature no-op |
| `reactive.cljc:141-157` `result-envelope`, `web/feed.clj:145-153` | `:all` as the documented fail-open case | `interest/widen!` |

The two-stage shape is the load-bearing part and it is the old system's, not a
new invention: **the reverse index narrows to candidates, then per-interest
matching confirms.** Neither stage alone is sufficient — the index alone
over-delivers on shared attributes, the matcher alone is O(interests).

### 1.2 Adapted, with reasons

- **The reference is `[agent-id registration-name]`, not
  `[transport-connection request-id owner]`.** The old triple plus
  `current-interest`'s identity check existed because an interest lived across
  a wire and a reconnect could resurrect a stale request id. In-process the
  holder is the agent's own proc state, which dies with the proc.
- **Datoms are raw, not wire maps.** `datom-matches-pattern?` read
  `:seon.db/e/a/v/added?` off a protocol datom map; a fresh `listen!` report
  carries Datahike datoms, so the matcher indexes `nth`. Pattern keys keep the
  old `:seon.db/*` spelling so the two can be diffed.
- **An EMPTY dependency set is legal here and means STATIC.** The old writer
  threw `"A query interest must depend on a database attribute"`
  (`writer.clj:2891-2895`) because a wire interest that never fires is a caller
  bug. Under ruling 19 an empty set is the correct answer for a block that
  reads no database at all — measured for `:identity`, `:execution`,
  `:trigger`, `:message-bar`. Those install nothing and never wake.

### 1.3 Deliberately dropped

- **`::by-scope`** (database-name + connection-id + branch). One cluster is one
  store and one branch by standing law, so there is exactly one scope; a second
  key would be dead structure.
- **`reactive.cljc`'s `::pending-db` / `::dirty-at` / `settle-delay` / `arm!`
  scheduler** (`reactive.cljc:252-306`). That is a hand-built coalescing queue.
  A `(sliding-buffer 1)` in-port plus one proc pass IS that mechanism, already
  owned by flow — reintroducing it would be a second scheduler.
- **`::patterns` as an agent-facing surface.** The matcher is kept (three lines,
  and it is what makes the index exact), but nothing in the render path authors
  patterns; plans are the automatic path, exactly as the old system had it
  (`query-invalidation-2026-07-29.md` §Old precedent, point 3).
- **Returned-`(e,a)` tracing.** Already falsified. Confirmed again here:
  `agent-header-html` pulls `:seon.cluster.agent/run`, which is ABSENT on a
  fresh agent, and the **plan still registers it**
  (`:absent-attribute-registered? true`). A returned-pair tracer would have
  missed it and skipped the wake.

### 1.4 Measured

Five agents, real renderers, real `d/listen`:

```clojure
;; captured interest, per registration, from the real block set
[:agent-header  :seon.render/html] #{:seon.cluster.agent/id :seon.ns/name
                                     :seon.cluster.agent/namespace
                                     :seon.cluster.agent/run}
[:assignments   :seon.render/ai]   #{:seon.cluster.agent/id :seon.problems/id
                                     :seon.cluster.message/about
                                     :seon.cluster.message/to
                                     :seon.cluster.message/from}
[:peers         :seon.render/ai]   #{:seon.cluster.agent/id}
[:settlement    :seon.render/ai]   #{:seon.cluster.run/id
                                     :seon.cluster.run/plan-digest}
[:transcript    :seon.render/html] #{:seon.cluster.message/to
                                     :seon.cluster.message/from
                                     :seon.cluster.agent/id
                                     :seon.cluster.run/agent
                                     :seon.cluster.eval/run
                                     :seon.error/run :seon.error/agent}
[:focus         :seon.render/html] :all      ; wildcard pull
[:namespace     :seon.render/ai]   :all      ; wildcard pull inside the walk
[:identity :execution :trigger :message-bar] #{}   ; static
```

| observation | result |
|---|---|
| commit touching only `:seon.cluster.message/*`, 4 narrow agents + 1 `:all` agent | woke `#{a3}` — the `:all` one only |
| commit touching `:seon.ancestor/digest` (read by nobody) | woke `#{a3}` — the four narrow agents correctly skipped |
| the same commit under today's unconditional mode | woke all 5 |
| absent attribute (`:seon.cluster.agent/run`) present in the index | **yes** |
| one exact interest among 1,001 registered | 1 addressed |
| two-stage delivery cost per report, 1,001 interests | **0.49 µs** |
| 3 commits into an undrained (parked) render channel | **1** wake retained — coalescing, latest-wins |
| commit while a render channel is CLOSED | commit succeeds; `offer!` returns false; nothing throws |

`d/pull-dependency-plan '[*] [1]` returns
`#:datahike.query.dependency{:sources [{:attributes :all}]}` — the widener,
named.

**The data-dependence soundness argument (E1b).** `transcript-html` queries for
this agent's messages (the GUARD) and then wildcard-pulls each one. On a fresh
agent the pull never runs, so the captured plan is the 7-attribute set above.
Commit one message and the same capture returns `:all`. This is safe because
**the guard read is always captured**: `:seon.cluster.message/to` is in the
narrow set, so the commit wakes the agent, the render re-runs, the wildcard pull
executes, and the plan re-registers wider. One render of lag, never a missed
wake. The general rule: a branch can only be taken because of a fact the
renderer READ to decide, and that read is in the plan. Re-registering the plan
after every pass (the old `evidence-signature` rule) is therefore not an
optimization — it is the correctness condition.

### 1.5 The blocker this exposes

The fresh tree has **no read seam**. All five render owners call
`datahike.api` directly (`render/agent.clj:97,108,126,136-158,220,340`,
`render/walk.clj:221,285,307,325,361`), so the prototype captures plans by
rebinding `d/q`/`d/pull`/`d/entity` with `with-redefs` — process-global and
unusable in production. Narrow waking requires one capture seam covering every
`q`, `pull` and `entity` read. Until it exists, `:all` is the correct answer and
today's unconditional per-report render wake (`wake.cljc:112-118`) is not a
defect — it is the fail-open case the old JVM feed also used
(`src-old/seon/web/feed.clj:145-153`).

## 2. Proc cost at 100 parked agents

100 agents in one in-memory cluster, real `seon.cluster.agent/graph-definition`
for the baseline, that definition plus the render proc for the target, created
→ started → resumed → parked (no wake primed). JIT and graph machinery warmed
by two discarded measurements first; the third-proc pass runs BEFORE the
two-proc pass so warm-up cannot be read as the render proc's cost.

| measurement | 2 procs/agent | 3 procs/agent | delta per agent |
|---|---|---|---|
| retained heap after park | 1.68–1.75 MB | 2.40–2.68 MB | **+7.3 to +9.2 KB** |
| implied per parked proc | 8.4–8.8 KB | 8.0–8.9 KB | — |
| platform threads before → after | 73 → 73 | 72 → 72 | **0** |
| `create-flow` (pure data) | 0.76–1.05 ms total | 0.99–1.44 ms total | +2.2 to +3.9 µs |
| `start` + `resume` | 2.74–3.84 ms total | 4.54–5.75 ms total | **+18 to +19 µs** |

Conditions: JDK 26, `-Xmx512m -XX:+UseG1GC`, `clojure -M:dev:test`, in-memory
Datahike, three `System/gc` calls plus a 120 ms settle before each heap read,
400 ms park before the post-measurement.

Reading: the render proc is **the same class of cost as the two procs already
there** — it makes an agent's parked footprint ~1.5×, about 0.9 MB for 100
agents, and costs ~19 µs at arm. Zero new platform threads at 100 agents
confirms `:io` pins virtual threads through `var-process`, and the ~8.5 KB
figure from `flow-mechanics-2026-07-28.md` §1 reproduces independently here.
No cliff was found at 100; the measurement was not pushed to 1,000 and the
per-proc figure includes the proc's own state map, which for the render proc
will grow with the memoized outputs it holds (see §5, open decision 3).

## 3. One mechanism for all render kinds

The claim under test is the owner's: "we can cache all data this way, not just
html." Measured on one real agent's real default block set, 12 registrations —
**8 `:seon.render/ai` and 4 `:seon.render/html`** — through one pass with one
memory:

| pass | derivations | emitted | note |
|---|---|---|---|
| first | 12 | 12 | cold memory |
| idle wake, zero fact change | **11** | **0** | the static registration is not re-derived; 12/12 suppressed by digest |
| after one committed message | 11 | **3** | `[:focus html]`, `[:transcript html]`, `[:namespace ai]` |

That third row is the whole point: **one wake, one pass, one memory
re-derived an ai context piece and two html blocks together.** There is no
prompt-time render path and no page-time render path; there is a pass.

The context assembly is then a projection of the same proc state, churn-ordered
stable-first (ruling: stability-descending):

```clojure
[{:piece [:execution :seon.render/ai]    :churn 1 :bytes 317}
 {:piece [:identity :seon.render/ai]     :churn 1 :bytes 78}
 {:piece [:static-note :seon.render/ai]  :churn 1 :bytes 28}
 {:piece [:namespace :seon.render/ai]    :churn 2 :bytes 213}]
```

Churn is the count of digest CHANGES the memory has seen — derived, not
declared, and free because the digest is already computed for suppression.

**Ruling 19 confirmed on both sides.** A registration whose projection is a
literal (`:seon.render/ai "…"`) is STATIC: `static-never-rederived? true`, and
its measured interest is `#{}`, so it installs no listener at all. The two
independent derivations agree — the input contract says static and the captured
plan says empty — which is the check that would catch "a static block whose
bytes churn is lying about its inputs."

**This is why it solves the context problem.** Today the prompt is assembled by
re-rendering every ai block on the turn's critical path. With the third proc the
pieces are already derived, already digested, already ordered; a turn reads them
instead of computing them, and the pieces that did not change did not cost
anything. The 11-vs-12 and 0-emitted numbers above are that saving measured.

## 4. Cross-namespace production

Agent A's render proc registered one block whose projection symbol names a
plain `defn` (standing in for a corpus row) that reads agent **B**'s entity.
Result:

```clojure
{:package {:revision 1 :changed #{[:peer-b :seon.render/ai]}
           :virtual-thread? true :agent "A"}
 :b-rendered-by-a ("peer B namespace: my.agents.B")}
```

Production stayed in A's proc, on A's virtual thread. B's function was
**called**, not messaged: B had no proc in this graph at all. There is no
process coupling to design, because a renderer is a function of a database
value and the database value is A's. The late-var resolution
`seon.render/render` already performs (`render.clj:31-42`) is what makes the
corpus row work the same way once it is a fact instead of a `defn`.

## 5. The working design — the third-proc blueprint extension

### 5.1 Proc

One more proc in `seon.cluster.agent/graph-definition`, pinned `:io` through
`seon.flow/var-process`, exactly like its two siblings:

```clojure
::renders
{:proc (seon.flow/var-process
        #'render-step :io
        {:seon.cluster.loop/cluster handle
         :seon.cluster.agent/id agent-id})}
```

Four arities, flow's own contract. `:ins {}` — its input is an in-port, the
same idiom `::mailbox` uses for the wake channel. `:outs {::package …}`.
`:ping-map-fn` publishes `::passes`, `::revision`, and the memory's size, so
"is this agent's page current" is a ping, not a query.

Init declares its ports as dependencies and REFUSES to be built without them,
the rule `render/web.clj:408-420` already states: a nil in-port leaves the proc
`:running` with an unreadable port and its stop transition never runs, which
turns `disarm!` into a hang.

### 5.2 Channels

| edge | value | buffer | workload | loss is free because |
|---|---|---|---|---|
| `route!` → `::interest` in-port | payload-free "look" | `(sliding-buffer 1)` | listener `offer!`s only | the pass derives everything from the newest database value |
| `::package` out-port → cluster delivery | one revisioned package | `(sliding-buffer 1)` per page | `:io` | the newest package repairs itself (keyframe), per `render-pipeline-design-2026-07-29.md` |

Nothing else. Production is per-agent and ends at the package; delivery
(tabs, `mult`, keyframe snapshot, parked writer) stays per-cluster and unchanged.

### 5.3 Registration — one shape, every kind

The registration IS the block row already in the tree; no new attribute, no
registry table:

```clojure
{:seon.render.block/name :transcript
 :seon.render.block/band :dynamic
 :seon.render.block/priority 40
 :seon.render/html `transcript-html      ; and/or
 :seon.render/ai   `namespace-ai}
```

The proc's registration set is `(block/membership db agent-id)` × the kinds each
block actually declares — derived per pass, so an agent that commits a block
is registered by the next wake and nothing installs anything. `seon.render/kinds`
already computes the kind set from the value, so no kind is enumerated anywhere.

Per-registration memory (process-local, losable, rebuilt by one pass):

```clojure
{[:transcript :seon.render/html]
 {:digest "…" :output <hiccup> :kind :seon.render/html
  :churn 3 :interest #{:seon.cluster.message/to …}}}
```

### 5.4 Wake-interest derivation

`interest = f(captured dependency plans)`, with `:all` fail-open:

1. the pass renders under the read seam, which records each read's Datahike
   dependency plan (`q-with-evidence` / `pull-with-evidence`, or the plan
   functions directly);
2. `dependency-plan-attributes` reduces plans to attributes; `:all` absorbs;
3. `install!` re-registers only when the signature changed;
4. `route!` gains ONE block — the reverse index narrows, `matching-datoms`
   confirms, `offer!` delivers. Never a second listener.

Until the read seam exists, step 1 returns `:all` and step 4 degenerates to
today's unconditional wake. **This is a correct ladder, not a stub**: the same
code path, one derivation weaker.

### 5.5 Context assembly

The turn's prompt reads the render proc's memory instead of re-rendering:
filter `:seon.render/ai`, drop nil outputs, order by `(churn, name)` ascending.
The pieces are already bytes and already digested.

The seam to settle: mailbox and turn are separate procs from `::renders`, so
the turn must read the memory without a channel round-trip. `ping-proc` is
observation, not a dependency. The clean shape is that the memory lives in an
atom the agent's handle carries — process-local derived state, the same class as
`seon.cluster.agent/routing` — written only by `::renders` and read by `::turn`.
Single writer, so no coordination. This is the one piece of the design that is
NOT falsified below and is the first thing the implementation wave should break.

## 6. Breaks and risks found

1. **No read seam** (§1.5). Blocks narrow waking entirely. Not a blocker for the
   proc; a blocker for its optimization.
2. **Wildcard pull defeats narrowing.** `:focus` and `:namespace` are `:all`
   today. Worth naming as a renderer rule: name your pull pattern or accept
   `:all`. It is a derived consequence, not a hand list.
3. **Data-dependent plans lag one render** (§1.4). Sound, but only because
   re-registration happens every pass — an implementation that installed once
   at arm time would be WRONG.
4. **Memory growth is unbounded by construction.** The proc holds every
   registration's last output. For a 250-event transcript that is ~85 KB per
   agent (`render-pipeline-design-2026-07-29.md`), i.e. ~8.5 MB at 100 agents —
   an order of magnitude above the proc's own 8.9 KB. The measurement in §2 does
   NOT include held outputs. This is the real cost of "cache all data this way".
5. **The turn↔renders read path is unproven** (§5.5).
6. **`with-redefs` in the prototype is not production-safe.** Named so nobody
   copies it.
7. **`:seon.cluster.message/to` is in both the routing wake set and the render
   interest set.** Harmless today (the render pass commits nothing, so L8's
   disjointness property is not at risk), but the property is stated over
   `wake-attributes` vs `committed-attributes` and should be re-derived once
   render interests are dynamic.

## 7. Open decisions for the owner

1. **Land the third proc before the read seam?** Recommendation: yes. The proc
   is the mechanism; narrow waking is one derivation inside it, and §2 shows the
   proc is cheap while §3 shows the context saving is immediate. Landing them
   together couples a sealed contract to an unbuilt seam.
2. **Where does the render memory live so `::turn` can read it?** Options: an
   atom on the agent handle written only by `::renders` (recommended — same
   class as `routing`, single writer); or a third conn from `::renders` into
   `::turn` (adds an edge and an ordering question at episode start).
3. **Bound the memory (break 4).** The measured lever already exists: hold the
   DIGEST always and the OUTPUT only for pieces the delivery tier or the next
   turn will actually ask for. Needs an owner ruling on which those are.
4. **Does the read seam become a `seon.db` facade, or does each renderer call
   the `*-with-evidence` variants?** The facade is the old system's answer and
   the one the fresh tree deliberately has not rebuilt.
5. **Should a static registration whose bytes churn PANIC in dev?** Ruling 19
   says "flags it loudly". Two independent derivations disagree in that case
   (input contract says static, digest says changed), which is a clean dev
   panic under R41.
