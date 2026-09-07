---
type: research
status: complete
date: 2026-09-07
tags: [research, eval, cache, sci]
---

# Eval points and caches census

The owner's question, verbatim:

> "Confirm we only have one eval point for the entire system. The parser and
> eval system and the cache is using this. I get the feeling we still have eval
> and caching in multiple places and this is going to fuck us."

**Short answer.** The *form* eval point is genuinely one: in all of `src/`,
`sci.core/eval-form` is called from exactly two places and both are inside
`seon.sci.eval` (`src/seon/sci/eval.clj:890`, `:912`, `:2293`). That half of
the feeling is unfounded — and it is provable by query, not by grep (§5).

The feeling is *right* about three other things:

1. There is a **second entrance into the run loop's evaluator from the web
   renderer** — `seon.render.web/render-source-call`
   (`src/seon/render/web.clj:1517`) forks a turn ctx and calls
   `seon.cluster.loop/evaluate-sources` (`src/seon/cluster/loop.clj:1535`)
   *during a render*. It is one of only two callers of that function; the other
   is the run loop itself.
2. The **`:seon.cluster.loop/evaluate` indirection is invisible to the program
   graph**. The busiest eval site in the system resolves its evaluator at
   runtime (`src/seon/cluster/loop.clj:1553`) from a config fact
   (`src/seon/cluster.clj:2498`), so no `:seon.fn/calls` edge exists for it.
   A "one eval point" checker written naively over call edges would report
   health because the subject is absent — the exact failure class AGENTS.md
   names.
3. There are **two caches holding the same rendered output under two keys**
   (`::calls` and `::invocations` in the render proc), **two independently
   written reuse predicates**, and **two independently written read-evidence
   refreshers**. That is the duplication the owner smells.

Measured on the live `juniper-context` cluster: neither the database read nor
the validity check is the cost being cached against. A full agent pull is
**304–420 µs**, a query over all its evaluations **89–149 µs**, and
`read-evidence-current?` over a pull's evidence **1–4 µs** (§4).

---

## 1. EVAL POINTS

### 1.1 The graph

"Form entrance" = source text or a form becomes running code. "Invocation" =
a named Var in the SCI ctx is called with arguments. Both are execution in an
agent context; only the first parses.

