---
type: research
status: active
tags: [prd, research]
---

# Error handling, grounded — how the exact problem always surfaces (2026-07-27)

The question: how should Seon's error system be wired so the exact
problem is always surfaced, never hidden? The owner's target: faults
reach the TRIGGERING AGENT (and an escalation recipient for non-trivial
ones) through the existing message mechanism, with a clear explanation
of what happened and why they are being told; plus malli instrumentation
at boundaries, which is enabled NOWHERE in the fresh tree today
(verified: the only `collect!` in `src/` is
`seon.cluster.registry/collect!`, a blob sweeper, and nothing requires
`malli.instrument` or `malli.dev`).

Everything below carries file:line. Numbers are measured on this
machine today and the exact form is reproduced so anyone can re-run it.

## 0. Dependency ledger

| Dependency | Selected coordinate | Read at |
|---|---|---|
| core.async + flow | `org.clojure/core.async 1.10.874-alpha3` (`deps.edn:17`) | `reference-code/core.async` @ `dc35f3e0d7bc2eef502e77982f48641f025c8051` (2026-06-04) |
| malli | `metosin/malli 0.20.0` (`deps.edn:14`) | `reference-code/malli` @ `80f13807…` — CHANGELOG head is `UNRELEASED` above `0.20.0 (2025-11-17)`, so the vendored tree is slightly AHEAD of the pinned artifact. Nothing cited below is in the UNRELEASED delta, but the drift is real and should be closed (pin the vendor to the 0.20.0 tag or bump the dep). |
| sci | `:local/root reference-code/sci` (`deps.edn:41`) | `reference-code/sci/src/sci/{interrupt,impl/utils,impl/callstack,impl/parser,core}.clj(c)` |
| Datahike | `:local/root reference-code/datahike` | `writer.cljc`, `api/specification.cljc` |

First-party owners read in full: `src/seon/flow.clj`,
`src/seon/cluster/{loop,wake,run,work,prompt}.cljc`,
`src/seon/cluster/{store,registry,ancestor,export}.clj`,
`src/seon/cluster.clj`, `src/seon/sci/{eval,admit}.clj`,
`src/seon/schema/{error,eval,config,message,run}.edn`,
`test/seon/flow_test.clj`. Quarry: `src-old/seon/error.cljc`,
`src-old/seon/error/instrument.cljc`, `src-old/seon/instrument.cljc`.

## 1. What core.async.flow actually does with a throw

### 1.1 The error channel is created, buffered, and closed by the graph

`impl.clj:99-102` — `start` creates, per graph:

```clojure
control-chan (async/chan 10)
report-chan  (async/chan (async/sliding-buffer 100))
error-chan   (async/chan (async/sliding-buffer 100))
```

`flow/start` returns `{:report-chan … :error-chan …}` (`impl.clj:172`,
documented `flow.clj:108-120`). `stop` sends `::flow/stop` to all, then
**closes both channels** and drops them (`impl.clj:177-182`).

**If nobody reads the error channel, errors are SILENTLY DROPPED.** A
`sliding-buffer` never blocks and never rejects — `add!*` discards the
OLDEST entry once full. So an unread error channel is not backpressure
and not an exception; it is a 100-deep window that quietly forgets. This
is the single most important fact for the wiring: *the consumer is not
optional, it is the mechanism*.

### 1.2 Three distinct error shapes ride that one channel

Our codec must be total over all three; they do NOT share a key set.

| Origin | Shape | Source |
|---|---|---|
| transform threw | `#::flow{:pid :status :state :count :cid :msg :op :step :ex}` | `impl.clj:312-316` |
| anything else in the proc loop threw (control read, `handle-transition`/`:transition` arity, `send-outputs`) | `#::flow{:pid :status :state :count :ex}` — **no `:cid`, no `:msg`, no `:op`** | `impl.clj:317-320` |
| a channel `xform` threw | `#::flow{:ex :pid :cid :xform}` — **no `:status`, no `:state`, no `:count`** | `impl.clj:106-110` |

Two of the three carry `::flow/state` — **the proc's whole init state**.
For our run loop that value is `{:seon.cluster.loop/cluster <cluster>,
:seon.cluster.loop/turns n}` and the cluster holds a live Datahike
connection and executors (`loop.cljc:226-229`). `::flow/msg` is likewise
an arbitrary input value. **Neither may ever be `pr-str`'d raw into a
datom.** They must go through the one total codec (§6.1).

### 1.3 A throwing transform does not stop, restart, or reset the proc

`impl.clj:312-316`, the whole recovery:

```clojure
(catch Throwable ex
  (async/>!! (outs ::flow/error) #::flow{… :ex ex})
  [status state count read-ins])
```

The loop `recur`s with the **pre-step** `status`, the **pre-step**
`state`, and the **unincremented** `count` — the reported `:count` is
`(inc count)` but the loop keeps the old one. So:

- the proc survives and keeps consuming its inputs;
- there is **no retry** — the failed message is gone;
- every mutation the transform made to its own state is discarded;
- `flow/ping-proc`'s `::flow/count` **does not advance** across a
  faulted message. That stalled counter is the observable fingerprint of
  a fault, and `test/seon/flow_test.clj:496-522` already asserts exactly
  this (`thrown-step-reports-error-and-keeps-pre-step-state`).

`ping`/`pause`/`resume` all keep working around a throwing proc: `pong`
is a closure over the live loop (`impl.clj:272-279`) and the control
channel is read with `:priority true` both in the main `alts!!`
(`impl.clj:288-296`) and inside `send-outputs` (`impl.clj:232-234`).
`inject` is unaffected — it resolves a write channel and `>!!`s it on an
`:io` future (`impl.clj:190-197`).

### 1.4 The exceptions that do NOT reach the error channel

- **A throw in the `init` (arity-1) call.** `start-proc` catches it,
  broadcasts `::flow/stop` to `::flow/all`, and **rethrows out of
  `flow/start`** (`impl.clj:163-165`). This is a boot failure delivered
  to the caller as a Throwable, not a fault fact. The boot must catch it
  the way `seon.cluster/start!` already catches tower failures
  (`cluster.clj:522-531`).
- **A throw in `describe` (arity-0)** happens during `create-flow`
  (`impl.clj:39`) — same class.
- **`:workload :compute` timeouts.** With `:compute`, each transform is
  submitted to the compute executor and awaited with
  `(.get fut compute-timeout-ms TimeUnit/MILLISECONDS)`
  (`impl.clj:258-260`, default 5000, `impl.clj:245`). A timeout throws a
  `TimeoutException` *inside* the transform position, so it IS reported
  as an `:op :step` error — **but the future is never cancelled**. The
  compute thread keeps burning. Config invariant that follows: any
  `:compute` proc's `compute-timeout-ms` must be strictly greater than
  the deadline the work itself enforces, or flow reports a spurious
  fault while the real limit is still doing its job. (Our eval does not
  hit this today: the loop is `:io` (`loop.cljc:214`) and eval reaches
  compute through `seon.flow/submit!!`, which has its own
  `(deref result time-limit-ms)` and `::wedged?` marking,
  `flow.clj:406-413` — see the open issue
  `docs/seon/issues/flow-submit-waits-forever-before-time-limit.md`.)

