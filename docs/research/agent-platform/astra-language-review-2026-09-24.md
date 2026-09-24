---
type: research
status: source-reviewed; isolated diagnostics; not an installation proof
created: 2026-09-24
---

# Review of Seon's decoration language v0.1

The proposal joins ordinary Clojure metadata, authored task values, and executable completion conditions. Keep that composition; use explicit data for authority and ordinary functions to derive observations. The central defect is treating a mention or namespace relationship as evidence of ownership, prerequisite order, or completion.

**UNVERIFIED — recommendation:** Do not adopt the present inference rules as an admission or merge policy. The notation is promising; its apparent brevity currently hides decisions an agent cannot reliably infer. Preserve `intent`, native names, task-local conditions, separate attempts, and the distinction between requirements and lasting guarantees.

## Evidence and scope

**VERIFIED:** Read AGENTS.md and every requested artifact end to end, including all five real task files. Baseline: `22581f5bfd91ab36bcfc058be2db3b6cd4f545f5`; history inspected: `c3fe4aeb3`, `8c487331c`, `307a30a4b`, `22581f5bf`. Research-relative citations below are relative to `docs/research/agent-platform/`; `resources/`, `src/`, `reference-code/`, and `.agents/` paths are repository-relative.

**VERIFIED** means inspected source, a deduction with an explicit counterexample, or the isolated diagnostic recorded below. **UNVERIFIED** marks proposals, predicted costs, and unproved installed behavior. Prior reports' experiment results are historical claims, not new verification. Requirements themselves distinguish experiment from installation (`language-requirements-2026-09-24.md:10`).

**VERIFIED — live boundary:** Read-only `bin/seon status` and MCP `runtime_status` found default alive, PID 90471, start `2026-09-23T22:18:14.324Z`; no missing readiness layers. They also reported 16 error signatures, 34 errored receipts, one failed run and one unknown failed-test count. Publication is off: the JVM uses archive `ce73846828a5cc32798ef630b5a574f777646f30`. This is not a clean health result. No default code evaluation, restart, reset, publication, branch mutation or Seon test request was performed.

## 1. Elegance: remove hidden decisions before removing keys

**UNVERIFIED — naming and authoring recommendations**, grounded in the fields at `decoration-language-v0-2026-09-24.md:46` and `experiments/tasks_v01.clj.txt:10`:

| Current spelling/rule | Recommendation and reason |
|---|---|
| `id`, `intent`, `done-when` | Keep. A task has a name, an explanation and conditions. Strict boolean conditions are reasonable if documented and checked. |
| Fully repeated `:seon.task/...` | Prefer ordinary `#:seon.task{...}` in files. At a REPL quote the whole task map once; the stored proof forms remain data. No new macro is needed. |
| Mandatory `subjects`, separate `entities` | Make both optional, with at least one meaningful subject/output/condition as appropriate. A question already names its message; requiring the implementation namespace `seon.message` adds no domain fact. Retain separate native symbol, schema-key and entity-identity attributes where storage requires them. |
| `changes` defaults to `subjects` | Rename to `definitions` for the explicitly permitted program declarations; default to no program changes. Being about code must not authorize changing it. Exact schema edits remain separate. |
| Derived `delivers` plus authored override | Delete the competing authority. An explicit proposed declaration set is cheap and reviewable; namespace membership can suggest additions, never grant them. Distinguish program edits from a data `output` schema. |
| `schemas` and derived `keys` | Keep the target-schema map where definitions actually change; derive its key set only in a view. Call that view `schema-keys`, not the ambiguous `keys`. An output's existing schema is a reference, not another definition. |
| `needs` as an “override” | Keep the familiar name for declared prerequisites. Inference may suggest needs. Currently it unions inferred edges, so it cannot override a false edge (`experiments/tasks_v01.clj.txt:68`). |
| `recurs {:every :day}` | Reuse `schedule` and the existing schedule identity. A day is not “nightly”: wall time, zone and firing identity matter. Existing fields already include expression and zone (`resources/seon/schemas/seon.schedule.edn:1`). |
| `evidence` as paths | Keep the name; accept existing observation identities and content-pinned file citations. Path existence alone neither preserves evidence nor ties it to the task revision. Remove the hard-coded checkout root (`experiments/tasks_v01.clj.txt:92`). |
| `context` containing a function name | If retained, call it `context-fn`. “Has proofs” does not imply “implement”: a research or review task also has proofs (`experiments/tasks_v01.clj.txt:56`). |
| Intent at least 20 characters | Drop the character threshold. “Delete obsolete cache.” is precise; twenty filler characters are not intent. Require meaningful reviewed intent, not string padding. |