| # | Site (`file:line`) | Called by | Context | Through the reply reader? | Result persisted? | `result/eN` bound? |
|---|---|---|---|---|---|---|
| E1 | `seon.sci.eval/evaluate` — `src/seon/sci/eval.clj:2116`, sci call at `:2293` | E2, E3, E5, E6, E7 | whatever ctx the caller hands (`:seon.sci.eval/ctx`) | **no** — its own single-form reader (`reader-context` `:488`, `one-event` `:505`) | no — returns the evaluation map; the caller persists | no — the *caller* binds |
| E2 | `seon.cluster.loop/evaluate-sources` — `src/seon/cluster/loop.clj:1535`; resolves E1 at `:1553` | `resume-turn` (`:1608`) and **`seon.render.web/render-source-call`** | turn fork from `fork-for-turn` | **yes** upstream — `planned-sources` (`:145`) → `reply/sources` (`src/seon/cluster/reply.clj:138`) → `seon.sci.reader/read` | run loop: yes, via `settle-batch!` (`:638`); web preview: **no** | **yes** — `bind-result!` at `src/seon/cluster/loop.clj:1599` |
| E3 | `seon.render.web/render-source-call` — `src/seon/render/web.clj:1517`, fork at `:1570`, eval at `:1582` | `render-value-source` (`:1270`), selection inspection (`:1673`), debug page (`:1910`) | **a fresh `fork-for-turn` per preview**, thrown away | yes (via E2) | **no run, no receipts** — held only in the render call entry as `:seon.cluster.loop/evaluated-sources` | yes, into the throwaway fork |
| E4 | `seon.sci.eval/install-row!` — `src/seon/sci/eval.clj:807`; sci calls at `:890`, `:912` | `acquire!` (`:1451`), `install-evaluated-rows!` (`:931`), `seon.cluster/…` (`src/seon/cluster.clj:1903`) | **base/cluster ctx** (program install, not a turn) | no — evaluates a stored `:seon.program/source` | n/a (this *is* the program) | no |
| E5 | `seon.sci.eval/evaluate-candidate` — `src/seon/sci/eval.clj:2529` | `gate-function-install` (`src/seon/cluster/loop.clj:268`), `seon.test.accretion` | candidate ctx (`sci/fork`) | no | no | no |
| E6 | `seon.dev.mcp` SCI-evaluation mode — `script/seon/dev/mcp.clj:607` | operator/agent tooling | **the cluster's live shared base ctx** — mutates it | no | **no run, no receipts** (documented at `script/seon/dev/mcp.clj:827`) | no |
| E7 | `seon.bootstrap-drive/evaluate-function` — `src/seon/bootstrap_drive.clj:147` | eval-harness grading | **builds a whole new `cluster-ctx` per call** (`:148`) | no | no | no |
| E8 | `seon.sci.kernel/invoke` — `src/seon/sci/kernel.clj:544` | `seon.render/invoke-selected` (`src/seon/render.clj:739`), `seon.test.accretion/observation` (`src/seon/test/accretion.clj:69`) | the ctx on the request | n/a (no parse) | no — the render cache holds the output | no |
| E9 | `seon.cluster.loop/generate-turn` — `src/seon/cluster/loop.clj:1808` | `turn` (`:1903`) | cluster ctx for `bootstrap/next-entry`, then **delegates to `resume-turn` → E2** | yes, through E2 | yes | yes |
| E10 | `seon.cluster.curate` proof — `src/seon/cluster/curate.clj:178` | curation proof | fresh `cluster-ctx` on a **new branch/connection** | via `loop/turn` (`:139`) | yes, on the proof branch | yes |
| E11 | MCP `jvm` mode — `script/seon/dev/mcp.clj` `jvm-evaluation-form` | operator tooling | **host JVM prepl, not SCI at all** | no | no | no |

### 1.2 The ONE seam

**`seon.sci.eval/evaluate` (`src/seon/sci/eval.clj:2116`) is, and should remain,
the only place a form becomes running code in an agent context.** It is the only
function that owns the whole contract at once: single-form read (`:505`), the
`kernel/arm` time limit (`:2255`), the `sci/binding` namespace frame (`:2285`),
admission (`:2293`ff), the def/program-row projection, and the flat
`:seon.error` value on failure.

It already holds. The bypass list is short and every entry is *structural*, not
a stray `sci/eval-string`:

| Bypass | `file:line` | What it bypasses | Verdict |
|---|---|---|---|
| `install-row!`'s two `sci/eval-form` calls | `src/seon/sci/eval.clj:890`, `:912` | the reader, the arm, admission | **Legitimate, same namespace.** Program install is not agent evaluation: the form is already a stored `:seon.program/source`. Keep, but it is the one place a second sci entrance exists — it must stay inside this file. |
| `render-source-call` → `evaluate-sources` | `src/seon/render/web.clj:1582` | the run, the receipts, the run loop's custody | **The real bypass.** A *render* evaluates agent source in a fork nobody owns. It is why the preview needs its own cache (§2.4). |
| `:seon.cluster.loop/evaluate` runtime resolve | `src/seon/cluster/loop.clj:1553` ← `src/seon/cluster.clj:2498` | the *program graph's* ability to see the edge | **Invisible, not wrong.** See §5. |
| `bootstrap-drive/evaluate-function` builds `cluster-ctx` per call | `src/seon/bootstrap_drive.clj:148` | reuse of the live acquired ctx | Harness-only, but a cold acquisition per graded function. Should take the instance's ctx. |
| MCP SCI-evaluation mode | `script/seon/dev/mcp.clj:607` | run/receipt creation; **mutates the shared cluster ctx** | Deliberate and documented; keep probes disposable. |
| MCP `jvm` mode | `script/seon/dev/mcp.clj` | SCI entirely | Deliberate — a host REPL, not an agent context. |

