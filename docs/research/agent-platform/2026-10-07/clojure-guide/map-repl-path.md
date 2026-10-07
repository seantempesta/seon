# The REPL path vs. the libraries: where the lines go

Research only; nothing edited, no JVM run. Every `file:line` was read in this session. Line counts are `wc -l`
of the working tree; "est." marks estimates that I did not measure by editing. Library paths are under
`/Users/sean/src/seon/reference-code`.

## 0. Findings first

1. The area is 17,739 lines (turn.clj 6055, sci/eval.clj 4250, call_preparation 1129, sci/kernel 978, batch/turn.batch
   1656, sci/admit 868, mcp 398 ...). The things libraries do for the same job are 100-1,200 lines (section 1).
2. About 30% of the area is not the REPL path at all. It is program installation into SCI contexts (sci/eval.clj
   ~1,700 of its 4,250), agent wake/plan/receipt/attempt bookkeeping (turn.clj ~4,000 of 6,055), a value codec
   (sci/admit 868), an argument-supplying planner (call_preparation 1,129), and one dead 420-line driver.
3. Shape, not subject, is the main multiplier. In this area 76 of 3,713 src forms are >= 100 lines (area-only: 16
   forms, 2,877 lines; 66 forms >= 50 lines hold 6,188 lines). The libraries I measured have 0-1 such forms
   (nrepl: 1 of 404, longest is `evaluator` at 108 lines; clj-reload: 0 of 81, longest `reload` at 64). Median form
   length: Seon src 13 lines, nrepl 7, clj-reload 5 (my crude scanner; library numbers are approximate for cljc).
4. Contract metadata alone is 499 `:malli/schema` blocks = 1,283 lines in this area (7%), and ~610+305+39 comment
   lines in the three biggest files. The owner's law requires the contracts; the reduction is to make the *forms*
   fewer, not to drop contracts.
5. Errors: this area carries 442 `:seon.error/at` and 269 `:seon.db/invalid-read` references across src, and the
   same three-way "did the write refuse?" triple is pasted at turn.clj:4493, 4576, 4623, 4638, 5356 and
   sci/eval.clj:2277. Libraries throw once and catch once at the boundary (`clerk/eval.clj:167-170`).
