---
type: spec
status: reviewed
created: 2026-10-07
revised: 2026-10-07
tags: [spec, errors, malli]
---
# Errors: one map, two carriers, one boundary

What exists: five constructors, value-to-exception round trips at every read, a bespoke re-description of Malli
reports, a separate "fault" vocabulary for Seon's own failures and a 4,735-line error area (`error.clj` 2838,
`error/refusal.clj` 787, `fault.clj` 453, `problems.clj` 657). S1 (`33fb994f2`) installed the optional
`:seon.error/type` key and removed the metadata stamp from `src`; `seon.error.refusal/chain` already walks cause and
suppressed edges cycle-safely and keeps each link's data whole. Data flow after this spec: a producer builds ONE error
map with ONE validating constructor; it returns the map when answering is its job, else throws it as `ex-info` data;
the entrant boundary takes it back once; Seon's own errors are recorded once per signature at the task home with a
count and one notification to root, and an agent or root files the task; every occurrence rides the evaluation or call where it happened and
reaches the agent that caused it in that reply. There is no separate fault concept. Principal approach: Malli's own
shapes (`:multi` on a type key, the instrument report, cached explainers, `malli.error`) and Clojure's (`ex-info`).

Rulings: §9. **[V]** read or probed 2026-10-07 (HEAD `42e569d9d`, default running it); **[U]** proven in the named
slice. Functions are cited by name: line numbers drift.

## 1. The error map

```clojure
{:seon.error/type      :my.fs/not-found-error      ; required: the variant (= its schema key); the :multi dispatch
 :seon.error/operation 'my.fs/read                 ; required: who refused; signature member
 :seon.error/message   "No such file."             ; required: ex-info's message; short, no printed values
 ;; optional base members
 :seon.error/fix       "Check the path with my.fs/glob."
 :seon.error/problems  [...]                       ; Malli explain errors (§5), validation failures only
 :seon.error/chain     [...]                       ; refusal/chain's links, boundary only
 :seon.error/frame     [cls method file line]      ; first authored frame, boundary only
 :seon.error/caller    'my.ns/f                    ; the blamed caller of a refused call (instrumentation)
 :seon.error/digest    "…64…"                      ; the blamed producer's armed digest at the report (instrumentation)
 :seon.error/world     {:seon.agent/id .. :seon.agent/branch .. :seon.cluster.eval/id ..}
 :seon.error/recorded  {...}                       ; the recorder's receipt, boundary only (§6)
 :my.fs/path "x.md"}                               ; variant members, fully namespaced
```

The type selects the variant schema, the render pair and the signature, and survives `merge`/`into` (optional since S1
[V]); the operation is the refusing definition (required today); the message is `ex-info`'s (optional today [V]).
`:seon.error/at` and `:seon.error/layer` leave the map at the cut (§7): the recorder stamps occurrence time; a returned
error rides an evaluation that has its time; the type's namespace names the layer. They are load-bearing until then:
the recorder's signature reads layer (`seon.error/signature`), the recorded-error facts require both
(`seon.error.edn`), and `seon.error.refusal/error?` plus about 20 readers test `(:seon.error/at x)` as "is an error"
[V]; all move in the same cut.

`:seon.error/world` holds references only. `world-reference` derives the evaluation id with the two-argument
`(id/evaluation run-id ordinal)` today [V]; after the loop cut the run id is the eval request id and the ordinal the position in
the eval request (agent-loop §1), so the formula is unchanged; the world keys it reads (`:seon.turn/id`,
`:seon.batch/ordinal`) change in agent-loop P2.

**Variations.** Each variant is the base plus its own members, in its owner's schema EDN. Seon's registry has no
`:merge` (`(mr/schema r :merge)` nil, `:multi` present [V]), so a variant is an `:and`; a function that RETURNS errors
lists exactly its alternatives as one `:multi`:

```clojure
:my.fs/not-found-error [:and :seon.error/base [:map :my.fs/path]]
:my.fs/tool-error [:multi {:dispatch :seon.error/type}
                   [:my.fs/not-found-error :my.fs/not-found-error]
                   [:my.fs/path-refused-error :my.fs/path-refused-error] ...]
my.fs/read [:=> [:cat :my.fs/read-request] [:or :my.fs/read-result :my.fs/tool-error]]
```