Everything else in `src/` that "evaluates" reaches E1 or E8. Verified by the
program-graph query in §5, which returns **only** `seon.sci.eval/evaluate` and
`seon.sci.eval/install-row!` as first-party callers of `sci.core/eval*`.

---

## 2. CACHES

### 2.1 The render call cache (`::calls` / `::ai-calls`)

| | |
|---|---|
| Key | `:seon.render.call/id` — e.g. `[:seon.render/html [::fleet-oversight]]` (`src/seon/render/web.clj:2265`) |
| Lives | render proc **state map**, `::calls` / `::ai-calls` (`src/seon/render/web.clj:2247`, `:2563`) — per cluster, not process-global |
| Stores | `:seon.render.call/output` (the finished string/hiccup), `:seon.render.call/static-evidence`, `:seon.render.call/read-evidence`, `:seon.render.call/invocation-key`, plus the merged cache evidence |
| Validity | `same-invocation-evidence?` (`src/seon/render.clj:664`) — `identical?` on program snapshot **and** projection, `=` on selection input — **and** `db/read-evidence-current?` (`src/seon/render.clj:1064`) |
| Consulted | `render/render-call` fast path, `src/seon/render.clj:1050–1070` |

### 2.2 The invocation cache (`::invocations`)

| | |
|---|---|
| Key | `invocation-cache-key` (`src/seon/render.clj:634`) = `[selected output (System/identityHashCode program-snapshot) projection-fingerprint (hash selection-input)]` |
| Lives | render proc state, `::invocations` (`src/seon/render/web.clj:2370–2374`, `:2602`) |
| Stores | **`:seon.render.call/output`** (again), `:seon.render.call/source`, `:seon.render.call/read-evidence`, `:seon.render.call/basis-transaction` |
| Validity | `reusable-invocation` (`src/seon/render.clj:642`) — the **same** `same-invocation-evidence?` + `read-evidence-current?` |
| Eviction | reachability from the call cache: `reachable-invocation-keys` (`src/seon/render/web.clj:2091`) keeps only keys named by a live `::calls`/`::ai-calls` entry |

**These two cache the same thing.** `render-call` merges the invocation entry
into the call entry at `src/seon/render.clj:1157`, so a hit in either returns
`:seon.render.call/output`. They differ only in *addressing*: `::calls` is
"this slot on this page", `::invocations` is "this producer on this input".
The second exists so two page slots showing the same value share one
invocation — a real win — but it is paid for with two key spaces, two lookups
per render, and an eviction rule that makes one cache a dependent of the other.

### 2.3 Duplicated helpers (same computation, two implementations)

- `refresh-read-evidence` — `src/seon/render.clj:673`
- `current-read-evidence` — `src/seon/render/web.clj:2097`

Both rebuild `db/read-evidence` from a retained evidence vector's
`:datahike.read/dependency-plan` + `:seon.db/source-argument-position` against a
new database. Same function, two files.

### 2.4 `reusable-evaluated-preview` — a third, hand-written reuse predicate

`src/seon/render/web.clj:1491`. It re-implements `same-invocation-evidence?`
inline: `identical?` on `:seon.render/program-snapshot`, `identical?` on
`:seon.render/projection`, `=` on the producer, then
`db/read-evidence-current?` — **the same four checks, spelled out again**,
plus source/agent/namespace equality. It exists only because `render-source-call`
(E3) evaluates something the invocation cache has no slot for
(`:seon.cluster.loop/evaluated-sources`). Note `same-invocation-evidence?` is
public (`src/seon/render.clj:664`) — the duplication is avoidable today.