6. Dead code, verified by `rg`: `seon.eval.drive` (420 lines, no caller in src/test/script/bin), `turn.batch/turn-request`
   (830), `turn.batch/read-only?` (761), `seon.turn/row-tx` (1057-1180, its own docstring says "No production
   caller"; only two test files use it). ~635 lines.
7. Realistic target for this area from the reductions below: -4,500 to -6,000 lines with the first six being
   low/medium risk. Getting the area to the ~2k budget the run spec names (docs/seon/specs/run.md:9-11) needs the
   design cuts in reductions 3, 8, 9 (admit, call-preparation, program installation), which are owner rulings, not
   refactors.

## 1. Pairings and line counts

| Seon namespace (lines) | Closest library code (lines) | What the library part is |
|---|---|---|
| `seon.batch` evaluate-batch/form-step/evaluate-step (batch.clj 666-725, 321-422; 726 total) | `sci/src/sci/impl/interpreter.cljc:94-117` eval-string* loop (24), `:23-78` eval-form* (56); `nrepl/.../interruptible_eval.clj:72-180` evaluator (108) | read -> eval -> publish loop |
| `seon.turn.batch` entrants + derive/facts/apply/settle (931) | `clerk/src/nextjournal/clerk/eval.clj:137-172` eval+cache! (36), `:181-224` read+eval-cached (44), `:245-265` eval-analyzed-doc (21) | per-form eval that records what a form defined, one result per form |
| `seon.sci.eval/evaluate` (3862-4220, 359) | `sci/core.cljc:320-345` init/merge-opts/fork (25) + `interpreter.cljc:99-117` | one evaluation in a forked context |
| `seon.sci.eval` context build/fork/acquire (350-437, 2315-2635, 2842-2894, 3054-3416; ~1,100) | `sci/core.cljc:320-345`; `sci/impl/opts.cljc:283-330` init/merge-opts (48); `clj-reload/src/clj_reload/core.clj:334-398` reload (64) | build a context; bring changed namespaces up to date |
| `seon.sci.eval/install-row!` (1206-1415, 210) | `sci/core.cljc:260-275` intern (16); `sci/core.cljc:-copy-ns` | put one definition into a namespace |
| `seon.sci.kernel` arm/interrupt/failure-value (978) | `sci/impl/opts.cljc:~215-240` `:interrupt-fn` Ctx field; `clojure/src/clj/clojure/main.clj:207-300` ex-triage/ex-str (~95); `nrepl/.../caught.clj` (105) | time limit; turning a throwable into a message |
| `seon.sci.admit` (868) | `nrepl/src/java/nrepl/out/QuotaBoundWriter.java` (54) + `nrepl/.../middleware/print.clj` (205); `puget/src/puget/printer.clj` | bounded printing of any value |
| `seon.sci.forgive` forgiving reader (376) | `edamame/src/edamame/core.cljc:126-170` parse-next / parse-next+string; `nrepl/.../interruptible_eval.clj:125-146` read loop | read forms from text, tolerate errors |
| `seon.turn` agent loop (call-turn 4377, step 5807, resume-turn 5072, system-turn 2045; ~6,000 file) | `core.async/src/test/.../flow_test.clj:33-40` minimal-step (8); `langchain4clj/src/langchain4clj/agents.clj` (234 whole file) | a flow proc; an LLM agent loop |
| `seon.call-preparation` (1129) | `malli/src/malli/core.cljc:3166-3187` -instrument (22) | wrapping a function with a contract |
| `seon.repl` (636) | `puget/src/puget/color/html.clj` (114), `fipp` (ednize 91) | rendering a value as colourised text |
| `seon.mcp` (398) | `nrepl/src/clojure/nrepl/server.clj` (239) + `transport.clj` (283) | a tool server and its transport |
| `seon.run` (124), `seon.eval` (70), `seon.turn.world` (154), `seon.context` (420), `seon.eval.drive` (420) | no counterpart (domain) | dispositions, queries, worlds, prompt assembly, a driver |

Whole-library scale for context: nrepl eval + caught + print + session + transport + server = ~1,450 lines gives
a complete REPL server with sessions, interrupts, printing quotas and middleware. clj-reload is 955 lines total
for dependency-ordered unload/reload. sci's whole evaluator entry (interpreter.cljc) is 127 lines.

## 2. Why the libraries do it in fewer lines (with evidence)

### 2.1 The loop is a loop, and one thing is a function

nrepl's evaluator is one `loop` with three phases and one catch (`interruptible_eval.clj:72-180`):

```clojure
(loop []
  (let [input (try (clojure.main/with-read-known (read))
                   (catch Throwable e (caught e) eof))]
    (when-not (identical? input eof)
      (try (let [value (Compiler/eval input true)]
             (set! *3 *2) (set! *2 *1) (set! *1 value)
             (t/respond-to msg {:ns (str (ns-name *ns*)) :value value}))
           (catch Throwable e (caught e)))
      (when-not (and stop-on-error? @errored?) (recur)))))
```

sci's `eval-string*` (`interpreter.cljc:99-117`) is the same loop in 18 lines: `parse-next`, `eval-form`, `recur`. Neither
publishes, derives, applies and settles inside the loop; they hand the value to whoever called.

Seon's `evaluate-batch` (batch.clj:650-725) wraps the loop body in `guard` (167-175) and runs ten steps per form
(`form-step` 549-583: ns-before, read, evaluate+print, place, publish, freeze, derive, facts, apply, reshow). The
request carries 14 stage values (batch.clj:1-33), but only one entrant (`program-request`, turn/batch.clj:817)
supplies derive/facts/apply/fold/settle, and the spec (docs/seon/specs/run.md:20-40) already notes that
`show` has three implementations and `settle` two. A stage that has one implementation is not a stage.

Seon (batch.clj:321-424, 102 lines for one function):

```clojure
(let [r (:seon.batch/step batch) n (:seon.batch/ordinal batch) t0 (System/nanoTime)
      base (merge {...} (source-of r)) world (form-world request n)
      writes (java.util.concurrent.ConcurrentLinkedQueue.)
      open (java.util.concurrent.atomic.AtomicBoolean. true)
      on-write (fn [w] (when-not (.get open) (throw ...)) (.add writes ...))
      returned (volatile! nil) printed (volatile! nil) ...
```

Seven mutable cells (`returned`, `printed`, `measured`, `open`, `writes`, plus two in `form-world`) to thread one
evaluation's outcome out of a callback. The library idiom is that `eval-form` *returns* the value and the caller
composes: `(let [v (eval-form ctx form)] ...)`. Clerk observes definitions with one 8-line wrapper
(`clerk/eval.clj:128-136`, `intern+record`) and returns `(cond-> (wrapped-with-metadata result blob-id) (seq @!interned-vars) (assoc :nextjournal/interned @!interned-vars))`.
Seon already has the equivalent hook in its SCI fork (`sci.lang/*write-observer*`, used at sci/observe.clj:33-58,
26 lines of logic); the 100 lines around it in `evaluate-step` exist to make it concurrent-safe and to print
inside the scope.

### 2.2 Failure: one throw, one catch, one triage

- clerk (`eval.clj:167-171`): `(catch Throwable t (let [triaged (main/ex-triage (Throwable->map t))] (throw (ex-info (main/ex-str triaged) (merge triaged (analyzer/form->ex-data form))))))`. Four lines.
- nrepl: `caught` (`interruptible_eval.clj:117-123`) sets `*e`, flips a volatile, sends `{:ex (str (class e)) :root-ex ...}`.
- Clojure itself: `clojure.main/ex-triage` + `ex-str` (`main.clj:207-300`) turn `Throwable->map` data into the message.

Seon: `kernel/failure-value` (kernel.clj:787-882, 96 lines) plus its five helpers `without-interrupt-marker` (722),
`execution-error-text` (733), `sci-location?` (754), `stacktrace` (763), `author-throwable` (777) = ~165 lines to turn
one SCI throwable into one flat map. It calls `main/ex-triage` itself (kernel.clj:741) and then rebuilds the chain
(`error.refusal/chain`, `without-links`, `attributed`, `located`). The cause chain the owner wants is already
`Throwable->map`'s `:via` vector (class, message, data per link). The extra work is reshaping that into
`:seon.error/chain` and re-deriving "own link" identity.

The error-as-value discipline multiplies code at every call site. The same pattern is pasted six times:

```clojure
(or (:seon.db.write.attempt/request-id outcome) (:seon.db/invalid-read outcome) (:seon.schema/expected-value outcome))
```

(turn.clj:4493, 4576, 4623, 4638, 5356; sci/eval.clj:2277). `call-turn` (turn.clj:4377-4642, 266 lines) is a sequence of
`(cond (failure? x) (fail! x) ...)` steps because every `db/transact!` can return a map instead of throwing. Datahike's
own contract is that `transact` throws `ex-info` on a bad tx and the caller has one `try`. A library author writes:

```clojure
(try (-> (capture! ...) (call-provider! ...) (record-attempt! ...) (freeze! ...))
     (catch ExceptionInfo e (close-with-error! run e)))   ; the one boundary (CLAUDE.md "every entrant boundary")
```

CLAUDE.md allows `throw` "for internal control flow that its own boundary converts to that value in the same
function or its declared caller". A turn is that boundary. The 266-line function becomes ~100 (est.).

### 2.3 State: a context is `sci/init` + `sci/fork`

```clojure
;; sci/core.cljc:334-340
(defn fork [ctx]
  (update ctx :env (fn [env] (atom (assoc @env :sci/generation (utils/next-generation))))))
```

Seven lines. Seon's equivalent is spread across `build-base-ctx` (sci/eval.clj:350-437, 87), `fork-for-turn` (2842-2894),
`derive-base-ctx`/`base-ctx`/`copy-base-ctx` (2991-3083), `fork-cluster-ctx` (3324-3416, 92), `cluster-ctx`
(3269-3323), `regenerate-agent-context!` (2697-2770), plus `program-identity` / `remember-program-identity!` /
`watch-program-identity!` (2965-2990) and ~24 `atom`/`volatile!` in the file. Nothing in the library does "identity of
a program" because clj-reload's answer is the namespace file's mtime/content (`clj_reload/core.clj:77-130` scan) and
it just reloads.

### 2.4 Installing definitions: one eval per changed thing

clj-reload's `reload` (`core.clj:334-398`): compute `to-unload`/`to-load` as namespaces, `remove-ns`, `require` in
dependency order, loop until done. 64 lines for the job; the only data is `{:unloaded [...] :loaded [...]}`.
sci's `intern` (`core.cljc:260-275`) is 16 lines.

Seon `install-row!` (sci/eval.clj:1206-1415) is one function that `case`s on the row's identity attribute
(`:seon.ns/name`, `:seon.fn/sym`, `:seon.schema/key`, `:seon.test/sym`, `:seon.program/delete-identities`) and, for
functions, branches on `interpreted?`, `host-row`, `unloaded?`, `changed?`, `compiled?`, `unloaded-core?`, `refusal`,
`failed`, five outcomes (`:jvm`, `:interpreted`, `:unavailable`, `:refusing` root, refusal map), and then patches the
SCI env map by hand (`swap! (:env ctx)`, 1393-1409). `acquire-program!` (2315-2635, 320 lines) pulls the same rows
again (`function-rows`, namespace rows, test rows, digests, `reverse-closure`) and rebuilds. The "branch never changes
the platform" rule is real, but the compact shape is: `(sci/fork base)`, then for each row changed *since the loaded
commit* (`d/since` / digest diff), `(sci/eval-string* ctx source)` in its namespace in dependency order. Compiled
Vars are already in the base ctx (that is `copy-host-var`, eval.clj:238); only changed rows need work.

### 2.5 Functions are small and take plain args

nrepl median form 7 lines; clj-reload 5. Seon's biggest functions in this area (my scan, lines per top-level form):
`evaluate` 359, `acquire-program!` 320, `call-turn` 266, `step` 249, `install-row!` 210, `system-turn` 208,
`resume-turn` 184, `record-attempt!` 143, `evaluate-sources` 141, `row-tx` 123, `generate-turn` 121,
`evaluation-terminal-data` 117, `open-node` 117, `settle-batch-refusal!` 111, `settle-batch!` 106.

Typical shape, `step` (turn.clj:5807-6055): one flow transform arity holds the park-after-refusals counter, the
turn-permit, the backstop arming, the submission plan, the report building, the self-rewake, the escaped-throw reply
and a `finally` that republishes a permit. core.async's own minimal flow step (`flow_test.clj:33-40`) is:

```clojure
(fn ([] desc) ([_args] init-state) ([state _trans] state) ([state _in _msg] [state nil]))
```

A flow proc that does one thing per arity and puts park/permit/backstop in `:ping-map-fn`/`transform` wrappers is
~60 lines.

### 2.6 Data shape: namespaced everything vs. what the library passes

Libraries pass a request map of 5-8 short keys, destructured once. Seon requests carry 10-25 fully-qualified keys
and are re-destructured per function. Example `evaluate` (sci/eval.clj:3862-3920) destructures 11 keys, then builds
`turn-members`, `turn-environment`, `evaluation-ctx`, `arm-state`, `record`, `printed-text`, `printed-bytes`,
`put-printed!`, `printed` (a `proxy [java.io.Writer]`), `print-session`, `raw`, `connection`, `receipt`,
`namespace-name`, `ending-namespace`, `print-options`, `projection-state`, then `request` again, before the first
`with-bindings`. The output-capture Writer (about 25 lines at 3910-3940) is a quota writer: nrepl ships exactly that
as `QuotaBoundWriter` (54 lines Java, `src/java/nrepl/out/QuotaBoundWriter.java`) with `*out*` binding done by
`with-out-str`-style `binding`.

### 2.7 What the libraries do not do

- They do not validate every function's input and output at runtime in the hot path and then build error values for each
  (malli `-instrument`, `core.cljc:3166-3187`, wraps a function with one `props` map and a `:report` fn).
- They do not store the evaluation. nrepl sends a message; clerk caches by hash in a file. Seon records each form's
  evaluation, read evidence, call profile, handle, blob, terminal facts in one validated transaction; this is the
  required feature, but `evaluation-terminal-data` (turn.clj:3330-3446) and `evaluation-facts` (97-188) hand-build ~210
  lines of map where `d/transact` of one nested map per evaluation would do.
- They do not render per consumer. `repl.clj` has a 50-line lexer (`syntax-tokens`, 406-452) because puget/fipp colour
  by walking a *value*, not text; Seon colours *source text* that may be incomplete. See 4.
- They do not carry their own reader state, repair state, namespace cursor, handle table, stage table and world table in
  one request. Seon's 14-stage request is the composition mechanism and the largest source of "plumbing" lines
  (`turn-rows-state`, `namespace-cursor` volatile, `frame` closure in evaluate-sources 4875-5017 which is 141 lines of
  assembling `world`, `frame`, `request`, `handles`, then post-processing every entry).

## 3. Top 10 concrete reductions

Ordered by (confidence x lines). Line estimates are for this area only and are estimates.

### R1. Delete verified-dead code (~635 lines, risk low)

- `src/seon/eval/drive.clj` 1-420. `rg "eval\.drive|run-episode!|run-sample!" src test script bin` finds only the file
  itself and docs. Not loaded by any namespace in src; a disposable drive can be a `test/` helper if needed.
- `src/seon/turn.clj:995-1180` `row-tx` + `cardinality-many?`, `component-ref?`, `ref-attribute?`, `identity-ref`,
  `declared-one/-value/-map/-content` (the declared-* helpers are used only by `row-tx`; confirm with `rg declared-`).
  Docstring at 1062-1066 already says "No production caller ... convert them to the fold's writer and delete it".
  Remaining users: `test/seon/schema_usage_guard_test.clj:64`, `test/seon/program_test.clj:433`. Convert the two test
  fixtures to the fold's writer (`turn.batch/entity-facts` / `schema-tx`), then delete. ~190 lines.
- `src/seon/turn/batch.clj:761-768` `read-only?` (no caller in src; turn.clj has its own `read-only-evaluation?`
  at 1866) and `:830-834` `turn-request` (no caller; `turn.clj` builds its request inline). ~20 lines.
- Replace with: nothing. Verification: `rg` of each symbol, then lint `src script test --cache false` per
  CLAUDE.md "HEAD must boot".

### R2. Collapse the three `show` and two `settle` implementations into one each (~250 lines, risk medium)

- Show: `show-row` turn.clj:4746-4770, `turn.batch/show` 734-759, `jvm-show` 860-887. All end in
  `sci.eval/shown-result`/`shown-failure`. One `show` taking `{:render-head? bool}`.
- Settle: `turn.batch/settle` 683-726, `settle-batch!` turn.clj:3512-3617 (106), `settle-evaluated!` 3619-3668,
  `settle-batch-refusal!` 3786-3896 (111), `settle!` 3898-3963; both already call `turn.batch/revalidate` and
  `program-facts` (spec docs/seon/specs/run.md:60-66). Replace with one `settle` that takes the per-entrant extra
  tx-data as a vector (product turns add terminal data, blobs, issue tests; MCP adds nothing). Library idiom:
  `d/transact` takes data; extra data is `(into base-tx extras)`, not another settle function.
- Risk: settlement is "one synchronous transaction, durable before the run answers" (CLAUDE.md). Keep one transaction.

### R3. Replace `sci/admit.clj` with a counting writer around `pr` (~600 lines, risk high: needs owner ruling)

- Seon: admit.clj 1-868 walks every node with an explicit frame stack (`open-node` 293-410 is 117 lines; `frame-advance`
  424-480) to stream EDN into a byte-bounded buffer while calling the interrupt fn, producing `::print-node`, a
  semantic value, and the EDN. Docstring 1-40: "a lazy sequence can block before its first byte, so the count is
  taken as the bytes are emitted".
- Library: that is `QuotaBoundWriter.write` (above): it throws `QuotaExceeded` when `len > remaining`, which
  stops a lazy realisation mid-print; the interrupt arrives through SCI's `:interrupt-fn` or thread interrupt, which
  the printer already honours as it realises seqs. Clojure's own `print-method`/`*print-length*`/`*print-level*`
  provide cycle-safe node bounds; unprintable host objects print as `#object[...]` via default `print-method`.
  Two functions: `(defn admit [v bound] (let [w (counting-writer bound)] (binding [*out* w] (pr v)) ...))`, plus a
  `print-method` for SCI vars/namespaces/contexts (the 12 `sci-named`/`record-name`/`object-node` cases at 157-190).
- Risk: `:seon.print/face` envelope and the derived "semantic value" are consumed by render/value.clj (19 refs) and
  error.clj (51 refs). If the owner confirms those consumers can read EDN with tagged literals, the walk goes.
  Prototype first: print 20 representative stored values both ways and compare round-trip and timing.

### R4. Make a turn one try at the boundary (call-turn / record-attempt! / settle paths) (~250 lines, risk medium)

- Seon: turn.clj:4377-4642 `call-turn` (266), `record-attempt!` 4084-4226 (143), repeated triple checks (turn.clj:4493,
  4576, 4623, 4638, 5356), `fail!` closure with a `refusal?` flag (4430-4443), `freeze!` closure (4444-4518).
- Library: sequential `->` of throwing steps; `(catch ExceptionInfo e (close! run e))` once; clerk eval.clj:167-171 and
  nrepl `caught` (interruptible_eval.clj:117-123). Make `db/transact!` have a variant that throws the declared
  refusal ex-info (the writer already builds the value: `seon.error.refusal/diagnostic`) and let `call-turn` be:
  prompt -> capture tx -> provider call -> attempt tx -> freeze tx, each throwing; the boundary converts the throw to the
  one error value and settles (CLAUDE.md already authorises this: "`throw` is reserved for internal control flow that
  its own boundary converts").
- Also kills `(when (:seon.error/at next) (throw ...))`-style rethrows in `step` (5898-5902) that immediately convert a
  returned error back into a throw.
- Risk: medium; the refusal/park accounting in `step` distinguishes a refused *write* (counts toward the park bound)
  from a provider failure (turn.clj:4428-4442). Keep it as `ex-data` on the throw (`::write-refused true`).

### R5. Shrink the stage request from 14 stages to read / eval / show / settle (~300 lines, risk medium-high, an Opus lane)

- Seon: batch.clj:1-33 stage list, 549-583 `form-step`, 424-548 `publish-step`, `freeze-step`, `place-step`,
  `apply-pending`, `derive-step`, `reshow-step`; per-stage `guard` at 167. `outcome?` (314), `write?` (269-285),
  `unprintable` (244-252), the `in-bounds?` re-validation in `read-step` (199-222), `source-of`/`line-column` (187, 224).
- Library: nrepl's evaluator has two customisation points (`::read-fn`, `::eval-fn`, `interruptible_eval.clj:78-84`) and
  works for every middleware. `eval-form*` returns a value. Derivation (entities from writes) and facts/apply belong
  *after* the loop over all entries, in `settle`, from the list of `{form value writes}` the loop returned, not
  interleaved per form. The spec's own §3 admits "the defect is the double print".
- Concrete shape: loop returns `[{:form :value-or-error :shown :writes :reads :ns-after} ...]` (a plain `reduce`
  with `ns` cursor in the accumulator, no volatile), then `(settle request entries)`. The later-form-sees-earlier-form
  requirement (run.md §2 item 3) is satisfied by evaluating in the same SCI ctx; "facts refused" is decided in
  settle and reported beside values. Needs a check that "a later form sees an earlier form's *validated* change"
  (run.md §2 item 3: `a-call-omitting-its-database-sees-the-forms-own-write`) is not violated: that test reads the
  connection's current value, so keep the per-form `d/with` only when the form wrote through the connection.
- Risk: high-ish (it is "semantics of the one REPL path", Opus per CLAUDE.md). Gate with the run.md §2 test list.

### R6. Cut the JVM-mode entrant: run host eval as a form (~200 lines, risk medium)

- Seon: `mcp.clj:255-292` `jvm-run` (38), `turn.batch/jvm-request` 888-911, `jvm-show` 860-887, `jvm-read-next` 842-859,
  `test-request` 912-931 (the harness), `turn.world/jvm-world` 77-95; plus the `record-facts`/`evaluation-record`
  (612-649) that only the non-product entrants use.
- Library: nrepl's `"eval"` op evaluates a string with `*ns*` bound (`interruptible_eval.clj:104-110`); host eval is
  just `clojure.core/eval`. In Seon the same effect is a contracted function `my.jvm/eval-string` (declared once,
  `host-bound`), called from a normal SCI form: `(my.jvm/eval-string "(seon.cluster.boot/connection \"default\")")`.
  The result goes through the one path like any value; the "JVM has no custody, pass the connection" rule is the
  function's contract. Then the MCP tool has one mode, `mcp.clj` loses `jvm-run`, `pages-changed!` moves into that
  function, and `turn.batch` loses two entrants.
- Risk: medium. `test-request` is used by `test_support.clj:346,418` and must survive in some form (R5 makes it a one-liner).

### R7. Drop the per-form clj-kondo join; take the entity from the write's own origin span (~250 lines, risk high, measure first)

- Seon: turn/batch.clj `namespace-entity` 56, `analyzed-entities` 64-96, `synthetic`/`span-of`/`contains-pos?` 97-118,
  `join-write` 119-157, `captured-locals` 158-179, `entry-coords`, `local-offset`, `definition-source`,
  `form-relative-expansions` 180-223, `analyze-form` 224-238, `write-entity` 239-275, and `derive-form` 301-373 (73 lines);
  reaches seven private vars with `#'sfn/...` (64-96, 224-238), the "reach into another namespace's privates" smell.
  The spec says `defn` costs 450-770 ms and a `(+ 1 2)` costs ~90 ms (run.md:100-108).
- Library: clerk gets the same data from the evaluator, not an analyzer pass: `form-info` carries `:var`, `:form` from the
  parse (`clerk/eval.clj:137`), and records interned vars from the evaluation (128-136). Seon's SCI fork already hands
  each write the definition's own span (`origin`, observe.clj:36-45; `join-write` itself starts from
  `{sym :seon.batch/sym origin :seon.batch/origin}` at turn/batch.clj:119). With `origin` = the def form's span, the row's
  source is `(subs text start end)`; name and ns come from the Var; the contract is `(:malli/schema (meta v))`
  (already read at 239-275). Edges (calls) are derived when the definition is *indexed*, by one kondo run over the
  stored source (the existing `seon.fn` analyzer owns it), not per form inside the REPL path.
- Expected: removes the kondo call from the hot path (the spec's measured defn cost) and ~250 lines.
- Risk: high. The root law says kondo analysis of the same text is joined by exact span *as part of the one transaction*
  (CLAUDE.md "One REPL path"); the owner must accept edges being written by the indexer one step later. Falsify by
  timing `(defn f [x] x)` with the join removed on a branch.

### R8. Reduce call-preparation to map-entry supply (~500 lines, risk high: law change)

- Seon: call_preparation.clj 1129 lines; the positional planner is `arity-plan` 568-611, `compiled-plan` 651-716,
  `resolve-count` 521-544, `planned-arities` 621-631, `prepared-arities` 744-766, `partial-insertions`/
  `partial-preparation` 951-1004, `prepare` 1005-1085 (the "unique placement under declared slot schemas" rule,
  docstring 41-45), and the cached snapshot (`derive-snapshot` 286-359, `snapshot-memo` 387-448; `clojure.core.cache.wrapped`).
- Library: malli wraps a function with one closure (`-instrument`, `core.cljc:3166-3187`; the wrapper fn is a plain
  `(fn [& args] ...)`). Supplying a world is Clojure's `binding`/an explicit leading map. If supply is restricted to
  *keys of a leading options map* (the existing `supplied-map-entries` 632-650 and `supply-from-world` 858-879 already
  do that part), the wrapper is: `(fn [& args] (apply f (update-first-map args merge-missing world-keys)))`, with no
  arity search, no ambiguity resolution, no positional insertion. Est. 1129 -> ~450.
- Risk: high. CLAUDE.md "Supply defaults by declared schema name" includes positional slots ("a call already matching a
  declared arity is never prefixed"). Measure how many first-party functions declare a positional world slot
  (`:seon.db/db`, `:seon.agent/id` as arg 1): `rg ":seon.fn.argument/index"` over the stored plans. If every real call
  site is map-entry or first-slot, the positional planner is unused machinery. Needs owner ruling.

### R9. Replace program acquisition with fork + eval of changed rows (~900 lines, risk high)

- Seon: sci/eval.clj `install-row!` 1206-1415, `acquire-program!` 2315-2635, `regenerate-agent-context!` 2697-2770,
  `fork-for-turn` 2842-2894, `base-ctx`/`derive-base-ctx`/`copy-base-ctx`/`loaded-*` 2965-3110, `acquire!` 3193-3255,
  `install-first-party-namespaces!`/`host-namespace!`/`load-core-namespaces!` 1620-1790, `program-doc`/`directory-value`
  1829-2160 (docstring and directory value construction, ~330 lines), `record-acquisition-refusals!` 2222-2290.
- Library: clj-reload (`core.clj:334-398`) is "diff, unload, load in order, return `{:unloaded :loaded}`". sci's own
  `merge-opts`/`fork` handle the context. The compact shape: base context = compiled Vars once per JVM (exists:
  `build-base-ctx`), per branch = `(sci/fork base)` + `(doseq [row changed-rows] (sci/eval-string* ctx (:source row)))`
  where `changed-rows` is one Datalog query "entities whose digest differs from the loaded program", ordered by the
  dependency graph the DB already holds (`seon.fn/reverse-closure`, used at acquire-program!:2369). Documentation
  (`install-program-doc!` 2139, `documentation-value` 2058) is a render over entities (CLAUDE.md "Documentation is data"),
  not a context-construction step.
- Risk: high; "branch never changes the platform" rules (host-bound rows, unloaded core rows) are real and are why
  `install-row!` has eight branches. Land after R5, with the test list `acquire`/`fork`/host-bound tests on a branch.

### R10. Move non-REPL material out of `seon.turn` and shrink `step` (~600 lines moved/removed, risk low-medium)

- `render-ai`/`render-html` (turn.clj:1585-1691, ~110 lines) are presentation of a turn, belong with `seon.repl` render fns
  (repl.clj:576-636 already has `render-ai`/`render-html`; two render owners).
- Wake derivation `unanswered-wakes`, `next-agent-work`, `deferred-triggers`, `turns-left`, `episode-runs`,
  `spent-runs`, `outside-wake-t` (turn.clj:2588-3060, ~470 lines) is a query over datoms; the CLAUDE.md "Derive wakes
  from listened datoms" idiom is `d/listen` + one Datalog rule set (datahike reference: `d/listen` in the api). A rule
  file plus one `next-work` function is ~120 lines (est.).
- `step` (5807-6055, 249 lines) -> a flow `transform` that calls `turn` and a wrapper for the three cross-cutting concerns
  (permit, park, backstop) at the proc level (`:ping-map-fn`, core.async flow `step` arities, `flow_test.clj:33-40`).
- Risk: low to medium; each piece has its own tests, and each can land alone.

Sum of lines in R1-R10 (not double-counted): ~635 + 250 + 600 + 250 + 300 + 200 + 250 + 500 + 900 + 600 = ~4,500
(range 3,500-5,500).

## 4. Features with no library counterpart that are genuinely necessary (and how a library author writes them)

1. **Forgiving reader** (prose to comments, delimiter repair; forgive.clj 376 lines; `next-form` 192-260 is 69 lines).
   Necessary: agents reply in prose + code. Compact form: use edamame's `parse-next` with `{:all true :end-location true}`
   and a plain `loop`; on a read error comment out the offending line and retry, bounded by a counter:

   ```clojure
   (loop [text text pos 0 out [] n 0]
     (let [r (try (edamame/parse-next (reader text pos) opts) (catch ExceptionInfo e e))]
       (cond (instance? Throwable r) (if (< n 64) (recur (comment-line text pos) pos out (inc n)) (conj out {:unread pos}))
             (= ::eof r) out
             :else (recur text (end-pos r) (conj out {:form r :span (span r)}) n))))
   ```

   edamame already gives `:row :col :end-row :end-col` locations (`edamame/core.cljc:78` `:location?`). Est. 376 -> ~150.
   The one-path law (one reader) holds: this *is* the one reader.
2. **Per-agent SCI world** with result handles (`result/e1a2...`). `sci/fork` + `(sci/intern ctx ns 'e1a2 value)` (3 lines;
   `core.cljc:260-275, 334-340`). The only data Seon adds is the handle table; keep it as ordinary interns, no extra registry.
3. **Time limit + memory pressure.** sci's `:interrupt-fn` (a Ctx field, `opts.cljc` Ctx record) is called on every fn/loop entry;
   a deadline check is `#(when (> (System/nanoTime) @deadline) (throw (ex-info "time" {...})))`. Kernel's 978 lines include
   a guard thread, tenured-pool monitoring (`watch-memory!`, `memory-pressure`, 106-205), arm adoption across threads
   (`detached-arm`, `adopt-arm`, 561-646) and `future-call`/`pmap` wrappers (670-720). The deadline, one memory watch, and
   `future-call` binding conveyance are necessary; the arm-adoption protocol should be replaced by passing the ctx/deadline
   as data (sci already conveys bindings to futures via `sci/future`, `core.cljc:~231` `pmap`/`future` support).
4. **One settlement transaction with basis check at the writer.** Datahike serialises transactions already; the library way is
   a transaction function `[:db.fn/call settle-fn args]` that reads the head and throws on a stale digest (Seon already does
   this: `program-facts-call`, `record-call`). Write it as one `defn` of (db, entries) -> tx-data (a pure function, testable with
   `d/with`), and `d/transact` it. Seon has three such builders (`record-facts`, `settle-batch!`, `receipt-settle-batch-tx`);
   they should be one.
5. **Dispositions** (`seon.run/wait`, `complete`). Necessary and already tiny (124 lines). A library author would return a
   map `{:disposition :complete :result text}` from a plain fn and validate the string with `(s/assert ...)` instead of
   building `seon.error.refusal/diagnostic` (5 lines each, run.clj:96-120 and 108-124).
6. **Agent-facing replies as data** (`seon.repl` 636 lines, `seon.mcp` 398). Necessary. nrepl's pattern is `print` middleware
   with one `*print-fn*` and one quota (`print.clj:34-60`); render selection by schema is Seon-specific (render functions
   return data). Compact: keep the render registry but compute the `[:schema -> render-fn]` table once from the schema
   registry (a map), and let the value printer (`seon.print.value`) be the only printer. `syntax-tokens` (repl.clj:402-452)
   could be `(re-seq #"..." source)` with a combined regexp, but the law forbids production regex without owner permission;
   puget's HTML colouring walks values, so it does not apply to incomplete source.
7. **MCP.** No MCP reference exists in reference-code; nrepl's server/transport pair is the nearest. `tool-table` as data
   and `json-schema/transform` (mcp.clj:66-101) is already the compact idiom. The 45-line `execution` (170-213) is
   custody/branch creation that belongs in `my.agent`, leaving `mcp.clj` at ~250.
8. **Call preparation** (supplying world members by schema name). Necessary per CLAUDE.md. The compact form is R8.
9. **Admit/bounded storage of values.** Necessary (a result must be stored whole or missing). The compact form is R3.

## 5. How to verify before cutting

- For each of R1 symbols run `rg -n "<symbol>" src test script bin` (done above for `row-tx`, `turn-request`, `read-only?`,
  `eval.drive`, `run-episode!`); `bin/seon` stays off in this research lane.
- For R5/R6/R7: measure the five timings run.md:100-108 reports (`(+ 1 2)`, one `defn`, two `defn`s) on a branch before and after;
  CLAUDE.md: "fix regressions over 20% or 50 ms".
- For R3/R8: sample stored data first (which print nodes and which call plans exist) before deciding a grammar can go.

## 6. Caveats

- Library form-length numbers come from a crude scanner (`(defn|def|defmacro|defmethod|defonce` start to the next
  top-level form), so cljc data `def`s inflate some library maxima (e.g. sci's `namespaces.cljc` `eval*` 1079). For nrepl,
  clj-reload and the Seon area the numbers are reliable to a few lines.
- I did not read all 17.7k lines. I read in full or in large part: batch.clj (1-425, 650-725), run.clj, mcp.clj (40-300),
  turn.clj (995-1180, 3512-3530, 4377-4780, 4875-5010, 5807-6055), sci/eval.clj (1206-1420, 2291-2420, 3862-4100),
  sci/kernel.clj (722-900), sci/observe.clj, sci/forgive.clj (186-262), sci/admit.clj (1-62, 806-848),
  call_preparation.clj (1-55, 555-830), turn/batch.clj (56-160, 224-375, 755-842), repl.clj (398-455), eval.clj,
  eval/drive.clj (1-60). Everything else (context.clj, turn/world.clj, sci/arm.clj, render-ai in turn.clj, system-turn,
  resume-turn, record-attempt!) is characterised from its defn table and docstrings; the proposals about them
  (R4 partly, R10) carry correspondingly lower confidence.
