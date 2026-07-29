---
type: research
status: active
tags: [research, audit]
---

# Frozen-checkpoint adversarial audit, 2026-07-29

## Verdict

The wave is not ready to leave the checkpoint. The principal happy-path
claims are independently true, but a refused terminal program transaction
leaves its receipt running and drives the agent into a durable-error hot loop.
That is one blocker. Two operator/config seams and one stored-derived renderer
fallback are real but lower-ranked.

| Rank | Count |
|---|---:|
| Blocker | 1 |
| Friction | 2 |
| Cleanup | 1 |

Audit start was source checkpoint `9f2fba0ae`; the final inspected HEAD was
`232e7981d`. The intervening commits were documentation-only falsifier
results. No lane report was accepted as evidence, no concurrent scratch
artifact was read, and this audit changed no source or test file.

## Dependency ledger and scope

The source and dependency boundaries read directly were:

- Clojure `1.12.5`, Malli `0.20.0`, and core.async
  `1.10.874-alpha3` from `deps.edn`;
- Datahike checkout `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`,
  especially its writer transaction-function path;
- SCI checkout `8fac6e88f32d53a5fd82ebe80640881e317b84fd`,
  especially the forked-context and interrupt behavior;
- core.async reference checkout
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`;
- http-kit fork `238a85cc555a38892f2f9a7583c9cf5cec0fb201`,
  including `ServerAtta`, `HttpServer`, `AsyncChannel`, `WriteState`, and its
  Java tests; and
- first-party owners `seon.sci.reader`, `seon.sci.eval`,
  `seon.cluster.run`, `seon.cluster.loop`, `seon.fn`, `seon.config`,
  `seon.schema.edn`, `seon.render.value`, `seon.render.web`, and the fresh
  operator.

The diff sweep covered the named landing commits for CRLF spans, integer
coercion, config, compute submission, program rows and acquisition, schema
cycles, the value renderer, failed stop, the http-kit fork, and resource
relocation. The commits were inspected individually because unrelated
research and documentation commits interleave the wave.

## Ranked findings

### B1 — a refused terminal transaction leaves a running receipt and hot-loops

The ownership fence itself is correct. In a live scratch agent,

```clojure
(ns-unmap (quote seon.config) (quote defaults))
```

produced `:seon.cluster.run/program-delete-not-owned`. The function remained
in the database and the SCI context did not apply the deletion. There was no
partial program mutation across the refused transaction.

The receipt did not become terminal. The same agent immediately derived
ordinal zero again, refused `receipt-start-call` with `receipt-exists`,
committed another durable error, and self-woke. For roughly ten seconds, until
the audit disarmed it, the process emitted and committed repeated
`receipt-exists` failures.

The source explains the loop:

- `src/seon/cluster/run.cljc:543-588` refuses the program mutation inside the
  receipt-settle transaction, correctly preventing a partial commit;
- `src/seon/cluster/loop.cljc:913-937` installs a program row only after a
  successful transaction report, correctly preventing a context mutation;
- `src/seon/cluster/loop.cljc:265-298` records the refusal but does not
  terminalize the already-started receipt; and
- work derivation therefore selects the same running receipt again.

The namespace docstring at `src/seon/cluster/loop.cljc:18-23` says a rejected
terminal transaction is followed by a terminal error receipt. That is false
today. The issue is
[[refused-terminal-transaction-leaves-a-running-receipt-hot-loop]].

### F1 — the fresh operator calls start through stale instrumentation

A clean JVM zero-overlay start succeeded. Adding a cluster to the already-live
development JVM did not:

```bash
bin/seon start checkpoint-audit-stale-instrumentation
```

The live wrapper rejected `:seon.config/manifest` under the old
`:seon.boot/overrides` schema, although current `start!` source declares
`:seon.boot/start-request`.

`script/seon/fresh_operator.clj:335-345` calls `start!` before
`instrument-form` can reapply instrumentation from the new instance's config.
This is the start-side recurrence of the stale-wrapper class previously seen
on operator stop. The issue is
[[fresh-operator-start-enters-stale-instrumentation-before-refresh]].

### F2 — default Babashka still cannot load the mixed operator graph

`resources/` is now present in `bb.edn`, and the fresh JVM resource loader is
green. The broader Babashka claim is not:

```bash
bin/seon --help
```

exits 1 because fresh `src/seon/schema.cljc` requires `datahike.api`, which is
absent from `bb.edn`'s dependency closure. The stack then continues through
quarry `seon.content-hash`, `seon.config.resolve`, and the old operator.

This is additional evidence on the existing
[[babashka-default-classpath-exposes-src-old]] issue, not a new root cause.
The script-only fresh `start` and `config` branches avoid this particular
dependency graph; old-facing help/status do not.

### C1 — the universal value renderer caches file-derived effective config

`src/seon/render/value.cljc:44-45` stores `(config/defaults)` in a delay.
`presentation-options` at lines 74-99 and `prepare` later in the namespace
merge that cached effective map into every generic structural render.

The normal database-backed block path supplies caps, so the focused renderer
and web suites do not show a wrong hard maximum. The process-local fallback is
still a second effective-config projection and is live for incomplete generic
units. Runtime consumers are supposed to read the cluster's database value,
not `config/default.edn` once per process. The issue is
[[value-renderer-caches-file-derived-effective-config]].

## Code-graph falsification

### Same-transaction publication

One agent executed six forms under the real per-agent flow:

1. a contracted `defn`;
2. `(def x 42)`;
3. a plain expression;
4. a `seon.schema/register!`;
5. a `deftest`; and
6. a thrown exception.

Queries over transaction ids showed:

| Form | Receipt/program result |
|---|---|
| contracted function | receipt result and `:seon.fn` source both at `536870931` |
| ordinary `def` | receipt only; no program row |
| plain expression | receipt value `43`; no program row |
| schema registration | receipt result and `:seon.schema` form both at `536870937` |
| `deftest` | receipt result and `:seon.test` source both at `536870939` |
| failed eval | terminal error receipt; no program row |

This is the intended one transaction at `receipt-settle-call`
(`src/seon/cluster/run.cljc:590-647`), not two writes observed close together.

### Selective admission

A second agent defined an uncontracted function and called it in the same run.
The definition returned an admitted Var receipt, the next form returned `2`,
and no `:seon.fn` row existed. That agrees with the explicit contract gate at
`src/seon/sci/eval.clj:320-346`: only a function carrying a valid complete
Malli schema becomes a durable row. Schema registration and `deftest` each
did become rows, as the transaction evidence above shows.

### Restart acquisition

In a separate scratch database, agent `restart-a` committed:

```clojure
(defn ^{:malli/schema [:=> [:cat :int] :int]} persisted [x] (inc x))
```

The audit stopped the cluster and JVM, reopened the same database in a new
JVM, and queried the function row before creating a second agent. Agent
`restart-b` then evaluated:

```clojure
(my.agents.restart-a/persisted 41)
```

Its ordinal-zero receipt contained `"42"`. Acquisition used program facts,
not receipt replay, matching `src/seon/sci/eval.clj:450-474`.

## Config falsification

### Fresh JVM and resource classpath

A fresh JVM started a zero-overlay scratch cluster successfully with
`:seon.boot/ready? true`, effective compute queue depth `10`, and populated
schema facts. A direct resource load enumerated the 34
`resources/seon/schema/*.edn` documents and registered 501 keys. The loader's
file and jar branches are one classpath mechanism at
`src/seon/schema/edn.clj:126-170`.

### One registration

In a fresh process, after loading the resource population, the audit performed
one registration:

```clojure
(schema/register!
 :seon.config.checkpoint-audit/scratch-dial
 [:int {:seon.db/index true :seon.config/default 17}])
```

With no other declaration edit, the next scratch boot installed the Datahike
attribute, committed the canonical schema row, and placed value `17` in the
zero-overlay effective config. An explicit override to `8` also followed the
same compiler in the standing test suite.

### Explicit absence

`:seon.config/manifest` validated
`:seon.config.error/escalate-to :seon.config/absent`. Compilation removed the
optional entry from the effective map and row; the marker itself did not
become a stored value. Required-entry absence remains a named refusal in the
full suite.

## http-kit fork falsification

The Seon renderer/web checkpoint passed:

```text
61 tests, 222 assertions, 0 failures, 0 errors
```

The real stalled-consumer regression was then run alone with an outer
`write-state` sampler. It passed two assertions and observed:

```clojure
{:samples 24
 :max-pending 239188
 :bound 524288}
```

The client never read from its socket while 20 complete 256 KiB morphs were
produced. The socket remained registered, and pending bytes stayed below the
two-morph backstop. `src/seon/render/web.clj:492-518` waits on the fork's
per-channel drain-or-close completion before submitting another event.

The fork's targeted Java tests also passed:

```text
OK (2 tests)
```

The broader upstream JUnit suite was not counted green: it ran 24 tests and
failed one unrelated `HttpClientDecoderTest` fixture with a null resource.
That failure does not touch `WriteState`, but it prevents claiming the entire
upstream suite passed.

A repository search over `src/`, `test/`, and `script/` found one production
caller of `http/write-state`, the web writer above, and no
`getDeclaredField`, `setAccessible`, `Reflector`, `toWrites`, or
`pendingWrites` access. The old reflection path has no surviving first-party
caller.

## Known-failure-mode sweep

- **Second mechanisms:** no second program reader, receipt-derived acquisition
  path, schema-registration executor, socket-queue accessor, or config
  compiler was found in the wave. Reader events feed both indexing and eval;
  current program facts feed restart.
- **Hand lists:** the config application ledger is test evidence with an
  equality check against the derived registered-dial set, not a runtime
  classifier. `seon.fn/source-roots` is an explicit build input to the
  ancestor digest, not a provenance/trust rule. Neither was promoted to an
  issue without a failing behavior.
- **Stored-derived state:** the value renderer's delayed effective config is
  the one real occurrence introduced by the wave; C1 owns it.
- **Symptom patches:** the fresh operator's post-start instrumentation refresh
  fails at the exact ordering boundary it claims to cover; F1 owns it.
- **Lying documentation:** the terminal-refusal and recursion-fence claims in
  `seon.cluster.loop` contradict the live refusal; B1 owns both prose and
  behavior.
- **Other named slices:** focused/full tests cover CRLF source spans, JDK
  integer coercion, schema-cycle refusal, compute submission, and failed-stop
  addressability. The diff sweep found no independent defect in those slices.

## Calibration — what is genuinely solid

The living program graph is materially simpler than receipt replay: one reader
produces declaration events, selective admission happens before the terminal
transaction, the transaction function validates and commits the declaration
with the receipt, and restart reads only current facts. Happy-path atomicity,
negative admission, cross-restart acquisition, and the ownership fence all
held under independent live probes.

The config compiler also has a strong center. Registration-derived composites,
zero-overlay completeness, explicit absence, schema population before config
apply, and a no-op converged reconcile all passed. The defects are at launcher
classpath/instrumentation edges, not a second config compiler.

The http-kit fork is narrow and source-grounded. It adds one atomic snapshot
and one drain-or-close future without changing `send!` semantics. The real
stalled socket stayed bounded, the fork tests passed, and Seon has exactly one
production caller.

The universal renderer's structural bounding and AI/HTML twin projections
passed their focused suite. Its issue is the config fallback, not its walk
totality or output grammar.

Finally, the complete independent gate at final source HEAD passed:

```text
Ran 531 tests containing 2183 assertions.
0 failures, 0 errors.
```

That green gate is useful calibration, but B1 demonstrates why it is not the
graduation gate by itself: no recurring test currently exercises a refused
terminal program transaction through the live agent flow and waits for
quiescence.

## Seam re-audit

The second seam re-audit stopped before executing the refusal scenarios. It
does not graduate the checkpoint.

The required scratch cluster could not cross the fresh operator's pre-start
instrumentation boundary:

```text
bin/seon start seam-reaudit-20260729
✗ The cluster rejected the prepl operation.
:malli.core/register-function-schema
ns=seon.render.value name=sample
:malli.core/invalid-schema
schema=:seon.render/value
```

The failure occurs in `script/seon/fresh_operator.clj`'s generated add form.
Its `refresh-instrument-form` calls `seon.instrument/apply!` against the
already-running anchor before `seon.cluster/start!` is entered. Registering
`seon.render.value/sample` then fails because the anchor JVM's live schema
registry cannot resolve `:seon.render/value`. No
`data/clusters/seam-reaudit-20260729/prepl.edn` advertisement was published, so
there was no scratch-cluster REPL on which to run the current
`store/transact!` encoder, channel-quiescence observations, refusal variants,
or crash/recovery proof.

The source tree was initially clean. During this attempt another lane began
editing three archived issue files. Per the audit instruction, this lane did
not restart the shared JVM, bypass the operator with a separately launched
cluster process, resume or message another lane, or edit its files. The exact
gate boundary remains: a scratch cluster must start through the maintained
operator with the current schema population and instrumentation, after which
the full refusal matrix must be rerun. No seam verdict is claimed.

## Seam re-audit (attempt 3)

The third seam re-audit stopped at the same required precondition. The
checkpoint is **not graduated**.

The audit ran from source HEAD `f30526c447e9`, which includes the claimed boot
fix at `bf9d9425e`, and invoked:

```text
bin/seon start seam-reaudit3-20260729
```

The maintained operator sent the new pre-start form to the live anchor. The
returned form visibly contained the intended ordering:
`seon.schema.edn/load!` → `seon.instrument/apply!` →
`seon.cluster/start!`. Instrumentation nevertheless refused before `start!`:

```text
✗ The cluster rejected the prepl operation.
:malli.core/register-function-schema
ns=seon.render.value name=sample
:malli.core/invalid-schema
schema=:seon.render/value
```

No `data/clusters/seam-reaudit3-20260729/prepl.edn` advertisement was
published. Consequently there was no scratch-cluster REPL on which to
construct the current encoder refusal, run the first-form or mid-plan
variants, observe turns below or at the episode cap, inspect channel
quiescence, or perform the crash/recovery proof. None of those scenarios is
claimed.

This is itself the instrumentation finding named by the audit instruction:
the fresh-child proof and green suite do not establish that the maintained
`bin/seon start` path can add a scratch cluster to the live instrumented
anchor. Per the stop rule, this audit did not restart the shared JVM, bypass
the operator, touch another lane's source or tests, or continue beyond the
failed boundary. The archived
`fresh-operator-instrumentation-cannot-resolve-render-value-schema` resolution
is falsified on the required live-add path.

## Seam re-audit (attempt 5)

The fourth attempt left no notes; it did leave a healthy isolated operator
root (`tmp/seam-reaudit4-operator-root`, cluster `seam-reaudit4-20260729`,
prepl 57979, PID 76448). Attempt 5 reused it, executed the complete refusal
matrix and the crash/recovery proof, and never touched the shared default root
or the owner's JVM on port 7994.

**The checkpoint is NOT GRADUATED.** The refusal seam itself is genuinely
correct under every scenario the ruling names, but two defects stand in front
of the graduation claim: one blocker that stops any agent from executing a
form in a development cluster, and one that leaves the seam's central
"un-refusable" claim unproven by construction.

### The apparatus

Live REPL through the isolated cluster's own prepl. Wakes were observed on the
CHANNELS, not inferred from facts: `clojure.core.async.flow/ping` on each
agent's graph reports `:seon.cluster.agent/deliveries` on the mailbox proc and
`:seon.cluster.agent/passes` / `:seon.cluster.agent/turns` on the turn proc,
plus every in/out port's `put-count`/`take-count` and buffer occupancy. An
independent `d/listen` observer recorded every commit's datom attributes, from
which `wake/route!`'s own rule was replayed to count agent wakes
(`:seon.cluster.message/to`), armer wakes (`:seon.cluster.agent/id`) and render
wakes (one per report). `seon.ai/complete` was replaced by a scripted stub, so
no paid call was made; the fresh root has no `DEEPSEEK_API_KEY` at all.
Probe scripts are committed under `tmp/seam-reaudit5/`.

### Finding 1 (blocker, new) — armed instrumentation refuses every agent turn

The first live drive never reached a receipt. `seon.cluster.loop/turn`'s
`:resume` branch begins with `seon.sci.eval/acquire!`, which calls
`activate-program-schemas!` → `seon.schema/activate-projection!`, which passes
BOUND forms (core predicate symbols already replaced by function objects) to
`seon.schema.internal/assert-compilable-schema!` — whose declared input schema
is `:seon.schema/definition`, an EDN-readable Malli form. With
`:seon.config/on-core-error :panic` the armed reporter throws:

```clojure
(seon.sci.eval/acquire! {:seon.sci.eval/ctx (sci.core/fork (seon.sci.eval/base))
                         :seon.db/db @(conn)})
;; throws: seon.schema.internal/assert-compilable-schema! violated its
;; contract (invalid-input): [nil nil ["must be a parseable, EDN-readable
;; Malli form"]]   :seon.error/kind :seon.instrument/contract-violated
```

Driven through a real trigger this produced two runs opened, two plans frozen,
ZERO receipts, four `:seon.instrument/contract-violated` facts carrying
`:seon.error/proc :seon.cluster.agent/turn`, and both runs left OPEN, HELD and
PLANNED — a wedge no later pass can clear, because every later pass dies at the
same call. 357 vars are instrumented and
`#'seon.schema.internal/assert-compilable-schema!` is one of them.

This is not an artifact of a long-lived JVM: after the crash test below, a
fresh operator boot of the same root (new PID 77445) produced the same fact on
its first turn. The recurring gate passes because no test exercises the
acquisition path with instrumentation ARMED. Filed as
`docs/seon/issues/instrumented-assert-compilable-schema-refuses-every-agent-turn.md`.

Everything below was then run with the wrappers stripped
(`seon.instrument/remove!`), which isolates that defect and lets the seam
itself be judged.

### Scenario 1 — refusal on the FIRST form, episode below the cap

Agent `audit-c`, unowned program row `audit.victim1/target`, scripted reply
`(ns-unmap (quote audit.victim1) (quote target))`.

```clojure
{:calls 1
 :wakes {:agent-wakes 1 :armer-wakes 0 :render-wakes 7}
 :quiesce {:quiet-after-ms 6007
           :counts {:deliveries 4 :passes 4 :turns 3}}
 :state {:runs [{:closed? true :held? false :planned? true}]
         :agent-run nil
         :receipts [{:seon.cluster.eval/ordinal 0
                     :seon.error/kind :seon.cluster.run/refused
                     :seon.cluster.eval/error
                     "seon.cluster.run/receipt-settle-call was refused by
                      :seon.cluster.run/program-delete-not-owned."}]
         :errors [#:seon.error{:kind :seon.cluster.run/refused}]
         :more-work? false :next-work nil}
 :victim "(defn target [] :original)"}
```

EXACTLY one receipt, terminal, carrying the admitted flat error as both
`result-edn` and `error`; EXACTLY one durable error fact; the run closed,
unheld, and the agent pointer retracted; the program row byte-identical; ONE
model call. The only agent wake in the window is the trigger commit itself —
the refusal event produced zero agent wakes, the mailbox took no further
delivery, and both work predicates went quiet.

### Scenario 2 — refusal MID-PLAN

Same shape, three forms: `(def midplan-marker 1)`, the poison `ns-unmap`, and
`(def never-ran 2)`.

```clojure
:forms   ([0 "(def midplan-marker 1)"]
          [1 "(ns-unmap (quote audit.victim2) (quote target))"]
          [2 "(def never-ran 2)"])
:receipts [{:ordinal 0 :result-edn "{:seon.sci.admit/reference \"sci.lang.Var\" …}"}
           {:ordinal 1 :seon.error/kind :seon.cluster.run/refused …}]
:errors  [#:seon.error{:kind :seon.cluster.run/refused}]
:runs    [{:closed? true :held? false}]   :more-work? false
```

Form 0 settled ordinarily, form 1 terminalized as the refusal, form 2 NEVER
started — no receipt for ordinal 2 exists. The fold stops at the refusal and
the run closes in the same event.

### Scenario 3 — below the cap, the next turn reads the refusal

A peer follow-up to `audit-c` opened one new run. The rendered prompt carried
the fact, not a retry:

```text
(:seon.error/agent) The receipt-settle of b16aab90-… was refused atomically by
:seon.cluster.run/program-delete-not-owned. Nothing from this receipt-settle
committed. Re-read the run before deciding whether a new transition is
eligible. Evidence: error 5e4a5443-…, kind :seon.cluster.run/refused,
signature 4d949eaf….
```

The second run completed; the agent's error-fact count stayed at ONE — later
work never re-records the original event.

### Scenario 4 — AT the episode cap

`:seon.config.run/max-episode-runs` set to 1, fresh agent `audit-e`, same
poison, then a peer follow-up.

```clojure
{:cap 1 :episode-runs 1
 :calls-after-first 1 :calls-after-follow-up 1
 :deferred ["C4-follow-up"]
 :state {:runs [{:closed? true :held? false}]
         :receipts [one terminal refusal receipt]
         :errors [one] :more-work? false :next-work nil}}
```

The episode simply ends. The follow-up is a deferred trigger, not a run; the
cap is the only retry budget; nothing dangles.

### Scenario 5 — the "un-refusable claim", falsified

`terminal-refused!`'s minimal commit was attacked directly against REAL running
receipts. Four hostile outcomes settled correctly — a 200 KB message with a
20 000-element data payload, an empty message, an absent message, and an
ordinary transition refusal each produced a terminal receipt, a closed unheld
run, and one durable error fact. That is real robustness and worth saying.

The fifth falsified the claim. Replaying the function's exact construction
while KEEPING the transaction result:

```clojure
(probe2! "audit-g" "string-kind"
  {:seon.error/kind "not-a-keyword" :seon.error/message "hostile kind"})
;; => {:minimal-commit #:seon.error{:kind :seon.db/rejected
;;      :message "Bad entity value \"not-a-keyword\" at
;;                [:db/add 2570 :seon.error/kind \"not-a-keyword\"] …"}
;;     :terminal-refused!-returned true
;;     :receipt-terminal? false :run-closed? false :run-held? true}
```

The minimal commit was REFUSED and `terminal-refused!` returned `true` anyway:
it ends in a `when-let` on `kind` and discards `store/transact!`'s outcome, so
the caller reports `:error` and moves on while the receipt is still running
inside an open held run — the exact precondition of the hot loop `e7d9f14c3`
claims to have eliminated, with no fact recording that it happened.

The specific falsifier is not reachable from agent code today (every kind Seon
produces is a keyword), so this is a latent defect rather than a live one. But
"un-refusable" is currently an argument about today's inputs, not a fence: the
code cannot tell whether its own settlement committed. Filed as
`docs/seon/issues/terminal-refusal-never-checks-its-own-settlement-commit.md`.

### Scenario 6 — abrupt crash and recovery

A run was left in the exact mid-plan crash state — open, held, planned with two
forms, receipt ordinal 0 RUNNING, `next-agent-work` = `:resume` ordinal 0 — and
the JVM was killed with `kill -9 76448`. The same root was rebooted through its
own operator.

```clojure
{:boot {:recovered-runs 1 :recovery-operations 1 :ready-ms 968
        :process "77445-1785345496856"}
 :crash-receipt-0 #:seon.cluster.eval{:interrupted-at #inst "2026-07-29T17:18:25.911Z"}
 :crash-receipt-1 nil
 :receipt-count 18        ; unchanged across the crash
 :crash-marker-fn false}  ; the interrupted form's def never became a row
```

Recovery marked exactly the dangling receipt `interrupted-at`, asserted no
result and no error on it, released dead custody, and touched nothing else.
No receipt was created, no form re-executed, no paid call re-fired, and the
interrupted `def` produced no program row. Receipts inside already-closed runs
were left byte-untouched, as `recover-call` promises. Boot readiness was
968 ms.

### Item 4 — one settlement path

Terminal facts on a receipt are written by exactly three transaction
functions, all in `seon.cluster.run`, all fenced on PRESENCE/ABSENCE and all
sharing one assertion builder or its recovery counterpart:

- `receipt-settle-call` — refuses `::receipt-terminal` when any terminal fact
  is already present, and `::no-terminal-fact` when the request carries none;
- `receipt-refusal-call` — contributes NOTHING when the receipt is absent or
  already terminal, so the recorder sharing its transaction still commits;
- `recover-call` — stamps `interrupted-at` only on receipts carrying no
  terminal fact, and only when the holder is not live.

`receipt-terminal-assertions` is the one builder the first two share, and
`terminal?` is the one predicate all three fence on. There is no second
mechanism, no status label, and no fourth writer.

### Verdict

The seam is right. One refused terminal transaction yields exactly one
terminal receipt, one durable error fact and one closed run, in one commit,
with zero further wakes on the channels, zero re-execution, zero re-calls, and
an unchanged program row — on the first form, mid-plan, below the cap and at
the cap — and a crash mid-plan recovers by marking interruption and nothing
else.

Graduation is blocked on the two filed issues: a development cluster cannot
run a single agent form with instrumentation armed (finding 1), and the
settlement commit's own success is never checked (finding 5). Both are at the
newest seams, which is the expected yield.