At pinned malli `43c6dca8` the `:multi` validates a variant inside `[:or result multi]`; an undeclared type is
`::m/invalid-dispatch-value`, spell-checked as a misspelled value (`malli/error.cljc:343-349`) [V]. A success alternative
must not accept an error map (closed against `:seon.error/type` or disjoint by required keys) [U: each family slice
checks the `[:or …]` it types]. Generic boundaries accept `:seon.error/base`. Today the installed variant member is
`:my.fs/not-found` and `:my.fs/tool-error` is an `[:or …]` that also admits `:seon.effect/boundary-error` [V]; moving
to `:my.fs/path` and a `:multi` is that family's BEHAVIOR CHANGE.

**Cause chain.** `seon.error.refusal/chain` stays the one walk: cause and suppressed edges, seen-set membership, the
installed `:seon.error/link` (class, message, whole referenced `ex-data`, first-party frames, `:seon.error/suppressed-by`,
`:seon.error/cause-of`) [V]. Installed `cause-links` and `chain` use equality-based Clojure sets, not identity
sets; two distinct Throwables overriding equality can collapse into one link. Correct both to identity membership
in the cut (`seon.error.refusal/preserve-primary-and-suppressed-causes`), preserving the one walk. `Throwable->map` is never used as the traversal: it follows `.getCause` with no seen-set
and ignores suppressed throwables (`clojure/core_print.clj:484-501`); its `:via` shape is what each link resembles.
`:seon.error/frame` is `root-frame`: the root cause's first authored frame, else each enclosing link's outward.

**References and evidence.** Three separate concerns:

1. *Live value*: the map as built, held in memory and returned or thrown. `refusal/references` turns a database into
   its commit id, a connection or SCI context into its identity and a place (atom, delay, promise) into class and
   identity. It does not bound size.
2. *Stored evidence*: the recorder stores the first occurrence as the value printer's shown text of the final assembled
   map, nothing else (owner ruling, round 6). The printer already handles laziness, cycles, opaque objects and budget,
   so no second walk decides what is safe to store [U: cut asserts a nested opaque value and an unrealized sequence
   print by class without being realized].
3. *Display*: the value printer, at the edge, once.

## 2. The constructor and the two carriers

`seon.error.refusal/error` stays the one constructor. Its installed arity is `[alternative members]` (validates, still
stamps at/layer) [V]. Add `(error type operation message)` and `(error type operation message data)` to the
SAME defn in P2; they become the only arities in the cut. The three-argument arity supplies `{}` as data. Both use this target contract:

```clojure
[:function [:=> [:cat :qualified-keyword :qualified-symbol :string] :seon.error/base]
           [:=> [:cat :qualified-keyword :qualified-symbol :string :map] :seon.error/base]]
```

Acquire the operation's projection, reference the data, associate type/operation/message, then validate the complete
map. The constructor's failure boundary must cover projection acquisition and referencing too, not just explanation;
otherwise a registry failure escapes before the literal emergency map can be built. The emergency path performs no
registry lookup, reference traversal or attributed diagnostic construction.

```clojure
(defn fail!
  "Throw the error map as ex-info data: the carrier for functions whose job is not to answer."
  {:malli/schema [:function [:=> [:cat :qualified-keyword :qualified-symbol :string] :nil]
                            [:=> [:cat :qualified-keyword :qualified-symbol :string :map] :nil]]}
  ([type operation message] (fail! type operation message {}))
  ([type operation message data]
   (let [e (error type operation message data)]
     (throw (ex-info (:seon.error/message e) e)))))
```