### 1.5 The report channel is a different thing and must not be conflated

`::flow/report` carries ping replies and explicit `::flow/report`
outputs (`flow.clj:109-113`), also `(sliding-buffer 100)`. Our procs
push observations there (`flow.clj:130-132`, `loop.cljc:217-218`,
`260`). Reports are observation; errors are faults. Nothing on the
report channel is a fault, and nothing on the error channel is
observation.

### 1.6 What flow documents as the intended consumption pattern

`flow.clj:114-120` is explicit: *"Any (and only) exceptions thrown
anywhere on any thread inside a flow will appear in maps sent here."*
The SPI restates the process obligation: *"if a process encounters an
error it must report it on the `::flow/error` channel (format TBD) and
attempt to continue"* (`spi.clj:55-58`). Flow deliberately provides no
policy — it centralises delivery and leaves classification, durability
and notification to us. That is the seam this document fills.

## 2. Our current tree: every place an error can arise, and where it lands

Legend for **Lands**: **durable** = becomes a datom; **value** = a flat
`:seon.error/value` a caller branches on; **channel** = put on a
core.async channel; **throws** = escapes as a Throwable;
**EVAPORATES** = observed by nobody.

| # | Site | Trigger | Shape | Lands | Evidence |
|---|---|---|---|---|---|
| 1 | `sci.eval/evaluate` | agent form threw, or the time limit fired | `{:seon.error/kind ::evaluation-failed \| ::time-limit …}` inside the evaluation map | **durable** — receipt `:seon.cluster.eval/{status,error,result-edn,output}` | `eval.clj:269-281, 354-370`; `loop.cljc:400-421` |
| 2 | `sci.admit/project` | a node the total codec cannot project | `:panic` → **throws**; `:record` → marker in the projection | dev: throws out of `evaluate` → row 1; prod: **durable** inside `result-edn` | `admit.clj:388-419` |
| 3 | `sci.admit/admit` | sci's uncatchable interrupt during realization | rethrown, deliberately | row 1 with `:seon.eval/outcome :time` | `admit.clj:390`; `interrupt.cljc:32-42` |
| 4 | `store/transact!` | our transition refused | the transition's own map verbatim | **value** → row 8/9/10 | `store.clj:438-441` |
| 5 | `store/transact!` | Datahike aborted (`:transact/cas`, `:transact/schema`) | `{:seon.error/kind :seon.db/rejected …}` | **value** | `store.clj:443-447` |
| 6 | `store/transact!` | unclassifiable failure | `{:seon.error/kind :seon.db/unknown-failure …}`; **dev panics by throwing** | value, or **throws** into the loop in `:panic` | `store.clj:449-459`, dial read at `store.clj:385-396` |
| 7 | `ai/complete` | no credential, timeout, transport, provider, unparseable body | flat `:seon.error/value` | **value** → row 8 | `ai.cljc:92,157,162,169,174,178` |
| 8 | `loop/turn` `:call` | model error or unreadable reply | `fail!` writes `:seon.cluster.run/error` + closes the run in one tx | **durable**, and the next prompt says it | `loop.cljc:312-333`; `prompt.cljc:138-142` |
| 9 | `loop/turn` `:open`/`:call`/`:resume`/`:close` | a refused transaction (rows 4-6) | `(report :error …)` — a turn-report value on `::turn-report` | **EVAPORATES.** The report goes to an out that is documented "for observation only" (`loop.cljc:217`) and, once the loop is a real graph proc, nothing is connected to it. The rejected transaction's *rule* is discarded at the `if` and never committed. | `loop.cljc:307, 360, 388, 430, 451` |
| 10 | `loop/turn` `:resume` | a refused terminal transaction | same as 9, and the receipt stays `:running` until the next boot's `recover-tx` calls it `:interrupted` | **EVAPORATES** (the reason), partly recovered later (the state) | `loop.cljc:401-430`; `run.cljc:574-607` |
| 11 | `wake` listener | the wake channel is closed (the proc is gone) | `(ex-info "the wake channel refused delivery" {…::undeliverable-wake})` offered to `fault-channel` | **channel** — and today `:seon.cluster.wake/fault-channel` has **no producer-side wiring in boot**, so in the live system it is whatever the caller passes | `wake.cljc:130-134` |
| 12 | `wake` listener | the handler itself threw | offered to `fault-channel` | **channel**, same caveat | `wake.cljc:135-136` |
| 13 | any flow proc | transform/loop/xform Throwable | the three maps of §1.2 | **channel** — `::flow/error`, `(sliding-buffer 100)` | `impl.clj:106-110, 312-320` |
| 14 | `seon.flow/fault-committer-proc` | a fault arrives | `commit-fault!` or `panic!` per the injected mode reader | **durable — but only if a caller supplies a real `commit-fault!`** | `flow.clj:452-488` |
| 15 | `seon.flow/start-error-fanout!` | the fault buffer overflows | `commit-drop!` with the dropped fault | as 14 | `flow.clj:422-450, 534-536` |
| 16 | `seon.flow/submit!!` | compute work threw | rethrown to the submitter | **throws** | `flow.clj:416-417` |
| 17 | `seon.flow` launcher | required config facts missing / launcher absent | `ex-info {:seon.error/kind :configuration}` | **throws** | `flow.clj:304-307, 381-384` |
| 18 | `cluster/start!` | any tower layer failed | `ex-info {:seon.error/kind :seon.boot/refused, :seon.boot/instance <degraded>}` | **throws**, with the degraded instance carried | `cluster.clj:522-531` |
| 19 | `store/open-store!`, `registry`, `ancestor`, `export` | a refusal rule | `ex-info {… ::refused ::rule …}` | **throws** (caught by 18 at boot) | `store.clj:166`, `registry.clj:91`, `ancestor.clj:93`, `export.clj:88` |
| 20 | `config/apply!`, `reconcile` | a refusal | `{:seon.error/kind ::refused …}` | **value** | `config.cljc:59`; `reconcile.cljc:62` |
| 21 | `schema.cljc` | bad user input / a core bug | `ex-info` with `:seon.error/kind :user-input` or `:core-bug` | **throws** | `schema.cljc:112, 172, 724, 1513…` |

### 2.1 The three holes this table exposes

1. **Row 13 has no consumer in the live system.** `cluster/start!`
   builds the REPL, the store, the ancestor, the branch and the config
   (`cluster.clj:449-531`) and **never creates a flow graph, never
   starts the loop proc, never registers the wake listener, and never
   calls `start-error-fanout!`**. `seon.flow`'s fault machinery is
   exercised only by `test/seon/flow_test.clj`. Today, therefore, every
   core fault in a live cluster would be dropped by a `sliding-buffer`
   that nobody reads. This is the single highest-value fix and it is
   pure wiring — the mechanism already exists.