**VERIFIED — ordinary Clojure is already contradicted:** The ban on every unquoted qualified symbol in value position would reject the scenario's legitimate `(every? seon.task/specified? ...)` (`decoration-language-v0-2026-09-24.md:80`; `experiments/scenario-tasks.edn:16`). That argument is a function value. A bare function symbol evaluates to its value, not its Var; `#'f` denotes the Var. An asterisk naming convention does not prove a Var is dynamic. **UNVERIFIED:** Replace the blanket rule with analysis and the called function's input contract; distinguish “expected a symbol” from “expected a predicate.”

**VERIFIED — reading is not resolution:** Syntax quote resolves against reader context. An alias introduced by the same `ns` form's later `:require` clause is not yet established when that form is read. Full names in the example avoid that problem, but “nothing must load first” overstates the general alias guarantee (`decoration-language-v0-2026-09-24.md:29`; `reference-code/clojure/src/jvm/clojure/lang/LispReader.java:1050`; `reference-code/clojure/src/clj/clojure/core.clj:5821`). Edamame's syntax-quote resolver defaults to identity (`reference-code/edamame/README.md:211`). **UNVERIFIED:** Document full names in namespace metadata and verify normalization through the existing analyzer; do not promise identical raw `ns`/`defn` metadata shapes.

**UNVERIFIED — keep the code decorations small:** `must-read` and `see-also` are good names. Define whether links are unordered or ranked, whether expansion is one hop, and what happens when a required read exceeds the context budget. Code examples use vectors; task schema accepts only a set for `must-read` (`decoration-language-v0-2026-09-24.md:18`; `experiments/tasks_v01.clj.txt:18`). Use one semantic collection contract. A required link requests context; it does not prove the agent read or understood it.

**VERIFIED — unnecessary file authority:** “One file per namespace” cannot naturally place the flow tasks spanning `seon.flow` and `seon.fault`, or the vocabulary task spanning three namespaces (`resources/seon/tasks/seon.flow.edn:3`; `resources/seon/tasks/seon.task.vocabulary.edn:4`). **UNVERIFIED:** Treat filenames as organization only; reconcile by task identity, diagnose duplicate ids, and permit topic-grouped files.

## 2. Four tasks as I would author them

**UNVERIFIED — proposed syntax and semantics, not runnable v0.1:** These remain Clojure data read without evaluation. `done-when` holds ordinary function forms with explicit `[db task attempt]` inputs, subsequently called by the existing evaluation/test owner. This replaces ambient branch/time assumptions and repeated task-id literals. Omitted `definitions` permits no program edits. `output` names an existing schema; outputs must be validated, durable, and linked to this attempt. Predicate signatures below are proposed, including the extra database/attempt arguments; v0.1 does not provide them.

The shared finding schema must be delivered once by a prerequisite platform task, with nonempty claim and resolvable evidence. Likewise the maintenance receipt/query and task-output relation are prerequisites, not conveniences silently assumed installed. All four examples assume that vocabulary has been admitted. A missing vocabulary produces an explicit prerequisite diagnostic.

Question — name the message, and bind acceptance to this answer attempt:

```clojure
#:seon.task{:id :seon.message.task/answer-m-7f3a
 :intent "Explain Tuesday's squat-volume drop from the stored workouts."
 :entities #{[:seon.message/id "m-7f3a"]}
 :done-when [(fn [db task attempt]
               (and (some? (seon.message/reply-to db [:seon.message/id "m-7f3a"] attempt))
                    (seon.task/accepted? db attempt)))]}
```

Decomposition — children are outputs, and root assesses whether they describe the requested tracker:

```clojure
#:seon.task{:id :my.workout.task/tracker
 :intent "Plan a workout tracker: data model, logging and weekly volume view."
 :output :seon.task/task
 :done-when [(fn [db task attempt]
               (let [children (seon.task/outputs db attempt)]
                 (and (<= 3 (count children))
                      (every? (partial seon.task/specified? db) children)
                      (seon.task/accepted? db attempt))))]}