Validation checks the complete map against the schema named by `type` in the operation's projection with Malli's cached
explainer (`m/explainer`, cached on the schema object, `core.cljc:353-361,2690-2712`); valid costs one cached validator
call. On failure it returns `:seon.error/malformed-error` holding `:seon.error/attempted` (the referenced map) and the
explain `problems`. Both carriers pass through it, so a malformed `(fail! :my.fs/not-found-error 'example/f "m")` is
refused at construction, not at a base-only boundary (Malli validates outputs only after `(apply f args)` returns,
`core.cljc:2236-2240` [V]). The malformed error and every error built while the registry itself fails are assembled as
literals without validation or explanation: building an error never recurses into explaining one. The cached
explainer and packaged-projection fallback are installed; the nonrecursive literal failure path is
a new requirement, not current behavior (`construction-refusal` calls `diagnostic`, which re-enters projection and
attribution). `construction-refusal` and `checking-projection` fold into `malformed` in the cut.

**Rule.** Return the map when answering is the function's job: agent tools (`my.*`), validators and checks whose result
IS the verdict (merge checks, gates, write-back digests). Throw it everywhere else. Deep helpers of a returning tool
`fail!`; the tool catches once at its top and answers only its own declared variants:

```clojure
(catch clojure.lang.ExceptionInfo e
  (let [d (ex-data e)] (if (tool-error? d) d (throw e))))   ; tool-error? = the cached validator of its :multi
(catch java.io.IOException e (error :my.fs/read-failed-error 'my.fs/read "The file read failed." {:my.fs/path p}))
```

A contract refusal or core defect is not in the tool's union and propagates. A deliberate translation names its own
type and keeps the original Throwable as the third argument to `ex-info`, with the constructor's returned map
as data and that map's message. The shown `fail!` arities have no cause argument; they cannot preserve it alone. No other layer catches to convert.

| # | Before | After |
|---|---|---|
| 1 | `my.program/caller-data`: five `(if (:seon.db/invalid-read r) (throw (ex-info …)) r)` blocks; about 119 such rethrows in `src` [V] | the bare read; it throws to the boundary. Removed per read family only after that family's producer throws (S-reads). |
| 2 | `seon.db/q` returns refusals from argument shape, alignment, nil inputs, malformed patterns and query-value types, converts Datahike's `ex-info` through `dependency-error` and appends read evidence in its catch [V] | each refusal branch `fail!`s its named variant; Datahike's `ex-info` propagates with its `:error` data in the chain; the evidence append stays in the one catch, which rethrows. |
| 3 | `seon.fs.jvm/refuse!` throws `flat-error`; `parent-access` throws on an empty relative path before descending; `read-window` catches every Throwable into `error-value` [V] | `parent-access` calls `fail!`; `my.fs/read` catches once as above. |
| 4 | `seon.turn` and `seon.render.web` wrap a map in `ex-info` only to record it; `seon.instrument/boundary-refusal` reports; `seon.sci.kernel` re-chains structural ex-data | the recorder called with the map; `contract-error` (§5); `(caught t)` plus the SCI location |

**Typing producers.** Functions declaring only `:seon.error/base`, `:seon.db/error-result` or several alternatives
exist, and there are about 120 hand literals and 52 throwable-bearing `diagnostic` calls beside about 225 `diagnostic`
sites [V, lane notes]. Each family slice (P3) types every detection site by its actual failure branch: each failure
class gets a named variant in its owner's EDN; a base-only contract gains its variants; throwable-bearing sites move to
`caught` or to a deliberate translation. Base is never stamped as a producer's type.

## 3. The boundary

`seon.error.refusal/caught` converts a Throwable to the map; it does not record it. Normalize through the existing
`chain` owner once, carrying the primary path and referenced links as intermediate values for error selection,
frame and attribution. Installed `chain`, `root-frame` and `producer` independently traverse the Throwable;
change those helpers and every caller together so `caught` does not repeat them. Preserve `root-frame`'s
whole-root-trace rule: the chain's compressed per-link frames alone do not identify that frame. Build
`:seon.error/exception` only when no primary link carries a declared type, then add the chain, optional frame,
producer and world references without overwriting producer-supplied fields.

- The outermost declared error along the PRIMARY cause path wins: it is the producer's deliberate decision. A typed
  error that was only suppressed (a cleanup refusal) stays evidence in the chain and never retypes the primary.
