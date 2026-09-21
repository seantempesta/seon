---
type: research
status: data pack (facts only — no design, no schema proposal, no plan)
created: 2026-09-21
tags: [profiling, instrumentation, program-graph, agent-platform, data-pack]
---

# Data pack — profiling on the armed wrapper (lane C1 input)

Every `file:line` below was opened and read this session at the working tree
of `6d7192c22` (branch `steward-platform`). One REPL evaluation was spent
(§8); every other number is a count or a span read from a file. Claims I
could not verify from the tree are marked **unverified**.

## 1. The armed wrapper's invocation path

Two functions build it: `arm-var!` (`src/seon/instrument.clj:860-896`)
installs the OUTER fn as the Var root; `compiled-wrapper` (`:734-819`) builds
the inner chain, memoized per projection under the cache key
`[::wrapper function-symbol authored original policy]` (`:737-738`).

| Step on one armed call | Span | What it does |
|---|---|---|
| Call enters the Var root | `instrument.clj:880` | `(fn [& arguments] …)` — a rest-arg fn, so every call allocates an arg seq |
| Re-entrancy check | `:881-882` | reads the dynamic `*compiling-contract*` (`:570`); when true, calls `original` unwrapped |
| Projection discovery | `:885` → `supplied-projection` `:604-620` | scans EVERY argument three ways (`IMeta` metadata, `:seon.schema/projection`, `:seon.env/environment` → projection), each through `request-member` `:572-603` (a `map?` test + `get` in a `try`/`catch ClassCastException`); falls back to `schema/handed-projection` |
| Wrapper selection | `:886-888` | `compiled-wrapper` (cache hit → `schema/projection-cache-value` `:737`), or the `boot-wrapper` delay `:875-878`; both inside a `binding` of `*compiling-contract*` (a thread-binding push/pop per call) |
| Apply | `:889` | `(apply wrapped arguments)` |
| Refusal recording shell | `:810-819` | `try` … `catch ExceptionInfo`; in `:record` mode a refusal identified by the `marker` object (`:762`) is committed through `(:seon.flow/commit-fault! policy)` and returned as a value |
| Input + output + guard validation | `:768-774` | `m/-instrument` with `:scope #{:input :output :guard}`; `:report` builds `boundary-refusal` (`:682-733`) |
| The callee is invoked | `:776` | `(apply original arguments)` inside `m/-instrument`'s wrapped fn |
| Declared-facet check on the RESULT | `:777-808` | only when the result is a `map?` satisfying the `base?` validator: an arity scan (`:778-786`), `error/facets` over the projection (`:787`), set intersection (`:788-789`) |

**Per-call work today, as a list:** rest-arg seq allocation · dynamic var read
· `supplied-projection` argument scan (≤3 lookups per argument) ·
`binding` push/pop · projection cache lookup · `m/-instrument` input
validate · callee apply · output validate · guard validate · `map?`+`base?`
result test · `try`/`catch` frame.

**Where the wrapper captures what it was armed for.** `arm-var!` closes over
`original`, `authored`, `contract`, `definitions`, `caps`, `policy`
(`:864-878`) and stamps them on the Var's metadata at `:890-896`:
`:malli.instrument/original`, `::policy`, `:seon.instrument/var`,
`:seon.instrument/authored`, `:seon.instrument/contract`,
`:seon.instrument/definitions`, `:seon.instrument/contract-digest`.
`contract-digest` is computed at `:872-874` as
`(seon.id/digest 64 [contract (schema/canonical-data-string definitions)])` —
it is a CONTRACT digest, not a definition-body digest; the malli audit records
that nothing reads it (`deletion-audit-malli-2026-09-21.md:33`). Staleness is
decided by `current-wrapper?` (`:844-858`) comparing var identity, policy,
authored schema, contract and the captured `definitions` map. Arming
enumerates candidates with `collect-contracts!` (`:898-908`, walks
`(mapcat (comp vals ns-interns) (all-ns))`); `apply!` (`:927-1021`) skips
current wrappers at `:995-997`.

**Scope fact.** These wrappers are on JVM Var roots, shared by every cluster
the JVM hosts (AGENTS.md §1: "One JVM may host many cluster instances").
Nothing in the invocation path reads a cluster, branch or connection.

## 2. The definition digest

There is **no `:seon.fn/digest`** attribute: `grep -rn ":seon.fn/digest" src
resources test` returns nothing. The digest carried by function and test rows
is `:seon.program/analyzed-source-digest`.