```

Maintenance — reuse a named nightly schedule; require the operation's receipt as well as the bound:

```clojure
#:seon.task{:id :seon.cluster.store.task/nightly-reclaim
 :intent "Reclaim eligible unlinked branches nightly; keep interval growth below 50 MB."
 :subjects #{seon.cluster.store}
 :schedule [:seon.schedule/id "root/nightly-reclaim"]
 :done-when [(fn [db task attempt]
               (and (seon.maintenance/completed? db attempt)
                    (< (seon.cluster.store/growth-bytes db attempt) 50000000)))]}
```

Research — an existing finding schema validates the output; acceptance assesses the explanation and evidence:

```clojure
#:seon.task{:id :seon.fn.task/why-reach-saturates
 :intent "Explain saturated tests-reaching results (1,720 tests; 1.9 s / 6.5 GB) and precise alternatives."
 :subjects #{seon.fn/tests-reaching seon.fn/gate-sets}
 :output :seon.finding/finding
 :done-when [(fn [db task attempt]
               (and (= 1 (count (seon.task/outputs db attempt)))
                    (seon.task/accepted? db attempt)))]}
```

**VERIFIED — physical line comparison:** Count nonblank map lines, excluding fences and scenario comments, without reflowing either version. Original spans are `experiments/scenario-tasks.edn:2`, `:11`, `:19`, `:26`.

| Scenario | v0.1 | Above |
|---|---:|---:|
| Question | 7 | 6 |
| Decomposition | 6 | 8 |
| Maintenance | 5 | 7 |
| Research | 8 | 7 |
| Total | 26 | 28 |

**UNVERIFIED — readability judgment:** Two additional lines buy explicit evaluation inputs, receipt evidence, and acceptance of a particular result. Most visual shortening comes from namespaced maps; v0.1 can adopt that independently. This is not equivalent implementation cost: output validation and attempt-aware vocabulary must exist. The original research schema is moved to its shared owner, not abolished. Original intents can be retained verbatim without changing these physical counts; shorter prose is not the claimed gain.

## 3. Correctness: concrete counterexamples

### A. Namespace ownership is too broad and too narrow

**VERIFIED:** `derive-task` projects each allowed function to its namespace, then claims every missing proof symbol in that namespace (`experiments/tasks_v01.clj.txt:45`). A task scoped only to `my.workout/log-entry`, with a proof calling missing `my.workout/weekly-summary`, thereby claims the summary. That contradicts the exact function scope. Conversely, a new public function explicitly named as the subject is refused by `findings`: its qualified symbol does not exist, and only simple namespace symbols get the exception (`:85`).

**VERIFIED:** A consumer testing that a retired name stays absent, e.g. `(nil? (resolve 'my.workout/old-summary))`, is interpreted as delivering that missing name if its namespace is in scope (`:40`, `:53`). This reverses the intent of a deletion task. A function introduced in a new test/helper namespace is also not owned merely because it is needed by the task. **UNVERIFIED:** Explicit declarations, including retractions, must govern scope; proof analysis proposes context and missing requirements only.

### B. Reference edges do not establish prerequisite order

**VERIFIED:** Every qualified symbol, called or quoted, becomes an edge (`experiments/tasks_v01.clj.txt:40`). Suppose A optimizes existing `pricing/total`; B fixes an invoice renderer and compares `(str 'pricing/total)` with displayed text. B now needs A even though it only prints the name. Two independent tasks modifying the same existing function can also infer needs on one another. A call to an already adequate helper is not a need for every planned change to that helper.

**VERIFIED:** Dependencies through schema keywords are absent from `referenced`. The actual `render-any-subject` task constructs `:my.note/about-symbol`, but its literal keyword cannot derive a need on `about-is-a-value`'s schema change (`resources/seon/tasks/seon.note.edn:4`, `:12`; `experiments/tasks_v01.clj.txt:43`). Transitive calls, `resolve` of computed names, task/data identities and required decisions are also not captured by this symbol scan. **UNVERIFIED:** Declare necessary precedence; show analyzer-derived suggestions separately. The existing analyzer already distinguishes references from call shapes (`src/seon/fn.clj:376`). Even a correct call graph cannot infer the intended product dependency.

### C. “Claimed twice” is neither complete conflict detection nor ordering

**VERIFIED:** Duplicate claims are diagnosed only for missing symbols (`experiments/tasks_v01.clj.txt:77`). The real tasks `about-is-a-value` and `declared-refusals` both claim existing `seon.note/add-note-call`; `render-any-subject` and `one-read-for-many-notes` both claim `seon.note/note-html` (`resources/seon/tasks/seon.note.edn:3`, `:11`, `:19`, `:25`). Multiple planned edits are legitimate, but require explicit ordering, alternatives, or merge-time conflict handling.

**VERIFIED — isolated probe:** The `deliverer` map retains only the last task for a symbol (`experiments/tasks_v01.clj.txt:65`). With A and B delivering `demo/f`, consumer C gets `needs #{:demo/b}` for `[A B C]`, but `#{:demo/a}` for `[B A C]`. Input order becomes semantic authority; `read-dir` does not sort files (`:26`). For a missing name the separate finding can refuse, but it does not repair existing-name ambiguity. **UNVERIFIED:** Index symbol → set of candidate tasks; refuse ambiguous authoritative claims, or present them as alternatives. Task coexistence alone should not be a global conflict.

### D. Red-on-base overgeneralizes regression testing

**VERIFIED:** Every condition must independently be red under the stated rule (`decoration-language-v0-2026-09-24.md:83`). A bug fix commonly preserves an already-green “valid input still works” condition while changing one failing edge case. A refactoring may intentionally preserve all observable behavior; its changed-size or timing condition identifies the improvement. A recurring check may already be healthy, and a setup task may already be satisfied. None warrants refusal merely for green evidence.

**UNVERIFIED:** Require an intended base failure for a claimed behavioral regression; retain green preservation conditions. Distinguish an already-satisfied task, an observational task, an unavailable check and a behavioral failure. Triage should identify which condition demonstrates the defect. “Red after prerequisites are satisfied” is useful diagnostic evidence, not a universal admission law.

**VERIFIED — implementation gap:** `red-on-base` calls a supplied evaluator sequentially, records any exception as `:red-error`, and does not construct a prerequisite-satisfied base or prove the cause (`experiments/tasks_v01.clj.txt:122`). Reusing one evaluator means a writing proof can affect later proofs; each no longer observes the original base. A Datahike branch does not roll back files, network calls or mutable host objects. The branch evaluator's 30-second limit is not proof of effect isolation or actual termination (`:109`). **UNVERIFIED:** Separate action execution from observations, pin inputs, and make independent proof setup explicit through the existing test owner.

### E. The namespace allowance is circular

**VERIFIED:** Missing namespace `typo.workuot` is accepted if any proof mentions `typo.workuot/f`; the same mention derives the missing deliverable (`experiments/tasks_v01.clj.txt:52`, `:87`). That is self-authorization, not evidence of an intentional new namespace. A data-only task can satisfy the namespace exception while `changes #{}` says it creates no code. Conversely, a raised task to create a new namespace, with no proofs yet, is refused because it cannot supply that circular witness. **UNVERIFIED:** Permit unresolved subjects as named observations; require explicitly reviewed additions before implementation, independently of subject existence.

### F. Attempts currently allow false settlement

**VERIFIED — isolated probes:** `attempt` on a task with no proofs yields `results []`, `green? true` because `every?` on empty is true. `states` then reports it settled before checking `raised`. A historical green followed by a newer red also yields settled. Neither task revision, proof digest, branch commit, schedule fire nor acceptance is consulted (`experiments/tasks_v01.clj.txt:145`). One successful Monday therefore settles the nightly task indefinitely, and an old accepted answer can satisfy a revised question unless acceptance names the result/revision.

**VERIFIED — actual authoring mismatch:** The guarantees task's successful `(some #{'seon.note-test/add-then-forget-round-trips} ...)` returns a symbol; `attempt` accepts only `true?`, so success remains red (`resources/seon/tasks/seon.note.edn:30`; `experiments/tasks_v01.clj.txt:151`). Keep strict booleans and wrap with `boolean`, or declare an assertion convention; do not silently accept truthy error maps.

**UNVERIFIED:** Store completed check observations tied to reviewed task/proof content, exercised program/input identities and the particular attempt/fire. Derive current satisfaction from applicable positive evidence. Preserve historical successes without treating them as current. Completion of branch checks and acceptance onto the destination are separate facts; prerequisites must identify which is required.

### G. Validation claims outrun the validator

**VERIFIED:** `findings` checks authored shape, missing names, evidence-path existence and missing duplicate claims only (`experiments/tasks_v01.clj.txt:71`). It does not check duplicate task ids, missing prerequisite ids, cycles, subset scope, the unquoted-symbol rule, proof reach into changed subjects, or validity of authored Malli forms. Both `schemas` values and proof entries are `:any` (`:16`). Invalid forms can also fail during derivation before shape findings run. “Met” for requirements 4.2 and 4.3 is not demonstrated by this reader (`language-requirements-2026-09-24.md:64`). Historical checks elsewhere may exist; they are **UNVERIFIED** here.

**VERIFIED — isolated probe:** A two-task prerequisite cycle is returned by `plan` as `[:demo/a :demo/b]`; the fallback arbitrarily selects the first remaining task instead of reporting the cycle (`experiments/tasks_v01.clj.txt:103`). A missing prerequisite id takes the same path. Duplicate ids are overwritten by `into {}` (`:99`). **UNVERIFIED:** Shape-check before derivation; use Malli `schema` to compile target forms against the candidate registry and `explain` for native diagnostics (`reference-code/malli/src/malli/core.cljc:2550`, `:2659`). Return cycles, dangling identities and conflicting declarations explicitly; never turn refusal into a plausible plan.

### H. Detector and example proofs establish less than their prose

**VERIFIED:** The stale-doc producer compares source/doc transaction positions (`experiments/tasks_v01.clj.txt:138`). If an unchanged docstring remains accurate after a body edit, an honest “reviewed; still accurate” observation cannot clear that comparison. A cosmetic edit can. Empty output from an incorrectly implemented detector proves no review. Returning `#{}` passes the detector task's two type checks (`resources/seon/tasks/seon.task.edn:6`). The earlier scenario explicitly proposed review observations instead (`task-scenarios-2026-09-24.md:117`; `astra-review-self-documenting-system-2026-09-23.md:129`).

