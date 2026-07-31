---
type: research
status: active
tags: [research, context, render, falsification]
---

# Context-walk falsification — 2026-07-31

Adversarial REPL lane against `plan/context-render-data-model-spec.md` (DRAFT),
per ruling 2026-07-31 #4(5). Everything below is measured on a live JVM against
the real indexed corpus (124 `:seon.ns` rows, 1380 `:seon.fn` rows) in an
in-memory Datahike database built by the production population owner
(`seon.test-support/with-database` → `cluster/populate-source!`).

Probe scripts are committed under `research/scripts/context-walk-falsification/` and are the exact forms cited:

| Script | Attack |
|---|---|
| `research/scripts/context-walk-falsification/harness.clj` | shared apparatus (corpus, two agents, spec §2.1 instruction rows, 20-agent fleet) |
| `research/scripts/context-walk-falsification/01_smoke.clj` | walk cost by distance |
| `research/scripts/context-walk-falsification/02_ordering.clj` | P4 over 30 turns of churn, two read-set models |
| `research/scripts/context-walk-falsification/03_pathological.clj` | the never-stabilizing block; tree-vs-flat order |
| `research/scripts/context-walk-falsification/04_prefix.clj` | P5 cross-agent byte identity; shared-hub fan-out |
| `research/scripts/context-walk-falsification/05_crash.clj` | the three claimed bounds; totality |
| `research/scripts/context-walk-falsification/06_cachekey.clj` | cache key: same key, different bytes |
| `research/scripts/context-walk-falsification/07_viewer_budget.clj` | viewer override; token budget by family |
| `research/scripts/context-walk-falsification/08_budget_sens.clj` | what governs the budget; worst realistic case |
| `research/scripts/context-walk-falsification/09_fnlens.clj` | where the d2 budget actually goes |
| `research/scripts/context-walk-falsification/10_instruction_churn.clj` | spec §3's own ordering claim |

Run any of them with:

```bash
D=docs/prds/sci-execution-runtime/research/scripts/context-walk-falsification
clojure -M:test -i $D/harness.clj -i $D/02_ordering.clj
```

## 0. BLOCKER found before the first attack — the shared clj-kondo cache is poisoned

`seon.test-support/with-database` and therefore `cluster/populate-source!` REFUSE
on this tree right now:

```
Execution error (ExceptionInfo) at seon.fn/assert-clean-analysis! (fn.clj:270).
Static program analysis found blocking errors.
```

Eleven blocking findings, seven of them this shape
(`research/scripts/context-walk-falsification/00e.clj`):

```
src/seon/render/walk.clj  251  clojure.core/vswap! is called with 3 args but expects 4 or more
src/seon/render/block.clj 742  clojure.core/vswap! is called with 2 args but expects 4 or more
src/seon/schema.cljc       46  ...
src/seon/sci/admit.clj  80,87  ...
```

Root cause, measured. `.clj-kondo/.cache/v1/clj/clojure.core.transit.json`
records `vswap!` at **row 556 with arglist `[_ _ vol f & args]` and
varargs-min-arity 4**. Linting the Clojure jar in an isolated directory records
the correct entry — **row 2560, min-arity 2, macro true**:

```bash
cd /tmp/kondo-poison && mkdir -p .clj-kondo
clj-kondo --lint ~/.m2/repository/org/clojure/clojure/1.12.5/clojure-1.12.5.jar \
          --dependencies --parallel
# => "~$vswap!" ... "^1",2560 ... macro true, varargs-min-arity 2   (CORRECT)
```

So the poisoned entry was not written by dependency warming. `[_ _ vol f & args]`
is a macro fn's arglist with the implicit `&form`/`&env` parameters, which is the
shape SCI's `clojure.core` macro implementations carry. The runtime reply lint
(`seon.fn.analyzer/analyze-forms`, `src/seon/fn/analyzer.clj:288-330`)
synthesises a `defn` prelude from `:seon.fn/arglists` — added by commit
`b028556b5` "Preserve runtime lint arities", 2026-07-30 — and calls the SAME
`invoke-kondo`, which uses the SAME `:cache-dir ".clj-kondo/.cache"`
(`src/seon/fn/analyzer.clj:8-9,107-115`). clj-kondo persists that synthesized
`clojure.core` analysis over its builtin definitions. The build indexer then
reads the poisoned cache and refuses `src/`.