### 2.5 Every other cache in `src/` (none of them duplicates)

| Cache | `file:line` | Key | Invalidation | Scope |
|---|---|---|---|---|
| `!database-projections` (LRU 64) | `src/seon/schema.clj:2430`, `:2433` | `datahike.db/committed-value-identity` of the db | LRU eviction only; key is exact committed identity so reuse is always correct | process-global `defonce` — safe because the key *is* the database identity |
| `!ambient-shape-projection` | `src/seon/schema.clj:3076` | candidate forms | single-deref `=` compare against forms in hand (`:3073` comment) | process-global |
| `!fallback-counts` | `src/seon/schema.clj:748` | diagnostic counters | n/a | process-global, not a value cache |
| projection-local compiled caches (`projection-cache-value`) | `src/seon/schema.clj:2264`ff | `[::function-arities sym]` etc. | rides the immutable projection value — dies with it | **correct shape**: derived state on the value it derives from |
| `source-analysis-cache` | `src/seon/cluster.clj:1409` | source snapshot digest | digest change | process-global; clj-kondo results, not evaluation |
| call-preparation `state` | `src/seon/call_preparation.clj:74`, installed `:91` | plan fingerprint / newest row transaction (`:279`, `:303`) | `watch!` listener (`:493`) + basis compare | **per-ctx**, inherited by every `sci/fork` — obeys 2.1 |
| `kernel/cache-program!` / `cache-function!` / `program-snapshot` | `src/seon/sci/kernel.clj:108`, `:117`; atom created `src/seon/sci/eval.clj:250` | qualified symbol | replaced on install; the snapshot's `identityHashCode` is what invalidates the render caches | **per-ctx** |
| `void-tag?` memoize | `src/seon/render/lint.clj:101` | HTML tag name | never (pure over the serializer) | process-global, correct |
| `source-for-transaction` memoize | `src/seon/sci/eval.clj:1471` | source tx | operation-local | fine |
| entity memoize | `src/seon/fn.clj:1898` | eid | operation-local | fine |
| `datahike` `memoized-parse-query` | `src/seon/db.clj:513`, `:1196` | query form | dependency-owned | fine |

Nothing here duplicates anything else. **The duplication is entirely in the
render layer** (§2.1–2.4).

### 2.6 The ONE cache

```
key   [agent-or-nil  namespace  source  producer-and-output]
value the stored evaluation / invocation output
valid (and (identical? program-snapshot ...)
           (identical? projection ...)
           (db/read-evidence-current? db read-evidence))
```

The owner's proposed key is `[agent ns source db-identity-or-basis]`. Two
corrections from the evidence:

**(a) The db component does not belong in the key.** Putting a db identity in
the key makes every cache line die at the next unrelated transaction — the
cache would be cold on every wake. Today's design is right and should be kept:
**identity is code + input; validity is read evidence.** `read-evidence-current?`
(`src/seon/db.clj:542`) is exactly the "is this still valid at a newer db"
answer, and it costs **1–4 µs** (§4).

**(b) Basis-t alone does NOT suffice.** `dependency-revision`
(`src/seon/db.clj:410`) returns per-attribute revisions
(`:datahike.cache/attribute-revisions`) for an attribute-scoped plan, and
collapses to the commit id **only** when the plan's attribute set is `:all`
(`src/seon/db.clj:434`). Measured live, a `pull '[*]` produces exactly that
`:all` case — so for pulls, basis-t is equivalent. For every attribute-scoped
query it is strictly coarser, and swapping to basis-t would invalidate every
retained render on every unrelated write. Basis-t is also already stored
alongside (`:seon.render.call/basis-transaction`, `src/seon/render.clj:1148`)
and is used only to decide whether a *page package* advanced
(`src/seon/render/web.clj:2241`) — a different question.