**VERIFIED:** The nightly bound can pass without GC running. Decomposition's three children can all be unrelated or duplicate in purpose, and no acceptance checks coverage. The finding schema accepts empty claim and empty evidence, and no condition assesses correctness (`experiments/scenario-tasks.edn:15`, `:23`, `:30`). `about-is-a-value` only checks two scalar schemas; it never exercises note writes or survival after target deletion (`resources/seon/tasks/seon.note.edn:6`). These are inadequate proofs of their intents, not evidence against executable conditions. **UNVERIFIED:** Review the oracle and the provenance of its observations; shape alone is not task success.

### I. Cost and availability must follow the selected world

**VERIFIED:** `exists?` runs up to three queries per symbol then consults JVM `find-var`; identical `(db, symbol)` inputs can change answer when host Vars change (`experiments/tasks_v01.clj.txt:32`). A host Var can survive deletion from a branch's rows or be unavailable in SCI. This is not a pure function of the selected branch despite `derive-task`'s docstring. Repeated existence checks span derivation and validation. `plan` scans remaining tasks each round: a chain of T tasks causes quadratic readiness scans, apart from sorting/edge checks (`:100`). “3–7 ms for eight tasks” is not a scale guarantee (`decoration-language-v0-2026-09-24.md:60`).

**UNVERIFIED:** Pass a captured program/availability view explicitly. Resolve each distinct reference once for that view, retain content-valid analysis, and build adjacency/indegree data once. Expected graph work is O(T+E), with deterministic ordering costs stated separately. Recompute only for relevant declaration/task changes; proof execution cost is separate and bounded. No performance claim is made for this unimplemented change.