Two mechanisms share one cache directory and one of them writes agent-derived
fiction into it. **`bin/seon init`, fresh cluster population, and the canonical
test fixture are all broken until this is repaired.** Verified fix direction:
a process-local redirect of `cache-directory` to a private path gives **0
errors** over the identical sources (`research/scripts/context-walk-falsification/00f.clj`) — the source tree is
clean; only the cache is wrong.

This lane worked around it with that redirect (`research/scripts/context-walk-falsification/harness.clj:15-16`).
Root cause and blast radius recorded on the existing open note:
`docs/seon/issues/clj-kondo-vswap-arity-blocks-program-publication.md` (its
original diagnosis — "the maintained clj-kondo core Var metadata" — was wrong;
the sources and clj-kondo are both fine, the shared cache is not).

Also blocking-listed: the intentional `test/seon/schema_edn_fixtures/unreadable/bad.edn`
fixture (4 parse errors). The indexer's blocking gate does not exclude
deliberately-malformed EDN fixtures; it should filter to Clojure sources.

---

## Attack 1 — ORDERING STABILITY (spec P4, §3 "basis is the ordering key")

Apparatus: 30 turns of realistic churn on agent `alpha` (a message arrives every
turn, an instruction row is edited in place every 7th turn), blocks flattened
from the d2 walk, ordered by last-change basis over each block's read set.
Two read-set models, because the spec does not say which one it means.

| Read-set model | Inversions among UNCHANGED blocks | Turns with any | Prefix survival (mean) |
|---|---:|---:|---:|
| **A. own datoms only** (a pull-narrow render) | **0** | 0/29 | 99.9% (80 523 of 80 625 bytes) |
| **B. what the walk really reads** (own + reverse scan) | **32** | **14/29** | 99.8% |

`research/scripts/context-walk-falsification/02_ordering.clj`. Model B is the honest one: `walk/refs` runs
`(d/q '[:find ?source ?attribute :in $ ?target :where [?source ?attribute ?target]] db eid)`
(`src/seon/render/walk.clj:221-224`) — the reverse scan *decides which neighbours
appear*, so under the spec's own §3 rule ("`deps` come from the facade's
evidence-capture pass … `basis` is max tx over the read set") it is part of the
read set.

### 1a. The spec's own stated example is FALSE

§3 claims: *"an instruction row untouched since seeding keeps its seed basis
forever and fronts every prompt."* Measured (`research/scripts/context-walk-falsification/10_instruction_churn.clj`):

```
turn | event                | inst narrow | inst walk | rank-by-walk
   0 | message to alpha     |   536870921 | 536870922 | 53/60
   3 | PEER AGENT CREATED   |   536870921 | 536870926 | 58/63
   6 | PEER AGENT CREATED   |   536870921 | 536870929 | 61/66
   9 | message to alpha     |   536870921 | 536870929 | 61/69
```

The instruction row sits at **rank 53 of 60 — the TAIL, not the front** — from
turn 0, and marches further tailward on **every agent creation in the cluster**,
because each new agent writes a `:seon.cluster.agent/instructions` datom pointing
at it. A cluster-owned shared instruction set is by construction the highest
reverse-fan-in node in the graph, so under model B it is the *least* stable
thing in the prompt. That is the exact inverse of the design's intent.

### 1b. The pathological block exists and is a PEER, not the agent