| Fact | Evidence |
|---|---|
| Declaration | `resources/seon/schemas/seon.program.edn:1-3` — `[:string {:min 64 :max 64 :description "Digest of the exact analyzed input, including resolver context. Its presence records completed analysis even when the computed relations have no members."}]` |
| Required on every function row | `resources/seon/schemas/seon.fn.edn:116` (in the `:fn` entity map, not `{:optional true}`) |
| Required on every test row | `resources/seon/schemas/seon.test.edn:88` |
| Written, file-indexed function | `src/seon/fn.clj:659-660` — value is `(:seon.fn.file/digest (get contexts (::analyzer/filename entry)))` |
| Written, file-indexed test | `src/seon/fn.clj:625-626` — same value |
| Written, agent-authored form | `src/seon/fn.clj:1012-1014` — `(:seon.fn.file/digest (text-context analyzed-source))` |
| What `:seon.fn.file/digest` hashes | `src/seon/fn.clj:110-116` — SHA-256 (`:105-108`) of the UTF-8 bytes of the WHOLE analyzed text |

So for a file-indexed function the digest is the digest of its **file**, shared
by every declaration in that file and changing when any of them changes; for an
agent-authored form it is the digest of that form's own text. The
per-declaration source string itself is stored as `:seon.fn/source`
(`seon.fn.edn:121`, declared `[:string {:min 1}]` at `:177`).

## 3. Task subject identity

`seon.issue/subject-id` (`src/seon/issue.clj:554-564`), verbatim body:

```clojure
[detector [attribute value]]
(id/id (into (sorted-map) {:seon.issue/detector detector attribute value}))
```

Its contract is `[:=> [:cat :qualified-symbol [:tuple :qualified-keyword
:seon.schema/value]] :seon.issue/id]` (`:560-562`). Callers: `subject-row`
(`:620`), which asserts `{:seon.issue/id … :seon.issue/detector program
:seon.issue/severity … :seon.issue/status :open <issue-attribute> #{entity}}`
and refuses unless exactly one installed subject identity is present
(`:574-587`), the subject entity exists (`:604-618`) and the detector resolves
to an installed `:seon.fn/sym` row and a resolving Var (`:632-660`);
`my.program/proposed-plan` (`src/my/program.clj:251-262`) reuses the same
derivation with `detector 'seon.program/unresolved-callers`.

**D2 writer:** no `seon.task` namespace exists (`ls src/seon/task.clj` →
absent). The "trigger → task, `start!` refuses while `:seon.task/agent`
exists" behaviour is a target only
(`durable-goals-and-rulings-2026-09-21.md`, §2d row 2; lane B3 owns the
rename). The upsert-by-detector-identity property that exists today is
`subject-row`'s (`issue.clj:557-559` docstring: "two runs upsert one entity;
an entity id would change under a refork and is refused").

## 4. `my.program` read idiom

All three take one namespaced map, return `[:or <value> refused not-found]`,
and wrap the body in `read-result` (`src/my/program.clj:15-44`).

| Function | Span | Contract, verbatim |
|---|---|---|
| `callers` | `152-166` | `[:=> [:cat :my.program/subject-request] [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]` |
| `tests-reaching` | `167-186` | same as `callers` |
| `breaks` | `278-346` | `[:=> [:cat :my.program/breaks-request] [:or :seon.program/breakage :seon.program/read-refused-error :seon.program/not-found-error]]` |

Argument destructuring is identical in all three:
`[{database :seon.db/db subject :seon.program/subject :as request}]`
(`:156`, `:173`, `:291`). Requests:
`:my.program/subject-request` = `[:map [:seon.db/db :seon.db/database-value]
[:seon.program/subject :seon.program/subject]]`
(`resources/seon/schemas/my.program.edn:6-8`); `:my.program/breaks-request`
adds `[:seon.program/contract {:optional true} :seon.program/contract]`
(`:9-12`). Empty groups are dropped by `present-groups` (`:91-93`); the
subject is located by `locate` (`:51-79`); `tests-reaching` delegates to
`seon.fn/gate-set` (`:180`). Every `db/q` / `db/pull` call site carries the
inline `;; debt: …declares :seon.error/value` throw shim (e.g. `:131-138`).

## 5. The turn close seam, and reaching a branch name