2. **Rows 9 and 10 evaporate.** The loop reduces a refused transaction
   to the keyword `:error` in a turn report. The *rule* — the exact CAS
   fence, the exact schema violation — is thrown away one line after
   `store/transact!` worked hard to preserve it (`store.clj:438-441`).
   This is the same class as the live-drive defect the `:call` branch
   already fixed (`loop.cljc:314-318`: "Before this the error value
   evaporated — the drive sat claimed-with-no-plan for two minutes").
   It was fixed in one branch and left in four.
3. **Rows 11-12 name a channel nobody creates.** `:seon.cluster.wake/
   fault-channel` is a required key of the listen request but boot never
   supplies one.

### 2.2 Two concrete defects found while mapping the table

**D1 — `loop.cljc:292` violates `store/transact!`'s own schema.** The
`:open` branch calls

```clojure
(store/transact! connection {:tx-data (into (run/open-tx …) (run/claim-tx …))
                             :tx-meta {:seon.db/trigger [...]}})
```

while the declared contract is
`[:=> [:cat :seon.store/branch-connection [:vector :any]] …]`
(`store.clj:430-431`). Every other call site passes a vector (verified:
`loop.cljc:321,349,380,401,443` and `store_transact_test.clj`). The call
works because `d/transact` forwards an arg-map and Datahike's own
`STransactions` spec (`reference-code/datahike/src/datahike/spec.cljc:66-67`,
`s/coll-of (s/or :seq coll? …)`) accidentally admits a map — every map
entry is a `coll?`. **This is exactly what instrumentation exists to
catch, and it is on the live critical path.** Fix: widen the schema to
`[:or [:vector :any] :seon.store/transact-arg-map]` with a real closed
arg-map shape, or pass tx-meta another way. Owner: the
run-contract-hardening lane (it holds `loop.cljc`).

**D2 — the one value that escapes the admission boundary.** `evaluate`'s
catch builds

```clojure
:seon.error/data (cond-> {…} (ex-data throwable)
                   (assoc :seon.sci.eval/data (pr-str (ex-data throwable))))
```

(`eval.clj:276-281`) and then stores `(pr-str value)` as the durable
`:seon.cluster.eval/result-edn` (`eval.clj:362`). For a sci error the
ex-data contains `:sci.impl/callstack`, **a `volatile!` holding a list of
frame maps with live `sci.lang.Namespace` objects** (`utils.cljc:173-179`).
So the one path that was designed to be unbounded-`pr-str`-free
(`admit.clj:99-101`: "The old unbounded `pr-str` is not made safe; it is
made unreachable") has an unbounded, unreadable `pr-str` on its failure
arm. Fix in §3.2.

Both need issue notes; I own only this file, so they are recorded here
and named in the return report.

## 3. sci: what we could surface and do not

### 3.1 What sci attaches to an error

`utils.cljc:62-70` (`throw-error-with-location`) and `utils.cljc:167-181`
(`rethrow-with-location-of-node`) build:

```clojure
{:type :sci/error          ; a hierarchy root; :sci.error/parse derives from it (utils.cljc:16)
 :line …, :column …, :file …
 :message <rewritten ex-msg>
 :phase "parse" | "analysis"        ; parser.cljc:186-188 ; resolve.cljc:12, analyzer.cljc:53
 :sci.impl/callstack <volatile of frames>}
```

with the original as the `ex-cause`. The public readers are
`sci/stacktrace` (`core.cljc:402-405`) → a vector of clean frame maps
`{:ns :name :file :line :column :sci/built-in :macro}`
(`callstack.cljc:12-14, 33-54`) and `sci/format-stacktrace`
(`core.cljc:407-410`) → a vector of formatted strings.

The interrupt is deliberately different: an `ex-info` carrying
`:sci.impl/interrupt <private marker>` (`interrupt.cljc:32-42`), which
sci's own `try` refuses to hand to a user `catch` and sandboxed code
cannot forge. `seon.sci.eval/interrupted?` reads exactly that marker
(`eval.clj:177-185`) — correct, and it is the single owner
(`admit.clj:390` calls it through `requiring-resolve`).

### 3.2 What we throw away

`failure-value` (`eval.clj:269-281`) keeps only the class name, a
message, and the raw `pr-str` of ex-data. It drops:

- `:line`/`:column`/`:file` — **the agent is never told WHERE its form
  failed.** For a multi-form plan this is the single most actionable
  fact available and it costs three keys.
- `:phase` — "parse" vs "analysis" vs runtime tells the agent whether it
  wrote invalid syntax, referenced an unresolvable symbol, or hit a real
  runtime error. Three completely different repairs.
- the structured stacktrace — `(sci/stacktrace throwable)` is already
  ordinary data (namespace symbols, names, lines) and projects cleanly.
- the cause chain's deepest ex-data, which for a capability failure will
  carry our own `:seon.error/kind`.

**Recommendation (S1).** Replace the `pr-str` of ex-data with a derived,
bounded map:

```clojure
:seon.error/data
{:seon.sci.eval/throwable  <class name>
 :seon.sci.eval/phase      (:phase d)            ; when present
 :seon.sci.eval/line       (:line d)
 :seon.sci.eval/column     (:column d)
 :seon.sci.eval/file       (:file d)
 :seon.sci.eval/stack      (sci/stacktrace throwable)   ; ordinary data
 :seon.sci.eval/cause-kind (:seon.error/kind (store/refusal throwable))
 :seon.sci.admit/record    record}
```

and run that map through `admit/admit` with the same caps and a
`(constantly nil)` interrupt-fn before it becomes `result-edn`.
Admission is *pure given the value and the caps* (`admit.clj:128-129`),
so calling it outside an armed eval is sound, and it is the one total
codec — reusing it here deletes the possibility of a second bounded
printer, which is precisely what the quarry grew (`error.cljc:519`
`datom-projection` plus `::data-edn` plus `::stack` truncation, three
separate bounding rules).

`store/refusal` (`store.clj:398-412`) already walks a cause chain for
the deepest non-empty ex-data and is pure and unit-testable. It should
move to a neutral owner (it is not about stores) and be reused by the
fault codec — see §6.1.

## 4. malli.instrument, measured

### 4.1 The API, from the source

- `mi/collect!` is a **macro** (`malli/instrument.clj:136-150`) that
  expands to `clj-collect!`; `mi/clj-collect!` (`:52-55`) is the plain
  function to call at runtime with `{:ns (all-ns)}`. It reads
  `ns-publics`, takes `:malli/schema` from var meta (or composes one
  from per-arglist metadata, `:43-46`) and calls
  `m/-register-function-schema!`.
- `mi/instrument!` → `-strument!` (`:18-41`) walks the registered data,
  finds each var, and `alter-var-root`s it to
  `(-> (m/-instrument dgen f) (with-meta {::original f}))`. It refuses
  primitive-hinted fns with a `println` warning (`:24-26`) — a real
  gotcha: those are skipped silently-ish and stay unprotected.
- `m/-instrument` (`core.cljc:3110-3131`, wrapper at `2205-2221`)
  defaults `:scope #{:input :output :guard}` and `:report` to
  `m/-fail!`.

**The measured semantics of `:report` matter more than the docs.** The
wrapper is:

```clojure
(when wrap-input  (when-not (validate-input args) (report ::invalid-input {…})))
(let [value (apply f args)]
  (when (and wrap-output (not (validate-output value))) (report ::invalid-output {…}))
  value)
```

`report` is called **for effect and execution continues**. A
non-throwing `:report` therefore does not prevent the bad call — it
observes it and the function runs anyway. Probed:

```
call still ran and threw: class java.lang.String cannot be cast to class java.lang.Number
reported before re-eval: [:malli.core/invalid-input user/add2]
```

So `:report` mode is "tell me, then let it break naturally", never
"degrade gracefully". This is decisive for the dev/prod dial (§6.4).

### 4.2 Cost, measured

Form (run `clojure -M:dev -i <file>`; 200k warm-up, 2M/500k timed calls,
`:report` a no-op so nothing throws):

```clojure
(defn ^{:malli/schema [:=> [:cat :int :int] :int]} add2 [a b] (+ a b))
(mi/collect! {:ns 'user})
(mi/instrument! {:report (fn [_ _] nil)})
```

| Case | raw | instrumented | delta |
|---|---|---|---|
| `(add2 1 2)`, `[:=> [:cat :int :int] :int]` | 1.31 ns | 128.99 ns | **+128 ns** |
| one closed 4-key map arg with a 32-element `[:vector :int]` | 4.96 ns | 179.66 ns | **+175 ns** |

Read it as a flat **~130-180 ns per instrumented call**, dominated by
`(vec args)` + `apply` + the validator walk, not as a multiplier. On a
per-turn or per-transaction boundary that is free. On a per-node walk it
is fatal: `admit/project` visits up to `max-nodes` nodes per value, so
instrumenting it would add ~130ns × nodes per eval.

**The good news is that the exclusion is already structural.** Every hot
inner function in the tree is `defn-` with no `:malli/schema`:
`admit.clj`'s `project`/`project-node`/`project-map`/`take-node!`,
`eval.clj`'s `arm`/`diagnosis`/`failure-value`. The rule "instrument the
public vars that carry a `:malli/schema`" therefore excludes every hot
path **by construction**, with no exception list — which satisfies the
no-hand-maintained-lists standing rule without any extra machinery.

### 4.3 The hot-reload reapplication problem, measured

Probe:

```clojure
(mi/collect! {:ns 'user}) (mi/instrument! {:report (fn [k d] (reset! seen [k (:fn-name d)]))})
(add2 "no" "nope")                                   ; => reported
(eval '(defn ^{:malli/schema [...]} add2 [a b] (+ a b)))   ; re-evaluate, schema UNCHANGED
(add2 "no" "nope")                                   ; => ???
```

Result:

```
reported before re-eval: [:malli.core/invalid-input user/add2]
after re-eval threw: class java.lang.String cannot be cast to ...
reported after re-eval: nil
schema still registered: true
var carries ::original after re-eval: false
watch fired on plain re-eval? no
watch fired on re-collect with identical schema? YES
```

So, precisely:

1. **Re-evaluating a `defn` silently removes its instrumentation.**
   `alter-var-root` is undone by the new `def`; the `::original` meta is
   gone; no warning anywhere. The var looks fine and is unprotected.
2. **The schema stays registered.** `m/-function-schemas*` is untouched
   by a re-`def`, so the registry drifts out of sync with reality.
3. **`malli.dev`'s watch does not help by itself.** `malli.dev/start!`
   adds a watch on `m/-function-schemas*` that re-instruments on change
   (`malli/dev.clj:52-62`). A plain `defn` re-eval never touches that
   atom, so the watch never fires. It *does* fire on any `collect!` —
   `-register-function-schema!` swaps unconditionally, so re-collecting
   an identical schema still triggers it.

**Conclusion: the reapply trigger must be a `collect!`, not a watch.**
The watch is still worth adding — it makes a *changed* schema
self-applying — but the reliable path is one idempotent function called
after any load.

`malli.dev/start!` itself should be **rejected** as the entry point: it
`alter-var-root`s `m/-fail!` globally (`dev.clj:13-23`), installs a
pretty printer as the reporter, and writes clj-kondo config
(`clj-kondo/emit!`). We want the report to become a durable fault, not a
coloured box on stderr.

## 5. The quarry: what State A did, and what must not be repeated

`src-old/seon/error.cljc` (773 lines) is genuinely the right *shape* in
three places and wrong in four.

**Worth keeping (as ideas, re-derived, never ported):**

- **The iron rule stated as a function.** `record!` — "nothing is caught
  without becoming data" (`error.cljc:716-731`) — converts, classifies,
  persists fire-and-forget, escalates on the dial, and **never throws**
  (`:768-773` is a last-resort catch inside `record!` itself).
- **Two populations, one dial.** `:agent` never escalates in any mode;
  `:core` obeys `:seon.config/on-core-error` (`error.cljc:54-58`,
  `633-679`). That survives verbatim into the fresh design.
- **Stack frames as component entities**, so "every `:core` fault whose
  top frame is in render/sci" is a Datalog query (`error.cljc:87-98`).
  Worth keeping in the fresh schema; sci's `stacktrace` already yields
  exactly the right rows.
- **The recursion fence.** If the error being recorded is the persister
  violating its own contract, do not persist it — console only
  (`error.cljc:738-745, 759-764`). A real, measured failure mode: the
  fault path must not be able to fault into itself forever.
- **One error → one datom**, via a `WeakHashMap`/JS-property tag on the
  raw throwable (`error.cljc:685-708`), because one rejection propagates
  through several nested wrappers.

**What was brittle, and must not come back:**

- **Hand-maintained classification lists.** `agent-fault-kinds` is a
  literal `#{:user-input :compile :read :seon.eval/repl-parity
  :seon.eval/repl-form}` (`error.cljc:237-249`) and
  `ei/caller-fault-kinds` is another (`error/instrument.cljc:98-106`).
  Two hand lists deciding blame. The fresh rule must be structural
  (§6.2). Note also `agent-authored-sym?` (`error.cljc:211-226`) *was*
  the computed alternative — provenance-derived, fail-closed — and it is
  the right ancestor for a computed rule; it is the enumerated sets
  beside it that rotted.
- **Ambient dynamic scopes as the classifier.** `in-dev-eval?`,
  `expecting-a-core-fault?`, `with-configuration`, `run-in-scope`
  (`error.cljc:418-448, 557-631`) put blame in thread/async-local state.
  The docstring at `error.cljc:598-613` is the confession: Bun does not
  carry AsyncLocalStorage into `unhandledRejection`, so *the same typo's
  fault was path-dependent* — live datoms 3689/3700/3767/3778/3857
  classified `:core` and 3711-3755 classified `:agent`. **Classification
  must never depend on ambient scope.** In the fresh design it depends
  on which channel the thing arrived on, which is a property of the
  code's shape, not of the call stack.
- **Escalation as `System/exit`.** `escalate!` prints a grep-able
  `SEON-CORE-FAULT` marker and, under `:crash`, exits the process
  (`error.cljc:648-679`). The marker string became a test-gate
  dependency, which then needed a second marker
  (`SEON-EXPECTED-CORE-FAULT`) and a test bracket to suppress it
  (`error.cljc:563-577`). A string in stderr is not a fact; the fresh
  design escalates by **committing a fact and messaging an agent**, and
  the dev dial's job is only to make the process loud/dead, never to be
  the delivery mechanism.
- **The whole instrumentation apparatus was CLJS self-host machinery.**
  `src-old/seon/instrument.cljc` (1154 lines) is `#?(:cljs …)`
  end-to-end: `find-js-var`/`set-js-var!` via `goog.object`,
  `expose-namespace-to-malli!` because "Malli 0.20.0 resolves
  instrumented vars only through `goog.global`"
  (`instrument.cljc:66-78`), async-shape detection because "wrappers
  erase asyncness" (`:87-112`), and a delta-vs-cold-projection protocol
  so Seon never used malli's own registry (`:1-13`). **On the JVM none
  of that exists.** `alter-var-root` is the whole mechanism.
  The fresh instrumentation owner should be tens of lines, not
  a thousand. This is the clearest case in the whole document of "do not
  port; design fresh from the target".

One quarry piece is worth *re-deriving*, not porting:
`seon.error.instrument/report-fn` turned a malli report into a
structured envelope with `:path`, `:leaf-type`, `:expected`, `:got-edn`,
`me/humanize` output and a "did you mean" hint
(`error/instrument.cljc:25-42`). That content is what makes an
instrumentation failure actionable rather than a wall of schema. The
fresh version should derive the same fields from the report data
(`{:input :args :schema}` / `{:output :value :args :schema}`) using
`malli.error/humanize`, and then run the whole thing through
`admit/admit` — because a report's `:args` can contain anything,
including a live connection.

## 6. Recommended wiring

### 6.1 (a) Who consumes the error channel, and how a fault becomes facts

**The consumer already exists and is unwired.** `start-error-fanout!`
(`flow.clj:514-565`) mults the report and error channels, taps errors
into a `CountedDroppingBuffer` whose overflow calls `commit-drop!`
(`flow.clj:422-450`), feeds that into a dedicated `:io`
`fault-committer-proc` in its **own** flow graph (`flow.clj:452-488`,
`538-549`), and hands Flow Monitor independent sliding taps so
observability never competes with commitment. That is the right design
and it is already tested (`flow_test.clj:530-560`).

*Options for the consumer:*

| Option | Shape | Trade |
|---|---|---|
| A | A bare `go-loop` in boot reading `error-chan` | Fewest moving parts; but it is a second scheduling mechanism outside flow, invisible to `ping`, and it *is* the thing `seon.flow` was built to avoid. |
| B | **Wire `start-error-fanout!` from `cluster/start!` with real `commit-fault!`/`commit-drop!`/`panic!`** | One mechanism, already written and tested, observable through `flow/ping-proc`, isolated in its own graph so a slow commit cannot stall the faulting graph. Costs one more graph per cluster. |
| C | Make the fault committer a proc inside the cluster's main graph | One graph; but then a fault in the fault committer is reported to the same error channel it is draining — a self-feeding loop. |

**Recommend B.** Explicitly reject C for the self-feeding reason.

**Who watches the watcher.** The fault graph has its own error channel
that nobody reads. Two rules make that safe rather than lucky:

1. `commit-fault!` must be **total** — it goes through
   `store/transact!`, which never throws (`store.clj:414-416`), and
   branches on the returned value. It must not itself call
   `panic-on-core-error?` → `d/q`, or a dead connection turns the fault
   path into a throw.
2. The fault graph's `:error-chan` gets one last-resort drain to stderr
   *plus* an in-memory counter exposed through the instance. This is the
   quarry's recursion fence (`error.cljc:738-745`) restated: the fault
   path may not fault into itself, and its failure must still be
   visible.

**ONE TRANSACTION.** The commit is a single `store/transact!` carrying
both the fault entity and the explanation message(s):

```clojure
(into (fault-tx fault-entity)
      (mapcat message-tx recipients))
```

This is the same discipline `terminal-tx` already enforces
(`loop.cljc:128-165`, "one transaction, no torn window").

**And the delivery mechanism is already there.** `:seon.cluster.message/to`
is *the* wake attribute (`wake.cljc:87`, `message.edn:9-13`). Committing
an explanation message addressed to an agent **wakes that agent's loop
by construction** — no notification queue, no acknowledgement flag, no
second channel. Error delivery is the existing trigger mechanism used
for its actual purpose. This is the load-bearing insight of the whole
design.

**The storm fence, computed.** fault → message → wake → turn → fault is
a real cycle. The computed rule: a fault whose triggering run was itself
opened by a fault-explanation message does not produce another
explanation message to the same agent; it escalates instead. This is
derivable with no new flags — the run's opening transaction carries
`:seon.db/trigger` as tx-meta (`loop.cljc:304-306`), and a fault message
is distinguishable by carrying `:seon.cluster.message/about` (a ref to a
fault entity) where a user message has none. Absence of an attribute is
the state; nothing is stamped.

**Proposed fault entity** — leaf attributes flat, optionality expressed
only inside the entity map, in the shape `src/seon/schema/*.edn` already
uses (`run.edn`, `message.edn`):

```clojure
{:seon.fault/id        [:string {:min 1 :seon.db/identity true}]
 :seon.fault/at        :inst
 :seon.fault/process   :string     ; (cluster, pid, start-instant), derived
 :seon.fault/proc      :keyword    ; ::flow/pid
 :seon.fault/op        :keyword    ; ::flow/op — absent for loop/xform errors
 :seon.fault/cid       :keyword
 :seon.fault/kind      :keyword    ; deepest ex-data :seon.error/kind
 :seon.fault/class     :string
 :seon.fault/message   [:string {:min 1}]
 :seon.fault/signature [:re "^[0-9a-f]{64}$"]
 :seon.fault/data-edn  :string     ; admit/admit of the flow error map, printed
 :seon.fault/basis-t   [:int {:min 0}]
 :seon.fault/run       :seon.db/ref
 :seon.fault/agent     :seon.db/ref
 :seon.fault/frames    [:set {:seon.db/component true} :seon.db/ref]

 :seon.fault/fault
 [:map {:seon.db/entity true}
  [:seon.fault/id :seon.fault/id]
  [:seon.fault/at :seon.fault/at]
  [:seon.fault/process :seon.fault/process]
  [:seon.fault/proc :seon.fault/proc]
  [:seon.fault/class :seon.fault/class]
  [:seon.fault/message :seon.fault/message]
  [:seon.fault/signature :seon.fault/signature]
  [:seon.fault/data-edn :seon.fault/data-edn]
  ;; absent for the loop-level and xform error shapes of §1.2
  [:seon.fault/op {:optional true} :seon.fault/op]
  [:seon.fault/cid {:optional true} :seon.fault/cid]
  ;; absent when the cause chain carries no :seon.error/kind
  [:seon.fault/kind {:optional true} :seon.fault/kind]
  ;; absent when no database value was pinned at the fault
  [:seon.fault/basis-t {:optional true} :seon.fault/basis-t]
  ;; absent when the fault has no attributable run/agent (§6.2 rule 1)
  [:seon.fault/run {:optional true} :seon.fault/run]
  [:seon.fault/agent {:optional true} :seon.fault/agent]
  [:seon.fault/frames {:optional true} :seon.fault/frames]]}
```

The entity map is not decoration: `canonical-database-attributes`
installs entity-map entries by construction, and a family declared only
as standalone leaves installs nothing but its identity attribute — the
fixture-vs-live-boot class that cost the N3 live drive two rounds
(`message.edn:15-22`).

No `acknowledged?`, no `escalated?`, no `seen-at`. Visibility is
derived; a stored acknowledgement flag is the quarry pattern the
standing rules forbid.

**One codec, not a new one.** `:seon.fault/data-edn` is produced by
running the *entire* `::flow/error` map — including `::flow/state` and
`::flow/msg` — through `admit/admit` with the config caps and a
`(constantly nil)` interrupt-fn, then `pr-str`. That is the only way the
live connection inside `::flow/state` becomes a safe
`{::admit/reference "…"}` marker instead of a stack overflow
(`admit.clj:82-92` documents that exact crash). Cause-chain digging uses
`store/refusal` (`store.clj:398-412`), which should be relocated to the
fault owner — it is about throwables, not stores.

**Who is the triggering agent?** The flow error map does *not* carry it:
the loop's state is `{cluster, turns}` (`loop.cljc:226-229`).

- Option A: put the current run/agent into the loop state before each
  turn, so it rides in `::flow/state` for free.
- Option B: **derive it** — the run claimed by this process and not
  closed, at the fault's basis.

**Recommend B.** It is exact today *because turns are serial within a
cluster* (`loop.cljc:36-40`), so there is at most one claimed-open run
per process. Record the dependency explicitly: **the day turns go
concurrent, B stops being exact and A becomes required.** That is a
contract note for the concurrency extension point the loop already
names.

### 6.2 (b) The classification rule, computed

**The primary rule needs no predicate at all: the channel IS the
classification.**

- `seon.sci.eval/evaluate` **never throws** (`eval.clj:41-45, 283-306`).
  An agent's mistake is therefore, by construction, a *value* — it
  cannot reach `::flow/error`. `flow_test.clj:474-477` already asserts
  this: *"an agent error value is not a Flow core fault"*.
- Everything that arrives on `::flow/error` is a Throwable that escaped
  our own code. That is a core fault, definitionally.

So: **value → receipt, no message. Throwable on `::flow/error` → fault
fact + message.** No name list, no kind set, no ambient scope, no
authorship inference. This is strictly better than the quarry's
`agent-fault-kinds` (a literal set, `error.cljc:237-249`) *and* better
than `agent-authored-sym?` (correct in spirit but requiring a corpus
lookup, `error.cljc:211-226`), because it requires no lookup at all.

**The second-order case** is a returned `:seon.error/value` that is
nevertheless a system failure — `:seon.db/unknown-failure`,
`:seon.error/kind :configuration`, `::no-credential`. These are values,
so the primary rule sends them to a receipt, which under-reports.
Options:

| Option | Rule | Trade |
|---|---|---|
| A | A predicate over `:seon.error/kind` | A hand list by another name. Rejected. |
| B | **Call-site topology**: the loop already knows which door each value came from. Values from `evaluate` are agent outcomes; values from `store/transact!`, `ai/complete`, `reply/sources`, `config/apply!` are system outcomes and take the fault path. | Computed from the code's shape, reviewable in one screen, no list to maintain. The loop is the only place both kinds meet. |
| C | Make the system-side doors throw instead of returning values, so rule 1 catches them | Violates "nothing throws into the run loop" (`store.clj:415`). Rejected. |

**Recommend B**, and note that it is what rows 8-10 of the §2 table are
already reaching for — the `:call` branch does it correctly
(`loop.cljc:312-333`) and the other four branches drop it. Closing holes
9/10 IS implementing B.

**The escalation split** (message the triggering agent vs. also message
an escalation recipient). Recommend two computed conditions, either
sufficient:

1. **No attributable agent.** If no claimed-open run exists at the
   fault's basis, there is nobody to tell — the fault belongs to the
   cluster and goes to the escalation recipient. This covers boot faults,
   wake-listener faults, fault-committer faults.
2. **Recurrence.** A *derived* count, never a stored counter:
   `(count faults with the same signature since this process's
   start-instant)` exceeding the config dial. The signature must itself
   be computed — recommend
   `(sha-256 [proc-pid, exception class, :seon.fault/kind, top frame])`,
   stored as `:seon.fault/signature` because it is an immutable property
   of the fault (a *derivation of the fault's own content*, committed
   with it, not a mutable tally).

**The escalation recipient does not exist yet.** There is no root-agent
concept in the fresh tree. This is a required contract decision before
implementation: either (i) a config fact
`:seon.config.fault/escalate-to` naming an agent id, or (ii) a derived
"the cluster's oldest agent". Recommend (i) — explicit, reconciled from
the manifest like every other dial, absent = no escalation message (and
absence is the state).

### 6.3 (c) Message content derivation

The message content must answer four questions: **what happened, why,
what it means for you, and why you are being told.**

*Is the content stored or derived?* The message schema requires
`:seon.cluster.message/content [:string {:min 1}]`
(`message.edn:6`). Two options:

| Option | Shape | Trade |
|---|---|---|
| A | Store the derived sentence in `content`; also carry `:seon.cluster.message/about` → the fault ref | One stored string. Defensible: **a message is a historical fact about what an agent was told, not a projection of current state** — it must not silently change when the fault's context changes. The ref keeps the full evidence one hop away. |
| B | Make `content` optional and derive it from the `about` ref at prompt time | Purest derive-don't-store; but it changes the message contract for every producer, and it makes "what did we tell the agent last Tuesday" unanswerable. |

**Recommend A**, with the explicit justification above so it is not
mistaken for a stored-derived slip. One pure function
`seon.fault/sentence` produces the string; the prompt's problems block
(§7) calls the *same* function over the fault entity, so there is one
derivation with two consumers, never two renderers.

**Content skeleton** (all fields present or the clause is omitted —
never a stored nil, never "unknown"):

```
A core fault stopped work in <proc>: <message>.
It happened while <op-clause>, during run <run-id>, form <ordinal>.
You are being told because this fault interrupted your own run.
Nothing was retried and nothing re-executed.
Evidence: fault <fault-id>, basis-t <t>, <class> at <file>:<line>.
```

For the escalation recipient the third line becomes *"You are being told
because you are this cluster's escalation owner and this fault could not
be attributed to a single agent"* or *"…because this fault has now
occurred N times since this process started"*. **Why-you-are-contacted
is a derived clause of the escalation reason, not boilerplate** — that
was the owner's explicit requirement and it is the one sentence an agent
will act on.

Say **"may have happened"** wherever it may have. `prompt.cljc:130-134`
already sets this standard for interruptions ("rows 6 and 7 of the crash
walk are indistinguishable… claiming otherwise would be a lie the agent
then reasons from"). A fault mid-transform has the identical ambiguity.

Sizes shown to anyone are estimated tokens, never characters — the
standing rule. `seon.ai.tokens/estimate` is a `src-old` owner today;
whichever fresh owner replaces it, the fault sentence must not print a
character count.

### 6.4 (d) Instrumentation at boot, with reload-reapply

**One new owner, `seon.instrument`, and it is small.** Four functions:

```clojure
(defn instrumentable []   ; the COMPUTED selection: nothing hand-listed
  ;; every loaded public var carrying :malli/schema. Hot inner functions
  ;; are excluded BY CONSTRUCTION because they are defn- without schemas.
  )
(defn report! [mode commit-fault!] …)  ; the reporter, per the dial
(defn apply! [{:keys [mode commit-fault!]}] …)  ; collect! + instrument!, idempotent
(defn remove! [] …)                    ; mi/unstrument!, the emergency switch
```

`apply!` is `(mi/clj-collect! {:ns (all-ns)})` followed by
`(mi/instrument! {:report (report! …)})` — note `clj-collect!` (the
function, `instrument.clj:52`) rather than `collect!` (the macro,
`:136`), because the ns set is a runtime value.

*Where it is called:*

1. **At boot**, at the end of `stack-tower!` — after `config/apply!`, so
   the dial is a fact before the reporter is built. It must be the last
   tower layer: instrumenting mid-boot would validate half-built values.
2. **On reload**, explicitly. Measured in §4.3: a `defn` re-eval
   silently strips the wrapper and no watch fires. Options:

| Option | Trade |
|---|---|
| A | `malli.dev/start!` | Rejected — globally alters `m/-fail!`, installs a printer as the reporter, writes clj-kondo config (`dev.clj:13-23, 40-66`). |
| B | The schema watch alone (`dev.clj:52-62`'s technique) | Free and worth adding, but measured: it does **not** fire on a same-schema re-eval, which is the common case. Insufficient alone. |
| C | **`apply!` called explicitly after any load** — from the prepl by a human, from `bin/seon-hook` after an edit, and from the graph-rebuild path — **plus** the watch from B for changed schemas | Covers both cases; the residual (someone re-evals a defn in the REPL and forgets) is honest, cheap to fix (`(apply!)`), and idempotent. |

**Recommend C.** And publish the count: `apply!` returns
`{:instrumented n :registered m}`, so "is instrumentation on right now"
is an answerable question rather than an assumption. A boot that
instruments zero vars is a bug and should be loud.

*Mapping onto `:seon.config/on-core-error`:*

| Dial | `:scope` | `:report` | Rationale |
|---|---|---|---|
| `:panic` (dev) | `#{:input :output}` | `m/-fail!` (throw) after committing the fault fact | A contract violation is a bug in OUR code; find it at the first call. The throw lands wherever it lands: inside a proc transform it becomes a `::flow/error` fault (§1.3) and the proc survives with pre-step state — so even dev-panic does not wedge the loop. |
| `:record` (prod) | `#{:input :output}` | commit the fault fact, return nil | Observed, durable, and the call continues. |

**The measured caveat must be written into the contract**: in `:record`
mode the invalid call **still executes** (§4.1) and will usually throw a
`ClassCastException` a moment later. That is acceptable — the fault fact
naming the *contract* violation is committed first, so the derived
exception is diagnosed rather than mysterious — but it must not be
described as graceful degradation. `:report` cannot substitute a value;
malli offers no such hook.

Drop `:guard` from `:scope`: no schema in the tree declares one, and
including it costs a `validate-guard` call per invocation for nothing.

*The reporter's content* re-derives the quarry's envelope
(`error/instrument.cljc:25-42`) from the report data — `:fn-name` (malli
adds it, `instrument.clj:30`), the `me/humanize` output, the failing
path, the expected schema form, and a bounded projection of `:args` —
then runs the whole map through `admit/admit`. `:args` can hold a live
connection; nothing else in the fresh tree may print it.

*The kill switch* is `remove!`, and it is emergency recovery only — not
a config dial, and never the answer to a noisy report. A noisy report
means the schema or the caller is wrong; fix that (D1 is the first
example waiting).

### 6.5 (e) What the crash drill and the live drives must assert

Every claim below must be owned by a recurring surface — a live proof
that ran once in a lane counts as NOT COVERED.

**Boot wiring (a standing test, not a drill).**

1. A started instance carries a non-nil error fan-out and a running
   fault-committer proc (`flow/ping-proc` returns `::flow/status
   :running`).
2. `(seon.instrument/apply! …)` ran and reported `:instrumented > 0`.
3. The wake listener's `fault-channel` is the fan-out's channel — not
   a channel the test invented.

**The visibility property (the class-killer).** For an injected
Throwable in the loop's transform, within one wake:

1. exactly one `:seon.fault` entity exists, carrying pid, class,
   message, and a `data-edn` that **reads back through
   `clojure.edn/read-string`** (this is what proves the codec ran and
   `::flow/state` did not escape);
2. exactly one message exists addressed to the triggering agent, whose
   content names the fault id and states why that agent was told;
3. the loop proc is still `::flow/status :running` and its
   `::flow/count` is unchanged — flow's continue-with-pre-step-state
   (`impl.clj:312-316`) held;
4. the next injected wake produces a normal turn.

**The negative property, equally required.** An agent eval error asserts
the OPPOSITE: receipt `:seon.cluster.eval/status :error`,
`(async/poll! error-chan)` nil, **and zero messages committed**. If an
agent's own mistake ever mails itself a fault, the classification rule
has broken. (`flow_test.clj:474-477` already covers the first half.)

**The drop property.** With the fault committer paused, N+capacity+1
faults produce exactly `capacity` commits and the rest counted by
`commit-drop!` — dropping is admitted, never silent. This is the
regression that would have caught "nobody reads the error channel".

**The self-fault property.** A `commit-fault!` that cannot write (a
released connection) does not throw, does not spin, and leaves a visible
stderr record plus a counter. The quarry's recursion fence
(`error.cljc:738-745`), re-proved.

**The kill drill (phase 2, in prep at `tmp/n3-crash-{child,verify}.clj`).**
Kill -9 mid-fold, then on the next boot assert: `recover-tx` marked the
dangling receipt `:interrupted` (`run.cljc:574-607`), the agent's next
prompt contains the one warning (`prompt.cljc:122-147`), and **no fault
entity was created** — a clean kill is not a fault, and if the crash
drill starts producing faults the classification rule has leaked.

**Instrumentation coverage.** One negative test that a deliberately
wrong call to an instrumented boundary produces a fault fact in
`:record` mode and throws in `:panic` mode. D1 is the ready-made
fixture: `store/transact!` with a map argument.

## 7. Interactions with in-flight work

- **run-contract-hardening lane (lease / `::now` in `run.cljc` +
  `loop.cljc`).** That lane owns both files. The fault wiring must
  therefore land in `cluster.clj` (boot composition) plus two new owners
  (`seon.fault`, `seon.instrument`) and **must not** touch `loop.cljc`.
  Two loop-local changes are owed to that lane rather than raced:
  (i) **D1**, the arg-map/vector schema mismatch at `loop.cljc:292`;
  (ii) **holes 9/10** — the four branches that reduce a refused
  transaction to the keyword `:error`, which should carry the refusal's
  own map to the fault path the way the `:call` branch already carries
  a model failure (`loop.cljc:312-333`). Hand both to the lane as
  acceptance items; do not open a second editor on the file.
- **Gap 1, `recover-tx` at boot: already closed.** Re-grepped —
  `cluster.clj:407` calls `run/recover-tx` from `recover-runs!`, which
  `stack-tower!` merges into the instance at `:438-441`. Boot commits
  nothing when nothing needs it. This row should be discharged in the
  plan rather than implemented again.
- **Gap 2, interruption settling.** Interruption and faults must stay
  disjoint **by construction**, and they already are: an interruption
  produces no Throwable (the process is simply gone), so nothing reaches
  `::flow/error` and no message is created; the next prompt's warning is
  the whole presentation. Assert the disjointness (§6.5 kill drill)
  rather than coordinating the two paths.
- **Gap 3, prompt shadowing.** The fault message and the interrupted
  warning can both be true at once. Rule: **the warning derives from
  facts; the message is a fact.** The prompt shows the interrupted
  warning (derived, `prompt.cljc:122-147`) and the trigger content
  (which for a fault message *is* the explanation). They do not shadow —
  but the fault message must never restate the interruption, or the
  agent reads the same event twice and infers two events.
- **The "problems" derivation.** Nothing named `problems` exists in the
  tree yet (grepped `src/` and `docs/`); this is the orchestrator's
  proposal, and it fits exactly. Recommendation: **problems is the PULL
  side of the same facts the message PUSHES.** A derived prompt block
  over open faults referencing this agent, built by the same
  `seon.fault/sentence` function, present exactly while the facts are
  and gone when they are not — the shape `interrupted-sentence` already
  proves (`prompt.cljc:120-147`). No third store, no acknowledgement
  flag, no "seen" attribute. "Open" is itself derived: a fault whose run
  is closed and whose signature has not recurred since is not a current
  problem.

## 8. Defects and issue notes owed

Recorded here because this lane owns only this file; each needs a note
under `docs/seon/issues/`.

| # | Defect | Evidence | Owner |
|---|---|---|---|
| D1 | `loop.cljc:292` passes an arg-map where `store/transact!`'s schema says `[:vector :any]`; it works only because Datahike's `STransactions` spec accidentally admits a map | `store.clj:430-431`; `datahike/spec.cljc:66-67` | run-contract-hardening lane |
| D2 | `eval.clj:276-281` `pr-str`s raw sci ex-data (including the `:sci.impl/callstack` volatile) into the durable receipt, bypassing the total codec the same file's docstring says is unbypassable | `eval.clj:276-281, 362`; `admit.clj:99-101`; `utils.cljc:173-179` | `seon.sci.eval` owner |
| D3 | Four of five `loop/turn` branches discard a refused transaction's rule (`store/transact!` preserves it verbatim one line earlier) | `loop.cljc:307, 388, 430, 451` vs `store.clj:438-441` | run-contract-hardening lane |
| D4 | `seon.cluster/start!` never wires `start-error-fanout!`, never creates the loop graph, never supplies the wake listener's `fault-channel`; the flow error channel is a 100-deep sliding buffer nobody reads | `cluster.clj:449-531`; `impl.clj:99-102`; `wake.cljc:110-134` | boot composition |
| D5 | Stale docstrings: `loop.cljc:6-7` and `wake.cljc:5-6` both say "Nothing here is implemented: every body throws `awaits implementation`" while both are fully implemented. Docstrings render into agent context. | `loop.cljc:6`, `wake.cljc:5` | file owners |
| D6 | Vendored `reference-code/malli` is ahead of the pinned `metosin/malli 0.20.0` (CHANGELOG head is `UNRELEASED`); reading the vendor is reading a future version | `deps.edn:14`; `reference-code/malli/CHANGELOG.md:17-21` | dependency ledger |

## 9. Summary of the recommendation

1. Wire `start-error-fanout!` from `cluster/start!` with a total,
   `store/transact!`-based `commit-fault!`; keep the fault committer in
   its own graph; drain the fault graph's own error channel to stderr
   with a counter.
2. Classification is the channel, not a predicate: values → receipts,
   Throwables on `::flow/error` → faults. Within values, the call-site
   topology (which door returned it) decides — the loop is the only
   place both kinds meet.
3. Commit the fault entity and its explanation message(s) in ONE
   transaction; delivery reuses `:seon.cluster.message/to`, which is the
   wake attribute, so the recipient's loop wakes by construction.
4. Escalate on two derived conditions — no attributable agent, or a
   recurring signature — to a configured escalation agent; absence of
   the config fact means no escalation message.
5. Message content is a historical fact built by one pure
   `seon.fault/sentence`, whose "why you are being told" clause is
   derived from the escalation reason; the same function feeds the
   derived `problems` prompt block.
6. One total codec everywhere: `admit/admit` bounds the flow error map,
   the sci diagnostics, and the malli report `:args`. Delete the
   possibility of a second bounded printer before it is written.
7. `seon.instrument` is a small JVM-only owner: computed selection
   (public + `:malli/schema`, which excludes hot paths by construction),
   ~130-180 ns per instrumented call measured, `apply!` at the end of
   boot and on reload, a schema watch for changed schemas, and the
   `:panic`/`:record` dial choosing throw-vs-commit — with the measured
   caveat that `:report` never prevents the call.