- `producer` receives the chosen map, never nil. `producer`, `attributed` and `world-reference` stay as the blame rule;
  their inputs move to the new report fields in the cut (§5).
- An error already recorded in this propagation keeps its receipt in its link's data and is not recounted.
- Recording is the entrant's step, not `caught`'s: each entrant calls the recorder (§6) on the map when it is Seon's
  own error and assocs the receipt as `:seon.error/recorded`; an agent's own mistake is delivered and not recorded.
  The classification rule is not decided [U: owner question, before P4]. An entrant that recorded an error never
  rethrows it to an enclosing boundary (`seon.flow/var-process` records every transform throw again).

Entrants, each with exactly one catch [V for location]: the one REPL path (`seon.batch` stage guards, `seon.sci.kernel`);
MCP (`seon.mcp`); Flow procs (`seon.flow`); boot (`seon.cluster.boot`); web (`seon.render.web` handler); the test runner
(`seon.test`); agent graphs (`seon.cluster.agent`). The model eval request is an entrant from the loop cut on (agent-loop §2).

## 4. Contracts

Only functions that return errors list error alternatives: `my.*` tools, validators, checks and the boundary functions
(`caught`, the recorder). Each reader's `[:or value :seon.db/error-result]` (about 150 references [V]) narrows to
`value` with its family in S-reads. `error?` becomes total on the type: `(and (map? v) (contains? v :seon.error/type))`.
`let-ok` goes with the round trips.

## 5. Instrumentation

Arming stays on every function (`seon.instrument` `apply!`, `arm-var!`, `wrap-interpreted`, call preparation). The
report becomes one adapter over Malli's five report kinds (`core.cljc:2219-2240` [V]):

Malli supplies these checked schema/value pairs: input/args, output/value, guard/[args value]. Arity and
schema-at-call reports are never explained. The adapter receives the armed callee symbol/digest, projection and
captured caller attribution as explicit inputs, so P4's calculation can be tested without a live stack or registry.
The wrapper captures those observations before calling it. Convert explanations with `me/with-spell-checking` and
`me/with-error-messages`; keep the declared problem members below. The report must throw the constructed map as
`ex-info` data: returning would allow the refused function to run (`m/-instrument-f`). Preserve the schema-at-call
exception as its cause. Capture and translate reporter failure as described below; it cannot retain raw report data
merely by escaping.

Caller and digest fields are optional when unavailable: omit them, never associate nil. In particular, a top-level
REPL form has no calling definition (`refusal/calling-definition`); do not call `var-digest` with nil or construct a
malformed error while reporting the original refusal. Retain the evaluation's world reference for that case.

- **Blame** is captured at the report: input and arity blame the caller, with the caller's digest as it ran
  (`refusal/calling-definition` and `refusal/var-digest`, as `boundary-refusal` captures both today [V]); output and
  guard blame the callee by its armed digest. Nothing is re-read after a reload. SCI branch
  attribution is kept.
- **Schema-at-call**: its original exception is the `ex-info` cause, so `caught` keeps it in the chain; the failing
  schema is never re-explained. An evaluation interrupt that Malli reported this way is rethrown untouched as the bound.
- **Reporter failure**: if explaining or spell-checking throws, the reporter's exception becomes the primary error
  (`:seon.error/exception`, blamed on the reporter), carrying the original report kind and the raw report data as
  members; it is never relabeled as the contract error (`a-reporter-failure-is-not-relabelled-as-a-contract-violation`).
  An interrupt propagates unchanged.
- **No printing in producers**: the message names the function and kind; `problem` keeps `:in :value :type :message
  ::me/likely-misspelling-of` and `(m/form schema)`; the value printer shows them at the edge. A message says why: a
  failed `[:string {:min 1}]` on `""` reads "should be at least 1 character", never "expected a string, got a string"
  (live run 1, defect 3).