## 4. Missing expressiveness versus missing evidence

**VERIFIED — schema limits:** The closed authored map restricts subjects/changes/delivers to symbols, recurrence to hour/day/week, and lacks the relations below (`experiments/tasks_v01.clj.txt:12`). Arbitrary Clojure forms can encode many observations, so “cannot express” here means no first-class authoring/authority/evidence contract; prose or an opaque helper could conceal almost anything.

| Work/fact | Concrete missing distinction |
|---|---|
| Delete code | “Retract this definition, preserve these callers after repair” versus “deliver this missing name.” Disappearance can be success, not automatic obsolescence. |
| Rename | Old/new identity and one atomic caller/schema repair; unrelated deletion plus addition does not express the relationship. |
| Retire/migrate a schema | `schemas` only maps keys to target forms; no explicit retraction, migration/data compatibility condition or schema-key subject domain. |
| Browser/UI proof | Rendered Hiccup is not paint, layout, interaction or accessibility. Need observation identity, screenshot/artifact digest, viewport/runtime and inspected revision (`task-scenarios-2026-09-24.md:228`). |
| Cross-branch work | Base/destination/tested commit, adopted prerequisite result, merge conflict subject, and acceptance identity. A mutable branch name in an attempt is insufficient. |
| Multi-agent comparison | Attempt identity, agent/model, common task/input revision, comparable costs and selected result; `attempt` stores only task, branch, results, green flag. Earlier scenario already identifies these (`task-scenarios-2026-09-24.md:291`). |
| Time-bounded evidence | Observation interval, clock, deadline, sample count, percentile, workload and freshness. A 30-second evaluator limit says none of these. |
| Recurring/mechanical work | Scheduled firing, executed action/receipt, overlap/exit policy, missed-fire policy and current-period completion. Existing scheduling should supply these. |
| Decomposition/review | Authorable parent relation, typed outputs, reviewer/decision identity, alternatives, rejection/supersession and requirement revision provenance. `children` can query a relation the closed map cannot author. |
| Other artifacts/effects | Documentation, dependency pins, files, external actions and their observations cannot be authorized by a set of Var symbols. Name their existing owners and evidence, not invented dummy namespaces. |

