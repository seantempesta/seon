# Review round 2: Clojure reference guide and CLAUDE.md (reviewer: opus)

Date 2026-10-07. Read-only; no JVM, no tests run. Source checked at HEAD `33fb994f2` plus the working tree. All 20 pins
quoted in `data-oriented-clojure/SKILL.md:25-29` match both the gitlinks (`git ls-tree HEAD reference-code/`) and
the checkouts (`rev-parse --short=10`). Abbreviations: **D** = `.agents/skills/data-oriented-clojure/SKILL.md`,
**T** = `.agents/skills/clojure-testing/SKILL.md`, **R** = `docs/seon/architecture/reference-code.md`,
**C** = `AGENTS.md` (= `CLAUDE.md`). Rulings (a)-(f) are the owner rulings in the assignment.

Verdict: the guide is much better than round 1. Every reference-code citation I opened shows what it is cited for,
and the three test examples and the decomposition are correct as written. Before this is made mandatory, fix five
things: one rule that contradicts the errors spec (`Throwable->map`), one worked example that contradicts the spec
(the tool's top catch), the task-reference mechanism (it describes an index that does not exist and leaves out the
`:seon/tasks` link that completion depends on), and two CLAUDE.md rewrites that contradict ruling (b) and the
agent-loop spec.

---

## HIGH

### H1. "Read the chain with `Throwable->map` at the boundary" contradicts the errors spec, and the EQUIVALENT label is false

Where: D:313-324 (§3.3 law and the `throwable-signature` rewrite), D:659 (quick-table row, "EQUIVALENT result"),
R:37 ("The owning boundary reads `(:via (Throwable->map t))` once").

Evidence:
- `docs/seon/specs/errors.md:67-71` says `Throwable->map` "is not used on the throwable itself". It follows
  `.getCause` with no seen-set and ignores suppressed throwables. `fault!` attaches suppressed failures, and
  `refusal_test.clj:218-245` exercises a cycle.
- `reference-code/clojure/src/clj/clojure/core_print.clj:494-497` builds `via` with `(recur (conj via t) (.getCause t))`
  and has no seen-set, so a cause cycle never terminates.
- `src/seon/test/runner.clj:83-88` `throwable-signature` returns at the first link that carries a signature. On a
  cyclic chain with an early signature, the original terminates and the rewrite loops forever. So the rewrite is not
  EQUIVALENT in result.
- The cycle-safe owner is already installed: `seon.error.refusal/cause-links` (`src/seon/error/refusal.clj:181-190`)
  walks with an identity seen-set and stops at the first repeat.

Both round-1 reviewers proposed `Throwable->map` "once at the boundary" (Astra M5). The revision adopted it, but the
reviewed spec rules it out. This is the one round-1 suggestion that should have been rejected.

Replace D §3.3's second and third sentences with:

> Read a cause chain once, at the boundary that owns the error, with the one cycle-safe walk
> (`seon.error.refusal/cause-links` today; `links`, which adds suppressed edges, after the errors cut), and pass the
> derived chain on. Do not call `Throwable->map` on the throwable: it follows `.getCause` without a seen-set and drops
> suppressed throwables (errors spec §1). Its `:via` link *shape* is what each link takes.

Replace the SEON paragraph with:

> SEON: `seon.test.runner/throwable-signature` walks `ex-cause` in its own `loop` and never terminates on a cause
> cycle that carries no signature. Rewrite (BEHAVIOR CHANGE: a cycle now terminates; the same result on every acyclic
> chain): `(some (comp :seon.error/signature ex-data) (refusal/cause-links t))`.

The load order of `seon.test.runner` → `seon.error.refusal` is unverified. Quick-table row D:659 becomes:
"an `ex-cause` loop | `(refusal/cause-links t)` | BEHAVIOR CHANGE: terminates on cycles (3.3)". In R:37, replace the
`Throwable->map` sentence with "The owning boundary walks the cause chain once with Seon's cycle-safe walk and passes
it on; `core_print.clj:473-507` shows the link shape, not the walk."

### H2. CLAUDE.md "Derive work and completion" contradicts ruling (b), leaves out ruling (c), and states a target as current

Where: C:291-295.

