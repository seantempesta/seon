---
type: research
status: design and fact-finding; no src/test/resources edits
created: 2026-09-23
tags: [agent-platform, errors, faults, flow, datahike, policy]
---

# One error route (2026-09-23)

Lane `one-error-route`. This pass is design and fact-finding only.

**Authorities.** AGENTS.md "No swallowed errors" and "The error policy" (owner,
2026-09-23). README §7, rows "Errors are explicit named schemas" (`:375`) and
"Recorded error identity" (`:376`). The
[swallowed-errors census](swallowed-errors-census-2026-09-23.md) counts 181 A
sites. F0 is `ed2e1a6b6`.

**Prior art, read before this draft was finished:**

- [flow](error-route-inspiration-flow-2026-09-23.md)
- [malli/sci/kondo](error-route-inspiration-malli-sci-kondo-2026-09-23.md)
- [datahike/clojure](error-route-inspiration-datahike-clojure-2026-09-23.md)
- [web](error-route-inspiration-web-2026-09-23.md)

Churn costs come from the
[invalidation census](invalidation-census-2026-09-23.md).

**The owner, verbatim:**

- "We either handle it or we need to go the proper route."
- "I want one smart mechanism that we can require and keep consistent in all places
  so if we want to change behavior we fix it once and not at hundreds of sites."
- "we can't have this thrashing the database … fingerprinting errors so we don't
  duplicate and just increment a counter."
- "the panic part needs to be designed to make sure YOU and YOUR AGENTS see it and
  have the info you need to fix it or at least chase it down."
- "If the db is down I think it's fair to just panic … even in production."
- "we have flow for all the processes. This has a good error design."

## 1. What runs today: seven partial routes, verified

Line numbers are from the working tree on 2026-09-22 at about 21:40Z. `cluster.clj`
carries other lanes' hunks, so lines can move. The pinned dependency is core.async
`dc35f3e` (`deps.edn:23`).

| # | route | file:line | stores? | delivers? | dial | defect |
|---|---|---|---|---|---|---|
| R1 | `seon.cluster/commit-fault!` | `src/seon/cluster.clj:2934-3036` | yes, one transaction **per delivery**, via `error/recording` → `error/commit-call` | only through R7 | none (it reads config for bounds only) | "TOTAL, never throws" (`:2939`). The last-resort catch (`:3027-3036`) keeps only `{:seon.error/message …}` (census R-HELPER). It is reachable only as a closure, `:seon.flow/commit-fault!` (`boot.clj:52-53`, `cluster.clj:3240-3250`). |
| R2 | Flow fan-out and `fault-committer-step` | `src/seon/flow.clj:999-1001`, `:1005-1071`, `:1081`, `:1190-1265`, `:1267-1295` | via R1 | via R1 | read per fault | **Two lossy stages.** Flow's own `error-chan` is `sliding-buffer 100` (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:102`), evicting uncounted. Seon's `counted-dropping-buffer` has capacity 64 (`cluster.clj:3230`). A step exception leaves the proc **running** on its old state (`impl.clj:312-316`). A committer throw is printed as a class name only (`flow.clj:1063-1071` → `:1135-1156`, whose own catch returns nil). |
| R3 | the panic handler | `cluster.clj:3042-3066` `emit-core-fault!`, wired at `:3253-3259` | — | — | `:panic` | **Prints one stderr line, returns nil.** No throw, no stopped graph, nothing in status, the page or the REPL. It fires for the first signature only (`flow.clj:1042-1044`). |
| R4 | fan-out mode reader | `cluster.clj:3235-3238`; again at `sci/eval.clj:686-687` | — | — | `(or … :record)` | An absent dial reads as production: silence read as health. |
| R5 | `seon.db` transact failure | `db.clj:3927-3953` (generic catch), `:3816-3829` (`write-observation`), `:2725-2730` (`panic-on-core-error?`) | **no, in either mode** | no | its own query | `panic-on-core-error?` scalar-finds `[_ :seon.config/on-core-error ?mode]` over the whole database, not the cluster: a second dial reader. |
| R6 | twelve other dial readers | `instrument.clj:466-476`, `:664-674`, `:798-839`; `sci/eval.clj:1742-1811`; `sci/admit.clj:422`; `cluster.clj:854-862` (low disk: record only logs); `cluster.clj:2419`; `effect.clj:200`, `:651`; `turn.clj:5373`, `:5396` (`offer!` + `println`); `cluster/wake.clj:388` (drops the `offer!` result); `schema.clj:935-947` (counted stderr) | mixed; four store nothing | mixed | each decides | Loudness is decided at each site. |
| R7 | delivery in the writer | `error.clj:1365-1484` `commit-call`; `:1324` `steward`; `:1351` `message-tx` | — | one `:seon.message` | — | Recipients are chosen **only on first occurrence** (`:1461-1466`). The order is: the steward of `:seon.instrument/fn`'s namespace; else the attributed agent **if** `:seon.error/exception-class`; else `escalate-to` **if** exception class; else `{}`. That last branch is **recorded, delivered to nobody** for value-shaped faults (contract refusals) outside a stewarded namespace. |