Spell checking needs a closed map (on Seon's open `:my.fs/read-request` the misspelled key yields only `missing required
key` [V]): `explained` explains the refusal path against `mu/closed-schema` and drops `::m/extra-key` errors that carry
no suggestion, so open maps stay open. Refusal path only: 207 µs per refusal, unarmed, default [V].

`(my.fs/read {:my.fs/pth "AGENTS.md" :my.fs/max-bytes 0})`, from the real report and explain output on default:

```clojure
{:seon.error/type :malli.core/invalid-input :seon.error/operation my.fs/read
 :seon.error/message "my.fs/read: invalid-input" :seon.error/caller user/...
 :seon.error/problems
 [{:in [0 :my.fs/max-bytes] :value 0 :schema [:int {:min 1}] :message "should be at least 1"}
  {:in [0 :my.fs/pth] :value "AGENTS.md" :type :malli.error/misspelled-key
   :message "should be spelled :my.fs/path" :malli.error/likely-misspelling-of [[0 :my.fs/path]]}]}
```

Today the same call throws about 540 estimated tokens in 21 keys, never naming the misspelling [V]. Deleted from
`seon.instrument`: `problem-path`, `failure-cause`, arglist re-derivation, `supplied-entry-problems` through
`violation`, `problem-location`, `boundary-refusal`, `minimal`, `refusal?` and the inner `refusal` (interrupt handling
moves into the report); about 590 lines out, 50 in. From `seon.error`: `schema-expectation` through `scalar-text`,
`evidence-path` through `call-lines` (the hand Levenshtein; Malli's is `error.cljc:245-280`).

## 6. Recording and delivery

Seon's own failures are errors like any other. An error that reaches an entrant boundary is delivered as data in the
reply of the agent whose evaluation or call it happened in, and recorded once per signature at the task home
(`seon.task/home`, root's home branch, where tasks and messages to root live [V]) by one `:db.fn/call` at the writer,
which receives the in-transaction database (`datahike/db/transaction.cljc:1172-1173` [V]); no counter, lock or
occurrence queue:

```clojure
{:seon.error/signature (id/id [type operation producer problem-shape exception-class (frame-site frame)] 64)
 :seon.error/type .. :seon.error/operation .. :seon.error/producer ..
 :seon.error/occurrence-count n            ; incremented at the writer
 :seon.error/shown "…"                     ; the first occurrence's shown text (value printer), stored once
 :seon.error/latest-digest d               ; the producer digest of the latest occurrence
 :seon.error/latest-at t
 :seon.error/opened-at t}                  ; when the current opening began
```

`problem-shape` is the set of `[:in :type]` pairs over the problems; do not sort heterogeneous path
keys with Clojure's default comparator (an index and a keyword at the same position throw). The signature requires
structural, order-independent `id/id` hashing, which is already implemented (`src/seon/id.clj:246-261`). P4 proves
equal sets have equal signatures regardless of insertion order; no identity change is a prerequisite.
`exception-class` is the root link's class for
`:seon.error/exception`, else absent. So two contract refusals with different failure types, or two host exceptions of
different classes at one frame, are different rows. Member names reuse installed keys where the meaning is the same
(`:seon.error/occurrence-count`, `:seon.error/shown`) [U: P1 registry search].

**Opening.** The writer function reads the row on the in-transaction database. It OPENS when the row is absent or
resolved (below). An opening writes, in the same home transaction, one generated task from the triage template
(`seon.task/generate-tx`, ledger RT-7, RT-8, RT-12) assigned to the function's steward if one is held, else to root:
a question about the error row whose answer is a decision (the owner tasks it filed, each with a red reproduction, or
no action with a reason), its proof the generic `seon.task-question-test/root-decided`. The row refs it as
`:seon.error/task`; the assignment is the one notification (an assignment is a wake), and no message is sent. The
agent whose call caused the error is never the assignee: the error comes back to it in its reply. A repeat increments
the count and updates `latest-digest`/`latest-at`; it writes no task. (This supersedes the 2026-10-07 "creates no
task" rule, per AGENTS.md "with a count, a task and one notification".)

**Every occurrence is attached and delivered.** The recorder returns a receipt `{signature count opened?}` for repeats
too; the boundary assocs it as `:seon.error/recorded`. The settlement stores the signature on the evaluation or provider
call where the failure surfaced (`:seon.cluster.eval/error-signatures`, `:seon.ai.attempt/error-signatures`, values,
since the row lives at the home; agent-loop §2.1). The causing agent reads the error in that reply and in its history;
an agent on another branch needs no copy. Every error in a reply's `:errors` and `:diagnostics` carries its result
handle, so the agent can dig into it (`result/e…`); results are runtime only, so after a restart the handle answers
that the live value is gone while the shown text and facts remain. This replaces the temporal fault window and its
folded repeats.

**Resolution** (rewritten): a recorded error is resolved

- when a previously identified producer is deleted (a successful read confirms no definition carries its symbol
  on the task home's program); missing attribution or a refused read is unknown, not deletion, or
- when its producer's current digest on the task home differs from `latest-digest` AND every success test naming its
  task has a current green run recorded on the task home after `latest-at`. Require a nonempty proof set,
  `(true? (seon.test/verified? ...))` for each proof, and the explicit time check: `verified?` returns a refusal
  map when evidence is unavailable, which is truthy and is not proof. Use `seon.task/task-edges` for the proofs.
  A digest change alone never resolves a surviving producer. A task with no proofs keeps its error open: adding the
  regression that names the task is part of fixing it.

Both are judged on the task home's program: a fix on a branch resolves when root merges it and its tests run green
there. `seon.task/task-resolution` reads this through `seon.error/resolution` (rewritten in place).

Cost: the home is root's branch, so a branch agent's errored form costs one home transaction before its branch
recording transaction (N recorded errors in an eval request, N home writes). The expected
cost follows the recorded errors, one small write each; P4 measures it against that expectation (no arbitrary
targets: 10× over it is a defect) and against AGENTS.md's 100 ms bound for the recording transaction.

An unknown or refused recording write runs the owner's stop request and prints through `print-unrecorded!`, never
retried. Goes: `prepare` and the admission stack, `commit-call`/`recording`/`occurred-since` (12 existence reads per
repeat), the `-valid?` predicates and generators, `seon.fault` folding, steward escalation and cluster copy; the
`seon.fault` namespace keeps only what the recorder needs, renamed into `seon.error` in S-delete. The fault vocabulary
(`seon.fault`, `:seon.fault/*`, `:seon.error.fault/*`, `:seon.cluster.eval/faults`, `:seon.task/faults`,
`:seon.problems/open-fault`, "open-fault") retires; old rows are not migrated (§9.6).

## 7. Slices

Preparatory slices are invisible unless labeled; each lands through `bin/lane commit --test`, HEAD boots after each, and
each is dual-reviewed. Then ONE dependency-complete cut, proven by publishing and adopting on a branch of the live store
before it lands.

**S1 (landed `33fb994f2`).** Optional type key; producer stamps and render/recording readers moved.

**S1b Repair (a correction, not invisible).** (1) `seon.error/prepare` retypes the converted observation to its final
declared schema (`:seon.db.write/error`, `:seon.schema/error`) before revalidation and before returning it as the
source the writer stores; (2) convert the missed metadata reader and stamps: `test/seon/error/declared_identity_test.clj`,
`test/seon/cluster/turn_test.clj` (four sites), `test/seon/turn_loop_test.clj` [V at `33fb994f2`; re-grep]; (3) stronger
assertions: the immediate return, stored occurrence and `latest-fact` name the stored variant and validate against it
(`schema-refusals-are-admitted-at-the-recorder`, `complete-error-children-validate-through-the-writer`), and the
provider-failure, phase and opening fixtures assert the recorded variant. References:
`malli/src/malli/core.cljc:1861-1905`.

**P1 Accrete schemas.** Variant wrappers by script, `:seon.error/malformed-error`, `:seon.error/exception`, the five
Malli report variants with their render pairs, `:seon.error/digest`/`caller`/`arm`/`arities`, the row members of §6,
`:seon.task/errors`, `:seon.message/error`. The eval/call `error-signatures` are loop P0's. Nothing reads them; the
active base is not tightened. Proven by publish+adopt on a live-store branch. References:
`malli/src/malli/registry.cljc:17-96`; `malli/docs/reusable-schemas.md:116-120`.

**P2 Constructor arities.** `[type operation message]` and `[type operation message data]` on the existing `error`,
still producing at/layer; `fail!`. Invisible: no caller uses them yet. Test: T8, T12 (they reach the arity directly).

**P3 Type producers, by owner family (BEHAVIOR CHANGE, declared per family).** Each family converts its sites to the
new arities with named variants. Typing changes which render pair is chosen and the recorded signature, so each family
slice states its render and signature change and lands with its regression. Test: T1 on `my.fs/read`; per-family `are`
rows of input to type. References: `malli/src/malli/core.cljc:203-207`; `datahike/src/datahike/db/transaction.cljc:27-32`;
`clj-reload/src/clj_reload/core.clj:367-381`.

**P4 Pure calculations, unwired.** Functions with explicit observed inputs (§5): report conversion,
the signature (after structural identity is installed), the row-writer's transaction calculation.
The active `resolution`, recorder and report stay wired until
the cut. Tested on values (T3, T9) and at the REPL on the lane's own SCI branch. References:
`malli/src/malli/core.cljc:2210-2240,3166-3187`; `malli/src/malli/error.cljc:332-390`; `malli/src/malli/util.cljc:128`;
`datahike/src/datahike/db/transaction.cljc:1172-1173`.

**Cut.** Together: the two-argument arity goes with every caller; base requires type, operation, message and drops
`at`/`layer`; `error?` and every `(:seon.error/at x)` predicate; `caught` at every entrant of §3, with identity membership in the existing chain walk; the report adapter and
attribution; the row writer, signature, row schema (`:seon.error/signature`'s row-schema `:seon.error/error` rewritten
to §6's members), task/message opening, receipts carried through the shared settlement owner (agent-loop P1),
resolution; every reader of recorded-error associations converted to carried signatures, including
`seon.render.transcript` and the MCP reply (`seon.mcp/observed`, over the receipts the settlement saved), keeping their run grouping;
`turn.batch/reply` carries error maps with their receipts and handles, not their messages; render selection by type.
Root resets default once after it lands: recorded errors are not migrated. Test: T1-T12 plus the kept list of §8.
References: all of P1-P4.

**After the cut** (each a declared change where output differs): **S-reads**, one read family at a time, its refusal
branches `fail!`, its rethrows and `[:or … :seon.db/error-result]` narrow, contracts and callers in one commit
(References: `datahike/src/datahike/api/impl.cljc:30-42`; `datahike/src/datahike/pull_api.cljc:541-555`). This also
ends error maps consumed as data by seq functions (live run 1: a refused `seon.db/q` became map entries under
`sort-by`). **S-renders**: `render-ai`/`render-html` over the map, prose renderers in `seon.error` deleted, golden
transcripts compared in estimated tokens (References: `clerk/src/nextjournal/clerk/viewer.cljc:1470-1497`;
`fipp/src/fipp/engine.cljc:10-46`). **S-delete**: §5/§6 spans and their tests; `seon.fault`'s remainder folds into
`seon.error`. The constructor moves into `seon.error` once its load-cycle consumers take it as an argument.

Estimated sizes after: `error.clj` 2838 → ~450; `error/refusal.clj` 787 → ~330; `fault.clj` 453 → 0 (recorder ~140 in
`seon.error`); `instrument.clj` 1326 → ~790; rethrows and literals ~1,100 → ~250; `seon.error.edn` 458 → ~140. About
5,000 lines out, 2,000 in.

## 8. Tests

One behavior each, asserted on data:

- **T1 returned error**: `my.fs/read` of an absent path returns `:my.fs/not-found-error`, valid as `:my.fs/tool-error`.
- **T2 thrown error reaches the reply**: an SCI form calling a function that `fail!`s gives a reply error equal on type,
  operation and message to the thrown `ex-data`, with a chain and a result handle.
- **T3 report kinds**: an `are` table over input, output, guard, arity and schema-at-call giving the kind, the blamed
  member and digest (caller for input/arity, callee for output/guard) and, for input, a problem at `[0 :my.fs/pth]`
  suggesting `[[0 :my.fs/path]]`; top-level calls with no caller omit unavailable attribution; schema-at-call keeps its exception in the chain; a reporter failure is
  `:seon.error/exception` carrying the original kind.
- **T4 references**: an error whose data holds a connection carries its identity, not the object; its stored shown
  text prints an opaque value and an unrealized sequence by class without realizing it.
- **T5 recording**: the same failure twice gives one row, count 2, no task, one message to root; both evaluations
  carry the signature. Mixed-type problem paths do not throw, and insertion order does not change the signature.
- **T6 unrecordable**: a refused recording write runs the stop request and prints (`fault_test.clj`'s case, renamed).
- **T7 undeclared variant**: a returning function answering a type outside its `:multi` is an output refusal naming the
  type.
- **T8 malformed thrown variant**: `(fail! :my.fs/not-found-error 'example/f "m")` throws `:seon.error/malformed-error`
  whose problems name `:my.fs/path`; the exception message equals the constructed map's message.
- **T9 chain and primary**: a two-link cause cycle terminates; distinct equal Throwables both remain; a plain exception with a suppressed typed cleanup error
  stays `:seon.error/exception` with the cleanup error in its chain.
- **T10 resolution**: a new digest without a green run stays open; a green run after it resolves; deleting the producer
  resolves; empty proofs (a task with no proofs after a digest change) and refused or stale evidence stay open.
- **T11 recurrence**: a failure after resolution opens one new message.
- **T12 registry failure**: an error built while schema lookup throws is a literal, with no recursive explain.

Kept, adapted: `refusal-test` (cleanup failure beside the primary, worlds referenced, wrapped cause recorded whole);
`instrument-test` (guard armed in JVM and SCI, deadline reported as the bound, reporter failure not relabeled, output
refusal names its function); `attribution-test` (blame follows the producer, batch failure names the visible
definition); `fault-test/an-unknown-write-outcome-stops-and-throws-without-retry`;
`error-test/a-fault-site-survives-recompilation-and-moved-lines`. Concurrent repeats are a concurrency test: root decides
its form. Deleted with their machinery (triage in S-delete): most of `error_test.clj`, the frame and
construction-refusal cases of `refusal_test.clj`, `refusal_grammar_test.clj`, violation sentences in
`instrument_test.clj`, `db_error_test.clj`, `error_result_test.clj`.

## 9. Owner decisions (2026-10-07)

1. One map: type (`:seon.error/type`), operation, message; Malli's own keywords type instrumentation errors.
2. Errors are validated in the ONE constructor against their named variant with Malli's cached explainer, covering
   both carriers; returning functions' `:multi` output contracts restrict allowed variants; generic boundaries accept
   `:seon.error/base`. An error stays buildable when the registry fails: no recursive explain while reporting.
3. Returned when answering is the job, else thrown in `ex-info`; taken back once at the boundary.
4. Instrumentation from Malli's report; refusal-path spelling suggestions against `mu/closed-schema`.
5. No fault concept: Seon's own failures are errors, recorded once per signature (64 characters) at the task home with a
   count, the first occurrence's shown text, the latest producer digest and time, and one notification; no task until an agent or root files one. Every
   occurrence is attached to the evaluation or call where it happened. A deleted producer's error resolves on deletion;
   otherwise resolution = the producer changed AND the task's success tests have a green run since. No escalation,
   stewards, folding or branch-to-cluster copy.
6. No `:seon.error/at` on the map. Recorded errors are not migrated: default is reset after each cut that changes them.
7. Work is cut as preparatory slices, then one dependency-complete cut proven by publish+adopt on a live-store branch.
   Loop P0-P1 (schema, shared settlement owner) land before this cut, which moves the settlement's error duty from the
   temporal window to carried receipts; this cut lands before the loop cut.
8. Results are runtime only: an error's stored form is its shown text and ids; every error in a reply has a result
   handle while its live value exists.