| Fact | Evidence |
|---|---|
| `close-tx` | `src/seon/turn.clj:432-440` — returns `[[:db.fn/call #'close-call request]]` |
| `close-call` | `src/seon/turn.clj:442-464` — runs INSIDE the writer as a transaction function: `require-open-run`, one Datalog query for covered messages, then `[:db/add turn-eid ::closed-tx "datomic.tx"]` plus one `::handled` datom per covered message |
| Callers of `close-tx` | `turn.clj:4483`, `:4837`, `:4889` (the turn loop's own settlement path) |
| `open-run-tx-call` | `turn.clj:471-497` — the writer, not the caller, decides whether run-dependent data applies |
| Branch from a database value | `src/seon/db.clj:4283` `(:branch (dbi/-config database))`; also `:4355` `(:branch (:config database))` and `:4392` `(name (:branch (:config database)))`, which is used as the cluster name |
| Branch from a connection | `src/seon/db.clj:460-462` `connection-branch` = `(:branch (:config @connection))`; used by the write-custody refusal `:467-500` |
| Basis identity already assembled | `db.clj:4281-4285` — `{:seon.error.basis/store … /branch … /commit (d/commit-id database) … /t (dbi/-max-tx database)}` |

## 6. Existing periodic / shared-plumbing procs

| Proc | Span | Facts |
|---|---|---|
| Per-agent schedule proc | `src/seon/cluster/agent.clj:504-510` — `seon.flow/var-process #'schedule/schedule-step :io`, carrying `:seon.turn.loop/cluster`, `:seon.agent/id`, `:seon.schedule/channel` | `schedule-step` at `src/seon/schedule.clj:818` |
| Schedule semantics | `src/seon/schedule.clj:1-16` | "one disposable timer and fact listener scoped to this agent"; each firing is its own wake, declared `:seon.wake/opens-turn? false` |
| Cron grammar | `src/seon/schedule.clj:39-42` | `CronDefinitionBuilder/instanceDefinitionFor CronType/UNIX` — UNIX cron, so the finest declarable period is one minute (**unverified**: no test read this session asserting sub-minute refusal) |
| Root maintenance portfolio (durable task rows) | `src/seon/schedule.clj:44-70` | five tasks, each `{:seon.schedule/id … :seon.schedule/expression "0 2 * * *" … :seon.schedule.task/id … :seon.fn/sym 'seon.operator/…}` — footprint, reap-dead-roots, rotate-logs, process-census, compact |
| Per-agent turn proc | `src/seon/cluster/agent.clj:496-503` | `#'turn/step`, workload `:io`, `:seon.agent/episode` channel on a `CountedSlidingBuffer` of 1 |
| Shared fault committer (per cluster) | `src/seon/flow.clj:1051-1131`, graph at `:1230-1240` | `var-process #'fault-committer-step :io` |
| Work launcher | `src/seon/flow.clj:241-944` | exists today; the SCI audit records it as deletable in favour of `flow/futurize` (`deletion-audit-sci-turn-render-2026-09-21.md` §2a) |

## 7. JDK and demunge facts

| Claim | Status |
|---|---|
| `java.util.concurrent.atomic.LongAdder` keeps a base plus a lazily grown table of cells, so concurrent increments spread over cells; under low contention it behaves like `AtomicLong`, under high contention throughput is much higher at the cost of space | **unverified from source this session** — stated from the JDK class documentation from memory; the lane should read the JDK javadoc or `java.util.concurrent.atomic` source |
| `sum()` is "typically not an atomic snapshot": it is accurate absent concurrent updates, and concurrent updates during the sum may not be incorporated | **unverified from source this session** (same origin) |
| `sumThenReset()` is "an effective equivalent of `sum()` followed by `reset()`", documented for the quiescent single-resetting-thread case; concurrent adds during the reset may be lost or carried | **unverified from source this session** |
| `LongAccumulator` takes a `LongBinaryOperator` and an identity, has the same non-atomic-snapshot caveat, and offers `get`, `getThenReset` | partially verified: a `LongAccumulator` with `Math/max` and `Long/MIN_VALUE` constructed and used successfully in §8 |
| Both were constructible and usable from Clojure in this JVM with a `reify java.util.function.LongBinaryOperator` | **verified** (§8) |
| Clojure class name → symbol is `clojure.lang.Compiler/demunge` | **verified, three call sites in the tree**: `src/seon/instrument.clj:114-125` (`caller-frame`), `src/seon/error.clj:505-525`, `src/seon/schema.clj:949-952` |
| Demunged frame grammar, from `error.clj:505-508` verbatim: "Demunged Clojure frames read `ns/fn`, `ns/fn--1234` for a compiled arity and `ns/outer/fn` for a closure, so the failing function is the first two segments with the compiler's suffix dropped. A frame that demunges to no `/` is a host class and is not a function at all." | **verified in tree**; the `--` suffix strip is at `error.clj:523-524` |
| JFR is started with `jcmd <pid> JFR.start …` / dumped with `JFR.dump` | **unverified** — no JFR invocation exists anywhere in `src/`, `bin/`, `script/` (grep found none) |

## 8. The one REPL evaluation

`mcp__seon__eval_clj`, mode `jvm`, `read_only`, cluster `default`, 415 ms. The
form timed `seon.id/valid?` (armed: the Var root carries
`:seon.instrument/var`) in four loops of 200,000 calls each after a 50,000-call
warmup: the unwrapped original via `malli.instrument/-f->original`; the armed
Var; the original bracketed by two `System/nanoTime` reads feeding a
`LongAdder` (sum), a second `LongAdder` (count) and a `LongAccumulator`
(`Math/max`, identity `Long/MIN_VALUE`); and bare `System/nanoTime`.

| Measurement | Value |
|---|---|
| Armed Vars in this JVM (`(count (seon.instrument/instrumented))`) | **1,570** |
| Unwrapped call | **179.79 ns** |
| Armed call (same function, same arguments) | **1,156.88 ns** |
| Unwrapped call + two nanoTime reads + 2 LongAdder adds + 1 LongAccumulator accumulate | **219.88 ns** |
| Sampling overhead (the difference) | **40.09 ns** |
| One bare `System/nanoTime` | **13.42 ns** |
| LongAdder count / sum after the loop | 200,000 / 37,338,211 ns; max 19,334 ns |

Single-threaded, one JVM (`default`), macOS/aarch64. Contention behaviour was
not measured. 40.09 ns is 3.5 % of the 1,156.88 ns armed call and 22 % of the
179.79 ns unwrapped call.

## 9. Open questions for a spec author

| Question | Evidence for one side | Evidence for the other |
|---|---|---|
| What identifies "the same version of this function"? | `:seon.program/analyzed-source-digest` exists, is required on every function row (`seon.fn.edn:116`) and needs no new attribute | it is the FILE's digest for file-indexed rows (`fn.clj:659-660`, `:110-116`), so it changes for every function in an edited file and cannot distinguish two functions in one file; a per-declaration digest would have to be derived from `:seon.fn/source` |
| How does a JVM-Var counter acquire a branch? | branch is one field away from any database value or connection (`db.clj:4283`, `:460-462`) | the invocation path reads no cluster or connection (§1), and one JVM hosts many clusters (AGENTS.md §1), so a host wrapper's counts are process-wide, not branch-wide |
| Is the arm-time key even available? | `arm-var!` already closes over five arm-time values and stamps seven metadata keys (`:864-896`) | none of them is a definition digest; `collect-contracts!` walks `ns-interns` (`:898-908`) and never consults the database, so a symbol→digest map would have to be supplied to `apply!` (whose request schema is `:seon.instrument/request`, `:935`) |
| Sample all calls or every Nth? | overhead measured at 40.09 ns / 3.5 % of an armed call (§8) | that is 22 % of the same call unwrapped, and the hottest functions in the program may be cheaper than `seon.id/valid?`; no distribution of per-function call cost was measured |
| Where does the flush run? | `close-call` is the one point a turn ends (`turn.clj:442-464`) | it is a `:db.fn/call` executing inside the writer, it is per agent and per cluster, and the counters are process-wide (§1); a cron portfolio already exists for periodic work (`schedule.clj:44-70`) at one-minute granularity |
| Does a window need self time? | `:seon.fn/calls` is a stored `[:set :qualified-symbol]` (`seon.fn.edn:135`, and `resources/seon/schemas/seon.fn.edn:59-67` on arities), so a callee-subtraction join is expressible | callee totals are process-wide sums over a window, not per-caller, so subtraction attributes a callee's whole cost to every caller of it; nothing in the tree measures per-edge cost |
| Which entity carries the aggregate? | AGENTS.md §3 and `durable-goals…:2d` forbid an entity per event and require "smart aggregation" | no attribute family for timing exists in `resources/seon/schemas/` (grep for `nanos`/`elapsed` in the schema resources was not exhaustive — **unverified**); whichever is chosen is a new schema resource |
| What does a zero-count entry mean? | skipping zero-count entries keeps the transaction small | "absence of signal read as health" is the project's named failure class (AGENTS.md §4); a function that was armed and never called is a different fact from one that was never armed (§1: 1,570 armed Vars in this JVM) |
| Is JFR the independent check? | `Compiler/demunge` is proven in-tree three times (§7), so JFR frames map to symbols | nothing in the repository runs JFR today, and the wrapper's picture excludes every unarmed frame — 1,570 armed Vars against 5,191 functions recorded in `durable-goals-and-rulings-2026-09-21.md:100` |
| Does A1 change the sample site? | A1 deletes `supplied-projection` (`:604-620`) and the per-call wrapper selection, leaving `arm-var!`'s outer fn as a straight `apply` | until it lands, the outer fn at `:880-889` also performs the argument scan and a `binding` push, so a measurement taken now includes work the spec's target path will not have |