**The mechanism to keep.** R1 + R7 already provide:

- D13 identity (`error.clj:151-176`);
- one occurrence component per `[signature agent turn|process]` (`:1545-1552`);
- no-history `count`/`last-at` (`3b321f261`,
  `resources/seon/schemas/seon.error.occurrence.edn`);
- blob-staged evidence;
- a wake message. `:seon.message/to` is listened (P2).

What is wrong is four things. Faults reach R1 through lossy channels. Twelve readers
apply the dial. Delivery falls through to nobody. `:panic` only prints.

### Probes on default (JVM mode, read-only forms)

Default was not pid 43581 or 51528. P1 (`bin/seon status`, 122 ms) found pid 64314,
started 21:42:43Z, with **`ready-ms 51,138` (a 51 s boot, over 10 s: a defect)**. It
went away at about 21:44:50Z, not by this lane. It came back as pid 70720, started
21:47:19Z, loaded commit id `6ab2f7cd-656b-58c7-90da-811f780cd2e7`.

- **P2** (one query form, 10.3 ms; pid 64314):
  - `:seon.config/on-core-error` **`:panic`** (one fact, entity 39813);
    `:seon.config.error/escalate-to` **`"root"`**.
  - Error facts: **0**. Occurrences: 0. Error notifications: **0**.
  - Stewards: 1 (`my.agents.root` → `root`).
  - Listened attributes: `:seon.message/to`, `:seon.effect/to`,
    `:seon.issue/agent`, `:seon.schedule.fire/agent`.

  So default runs `:panic`, and since that boot nothing has been recorded or
  delivered.
- **P3** (pid 70720; `error/recording` on the db value, then `datahike.api/with`;
  nothing was transacted). The source was a Flow fault whose `::flow/ex` is
  `(ex-info "wrapper" {…} (ex-info "Keyword cannot be cast to Number" {…}))`.
  - **First occurrence:** 33 datoms. One `:seon.message/to` = eid 40137 = `root`
    (escalate-to, because the fault has an exception class and no agent).
  - `recording` took 3.6 ms. **The transaction took 1,273.6 ms.**
  - **Repeat, same signature:** 9 datoms. That is `count`/`last-at` **plus a
    rewrite of layer, operation, exception-class, data-edn, `:seon.instrument/fn`
    and at**. No message. `recording` took 239.2 ms and the transaction
    **1,250.8 ms**.
  - So today **a recurrence costs a 1.25 s transaction, per delivery**.
  - **The fact has no `:seon.error/chain`** (`fact-has-chain false`) even though
    `seon.error.refusal/chain` is loaded. F0 does not reach Flow-fault facts.
  - A value-shaped fault declared `:seon.error/base` refused in `prepare`, as
    expected: the base is not a declared schema.
- **P4** (cause of the 1.25 s):
  - `commit-call` on the outer db value: **26.1 ms**. Applying its expanded output
    with `d/with`: **1.2 ms**. An empty `d/with`: 0.15 ms.
  - The same `commit-call` run **inside** the transaction: **1,099.9 ms**. The
    in-transaction database carries a projection that is **not identical** to the
    outer one.
  - Hypothesis, consistent with the evidence but not proven by a profile:
    `projection-cache-value ::declared-schema-attributes` (`error.clj:1397-1407`)
    misses on a projection without its runtime holder. Its own docstring says such
    a projection "simply derives afresh" (`schema.clj:397-401`).
  - **Out-of-scope defect:** every fault write pays about 1.1 s of in-transaction
    work. It is a precondition for §4: the fix is to compute
    `diagnostic-attributes` outside the transaction and pass it in, or key it by the
    projection's definition digest. Both are the owner's fix in `error.clj`.
- **P5** (0.9 ms): default holds 0 stored errors. **The owner's "one frame vs all
  first-party frames" grouping comparison cannot be measured on default.** It
  needs a store with real faults; §4 names the query.