**What to collapse.** Keep ONE store, addressed by the invocation key
(`src/seon/render.clj:634`), and make `:seon.render.call/id` a **pointer** into
it rather than a second store of the same output. Concretely:

- `::calls` keeps `{call-id → invocation-key}` plus per-slot presentation, not
  a second copy of `:seon.render.call/output`;
- `::invocations` becomes the single value store — it already has the
  reachability eviction rule that makes this safe
  (`src/seon/render/web.clj:2091`);
- `refresh-read-evidence` (`src/seon/render.clj:673`) is the one refresher;
  delete `current-read-evidence` (`src/seon/render/web.clj:2097`);
- `reusable-evaluated-preview` (`src/seon/render/web.clj:1491`) calls
  `same-invocation-evidence?` instead of re-spelling it — or disappears
  entirely with E3 (§5).

---

## 3. RESULT STORAGE

### 3.1 Where a result lives

| Representation | `file:line` | Lifetime |
|---|---|---|
| `:seon.sci.admit/value` — the **live object**, semantically projected | built `src/seon/sci/admit.clj:475–477`; carried `src/seon/sci/eval.clj:2007` | in-memory, dies with the turn |
| `:seon.cluster.eval/result-edn` — `canonical-edn` of the **print node** | `print-node-edn`, `src/seon/sci/admit.clj:449–457`; carried `src/seon/sci/eval.clj:2008` | **durable datom**, `:seon.db/no-history? true` (`resources/seon/schemas/seon.cluster.eval.edn:103`) |
| `:seon.cluster.eval/result-blob` + `result-size` | staged `src/seon/cluster/run.clj:242–262` | durable; `result-edn` becomes a window, the full text becomes a blob |
| `result/eN` in the fork | `bind-result!`, `src/seon/sci/eval.clj:533`; called `src/seon/cluster/loop.clj:1599` | **the live value**, in the turn fork only; a later turn's fork re-derives defs from `:seon.def/*`, not from `eN` |
| `:seon.print/node` faces | `src/seon/print.cljc`; re-read from `result-edn` at `src/seon/render/transcript.clj:687` | reconstructed on demand |

### 3.2 Can text rendering be a pure function of the stored entity?

**Yes — and it already is.** The transcript path never re-evaluates:

- `receipt-entry` (`src/seon/render/transcript.clj:449`) reads
  `:seon.cluster.eval/result-edn`, `result-blob`, `result-size`, `output`,
  `error`, `triage-edn` off the pulled entity and nothing else;
- `bounded-result` (`:668`) `edn/read`s the stored string back into a print
  node (`read-result`, `:~430`) and hands it to `print/emit-text`;
- `run/render-receipt-ai` (`src/seon/cluster/run.clj`) takes the receipt map and
  returns the string — a total function of stored attributes.

No SCI ctx, no fork, no `evaluate` on that path. (`floor-text`,
`src/seon/render/transcript.clj:600`, *does* need a ctx when it falls to the
value floor — but that is a **render call**, not an evaluation, and it already
degrades to `pr-str` when no ctx is present.)

### 3.3 What is lost between the live value and the stored node

1. **Identity and mutability.** An atom, a Var, a `sci.lang.Type`, a Java object
   becomes `::print/object` / `::print/type` / `::print/class` with a name only
   (`src/seon/sci/admit.clj:423–429`). `(= @a ...)` is unaskable afterwards.
2. **Functions.** Reduced to an opaque node; the *source* survives separately as
   `:seon.def/*` / `:seon.program/source`, which is why def restore
   (`fork-for-turn`, `src/seon/sci/eval.clj:1721`) reads those rows and not
   `result-edn`.
3. **Anything past the caps.** `::print/truncated-string`
   (`src/seon/sci/admit.clj:430`) and the elision values; `capped-result?`
   (`src/seon/render/transcript.clj:440`) detects the windowed case by comparing
   `result-size` against `(count result-edn)`.