The agent's own d0 block is stable (20 bytes, one digest across 12 turns —
`research/scripts/context-walk-falsification/03_pathological.clj`), because `agent-ai` renders only its own
fields. The unstable block is the **peer agent `beta`**, whose own datoms never
change while its walk-basis advances every single turn (536870923 → 536870934
over 12 turns) purely from inbound `:seon.cluster.message/from` datoms. Any
block that is a reverse-fan-in target of a high-churn attribute never
stabilizes, and shared/hub entities are exactly those blocks.

### 1c. P4 cannot be asserted against the assembler that exists

`walk/prose` emits a **tree**: indentation is depth and each line is prefixed
with the connection it was reached through (`src/seon/render/walk.clj:445-461`).
Its order is traversal order, which is not basis order:

```
traversal order (first 12): (4108 4113 4109 4110 4111 848 849 850 851 852 853 854)
basis    order (first 12): (849 850 851 852 853 854 855 2227 2226 2228 2229 2230)
identical? false
```

To order blocks by basis you must flatten, which discards the `(attribute)`
framing and the nesting — the walk's entire information content. **The spec owes
a ruling: tree assembly OR basis ordering. It cannot have both, and today's
assembler implements neither P4 nor anything close to it.**

**VERDICT: DESIGN BREAKS.**
Fix implied: (a) define the ordering read set as the render's **own attribute
reads only**, explicitly EXCLUDING the reverse-membership scan — that model
measured 0 inversions and 99.9% prefix survival, so the design works if the
read set is narrowed on purpose; (b) make reverse-derived membership its own
block whose churn is confined to itself; (c) state that assembly is flat and
that parent framing becomes a rendered field of the child block, or drop P4.

---

## Attack 2 — PREFIX SHARING (spec P5)

Two agents (`alpha`, `beta`) plus an 18-agent fleet, all referencing the same
four spec-§2.1 instruction rows. `research/scripts/context-walk-falsification/04_prefix.clj`.

```
block bytes identical?   true
alpha neighbour lookups: [4113 4114 4115 ... 4131]   ; 19 neighbours
beta  neighbour lookups: [4112 4114 4115 ... 4131]   ; 19 neighbours — DIFFERENT
alpha subtree bytes: 1295   beta subtree bytes: 1296
subtree byte-identical? FALSE
```

The instruction's **own** rendered bytes are identical across agents — P5 holds
at the leaf. The bytes that enter the prompt are not:

```
alpha:  (:seon.cluster.agent/instructions) {…:reply-grammar…}
          (:seon.cluster.agent/instructions) Agent beta is idle.
          (:seon.cluster.agent/instructions) Agent peer0 is idle.
beta:   (:seon.cluster.agent/instructions) {…:reply-grammar…}
          (:seon.cluster.agent/instructions) Agent alpha is idle.
          (:seon.cluster.agent/instructions) Agent peer0 is idle.
```

Two independent causes, both structural:

1. **Reverse traversal out of a shared hub.** Every agent that references an
   instruction row is a reverse neighbour of it. Alpha's walk therefore lists
   beta under the instruction; beta's lists alpha. The per-path `visited` set
   (`walk.clj:414`) excludes the walking agent itself and nobody else, so the
   difference is exactly one entity — and it is *guaranteed* to differ per viewer.
2. **The `(attribute)` framing is a lie in direction.** A reverse edge is
   labelled with the same attribute keyword as a forward one, so the prompt reads
   as if the reply-grammar instruction *has instructions*, which are peer agents.