## 2. The call-site rule

> A `catch` does exactly one of three things:
> 1. **Handle a declared case.** Catch the dependency's declared class, or check a
>    declared `ex-data` member, and return the function's **declared** error value (a
>    member of its arity's output union, README §7). Rethrow everything else
>    unchanged.
> 2. **Go the route:** `(fault/fault! world failure context)`, and return what it
>    returns.
> 3. **Not exist.** Let it propagate to the owning boundary. A rethrow that adds
>    context chains the cause, `(ex-info msg data cause)`.
>
> Nothing else: no nil, no default, no bare message, no `println`/log, no `offer!`
> onto a fault channel, no local read of `:seon.config/on-core-error`.

**Inside a Flow proc (the owner: "we have flow for all the processes").** The step
function has **no catch**; rule 3 always applies there. Flow's own step catch
(`impl.clj:312-316`) is the contract: "report it on the ::flow/error channel … and
attempt to continue" (`flow/spi.clj:56-58`).

Seon's one step catch lives at the proc construction seam, `flow/var-process`
(`src/seon/flow.clj:132`, "THE construction seam for every proc"). It wraps the
transform and transition arities of the step Var, calling the Var each time, so hot
reload still applies. The wrapper calls `fault!` **synchronously on the proc
thread**, then rethrows the ex-info that carries the stored identity into Flow's
catch. Flow's `error-chan` stays the notification seam for the monitor tap and the
committer.

The committer handles only what the wrapper cannot see:

- xform failures (`impl.clj:105-110`);
- outer-loop and control failures (`impl.clj:317-320`).

It skips anything already carrying `:seon.error/id`. **This is the one place
this design departs from "the error channel is the seam".** The channel is
sliding-100 and uncounted (`impl.clj:102`), so a burst larger than 100 would lose
faults before Seon sees them. The AGENTS policy says "never dropped by an overload
channel". The channel still carries every fault, but storage does not depend on it.

**Outside procs**, the required call runs at these boundaries:

- `seon.cluster.boot/request!`;
- the MCP/REPL evaluation entry;
- the web handlers (http-kit `:error-logger` → `fault!`, from the flow survey §3);
- the test runner's body boundary;
- `babashka.process` checks, where a JVM call runs a subprocess.