**UNVERIFIED — priority:** Add only the facts required by one real slice. Separate task facts, execution requests, observations and decisions; avoid making every optional runtime field an authored task key. Do not revive a task-kind enum merely to patch inference failures.

## 5. One alternative: explicit tasks, observed completion

**UNVERIFIED — design:** Keep metadata and data files. A task authors intent, subjects as observed names, explicit program changes/target schemas or a data-output schema, necessary prerequisites, and ordinary predicates. The task writer validates those declarations. An attempt supplies the selected world and executes through existing owners. Completion is a query over finished observations for that reviewed task revision and occurrence; acceptance remains a named decision over the candidate/result.

This removes inferred ownership, last-writer deliverer selection, compulsory per-condition red-on-base, and “ever green” settlement. It keeps derived backlinks, missing-reference diagnostics, candidate dependencies, schema-key views and plan/state queries. Distinguish *can derive a value* from *have evidence that the value means what we claim*.

**UNVERIFIED — smallest composition and retirement:**

1. Read task data with the existing reader; analyze proof forms with the existing analyzer; compile schemas through Malli against one supplied registry. Cache by task/source/schema inputs. Do not add a parser or registry.
2. Reconcile task declarations through the existing writer; keep attempts and decisions outside that reconciliation. Preserve symbols/keywords natively. A mixed union is not a free storage solution: the bridge can encode it as a string (`src/seon/schema/datahike.clj:116`).
3. Query explicit needs and applicable accepted results. Derive readiness without changing authority. Admit future definitions as obligations, not as already executable program. An explicitly needed branch result must be acquired before its proof can run.
4. Use the existing test/evaluation owner for checks and the schedule owner for recurrence. Retain full error/refusal/unknown distinctions and actual exit. No second runner, scheduler or recovery state machine.
5. Use the existing merge owner and required combined-program gate; proposed deltas and selected observations are review inputs, not permission to bypass it. This is a language recommendation, not evidence that additions, schema publication or write-back now work (`decoration-language-v0-2026-09-24.md:111`).

**UNVERIFIED — acceptance experiment:** Try one existing-function fix with a green invariant, one deletion, and two firings of the maintenance task. Require current result identities, no phantom prerequisites, refusal of a real cycle, and no reuse of yesterday's green for today's failed firing. Measure task read/validation, branch acquisition, checks, recording and memory separately. Only then compare authored tokens and agent completion quality. A shorter map is not yet an elegant language.

## Reproducible diagnostics and landing limits

**VERIFIED:** The following runs unchanged pure function definitions selected from the experiment in an isolated Babashka process. It does not load the experimental namespace, query Datahike, stub Seon owners, or prove SCI/instrumentation behavior. Run from the repository with `bb -e` containing this form; `/usr/bin/time -l` measured the invocation. The evaluator argument for the empty-proof case is never called.