4. **Unprintable/failed projections.** `::print/failed`
   (`src/seon/sci/admit.clj:432`) keeps the class and the message, not the value.
5. **`result/eN`.** The binding holds the *live* object; the datom holds the
   *node*. Nothing reconciles them, and nothing should — but note this is the
   one place the same result exists twice in two fidelities.

Live evidence: 27 evaluations on `juniper-context`, **all 27** carry
`result-edn`; 11 carry `result-size` (i.e. 11 crossed the sizing path).

---

## 4. LIVE MEASUREMENTS

Root `/Users/sean/src/seon/tmp/juniper-context-live`, cluster
`juniper-context` (pid 41706), `jvm` mode, read-only, basis-t `536871577`.

**Population**

| Fact | Value |
|---|---|
| evaluation entities (`:seon.cluster.eval/id`) | **27** |
| entities carrying `result-edn` | **27** |
| entities carrying `result-size` | **11** |
| `result-size` min / **median** / max | 47 / **1617** / **4341** bytes |
| per agent | `root` 14, `juniper` 13 |

**Timing** — 5 runs each after 3 warmup rounds, `System/nanoTime`, µs:

| Operation | Runs | Median |
|---|---|---|
| `seon.db/pull '[*] [:seon.cluster.agent/id "juniper"]` (6 keys) | 420, 312, 347, 331, 304 | **331 µs** |
| `seon.db/q` over that agent's 13 evaluations (`result-edn` + `result-size`) | 149, 89, 128, 104, 92 | **104 µs** |
| `read-evidence-current?` over the pull's evidence (1 entry) | 4, 1, 1, 1, 1 | **1 µs** |

**Reading.** The query over *all thirteen* evaluations is **3× faster** than one
agent pull. Neither is anywhere near a budget. The validity check that would
guard the single cache costs ~1 µs — three orders of magnitude below the read it
protects. **The database is not what the render caches are protecting against;
producer invocation and source evaluation are.** Any cache added "so we don't
re-query" is unjustified by this measurement.

Evidence sample for the pull, showing the `:all` collapse:

```clojure
{:datahike.cache/connection-id ["1b719351-…" "cluster-juniper-context"]
 :datahike.cache/generation    "472d6ac3-…"
 :datahike.read/attributes     :all
 :datahike.read/revision       "6a9eec0f-…"}
```

---

## 5. RECOMMENDATION

### 5.1 The minimal function set

| Role | The one function | Status |
|---|---|---|
| **parse** | `seon.sci.reader/read` (`src/seon/sci/reader.cljc`), reached for agent replies through `seon.cluster.reply/sources` (`src/seon/cluster/reply.clj:138`) and for single forms through `seon.sci.eval`'s `one-event` (`src/seon/sci/eval.clj:505`) | already one; two entrances, one reader |
| **evaluate** | `seon.sci.eval/evaluate` (`src/seon/sci/eval.clj:2116`) | already one |
| **invoke** | `seon.sci.kernel/invoke` (`src/seon/sci/kernel.clj:544`) | already one |
| **store** | `seon.cluster.run` settlement (`src/seon/cluster/run.clj:242`) | already one |
| **render-text** | `seon.cluster.run/render-receipt-ai` over the stored entity | already one, already pure (§3.2) |

**The set is already right. Nothing new should be built.** The work is deletion.

### 5.2 What to delete

1. **`current-read-evidence`** — `src/seon/render/web.clj:2097`. Duplicate of
   `refresh-read-evidence` (`src/seon/render.clj:673`). Delete; call the one.
2. **The hand-written predicate in `reusable-evaluated-preview`** —
   `src/seon/render/web.clj:1499–1511`. Replace the four identity/projection/
   evidence checks with `render/same-invocation-evidence?` (`src/seon/render.clj:664`).
3. **The second copy of `:seon.render.call/output`.** Make `::calls` hold a
   pointer (`:seon.render.call/invocation-key`) and presentation, and let
   `::invocations` be the only value store (§2.6).