- "An agent completes by marking its task complete; a task is complete only when its tests prove it" makes
  `complete!` the success path. Ruling (b) says green tests are the only success: the loop runs the task's tests after
  a pass that changed code they reach, and `complete!` is only the agent's way to give up on a broken or impossible
  task (reason recorded, task returns to its assigner). The agent-loop spec §4 (`agent-loop.md:231-251`, T7) still
  describes the pre-ruling design, so C copies a superseded spec.
- Ruling (c), that tasks name their success tests before launch or assignment, appears nowhere in C. "Launch lanes
  deliberately" (C, step "Operating") only asks for an issue or spec and evidence.
- "Work is a listened wake newer than the agent's answered basis" and "Context renders live through `seon.cache`" are
  the loop-cut target. `:seon.agent/answered-t` is not installed (P0 adds it, `agent-loop.md:26-31`), and context
  capture still runs today (`agent-loop.md:255-263` lists it for deletion). Neither is marked as a target.

Replacement:

> - **Derive work and completion.** Work is a listened wake the agent has not answered; a message subject is literal,
>   `:seon.message/from` marks inside wakes; never replay writes or effects. A task names its success tests (deftests
>   carrying its `:seon/tasks` id) before it is assigned. After a pass that changed code those tests reach, the loop runs
>   them; all green is the task's completion and the only success. `my.task/complete!` is the agent's explicit way to
>   give back a broken or impossible task, with its reason; the task returns to its assigner. A listener on task facts
>   decides who is notified. Target (the agent-loop cut): the answered basis is `:seon.agent/answered-t`, and context
>   renders live through `seon.cache`, never stored. Archive agents; preserve task and agent identity on loud,
>   resumable budget exhaustion.

Also add to "Launch lanes deliberately": "An assignment names the tests that prove it done; a task without them is
not launched."

### H3. CLAUDE.md "Recover facts": "leaves the database unchanged" is false, and the old loop's recovery law is gone

Where: C:286-290.

- `agent-loop.md:159-163` (Effects) and T8 (`:345-346`) say that forms run with the agent's real connection, so a
  message, task or file edit is durable when the form runs, before settlement. A crash loses the loop's bookkeeping
  and "never a completed effect", and the next pass "may repeat an effect unless that effect's owner is idempotent".
  C's new sentence, "A crash before settlement ... leaves the database unchanged", says the opposite. An agent that
  trusts it will assume a crashed pass is free to redo.
- "A pass" is target vocabulary. Today runs exist and boot recovery `recover-tx` still closes them
  (`agent-loop.md:255-256,279`). The deleted law "Close interrupted runs and mark unfinished evaluations" is still
  the installed reality, and nothing marks the new text as a target.

Replacement:

> - **Recover facts, not execution.** Never resume interrupted execution or serialize private objects or live
>   results. A pass reads and evaluates every form, then writes its settlement as ONE synchronous transaction, durable
>   before it answers, under 100 ms; no fire-and-forget write, so the next call sees it. A crash before settlement
>   loses that pass's bookkeeping (evaluation rows, call row, answered basis), never an effect a form already
>   committed; the work stays due, so an effect is repeated unless its owner is idempotent. Until the agent-loop cut,
>   boot closes interrupted runs and marks their unfinished evaluations. Everything with consequences beyond the
>   form (…unchanged…) is likewise durable before its caller is answered.

### H4. The worked error flow's top catch answers any typed error, which contradicts the spec's rule

Where: D:362-364.

`(defn- error-of [e] (let [d (ex-data e)] (when (:seon.error/type d) d)))` returns every thrown map that carries a
type. After the cut, a contract refusal (`:malli.core/invalid-input`, `errors.md:197,227`) and a
`:seon.error/exception` also carry a type. The tool would therefore *answer* them, and its `:multi` output contract
would then refuse with an output refusal blaming `my.fs/read` (T7). That loses the original blame. `errors.md:121-131`
says the tool "answers only its own declared variants" through `tool-error?`, a cached validator of its `:multi`, and
"A contract refusal or core defect is not in the tool's union and propagates."

Replace step 3 with:

```clojure
;; 3. The returning tool's top catches its own declared variants once; everything else propagates.
(def ^:private tool-error? (m/validator :my.fs/tool-error {:registry registry}))   ; once, not per call
(try (read-opened request ...)
     (catch clojure.lang.ExceptionInfo e
       (let [d (ex-data e)] (if (tool-error? d) d (throw e)))))
```

`registry` is the owner's projection registry. How a tool obtains it is unverified. Name the installed accessor
(for example the `schema/projection-validator` that `seon.fs.jvm-test` uses) rather than inventing one.

### H5. Task references describe an index that does not exist, and leave out the `:seon/tasks` link completion runs on

Where: C:126-127 ("reference a new task in that var's docstring"), C:244-249 ("A backticked span ... is a reference
(a task's is `ns/task-name`): the index resolves it into the subject's context; one resolving to nothing is a
problem on that var"), D:563-567 (§9.3-9.4), D:643-646 (§13.1), D:685. T has no word on how a test names its task.

Evidence:
- `src/seon/fn.clj:600-660` (`task-links`) indexes the metadata keys `:seon/tasks`, `:seon/must-read` and
  `:seon/see-also`. It ignores docstrings.
- `seon.task/task-edges` (`src/seon/task.clj:240-255`) derives a task's subjects and its **proofs**, the deftests
  that carry `:seon/tasks`. I found no code that parses backticked spans in docstrings (`rg` for doc-reference and
  backtick parsers over `src` found nothing).
- `src` already has 43 `:seon/tasks` uses. Example: `seon.db/write-owned-values-error` carries
  `{:seon/tasks #{"4bb788513862"}}`.
- `test/seon/task_ingestion_test.clj:26,125` shows the installed deftest form, `(deftest ^{:seon/tasks #{"<id>"}} name ...)`.
  A metadata map placed after the name is refused (`:metadata-after-test-name`).
- Under rulings (b) and (c), these deftest links ARE the success criterion. An agent that follows C or D writes
  `` `seon.turn/some-task-name` `` in a docstring. That links nothing, its task gets no proof, and the agent can never
  complete the task.

So C:244-249 states a target as current, and §9.3/§13.1 point agents away from the mechanism that works.

Replacement for C's sentence:

> A definition or test names its tasks with `:seon/tasks #{"<task id>"}` metadata (on a deftest, `^{…}` before the
> name); the index links them, and the deftests naming a task are its proofs. Target: a backticked fully qualified
> name in a docstring is a reference the index resolves into the subject's context; until that index lands, it is
> prose.

C step 5: "...otherwise create a task and name it in that var's `:seon/tasks`."

D §9.3: mark the backtick reference as TARGET and add §9.4: "Link a task with `:seon/tasks`; a known issue under
`Known issues` names the same task."

Add a T §2.12:

> **2.12 A test that proves a task names it.**
> `(deftest ^{:seon/tasks #{"023acee5f88d"}} a-refused-settlement-is-the-forms-refusal …)`. The deftests naming a task
> are its success criterion (`seon.task/task-edges` `:proofs`). A task names them before it is assigned; all green
> after a pass is completion.

---

## MEDIUM

### M1. The "fault" vocabulary survives in C and D (ruling a)

Where: C:36 ("recorded as a fault"), C:89 ("a fault without a linked task"), C:262 ("records the fault"), C:273 ("Core
faults are recorded"), C:290 ("faults"), D:368 ("the recorder upserts one fault per signature").

The errors spec, which D calls binding (D:264), keeps "Fault recording" (§6), `:seon.fault/recorded`, the
`:seon.error.fault/*` attributes and the `:seon.task/faults` link. Ruling (a) retires all of them.

Replacements:
- C:36: "...and recorded as an error".
- C:89: "a recorded error without its task".
- C:262: "records it".
- C:273: "Seon's own errors are recorded once per signature, with a count, a task and one notification, at the task
  home, and delivered as data...".
- D:368: "and the recorder upserts one row per error signature (count, task, one notification)".
- Add to D:264: "Owner rulings after the specs override them: there is no separate fault concept; a recorded error
  whose producer is deleted resolves on deletion (the errors spec §6 still says otherwise)."

### M2. C "Nothing crashes" makes the target law current, and forbids the catches the spec requires

Where: C:257-263.

- "every other function throws the same map inside `ex-info` and declares only its success value" has no carve-out
  for reads. Today reads return refusal values (`errors.md:135,319-321`; 119 copies of
  `(throw (ex-info (:seon.error/message …`, 150 `:seon.db/error-result` mentions in `src` plus `resources/seon/schemas`).
  D §3.2 correctly calls the unwraps "load-bearing until errors S-reads". C, which every agent reads first, does not.
  This is Astra H4 / Sol H3 again, now at the level of the law.
- "lists exactly those variants as one `:multi`" reads as current. The installed `:my.fs/tool-error` is an `[:or …]`
  that also includes `:seon.effect/boundary-error` (`resources/seon/schemas/my.fs.edn:166-167`).
- "No layer catches only to convert, wrap or rethrow" forbids two things the spec requires. The returning tool's one
  top catch converts thrown to returned and rethrows the rest (`errors.md:121-131`). A deliberate translation is
  "`fail!` with the cause" (`errors.md:144,163`).

Replacement for the middle sentences:

> A function whose job is to answer (`my.*` tools, validators, merge checks, gates) RETURNS its error map and lists
> exactly its variants (target: one `:multi` on `:seon.error/type`); every other function throws the same map inside
> `ex-info` and declares only its success value, except reads, which return refusals until errors S-reads converts
> each read family with its callers. The one catches are: a returning tool's top catch of its own declared variants,
> a deliberate translation that names its own type and keeps the cause, and the entrant boundary, which takes a
> thrown map back once, turns any other exception into an error map, records it and delivers it as data beside
> whatever value exists.

### M3. C still describes the turn model in three places the rewrite now contradicts

Where: C:159 ("each turn reacquires from the branch head"), C:320-322 ("the refresh before a turn re-renders it when
they change"), C:326 ("The reply the agent saw is its stored history, and history is never re-rendered").

The rewritten C:292-293 says context is "never stored or refreshed by system turns". Agent-loop ruling 7
(`agent-loop.md:383-385`) says the call stores its basis, not the prompt, and history units re-render on a cache miss,
for example after a reload changes the loaded identity (`:205-211`). C:335 ("Save exact shown text ... never clip ...
historical units again") is consistent only if "shown text" means the evaluation's shown value, not the prompt.

Replacements:
- C:159: "each pass reacquires from the branch head".
- C:320-322: "Its database reads are captured as read evidence, and the render is cached by content, so a changed
  read re-renders it."
- C:326: "A render is cached by the value's content digest, so nothing renders twice. Target (agent-loop cut): a
  model call stores its basis, not its prompt; history units re-render only when their content, profile or loaded
  printer changes."

### M4. The quick-table `pull-many` row is not EQUIVALENT

Where: D:669.

`seon.db/pull-many` (`src/seon/db.clj:3051-3075`) answers `[:or [:vector …] :seon.db/invalid-read-error
:seon.db/pull-budget-error]`, so one malformed id or a budget overrun refuses the whole batch. N single pulls refuse
per element and keep the rest. Datahike's `pull-many` (`pull_api.cljc:541-545`) also says "Malformed refs and lookup
attributes without uniqueness remain errors" for the call. A lazy `map` against an eager vector is a second difference.

Label: "EQUIVALENT when every id is a well-formed entity id and no read is refused; otherwise one refusal for the
batch (BEHAVIOR CHANGE)".

### M5. §3.4 and the worked flow give targets the force of law and leave out the member rename

Where: D:326-336, D:351-356.

- §3.4 is a **Law** whose example is a `:multi`. The installed union is `[:or …]` (see M2). Add "(TARGET for
  existing tools: P1/the cut. A new returning function may declare a `:multi` now only if every variant it answers is
  built by `seon.error.refusal/error`, which adds the type; this is unverified at the REPL)."
- The worked flow's variant `[:and :seon.error/base [:map :my.fs/path]]` follows the spec (`errors.md:55`), but the
  installed variant member is `:my.fs/not-found` (`my.fs.edn:120-121`), as D:288 itself notes. Moving to `:my.fs/path`
  is a BEHAVIOR CHANGE for every reader of `:my.fs/not-found`. Say so beside step 1.
- D:254 says the constructor "takes four positionals ... on purpose". That signature is the TARGET; the installed
  constructor takes two (`refusal.clj:743-787`). Label it.

### M6. A refinement the notes claim was made is missing (Astra round-1 M2, `debug-diagnostic`)

The notes say the call "still needs its named variant, stated". D:259-260 still reads "the function becomes one call
to the error constructor (§3)" with no variant caveat. Append: "...once `:seon.render.web/request-error` is a named
variant that carries these members (P1); until then it is a TARGET."

### M7. The guide does not say how later rulings rank against the specs it calls binding

D:11-12 and D:264 bind the errors and agent-loop specs. Both specs contradict rulings (a), (b) and (e):
`errors.md:241-278` (faults; "No proof, no resolution" for deleted producers) and `agent-loop.md:231-251` (complete!
as success). Ruling (d) is also still listed as an open question at `agent-loop.md:392`. Add one line under D's
header: "Where an owner ruling postdates a spec, the ruling wins; flag the spec section." The specs themselves need
revising; that is out of this review's scope.

---

## LOW

- **L1.** D:203-205: `seon.render.web` already requires `[seon.db :as db]`, `[seon.render.value :as value]` and
  `[seon.sci.admit :as admit]` (`src/seon/render/web.clj:62,74,81`). Only `seon.cluster`, `seon.agent`,
  `seon.turn.loop`, `seon.sci.eval` and `seon.config.eval` need `:as-alias`. The snippet also renames the parameters
  (`ctx db conn`); say so. The body itself is correct: all seven members match `web.clj:691-702`.
- **L2.** D:244 says `select-keys` "uses a not-found sentinel". It tests entry presence with `RT.find`
  (`core.clj:1566`). The conclusion is right; the mechanism is not.
- **L3.** D:558: the `seon.error/message` docstring rewrite skips the transition-explanation step that D:510-511
  correctly lists (`src/seon/error.clj:114-119`).
- **L4.** D:600-610: the GOOD `call-reach` example's own docstrings (`src/seon/render.clj:847-857,878-884`) carry
  dates, measurements and a `file:line`, which §9.2 forbids. Say "the body is the example".
- **L5.** D:535-536: babashka `*defaults*` is a dynamic Var whose docstring recommends `alter-var-root`
  (`process.cljc:215-220`). That conflicts with §8.3 and C's Var laws. Prefer `:program-resolver` passed in `opts`, or
  another function-argument example.
- **L6.** D:443-446: `seon.turn/require-open-run` is run machinery that the agent-loop cut deletes, so its "after
  S-reads" clause will never apply. Pick a read unwrap that survives, for example in `my.program/caller-data`.
- **L7.** D:346: the private `-levenshtein` is at `error.cljc:256-260`; `:245-259` is mostly its helpers.
- **L8.** R:59,62,69 (unchanged rows in an edited table) cite drifting first-party lines: `registry.clj:178` (`branch!`
  is at `:209`), `db.clj:4050` (not the validator, which starts at `:4082`) and `sci/eval.clj:841,1100,2218`.
  Replace them with function names, per D:30.
- **L9.** T:184: example 1 declares `(ns seon.id-test …)`, an existing namespace. Say "added to `seon.id-test`" so
  nobody copies the `ns` form over it.
- **L10.** D:371: `(my.fs/read {:my.fs/path "absent.md"})` through `agent-value` depends on the agent's filesystem
  policy root. The existing T1 test (`test/seon/fs/jvm_test.clj:97-103`) calls the handler with a temporary root.
  Unverified at the entrance.
- **L11.** D:296,303,336,406: the counts (225, 119, 151, 45) will drift. Prefix them with "at `33fb994f2`". My
  recount: 225 `diagnostic` calls, 119 rethrows, 45 exemption copies, 150 `:seon.db/error-result` mentions in `src`
  plus `resources/seon/schemas`.
- **L12.** C:241-262 deleted "a contract alone does not prove a body's error union — that needs its producer, checker
  and regression". T §1.1 keeps the substance, but the law no longer says it. Consider restoring one clause: "a
  contract is not proof of a body's error union; its regression is".

---

## 1. First-round HIGH/MEDIUM findings

| Finding | Status | Evidence |
|---|---|---|
| Astra H1 / Sol M1 (select-keys, first/filter, update-vals, seq) | Resolved | D:33-52, D:243-251, D:462-471, D:656-664 |
| Astra H2 / Sol H4 (debug-turn-request, settlement-projection) | Resolved | D:207-215 has all seven members (aliases: L1). D:478-494 is correct, and its added list-vs-vector precondition is real (`turn.clj:189-209`). |
| Astra H3 / Sol H1-H2 (test deletion policy) | Resolved | T:15-35, T:138-172 |
| Astra H4 / Sol H3 (removing unwraps early) | Resolved in D, regressed in C | D:298-311, D:657; C:257-263 (M2) |
| Astra H5 / Sol H5 (quota writer) | Resolved | R:46 |
| Astra H6 / Sol H6 (dropping buffer, socket bytes) | Resolved | R:50,75; `server.clj:345-350` and `ServerAtta.java:44-66` verified |
| Astra H7 / Sol M5 (global state, bounds vs exit) | Resolved | T:284-293 |
| Astra H8 / Sol M2 (merge seam, pull limit nil, `scan` purity) | Resolved | R:61,64,47; D:185-187; `versioning.cljc:738-756`, `writing.cljc:883-911`, `pull_api.cljc:16` verified |
| Astra M1 (`scan` pure) | Resolved | D:175-187 |
| Astra M2 / Sol M4 (counter-examples) | Mostly resolved | `debug-diagnostic` caveat missing (M6) |
| Astra M3 / Sol M3 (absolute style rules) | Resolved | converted to review prompts in §1.1, 2.2, 2.5, 6.1, 7.2, 10, 11 |
| Astra M4 / Sol M6 (contract brevity, `::interrupted-warning`) | Resolved | D:392-403; `:seon.turn/missing-results` is registered (`seon.turn.edn:138`) |
| Astra M5 / Sol M6 (messages, closed-schema refusal path) | Resolved, but the adopted `Throwable->map` advice is wrong (H1) | D:338-346 |
| Astra M6 / Sol M8 (test examples) | Resolved | T:39-41, 74-76, 84-89, 118-121, 134-136 |
| Astra M7 / Sol M9.5 (real Seon setup) | Resolved | T:174-253. All helpers exist with the signatures used: `with-database` `test_support.clj:857`, `fork-cluster-ctx` `:319`, `agent-value` `:437`. Examples 2 and 3 faithfully reduce `reset_edges_test.clj:72-83` and `admit_test.clj:528-542`. |
| Astra M8 / Sol M7 (area table framing, missing rows) | Resolved | R:26-33,40-44 |
| Astra M9 (stale seams) | Resolved | R:63-64 |
| Astra M10 / Sol M9 (missing coverage) | Mostly resolved | re-keying has no worked example (D:233-241, prose only); positive-proof (ex. 2), named schema (D:401-433), decomposition (D:73-171) and cache key (D:600-610) are present |

Wrongly rejected: none of the rejections in the notes is wrong. Not citing Sol's `db.clj:5913-5915` is fine, because
it was unverified. One finding was wrongly *accepted*: `Throwable->map` at the boundary (H1).

## 2. Citations changed or added this round

I opened every library citation in the round's diff (≈90 ranges): clj-reload, clojure (core, core_print, set, test,
string test), malli (core, error, util, registry, docs, core_test), datahike (transaction, api/impl, specification,
pull_api, versioning, writing, gc, query, db, datom, lookup_refs_test), sci (core, interpreter, utils, interrupt.md),
nrepl (interruptible_eval, QuotaBoundWriter), edamame, editscript, konserve, kaocha, babashka-process, core.async
(flow, flow/impl, async, flow_test), clj-kondo, rewrite-clj, hyperlith, http-kit, datastar-clojure, langchain4clj,
clerk and fipp. Each shows what it is cited for, with these exceptions:

- `core_print.clj:473-507` is cited as the walk to use. It is the walk the spec rejects (H1).
- `core.clj:1559-1571`: wrong mechanism named (L2).
- `error.cljc:245-259`: range slightly off (L7).
- `process.cljc:215-221`: a weak example (L5).
- R's first-party line numbers are stale (L8).

Checks I made beyond opening the ranges:
- `topo-sort` (`parse.clj:163-175`) is pure. `scan` (`core.clj:206-229`) is not.
- `unload`'s `swap!` calls are at `:313` and `:322-325`.
- clj-reload `reload` returns `:exception` where it would otherwise throw (`:367-381`).
- Malli `-instrument-f` continues the call if `report` returns (`core.cljc:2228-2241`).
- The `merge-writer!` fence holds only through the arg-map arity of `merge!` (`versioning.cljc:747-750`). The 3- and
  4-arity forms pass no basis. Worth one clause in R:61.

## 3. Worked examples

- **`transact-initialization!` decomposition (D:73-171): correct; EQUIVALENT holds.**
  - The "before" matches `src/seon/cluster.clj:1105-1141`, comments removed.
  - `refused!` always throws (`:262-275`).
  - `entity-ready?` is `every?`, so it returns exactly true or false (`:1100-1103`), and `group-by` keeps input order.
  - `(vec nil)` gives `[]` for an empty bucket.
  - `refuse-round!` returns nil on the transact path, as its contract says.
  - The input is `config/default-population`, whose contract is `[:vector :map]` (`config.clj:388-398`), so
    `[:sequential :map]` admits it.
  - `:seon.db/database-value` and `:seon.db/connection` are registered names.
  - Caveat: the new contracts are armed on first load. Arming adds validation work and a new refusal path, so strictly
    the "after" has a different failure surface. The label is acceptable because D:169 says the contracts are new and
    must be compiled.
  - Would compile as written: yes, judging by reading.
- **Three minimal tests (T:179-253): correct; all would run as written inside a `seon.test/run` member.**
  - Example 1: `seon.id/id` hashes the printed data (`id.clj:95-115`); "abc" → `ba7816bf…` is the SHA-256 of `abc`;
    `[:a 1]`-style rows and `nil`/`false`, `1`/`1N`, `'abc`/`"abc"` all print differently.
  - Example 2: matches the source shape.
  - Example 3: matches, and its TARGET assertions agree with `errors.md:197,227`.
  - The TARGET labels are right.
- **Error flow (D:348-375):** the TARGET label is right. Step 3 is wrong against the spec (H4). Step 4 uses fault
  vocabulary (M1). The member rename is not flagged (M5).
- **absent/nil/false (D:33-52):** correct. `:seon.fn/inline? false` is stored on purpose (`fn.clj:776`). The 6.2
  `[false :later]` row and the `select-keys` counterexample are right.
- **Schema registration (D:401-433):** correct.
  - 45 exact copies plus one variant (`seon/fs.clj:67`) confirmed.
  - The target file `resources/seon/schemas/seon.schema.admission.edn` exists.
  - The EQUIVALENT-after-adoption label is right, and the "do not compile until adopted" warning is honest.
- **Cache key (D:600-610):** matches `src/seon/render.clj:878-889` verbatim. Its docstring conflicts with §9.2 (L4).
- **Other rewrites:**
  - interrupted-warning (D:392-399): correct; both keys are registered (`seon.cluster.eval.edn:42,48`).
  - settlement-projection: correct.
  - turn-function-result probe count: correct (`web.clj:675-680`).
  - db delays (5 + 3): correct (`db.clj:53-68`).
  - caller-data has 5 unwraps: correct.
  - `my.test/run`: correct; it is a macro over `run-owned` (`src/my/test.clj:57-75`).
  - examples runner description: correct (`test/my/examples_test.clj:172-200`, a working-tree file someone else is
    modifying).

## 4. CLAUDE.md edits, sentence by sentence

| Edit | Judgment |
|---|---|
| Dual review (C:109-110) | Matches ruling (f). Fine. |
| Fix what you touch (C:126-127) | The task mechanism is wrong (H5). Otherwise fine. |
| Documentation is data (C:244-249) | The index is not installed, so this is a target stated as current (H5). "No dates, measurements, history or `file:line`" is a clear law, but existing code (including D's GOOD example) violates it; that is acceptable under §13 fix-what-you-touch. |
| Grounded vocabulary (C:251-254) | Removing "turn and evaluation" is right for the target. Today turns exist, which is harmless. Consider adding "fault" as a retired term (ruling a). |
| Nothing crashes (C:257-263) | Target as current, no read carve-out, and it forbids catches the spec requires (M2). |
| Errors one shape (C:264-272) | Good. Installed vs target is marked. "For both carriers" is true only of the installed `error` plus `ex-info`. Clause lost (L12). |
| Recover facts (C:286-290) | False against the spec, and an unmarked target (H3). |
| Derive work and completion (C:291-295) | Contradicts ruling (b), leaves out (c), unmarked target (H2). |
| Make bounds honest (C:374-375) | Matches the installed `:seon.test/long` reason and `:seon.test/long-ms` (`seon.test.edn:15,24,29`). Fine. |
| Stay inside the assignment (deletion) | Fine. The same duty remains in "Prove each slice" and "Report honestly". |

No other law was weakened. The leftover contradictions between sections are M1 and M3. Ruling (d) is not touched by
C or D and causes no conflict. Ruling (e) is contradicted only by the errors spec, which D binds (M7).

## 5. Usability and size

- **Size.** About 19.7k tokens for D+T+R (measured by characters/4: 10.5k / 4.9k / 4.3k), plus 10.8k for C that is
  always loaded. That is about right. Do not grow toward 22-26k. The examples carry their weight; the cost is in
  what will rot.
- **Cut (about 0.6k):**
  - the census counts in D §3 and §4.2 (keep "about N, at `33fb994f2`" once);
  - the key-length statistic in §10.1;
  - §7.2's library-protocol aside (one sentence is enough).
- **Add (about 0.5k):**
  - T §2.12 task-naming (H5), the highest-value missing item under rulings (b) and (c);
  - one line in T §1: "A task names its success tests before assignment; for a refactor, the existing tests that
    reach it";
  - optionally, the `receipt-entry` re-keying example the notes offered.
- **Still vague:** the §1.1 "about 40 lines" prompt is fine. §2.3 "a real boundary" would benefit from the
  `receipt-entry` example. §12.5 is good.
- **Over-absolute:** C's "No layer catches only to convert" (M2) and C's "leaves the database unchanged" (H3).

## Counts

High 5 · Medium 7 · Low 12 (24 findings).

## Files read

`tmp/model-trial/review-guide2-spec.txt`; `tmp/clojure-guide/guide-revision-notes.md`;
`tmp/model-trial/review-guide-trial-astra-guide.md`; `tmp/model-trial/review-guide-trial-sol61-guide.md` (findings
sections); `.agents/skills/data-oriented-clojure/SKILL.md`; `.agents/skills/clojure-testing/SKILL.md`;
`docs/seon/architecture/reference-code.md`; `AGENTS.md` (full diff and the affected sections);
`docs/seon/specs/errors.md`; `docs/seon/specs/agent-loop.md`.

Seon source (relevant ranges):
- `src/seon/cluster.clj`, `src/seon/config.clj`, `src/seon/render/web.clj`, `src/seon/render.clj`
- `src/seon/turn.clj`, `src/seon/db.clj`, `src/seon/error/refusal.clj`, `src/seon/error.clj`, `src/seon/id.clj`
- `src/seon/fn.clj`, `src/seon/task.clj`, `src/my/program.clj`, `src/my/test.clj`, `src/seon/test/runner.clj`, `src/seon/ai.clj`

Tests and resources:
- `test/seon/test_support.clj`, `test/seon/reset_edges_test.clj`, `test/seon/sci/admit_test.clj`
- `test/seon/fs/jvm_test.clj`, `test/seon/ai_test.clj`, `test/seon/task_ingestion_test.clj`, `test/my/examples_test.clj`
- `resources/seon/schemas/my.fs.edn`, `seon.turn.edn`, `seon.cluster.eval.edn`, `seon.test.edn`, `seon.fn.edn`
- `resources/seon/tasks/seon.turn.edn`

Every reference-code range in §2 above.

Not verified: nothing was executed. Load order for H1's `cause-links` call, the registry accessor in H4's validator,
the `:multi` usability claim in M5, and the entrance path in L10 are judged from source only.