```clojure
(let [forms (read-string (str "[" (slurp "docs/research/agent-platform/experiments/tasks_v01.clj.txt") "]"))]
  (doseq [f forms :when (and (= 'defn (first f))
                             (#{'referenced 'derive-needs 'plan 'attempt 'states} (second f)))]
    (eval f)))
(let [start (System/nanoTime)
      a (quote #:seon.task{:id :demo/a :delivers #{demo/f} :done-when []})
      b (assoc a :seon.task/id :demo/b)
      c (quote #:seon.task{:id :demo/c :delivers #{} :done-when [(= (quote demo/f) (quote demo/f))]})
      cyc [(assoc a :seon.task/needs #{:demo/b}) (assoc b :seon.task/needs #{:demo/a})]
      raised #:seon.task{:id :demo/raised :state :raised}
      empty-attempt (attempt (fn [_] {}) :scratch raised)
      recurring #:seon.task{:id :demo/nightly :state :specified :needs #{}}
      old-green #:seon.task.attempt{:task :demo/nightly :green? true}
      new-red (assoc old-green :seon.task.attempt/green? false)]
  (prn {:quoted-reference (referenced c)
        :order-ab (:seon.task/needs (last (derive-needs [a b c])))
        :order-ba (:seon.task/needs (last (derive-needs [b a c])))
        :cycle-plan (mapv :id (plan cyc))
        :empty-attempt empty-attempt
        :raised-state (states [raised] [empty-attempt])
        :stale-state (states [recurring] [old-green new-red])
        :some-result (some #{(quote seon.note-test/add-then-forget-round-trips)}
                           [(quote seon.note-test/add-then-forget-round-trips)])
        :elapsed-ms (/ (- (System/nanoTime) start) 1e6)}))
```

**VERIFIED — values:** quoted-reference `#{demo/f}`; order-ab `#{:demo/b}`; order-ba `#{:demo/a}`; cycle-plan `[:demo/a :demo/b]`; empty-attempt `results []`, `green? true`; raised-state `{:demo/raised :settled}`; stale-state `{:demo/nightly :settled}`; some-result `seon.note-test/add-then-forget-round-trips`. Diagnostic body **0.068667 ms**; full process **0.01 s**; peak RSS **48,758,784 bytes**. One diagnostic run, no warm distribution or production performance proof.

**VERIFIED — dependency grounding:** Pinned Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, Edamame `63373df6ab056d86d106a79caecc2ebfca18d357`, Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`. Malli `schema` compiles against supplied options/registry; `validator`/`explainer` cache on compiled schemas (`reference-code/malli/src/malli/core.cljc:2550`, `:2627`, `:2642`). Compile on relevant schema-input changes, reuse for values; compilation cost is unmeasured here. Neither that seam nor a source citation proves installed task admission.

**VERIFIED — ownership:** Only this review is changed; source/test delta **0/0**. Pre-existing `reference-code/http-kit` dirt and untracked `task-model-synthesis-2026-09-24.md` were not touched. No gates, worktrees, provider runs, issue-index edits or pushes. The data-oriented Clojure skill's contract citation at `.agents/skills/data-oriented-clojure/SKILL.md:49` still points to `AGENTS.md:271`; the current law is `AGENTS.md:156`. The one-file assignment prevents correcting that foreign file. Runtime adoption, arming, storage semantics, browser paint and agent-completion comparisons remain **UNVERIFIED**.

**VERIFIED — document checks:** `seon.dev.markdown/validate-file` reports `valid? true`, zero violations for this file. All 53 explicit path/line references resolve within file bounds; fences balance; all four proposed maps parse and each predicate has three arguments. The final summary has 144 words. The automatic repository-wide check still reports 57 issues, with displayed failures in other files' historical dependency-pin citations; that check is not a pass. This dated review is classified as research, preserving its measured revision rather than asserting a current dependency pin.

## Final summary

Keep the language's foundation: ordinary Clojure, native names, intent as data, task-local conditions, and separate attempts. Its weak point is semantic inference. Namespace membership cannot grant ownership, a proof mention cannot establish prerequisite order, and historical success cannot establish current completion.

Source inspection and isolated diagnostics show order-dependent prerequisites, cycle concealment, empty-proof settlement, stale-green settlement, and a real successful proof whose nonboolean result stays red. The four scenario rewrites use 28 map lines versus 26; the extra lines make evaluation inputs, outputs and acceptance clearer. Their supporting APIs remain proposals.

Prefer explicit reviewed changes and prerequisites, ordinary predicates over supplied worlds, and observations tied to task revision, exercised inputs and attempt/firing. Use existing analysis, validation, evaluation, scheduling and merge owners. Make inference useful advice; make authority explicit. Validate that design on a fix, a deletion and repeated maintenance before judging elegance by key count.