4. **`render-source-call`'s private evaluation (E3)** —
   `src/seon/render/web.clj:1570–1592`. This is the architectural one and the
   only item needing an owner decision. A render forking a turn ctx and running
   agent source is a *pre-read the run loop will re-decide* — the owner law. The
   preview it produces is thrown away as soon as the page re-derives, and it
   needs its own bespoke cache to survive (§2.4). Either the run loop owns the
   preview (an unsettled speculative run whose receipts the page reads), or the
   page shows the source and defers execution — but a renderer should not be an
   eval point.
5. **`bootstrap-drive/evaluate-function`'s per-call `cluster-ctx`** —
   `src/seon/bootstrap_drive.clj:148`. Take the instance's acquired ctx.

### 5.3 The regression that proves "one eval point"

Derived from `:seon.fn/calls`, not from a grep — and **it works today**. Run
live against `juniper-context`:

```clojure
(seon.db/q '[:find ?caller ?callee :where
             [?f :seon.fn/sym ?caller]
             [?f :seon.fn/calls ?t]
             [?t :seon.fn/sym ?callee]
             [(clojure.string/starts-with? ?callee "sci.core/eval")]]
           db)
```

Actual result (2026-09-07):

```clojure
[["seon.sci.eval/evaluate"      "sci.core/eval-form"]
 ["seon.sci.eval/install-row!"  "sci.core/eval-form"]
 ["seon.print-test/sci-value"           "sci.core/eval-string"]
 ["seon.call-preparation-test/probe"    "sci.core/eval-string*"]
 ["seon.effect-test/arm-probe-handler"  "sci.core/eval-form"]
 ["seon.sci.admit-test/evaluated"       "sci.core/eval-string*"]
 ["seon.sci.defs-test/function-root-edn" "sci.core/eval-string*"]]
```

**Exactly two first-party callers, both in `seon.sci.eval`.** The assertion is
therefore: *every caller of `sci.core/eval*` whose namespace is first-party and
non-test has `?caller` in `#{seon.sci.eval/evaluate seon.sci.eval/install-row!}`*
— with the allowed set itself derived (`:seon.fn/sym` prefixed
`seon.sci.eval/`), never enumerated in the test.

**The trap this test must not fall into.** `:seon.fn/calls` cannot see a
`requiring-resolve` of a symbol read from a config fact. Confirmed live:

```clojure
;; callers of seon.cluster.loop/evaluate-sources
[["seon.cluster.loop/resume-turn"] ["seon.render.web/render-source-call"]]
;; callers of seon.sci.eval/evaluate — resume-turn is ABSENT
[["seon.bootstrap-drive/evaluate-function"] ["seon.sci.eval/evaluate-candidate"] …]
```

`resume-turn` is the busiest evaluator in the system and has **no call edge to
`evaluate`**, because `evaluate-sources` resolves it at `src/seon/cluster/loop.clj:1553`
from `:seon.cluster.loop/evaluate` (set to `'seon.sci.eval/evaluate` at
`src/seon/cluster.clj:2498`). A checker over call edges alone reports health
where its subject is absent — the failure class AGENTS.md names.

So the regression is **two assertions, and the second is the one that matters**:

1. **Static.** No first-party non-test function outside `seon.sci.eval` has a
   `:seon.fn/calls` edge to `sci.core/eval*`.
2. **Dynamic-indirection closure.** Every value of `:seon.cluster.loop/evaluate`
   present in the database — a query, not a constant — resolves to a symbol in
   `seon.sci.eval`. This closes the hole the first assertion cannot see, and it
   fails loudly if the config fact is ever absent (rather than passing on an
   empty result set).

A third, optional: assert `seon.cluster.loop/evaluate-sources` has exactly one
first-party caller once E3 is removed — that single number is what "one eval
point" means in practice, and it is 2 today.