Spec §2.2 already anticipates this for `:seon.cluster.agent/cluster` ("reverse
fan-out exceeds `max-collection`"), but §2.1's instruction rows have the same
topology and the spec does not mention it. The instruction rows are the highest
fan-out hubs in the graph precisely because the cluster owns the authoritative set.

Cost of that fan-out with only 20 agents (`research/scripts/context-walk-falsification/04_prefix.clj`):

| Distance | Nodes | Distinct | ms | Tokens |
|---|---:|---:|---:|---:|
| 1 | 6 | 6 | 2 | 2 102 |
| 2 | 135 | 78 | 24 | 21 402 |
| 3 | 513 | 99 | 476 | **119 100** |

**VERDICT: DESIGN BREAKS.** The cross-agent prompt-cache claim is false as
written. Fix implied: shared/hub entities must not be traversed in reverse —
either mark reverse traversal as opt-in per attribute (a derived rule, e.g. skip
reverse when the target's fan-in exceeds a threshold), or render hubs as leaves.
P5 must also be restated at the level it is testable: *the assembled subtree*,
not the leaf block, since the subtree is what the provider caches.

---

## Attack 3 — CRASH THE WALK (three claimed bounds, totality)

`research/scripts/context-walk-falsification/05_crash.clj`.

| Probe | Result | Bound |
|---|---|---|
| 5 MB string attribute, d0 | 4 204 chars, 1 051 tokens, 10 ms | **HOLDS** (`max-string` 4096) |
| reverse fan-in of 500, d1 | 64 neighbours, **no elision marker** | **BREAKS** |
| ref cycle a→b→c→a at d3/d6/d8/d10 | 5 nodes every time, 1–3 ms | **HOLDS** (per-path visited) |
| 12-entity clique, d2 | 122 nodes / 12 distinct, 88 ms | degrading |
| 12-entity clique, d3 | **1 112 nodes / 12 distinct**, 208 ms | 92× redundancy |
| 12-entity clique, d4 | **4 104 nodes / 12 distinct**, 596 ms, 8 elisions | node budget saturated |
| unicode: emoji, RTL override, NUL, combining, lone surrogate | 109 chars, total, no throw | HOLDS |
| projection var that does not resolve | flat `:seon.render/unresolvable` value | HOLDS |
| lookup that answers to nothing | flat `:seon.render.walk/no-such-entity` value | HOLDS |

Nothing throws and nothing hangs — the totality claim survives. Two real breaks:

**3a. Silent collection truncation.** 500 reverse neighbours become 64 with no
marker of any kind (`walk.clj:216,232`); 436 entities vanish from the agent's
world with no signal. This is the audit's §7.5 finding, confirmed live, and P6 is
false today. The spec says it is fixed "in the same wave" (§5) — it must be,
because it is the difference between a bounded view and a lie.

**3b. The per-PATH visited set is not a cycle guard, it is a combinatorial
explosion.** The docstring calls it "a per-PATH visited set" because "the entity
graph genuinely cycles" (`walk.clj:346-349`). It stops cycles, but a *diamond* —
any fan-in DAG — re-renders the same entity once per distinct path. Twelve
entities produce 4 104 rendered nodes at d4 and saturate the node budget, so the
budget becomes the only bound and the prompt is 4 096 duplicates of 12 things.
This is not a synthetic worry: the real d3 walk from `alpha` already shows
225 nodes over 81 distinct entities (2.8×) and the 20-agent fleet shows
513 over 99 (5.2×), matching the audit's measured 4.75×.

**VERDICT: BOUNDS 1 (string) and 3 (cycle) SURVIVE; bound 2 (collection) BREAKS
silently; the node budget is load-bearing where a per-WALK dedupe belongs.**
Fix implied: emit an elision node per truncated reverse group; replace the
per-path visited set with a per-walk rendered-entity set that emits a
back-reference ("already shown above as …") instead of a re-render. That also
makes the cache key sound — see Attack 5.

---

## Attack 4 — TOKEN BUDGET REALITY

Realistic agent (`alpha` owning `seon.cluster.run`, 46 functions), config-default
caps (`max-collection 64`, `max-nodes 4096`), tokens via `seon.ai.tokens/estimate`.
`research/scripts/context-walk-falsification/07_viewer_budget.clj`, `08_budget_sens.clj`.

| Distance | Nodes | Distinct | Tokens | 30k budget | 50k budget |
|---|---:|---:|---:|---|---|
| 1 | 6 | 6 | 2 102 | FITS | FITS |
| 2 | 63 | 60 | 20 314 | FITS | FITS |
| 3 | 225 | 81 | **107 793** | BLOWN 3.6× | BLOWN 2.2× |

Worst realistic case (agent owns `seon.schema`, the largest namespace at 104
functions): d1 = 1 034 tokens, **d2 = 22 478 tokens**. So d2 fits a 30–50k budget
with headroom today, and the historical "50k blown by 11%" failure does **not**
reproduce. That is the good news, and it is the only attack the design wins.

It wins for a fragile reason. Where the d2 budget goes:

| Family | Tokens | Share |
|---|---:|---:|
| `seon.fn` | 17 729 | **87.3%** |
| `seon.cluster.agent` | 2 064 | 10.2% |
| `seon.ns` | 143 | 0.7% |

`max-collection` is the real dial, not distance (`research/scripts/context-walk-falsification/08_budget_sens.clj`):

```
max-collection=  8 -> nodes=25 tokens= 5158
max-collection= 16 -> nodes=33 tokens=10240
max-collection= 32 -> nodes=49 tokens=15944
max-collection= 64 -> nodes=63 tokens=20314
max-collection=128 -> nodes=63 tokens=20314   (46 fns is the real ceiling here)
```

And 87% of it is raw `:seon.fn/source` rendered through the structural floor,
because no `:seon.fn` lens exists (the audit's §7.4 defect). Measured
(`research/scripts/context-walk-falsification/09_fnlens.clj`): 46 function nodes cost **17 729 tokens** through
the floor, of which **10 677 is source text**; the same 46 functions as
`sym + arglists + docstring line 1` cost **1 162 tokens** — a **15×** reduction.

So: **distance is a binary dial.** d2 costs 20–22k and d3 costs 108k; there is
nothing in between and no way to spend the remaining 8–28k of a 50k budget on
anything useful. There is no whole-context budget mechanism, and the design
notices the token cap only in P6 ("any cap … leaves a marker") without saying
which mechanism owns it.

**VERDICT: DESIGN SURVIVES v1 — but only because d2 happens to land at 20–22k,
and only until the corpus grows or a second high-fan-out family joins d2.**
The absence of a whole-context budget is not fatal in v1; the absence of a
`:seon.fn` lens nearly is. Recommended per-family caps for a 30–50k context:
`seon.fn` rendered as signature + docstring line 1 (≈1.2k for a whole namespace,
leaving ~28k of a 30k budget for messages, instructions, runs and errors), full
source only for a function the agent explicitly opens. Fix that one lens and d3
becomes affordable, which is where the walk actually earns its keep.

---

## Attack 5 — CACHE KEY SOUNDNESS

Spec §3: a block instance is `(root-entity, renderer-var, distance, projection)`,
cached with `basis`. `research/scripts/context-walk-falsification/06_cachekey.clj`, `07_viewer_budget.clj`.

### 5a. Same key, different bytes — the viewer, via the subtree

```
entity 4108  distance 1  projection seon.render.block/data-prose  basis 536870923
key identical?            true
leaf block bytes identical?  true
SUBTREE bytes identical?     false   (533 vs 534)
```

The key is sound for a **leaf**. It is unsound for any node **with neighbours** —
which is every node worth caching. Two viewer-dependent inputs are absent from
the key: the per-path `visited` set (walk.clj:414) and the `dedupe-by :target`
first-mention rule (walk.clj:237-251, 314). Both are functions of the path from
the *viewing agent's root*, so the same entity at the same distance with the same
projection at the same basis produces different subtree bytes per viewer.

### 5b. The viewer's overrides ARE captured — through `projection`

Probed against a registered family (`:seon.fn/fn`,
`research/scripts/context-walk-falsification/07_viewer_budget.clj`):

```
no-override projection: seon.render.block/data-prose
  override projection: falsify.harness/shout-ai
bytes differ? true
```

`walk/projection` resolves the viewer's override to a *symbol*
(`walk.clj:172-179`), and that symbol is the key's `projection` field, so a
directly-overridden node keys correctly. **The viewer dimension leaks only
through descendants**: viewer-constancy means alpha's override applies three hops
down, where the descendant's own projection is unchanged, so its key is identical
to beta's while its parent chain — and therefore its subtree — is not.

### 5c. Same key, different bytes — hot reload

`seon.render/render` invokes the **var**, deliberately, so re-evaluating a `defn`
changes the next render with no re-registration (`src/seon/render.clj:31-40`).
The key contains no code identity:

```
key [4108 0 falsify.harness/instruction-ai 536870923] -> "INSTRUCTION :reply-grammar"
;; re-evaluate the defn
key [4108 0 falsify.harness/instruction-ai 536870923] -> "REVISED :reply-grammar"
SAME KEY, DIFFERENT BYTES: true
```

The spec calls the production cache "process-local, losable, rebuilt from facts —
never a second truth" (§3). Process-local does not save it: hot reload happens
*within* the process, which is the whole point of the hot-reload property. A
cluster-global cache would serve the pre-reload bytes indefinitely, and the
failure presents as a stale prompt — precisely the failure `render.clj:36-40`
prohibits memoization to avoid. **The cache reintroduces the memoization the
router bans.**

**VERDICT: DESIGN BREAKS.** Corrected key:

```
(root-entity, projection-symbol, distance, kind, basis,
 renderer-code-generation,          ; bumped on any render-var re-evaluation
 walk-identity)                     ; the viewing root + the per-walk dedupe state
```

`walk-identity` is the honest admission that a subtree is not cacheable across
viewers under the current traversal. The cheaper repair is to **remove the
viewer-dependence from the traversal itself** (Attack 3b's per-walk rendered-set
with back-references, plus Attack 2's no-reverse-out-of-hubs rule); then
`walk-identity` collapses away and only `renderer-code-generation` is needed.
That single change makes P5 true, makes the cache key sound, and removes the
combinatorial re-render — it is the highest-leverage fix in this report.

---

## Summary table

| Attack | Claim | Verdict |
|---|---|---|
| 1 | P4 ordering stability | **BREAKS** — 32 inversions/29 turns under the honest read set; §3's own instruction example is false (rank 53/60, not front); tree assembly and basis ordering are incompatible |
| 2 | P5 prefix sharing | **BREAKS** — leaf bytes identical, subtree bytes differ per viewer by construction (1295 vs 1296) |
| 3 | three bounds, totality | **PARTIAL** — string and cycle bounds hold, nothing throws or hangs; collection truncation is silent (500→64, no marker); per-path visited set gives 4 104 nodes for 12 entities |
| 4 | token budget | **SURVIVES** — d2 = 20–22k fits 30–50k; but 87% is one un-lensed family and distance is a binary dial (d3 = 108k) |
| 5 | cache key | **BREAKS** — same key/different bytes twice: subtree-by-viewer, and hot reload |

## What is in genuinely good shape

- **Totality.** Every hostile input returned a flat `:seon.error` value. A 5 MB
  string, a lone surrogate, an RTL override, a NUL byte, an unresolvable
  projection var, and a lookup answering to nothing all produced values. Nothing
  threw into the walk and nothing hung. The docstring's promise is kept.
- **The cycle guard and the string cap.** Two of the three bounds are real, and
  the cycle case is genuinely flat across d3→d10.
- **The narrow-read-set ordering model.** 0 inversions and 99.9% prefix
  survival over 29 turns is a *good* result — the ruling's ordering idea works,
  it is just being fed the wrong read set.
- **The resolution chain.** Viewer overrides resolve to a symbol that lands in
  the cache key, which is exactly the property that makes the direct-node key
  sound. The design got that right.
- **The d2 budget.** No reproduction of the historical 50k blowout.

## Skill drift

None found. Every `file:line` spot-checked from the `repl`, `datahike`,
`clojure-testing` and `data-oriented-clojure` skills held against current
source (`test/seon/test_support.clj:151-183`, `src/seon/render/walk.clj`
citations, `reference-code/datahike/src/datahike/api/impl.cljc:30-48`).