**Backstop.** `Thread/setDefaultUncaughtExceptionHandler` is set once at process
root to call `fault!` with the process's world. It catches go blocks, virtual-thread
tasks, and xform escapes that reach `dispatch/ex-handler`
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:63-69`).
Nothing in Clojure, Datahike or babashka sets it today (datahike/clojure survey §1).

**One call shape, one namespace, no macro.**

```clojure
(require '[seon.fault :as fault])

(catch Throwable failure
  (fault/fault! world failure {:seon.error/layer :seon.boot/request
                               :seon.error/operation `request!}))
```

- `world` is the `:seon.env/environment` the caller already holds (connection,
  cluster name, projection: `resources/seon/schemas/seon.env.edn:16-79`). Nothing
  is looked up from a registry.
- `failure` is a Throwable or an already-built core error value. An agent mistake
  never comes here.
- `context` requires `:seon.error/layer` and `:seon.error/operation` (D13 inputs).
  It optionally takes `:seon.agent/id`, `:seon.turn/id`,
  `:seon.error/declared-schema` (default `:seon.flow/exception-error`), and
  `:seon.error/offending`.

A `guard` macro would save only the `try`/`catch`. It cannot know the enclosing
function without reading `&env`, and it would hide the declared-case catch that
most sites also need. The conversion script (§7) writes `operation` from the
enclosing `defn` once instead.

**Record once per throwable** (Sentry's dedupe, web survey (2)). `fault!` keeps a
weak identity set (`java.util.WeakHashMap`, keyed by identity) over each routed
throwable **and its causes**. A throwable already routed is rethrown as-is under
`:panic` and returned as its stored value under `:record`, with no second write.
This makes it safe for every boundary on a propagation path to call `fault!`, which
the "every catch calls the one function" rule requires. It is not derived state:
the entries die with their throwables.

**Sentinels always propagate before recording** (malli/sci/kondo survey item 6):

- SCI's interrupt (`sci/admit.clj:404-406` already rethrows it);
- the storage over-bound (`:408-409`);
- `InterruptedException`.

**Namespaces below the route cannot require it.** This was computed from the ns
forms in 0.06 s (`scratchpad/deps.clj`). The route needs `seon.error`, `seon.config`,
`seon.blob` and `seon.db`, which close over 22 namespaces:

- `seon.ai`, `seon.ai.tokens`;
- `seon.blob`, `seon.call-preparation`, `seon.cluster.wake`, `seon.config`,
  `seon.db`, `seon.env`;
- `seon.error`, `seon.error.refusal`, `seon.fn.schema-shape`, `seon.id`,
  `seon.print`, `seon.reconcile`;
- `seon.render.route`, `seon.render.value`, `seon.repl`;
- `seon.schema*`, `seon.sci.admit`.

These use rules 1 and 3 only. The closure is wide because `seon.error` requires
`seon.render.value` (→ `seon.cluster.wake`) and `seon.repl` (→ `seon.ai`). Option C
cuts that edge.

## 3. `seon.fault/fault!`: contract and behaviour

It accretes from `seon.cluster/commit-fault!`. The move out of `cluster.clj` is a
retirement: `seon.cluster/commit-fault!` is deleted and every caller is converted
in one loadable slice. The callers are:

- `boot.clj:52-53`;
- `cluster.clj:3240-3250`;
- every `:seon.flow/commit-fault!` member: `instrument.clj`, `sci/eval.clj:704`,
  `:1742-1811`, `:2340-2577`, and `test/arm.clj:188`;
- the schemas `seon.flow.edn:129-133`, `seon.instrument.edn`, `seon.sci.eval.edn`.

It cannot stay in `cluster.clj`, because `seon.cluster` requires `seon.flow`,
`seon.turn` and `seon.instrument`, the callers that must require the route. That
cycle is why it is threaded as a closure today.

```clojure
{:malli/schema
 [:=> [:cat :seon.env/environment [:or :seon.error/throwable :seon.error/base]
            :seon.fault/context]
      :seon.fault/recorded]}   ; :record returns it; :panic throws
```

The order follows Datahike's writer seam (`writer.cljc:143-170`): the caller learns,
the error is recorded, then the dial applies. Each step runs on the caller's thread.

1. **Identify.** `error/prepare` produces the fact with F0's chain
   (`:seon.error/chain`, root frame first) and the fingerprint (§4). The Flow-fault
   path must extract `::flow/ex` before `prepare`, which P3 shows it does not do
   today.
2. **Store, deduplicated.** The first occurrence of a fingerprint is one
   synchronous transaction through the existing `recording` → `commit-call` →
   `db/transact!` (with `blob/with-publication!` when evidence is staged). Repeats
   are coalesced (§4).
3. **Deliver.** The same transaction carries the wake message (§5).
4. **Dial.** The dial is read from the database value step 2 already read
   (`config/effective db cluster-name`). There is no other reader and no captured
   copy.
   - `:panic` throws `(ex-info root-message {:seon.error/id … :seon.error/signature
     … :seon.fault/panic true} original)`. The failing graph stops (§6). The JVM,
     REPL and sibling graphs stay up.
   - `:record` returns `:seon.fault/recorded`, one named schema:
     `[:and :seon.error/base [:map :seon.error/id :seon.error/signature]]`. A
     returning caller lists that name in its output union.
   - An **absent or unreadable dial means `:panic`**. This deletes R4.

**Database down: the one exception to `:record` (owner ruling: "even in
production").** If step 1 or 2 cannot complete, the route **panics in both modes**.
That covers a transact failure, an outcome-unknown write bound, a refused
publication, or `prepare` itself throwing.

- It throws the original failure with its whole chain, with the store failure
  attached by `.addSuppressed`.
- The failing graph stops.
- `*e` at the REPL holds the object.
- stderr gets one `(pr-str (Throwable->map t))`.
- There is no fallback store, no replay file, no journal, no retry and no silent
  continue. AGENTS: "Database failure is handled at the reachable REPL."

This replaces R1's "never throws" and its last-resort shape.

**Deletions in the same slice:**

- `db.clj:2725-2730` and the `:panic` branch at `:3947-3952`. `seon.db` returns its
  declared value, and the caller above routes it.
- `emit-core-fault!` (`cluster.clj:3042-3066`).
- The mode reader at `:3235-3238` and `sci/eval.clj:686-687`.
- The local dial branches at `sci/admit.clj:422` and `cluster.clj:854-862`.
- `turn.clj:5373`/`:5396`.
- The committer's first-signature-only panic (`flow.clj:1042-1061`).

## 4. Storage without thrash: fingerprint, burst, write rate

**Fingerprint.** Today it is D13 (`error.clj:151-176`): layer, operation, declared
schema, throwable class, **one** frame, expected key/shape, and path. It never uses
the message, time, process or bytes. Sentry's grouping (web survey (1)) uses every
cause's class plus the first-party frames **without line numbers**, with generated
names like `$fn__N` normalized, the message ignored when a trace exists, and the
grouping **versioned**.

This design accretes D13 in place:

- the throwable-class member becomes the chain's classes;
- the frame member becomes the chain's first-party frames as `[ns-fn file]` without
  lines, with `fn__\d+` suffixes normalized.

The normalization uses `clojure.main/demunge`
(`reference-code/clojure/src/clj/clojure/main.clj`). Stripping the numeric suffix
without a regex needs one owner decision: AGENTS forbids production regexes without
permission.

The version is the definition digest of `seon.error/signature` itself, since
identity comes from the definition digest (AGENTS "No stamps"). So a grouping change
re-fingerprints by construction, never by a stored version stamp.

Whether one frame or all first-party frames groups better is **unmeasured** (P5:
default holds 0 errors). The measurement, on a store with faults: for each stored
occurrence, compute both keys and compare distinct-key counts against distinct
`(root message, root class)` pairs:
`(frequencies (map (juxt key-one-frame key-all-frames) occurrences))`.

**Burst.** Today there is one transaction per delivery (R1), and P3 measured
1.25 s each. Datahike's `:db/noHistory` skips only the temporal index. eavt and
aevt are still rewritten, and every commit writes index nodes plus a commit blob
(datahike survey §2, `db/transaction.cljc:447,546-573`, `writing.cljc:49-110`).
Every commit also moves the commit id, which re-arms commit-keyed derivations for
0.4-1.2 s (invalidation census, row A1). **One transaction per occurrence
thrashes.**

- **First occurrence** of a fingerprint in this process: one synchronous
  transaction with the chain, occurrence and wake message.
- **Repeat** within `:seon.config.error/flush-ms`: a new dial declared beside
  `recurrence-limit`, default 1,000 ms. The repeat is a `swap!` on the route's
  process-local `{fingerprint {:pending n :first-pending-at t}}` (Sentry's
  buffered counts). No transaction.
  - `:panic` still throws to that caller, because loudness is per call.
  - `:record` returns the known stored value.
- **Flush:** the first repeat after the window, the graph's stop transition
  (`flow.clj:1017-1022` already joins there), and process shutdown each write one
  transaction per pending fingerprint. That transaction carries only `count +=
  pending` and `last-at`: two no-history datoms on the existing occurrence, with no
  re-asserted fact attributes (P3 shows 9 datoms today), no message and no chain.
- **Losable, and never silently.** A crash loses at most one window of counts per
  fingerprint. The count is an observation, and AGENTS says "Channels carry only
  losable data". The first occurrence, the chain and the delivery are never
  coalesced, so no fault is lost; only an undercount of repeats is possible.
- This map holds undelivered observations keyed by stored identity, not derived
  state beside the connection.

**Write rate under a hot loop.** One fingerprint throwing continuously for T seconds
writes **1 + ⌈T / flush-ms⌉** transactions. At 1 s that is 61 in a minute, where
today it is one per throw (about 50 in a minute at P3's 1.25 s serialized, with the
writer saturated). k distinct fingerprints write k times that.

Error rows advance no program-partition revision (invalidation census W9), so under
the revision-keyed acquisition (`sci-program-revisions`, in flight) they invalidate
nothing. Until that lands, each flush costs one commit-keyed re-arm. The P4 defect
(about 1.1 s in-transaction) makes the first-occurrence write synchronous for about
1.1 s on the caller's thread today. It must be fixed in `error.clj` before or with
step 0.

## 5. Delivery: who is woken, derived from facts

The recipient is computed in `commit-call` (mid-transaction, where it already runs).
The first match wins:

1. **The attributed agent**: `:seon.agent/id` in the context, or the Flow tag
   (`cluster.clj:2896` `tagged-run`, `join-error-fanout!` tag). Reason `:your-run`.
2. **The namespace's owning agent, from namespace refs.** The namespace comes from
   `:seon.instrument/fn`, else the namespace of `:seon.error/operation`, else the
   root first-party frame. The owner is `:seon.ns/steward` today (`error.clj:1324`,
   `resources/seon/schemas/seon.ns.edn:29`), and the B3/D1 namespace ref when it
   lands. Reason `:recurring` today, renamed `:your-namespace`.
3. **`:seon.config.error/escalate-to`** (default `"root"`, P2). Reason
   `:no-attributable-agent`.
4. **Nobody is not an answer.** If `escalate-to` names no agent, `commit-call`
   refuses by name. That makes the route panic, which is the database-level
   refusal of §3. This deletes the `:else {}` (`error.clj:1465`) and the
   `interrupted?` precondition (`:1463-1464`) that makes value-shaped faults
   undeliverable.

The message is the existing `message-tx`. `:seon.message/to` is listened, so this is
the ordinary wake route, and P3 showed the eid-40137 message. It carries
`:seon.message/from`, so it is an inside wake (`cluster/wake.clj:167-185`) that does
not reset the recipient's turn bound. It is sent on first occurrence and on
**reopening** after resolution (§6), never on a flush.

## 6. The panic: where it surfaces, what it carries, how it closes

**One derivation feeds every surface.** `seon.problems` already has
`:seon.problems/error-signatures` (`src/seon/problems.clj:109-128`, `:398`) and
feeds `runtime_status`'s `problem-counts` (`cluster.clj:617-644`). Add one derived
family, `:seon.problems/open-panics`: fingerprints with an occurrence raised under
`:panic` and no resolution.

| surface | shows |
|---|---|
| MCP `eval_clj` (`script/seon/dev/mcp.clj:536`) | **every** response carries `:seon.dev.mcp/open-panics` while any exist; a panic thrown by the evaluated form sets MCP `isError` and returns the short flagged value (web survey (4)) |
| MCP `runtime_status` (`mcp.clj:668`) | the block, plus the failed graph's procs as `:failed`, never `:unknown` |
| `bin/seon status` | `:seon.operator/open-panics` beside `:seon.operator/clusters` |
| REPL | `*e` is the thrown ex-info (identity in `ex-data`); `(seon.fault/open db)` lists the rows |
| page | a banner on every namespace page from the same family |

**A failed graph is positively visible.** Under `:panic`, the `var-process` wrapper
stops the graph whose transform threw. It holds the graph through the start join, as
the render proc does (`cluster.clj:3262-3266`), because a proc otherwise has no
handle on its graph (`flow/impl.clj:166`). The stop is the graph's terminal fact, so
oversight reports `:failed` with the error id. Everything else keeps running.

**What each row carries, and the short flagged value:**

```clojure
{:seon.error/id "…" :seon.error/signature "c02f…93c"
 :seon.error.occurrence/count 1 :first-at … :last-at …
 :seon.error/layer :seon.flow/exception :seon.error/operation seon.turn/settle!
 :seon.fault/root-message "Keyword cannot be cast to Number"
 :seon.fault/frame [seon.turn$settle_BANG_ "turn.clj"]
 :seon.fault/explain (seon.fault/explain db "c02f…93c")}
```

`explain` returns what a fixer needs:

- the whole fact;
- `:seon.error/chain` (F0: each link's class, message and `ex-data`, and its
  first-party frames, root first);
- every occurrence;
- the basis: store, branch, commit id and `t` (`db.clj:3816-3829` already builds
  `:seon.error/basis`; the route adds it to every fault);
- the offending input, admitted once through the value renderer's bound. An elision
  names the bound, count, path and requery. The full value is in
  `:seon.error.occurrence/data-blob`.
- When the context carried the request, the exact reproduction form. For a proc
  that is `(step state cid msg)`, with the bounded pre-step state that
  `meaningful-source` currently drops (`error.clj:218-222`; flow survey addendum).

**Acknowledgement and resolution, never silent.** The schema already has
`:seon.error/issue` and `:seon.error/regressions` (`seon.error.edn:284-285`). The
issue family has the settlement-written `:seon.issue/resolved-tx` (absence means
open, `seon.issue.edn:38`).

- **Acknowledged:** the fingerprint refs an issue
  (`(seon.fault/acknowledge! conn signature issue-path)`, or filed by the woken
  agent). It leaves the every-response block and stays in `runtime_status` as open.
- **Resolved:** the issue's `resolved-tx` exists, meaning its regression verified.
  Only settlement writes it.
- **Reopened:** an occurrence with `last-at` after `resolved-tx`. This is derived,
  and it re-sends the wake.
- Those three are the only states. There is no dismiss.

## 7. Census conversion onto the route

| step | census rule (A sites) | conversion | tool |
|---|---|---|---|
| 0 | — | Land `seon.fault/fault!`. Retire `commit-fault!`, the `:seon.flow/commit-fault!` closures, R3-R6 and `panic-on-core-error?`. Add the `var-process` wrapper, the uncaught handler, and the P4 fix. All in one slice. | hand |
| 1 | R-OFFER (3+1C), R-TIMEOUT-ABSENT (1+3C), `println` fault sites (`turn.clj:5373`, `:5396`, `wake.clj:388`, `render/web.clj:2868`) | above the route: `fault!`; `wake.clj` is below it, so rule 3 via its above-route starter | hand (8) |
| 2 | R-MSG (27+8C) | **one rewrite-clj script** over catches whose value is a map literal with `:seon.error/at`. Above the route: `(fault/fault! <world> <e> {layer op})`, with `op` from the enclosing `defn`. Below it: the census's `refusal/diagnostic` form. The script lists sites where it cannot name the world binding. | script |
| 3 | R-HELPER (36 sites, 17 helpers) | helper bodies pass the throwable; callers unchanged | hand per helper |
| 4 | R-LOG in `bin/seon-hook` (10) | the census sed; Babashka prints `Throwable->map` and exits non-zero (outside the JVM route) | sed |
| 5 | R-DISCARD (64+14C) | a script lists each site with its enclosing fn and its side of the route. Each gets rule 1 (declared class) or rule 3 (delete the try). None defaults to a route call. | script lists, hand decides |
| 6 | R-UNKNOWN-STR (23), B4 runner rows (21) | ask the three questions; most die with plan 1.3d commit 5 | delete with the machinery |
| 7 | R-PRED (29C) | owner ruling on narrowing, then a per-dependency class script | ruling, then script |
| 8 | R-CAUSE (2), R-EXDATA (1) | hand | hand |

**Enforcement:** a clj-kondo hook in `bin/seon-hook`'s syntax check flags a `catch`
whose body does any of:

- reads `:seon.config/on-core-error`;
- calls `println`/`log/*`;
- `offer!`s a fault;
- returns a literal nil/false.

It also flags a `catch` inside a `var-process` step Var. This is kondo's
`reg-finding!` model (malli/sci/kondo survey §5).

## 8. One regression per behaviour class

All use the canonical fixture branch and the real `fault!`, with the dial as a
config fact on the branch.

1. **Stores its chain synchronously.** Under `:record`, `(fault! env (ex-info "w"
   {} (ex-info "leaf" {})) ctx)` returns `:seon.fault/recorded`. The branch holds a
   two-link chain before the call returns. The same holds for a Flow-proc throw
   (the P3 gap).
2. **Coalesces a burst.** 1,000 calls with one fingerprint in one window make
   exactly 2 transactions (the first and the stop flush), with count 1,000 and one
   `count` datom in history.
3. **Records once per throwable.** The same throwable routed at three nested
   boundaries makes one occurrence and one count.
4. **Always delivers to a named agent.** Attributed agent, namespace owner and
   escalate-to each yield one `:seon.message/to`. A missing escalate-to refuses by
   name. No case yields zero recipients.
5. **`:panic` throws with identity and stops only its graph.** A proc step throws.
   The rethrown ex-info carries `:seon.error/id`. Oversight shows the graph
   `:failed`, a sibling graph answers ping, and the REPL evaluates.
6. **Database down panics in both modes.** Under `:record`, a refusing writer makes
   `fault!` throw the original with the store failure suppressed. Nothing is written
   elsewhere.
7. **Absent dial means `:panic`.**
8. **Open panics surface and close.** The row appears in the `eval_clj` block and
   in `bin/seon status`, leaves the block on `acknowledge!`, leaves `runtime_status`
   on `resolved-tx`, and reopens on a new occurrence.

## 9. Three options

**A. The route as an environment member (simplest viable).** Keep `commit-fault!` in
`cluster.clj`. Rename its closure `:seon.env/fault!` in the environment. Every site
calls `((:seon.env/fault! world) e ctx)`. The dial, delivery fix, coalescing and
DB-down panic go inside it. The Flow fan-out stays as the proc path.

- *Guarantee:*
  - one implementation and one dial reader;
  - no recorded-to-nobody;
  - DB-down panics.
- *Cost:* about 1 day. No move.
- *Gives up:*
  - "one mechanism we can **require**": a function value in a map is invisible to
    kondo, so enforcement cannot prove use;
  - proc faults still pass flow's uncounted sliding-100 channel and the counted-64
    buffer, so a burst loses faults.

**B. `seon.fault`, synchronous at the owning boundary, Flow's step seam wrapped once
(RECOMMENDED).** §§2-8 as written.

- `fault!` accretes from `commit-fault!`, and the move retires it and every closure
  in one slice.
- Procs never catch. The `var-process` wrapper is the one step catch, and it stores
  before Flow's channel sees the fault. Flow's `error-chan` remains the notification
  seam for the monitor and for xform and outer-loop faults.
- An uncaught handler is the backstop. Record-once makes nested boundaries safe.
- Burst coalescing, the open-panics family, and the DB-down panic are included.

- *Guarantee:*
  - every unhandled error above the route is stored with its chain before its
    caller continues, and delivered to a named agent;
  - it is loud under `:panic` and stops only its graph, and panics when the store
    is down;
  - a policy change is one edit in `seon.fault`;
  - repeats cost at most one transaction per fingerprint per second.
- *Cost:*
  - step 0 is about 2-3 days, including the P4 in-transaction fix;
  - then census steps 1-8 with two scripts and one sed;
  - `counted-dropping-buffer` stays only for xform and outer-loop faults, which the
    wrapper cannot intercept.
- *Gives up:*
  - the 22 low namespaces cannot call the route and use rules 1 and 3 only, so a
    thread started inside one must be started from an above-route boundary that
    wraps it;
  - it departs from the pure "channel is the seam" reading (§2) where that reading
    would lose faults.

**C. B, plus cutting `seon.error`'s renderer dependencies.** Move `notice`/`ai-prose`
(the reason `seon.error` requires `seon.render.value` and `seon.repl`) out of the
writer and into the error family's render pair at read. The stored message is then
only `:seon.message/about` the fingerprint. The route's closure shrinks to about
`db`, `blob`, `config`, `error`, `id` and `schema`, so `seon.cluster.wake`,
`seon.ai`, `seon.repl` and `seon.render.value` can call `fault!`.

- *Guarantee:* B's guarantee, extended to the wake listener and the provider
  threads.
- *Cost:* B plus about 2 days. It changes stored message content (rendering at
  read, which AGENTS' one-AI/HTML-pair rule wants anyway), and it is a
  schema/render change that needs the incremental adoption proof.
- *Gives up:* nothing in guarantee. It adds schedule risk on render files that
  other lanes hold.

**Recommendation: B.** File C as its follow-up once the render lane releases
`render/value.clj`.

## Out-of-scope findings (no issue filed; this lane commits only this file)

- **P4.** A fault transaction spends about 1.1 s in `commit-call` mid-transaction,
  against 26 ms outside. The in-transaction projection is not identical. Owner:
  `error.clj`. The class is the invalidation census.
- **P3.** Flow-fault facts carry no `:seon.error/chain` despite F0. Owner:
  `error/prepare`'s `::flow/ex` extraction.
- **P1.** Default's `ready-ms` was 51,138 (pid 64314), a boot over 10 s.
- `seon.cluster/exception-summary`, the MCP JVM-eval failure face, dropped the
  `ex-data` of a refused `prepare` in this lane's own probe. Only
  `Throwable->map` showed it. This is census R-HELPER class, owner `cluster.clj`.
- **This lane wrote to default.** The P3 result exceeded the MCP window and settled
  one `:seon.dev.mcp.artifact` blob row (digest `0875d052…`, 9,331 B) despite
  `read_only`. This is invalidation census row W1.

## Timings

| operation | wall | notes |
|---|---:|---|
| `bin/seon status` (P1) | 122 ms | |
| P2 query form | 10.3 ms | |
| P3 form total | 3,012 ms | two `d/with` of the fault tx: **1,273.6 ms** and **1,250.8 ms**; `recording` 3.6 ms / 239.2 ms |
| P4 `d/with` of the tx, twice | **1,002.3 ms, 1,025.1 ms** | `commit-call` outside the tx 26.1 ms; inside **1,099.9 ms**; expanded `d/with` 1.2 ms |
| P5 query form | 0.9 ms | |
| ns closure (`bb scratchpad/deps.clj`) | 56 ms | |
| default boot observed (`ready-ms`) | **51,138 ms** | over 10 s; not this lane's operation |

The operations over 1 s are the fault transaction (P3/P4), which is §1's finding,
and the boot. No cache applies to either: a `d/with` has none, and P4 is a cache
miss.
