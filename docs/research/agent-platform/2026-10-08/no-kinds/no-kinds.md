---
type: research
status: proposed
date: 2026-10-08
lane: no-kinds
---

# No kinds: census, cause and removal slices

Owner, 2026-10-08: no "kinds". An entity is what its positive data says: the attributes it carries and the
refs it holds. Validation is a base Malli schema plus specific schemas composed over it, exactly as errors
are (`:seon.error/base` plus `[:and :seon.error/base [:map members]]` variants, `:seon.error/type` naming the
variant's own registered schema key, `resources/seon/schemas/seon.error.edn:299-335,421`).

Exists: tags such as `:seon.task/kind`, `:seon.cluster.eval/origin` and `:seon.schema.admission/source`,
written beside data that already says the same thing. Data flow after this note: writers record refs and
attributes only; readers ask for the attribute or ref; function contracts name the specific schema they
need. Clojure approach: delete the tag key, compose specific schemas with `:and`/`:merge`, and keep one
`:multi` only where the dispatch value is a registered schema key (the error pattern).

Probe basis: default cluster, `runtime_status` health `:unknown` with four over-bound warnings that already
carry tasks (`seon.db/transact-call`, `seon.schema/call-with-forms`, `seon.cluster.source/changed-identities`,
`seon.problems/problems`); not caused here. JVM probes of the live store at basis-t 536871963, 121-124 ms each.

## 1. Census

Searched `src`, `test`, `resources/seon/schemas`, `resources/seon/tasks` for keys named `*/kind`, `*/type`,
`*/sort`, `*/category`, `*/variant`, `*/origin`, `*/situation`, `*/disposition`, `*/phase`, `*/mode`,
`*/class`, `*/shape`, `*/step`, plus every `:enum` in the schema resources (about 110). Namespaced-map
literals (`#:seon.task{:kind ...}`) were counted separately: 543 task-file entries use them.

Classes: **(a)** a real bounded state of a living thing (allowed); **(b)** dependency or operation grammar
(allowed); **(c)** a type tag saying what the thing is (disallowed).

### Disallowed (c): 10

| Key | Where | What it says | Writers / readers | Positive data that already says it |
|---|---|---|---|---|
| `:seon.task/kind` `#{:function :namespace :schema :merge :work}` | `seon.task.edn:9`, entry `:15`, declaration `:20`, entity `:27`; handle `src/seon/task.clj:47-51` | what a task is about | writers: 543 task-file entries (489 function, 51 namespace, 3 schema), `seon.task/propose!` (`task.clj:668`, `:merge`), `my.task` own task (`my/task.clj:119`, `:work`); readers: only `task-handle` (identity), `task.clj:137,417`, the HTML kicker (`render/ns.clj:108,129-134`). No reader dispatches on it. Store: 657 tasks (594/55/3/4/1), zero ns+name collisions across kinds | a merge task is the target of a proposal's `:seon.task/merge` ref (`task.clj:671`); a work task carries `:seon.task/created-by` and `:seon.task/steps`; a task about a function is named by that function's `:seon.task/tasks` (`seon.task.edn:12`) and by its `:seon.task/must-read`; `:seon.task/sym` (ns/name) is already unique (`seon.task.edn:5`) |
| proposed `:question` task kind | `docs/research/agent-platform/2026-10-08/redo/task-finish.md:80,167,177` (slice F6) | a task answered by data | not yet written | owner-decisions Q9 (`review/owner-decisions.md:30,84`) already rules it derived: a task whose proofs call `my.task/answer` |
| `:seon.cluster.eval/origin` `#{:model :outside}` | `seon.cluster.eval.edn:36-41` (and the retired `:author` beside it, `:31-35`) | which entrant recorded an evaluation | writers: `turn.clj:501` (`:model`), `turn/batch.clj:658-663` (`:outside`); readers: `turn.clj:1847,3363,5053` (21 src, 18 test). Store: 295 rows, all `:outside` | the evaluation's `:seon.cluster.eval/run` ref; a model pass's run carries `:seon.turn/attempts` / `:seon.turn/reply`; the agent-loop target gives each evaluation its call (`2a95e0ccf`) |
| `:seon.schema.admission/source` `#{:core :agent}` | `seon.schema.admission.edn:1`; required on fn, test, ns, schema entities (`seon.fn.edn:139`, `seon.test.edn:100`, `seon.ns.edn:22`, `seon.schema.edn:119`), optional on lint and file | whether a declaration came from files or an agent | writers: `seon.fn` indexing (`fn.clj:1695,3434,4067`) and agent admission; readers: 83 src refs in 14 files (`test.clj:945,1522,2326`, `schema/admission.clj`, `turn/batch.clj` ...), 157 test refs. Store: 14,291 core, 34 agent | `:seon.fn/file` (`seon.fn.edn:19`): probe shows functions 5,667 core all with file, 7 agent none; tests 2,474/1; namespaces 488/26 — a perfect partition. The schema already says so: `seon.fn.edn:178-180` is `[:or [:map [admission/source [:= :agent]]] [:map [:seon.fn/file ...]]]`. Schemas (3,403) and lint findings (1,390) carry no `:seon.fn/file`; lint carries `:seon.lint/file` (required) |
| `:seon.fn/projection-boundary` `#{:seon.render/ai :seon.render/html :none}` | `seon.fn.edn:172-174,204-205` | "this function is an AI/HTML render boundary" | authored metadata on 4 functions (`render.clj:1388-1406`, `render/web.clj:2710,3150`); read by `fn.clj:2335` | CLAUDE.md: a render serves AI or HTML by its attachment key or declared output schema, "never a separate fact" |
| `:seon.fn/external-sink` `#{:ai-visible-text :html-response :codec-storage}` | `seon.fn.edn:121-122,169` | what role a function plays for output | same 4 functions; read by the output-reachability check `fn.clj:2328-2480` | the function's declared output schema (`:seon.render/ai-data`, `:seon.render/hiccup`, a Ring response, a blob digest) |
| `:seon.cluster.boot/disposition` `#{:refused :boot-failed :operation-failed}` | `seon.cluster.boot.edn:2` | a second discriminator inside `:seon.cluster.boot/operation-error` | writers `fn.clj:145`, `cluster.clj:268`, `cluster/boot.clj:290`; reader `cluster/boot.clj:800` | `:seon.error/type` — each disposition is its own error variant |
| `:seon.operator/disposition` `#{:refused :client-failed :argv-failed}` | `seon.operator.edn:1` | the same, inside `:seon.operator/operation-error` | no src writer; 2 test refs | `:seon.error/type` |
| `:seon.agent/mode` `#{:live :isolated}` | `seon.agent.edn:2`, execution handle `:21` | whether an agent runs on the cluster branch | writers `cluster/agent.clj:1184`, `test.clj:1941`; reader `my/agent.clj:76-85`; 7 test asserts | `:seon.agent/branch` against the cluster's branch (its own description, `seon.agent.edn:1`) |
| `:seon.program/kind` | `seon.program.edn:191-193`, read results `:241,:275` | which identity attribute answered a read | `my/program.clj:71,99`; read `:167,182,277,352` | it is `(first :seon.program/identity)`, carried beside it in the same map |

Stored copies of a derived decision, deleted with their entity rather than by their own slice:

- `:seon.turn.work/situation` stored on `:seon.turn/turn` (`seon.turn.edn:31`). The value-level `:multi`
  on the next-work plan (`seon.turn.work.edn:43-80`) is a derived scheduling decision, class (a); the stored
  copy on the run row is a tag. The agent-loop cut deletes the turn entity (`2a95e0ccf`: no turn or run
  entities), and with it this attribute (35 src, 77 test refs). No separate slice.
- `:seon.effect/disposition [:enum :interrupted]` (`seon.effect.edn:1`) beside `:seon.effect/interrupted-at`:
  a one-value enum mirroring a positive timestamp. Writer `shell/jvm.clj:420`, reader `effect.clj:952-957`.
  Slice 2 below.
- `:seon.schema.advisory/kind` (`schema/internal.cljc:198,395,421`, read `schema/admission.clj:406`,
  `fn.clj:2179`): an unschematized diagnostic category inside permissive-position findings. It is a finding
  variant; under the error pattern each variant is a schema key. Low value; slice 9.

### Allowed (a) state: 21

`:seon.task/outcome` (accepted/declined), proposed `:seon.task/status`, `:seon.step/status`,
`my.turn/disposition` and `:seon.turn/disposition` (wait/completed, each variant carrying its own members),
`:seon.turn.work/form-state`, `:seon.turn.loop/outcome`, `:seon.turn.loop/phase`, `:seon.eval/outcome`,
`:seon.eval.drive/outcome`, `:seon.cluster.source/phase`, `:seon.cluster.eval/code-evidence`,
`:seon.batch/evidence`, `:seon.health/state`, `:seon.ai.model` announced/active, `:seon.test.selection/disposition`
(why a member was excluded; a decision record, its own description says so, `seon.test.selection.edn:37`),
`:seon.test.run/policy` and `/stopped`, `:seon.render/selection-stage-status`, `:seon.render.walk/continuation-reason`,
`:seon.sci.admit/reason`, the value-level `:seon.turn.work/situation` plan.

### Allowed (b) dependency or operation grammar: 17

`:seon.error/type` (the variant's registered schema key — the model, not a parallel vocabulary),
`:seon.lint/type` (clj-kondo's finding type), `:seon.test.failure/type` (clojure.test `:fail`/`:error`),
`:seon.print/face` (Clojure value types of the printer node tree, a `:multi`), `:seon.fn.binding/shape` and
`:seon.fn.binding.child/role` (Clojure destructuring), `:seon.fn/workload` (Flow `:io`/`:compute`),
`:seon.batch/op` and `:seon.batch/kind` root/extend (what a write did to a Var), `my.edit` operations,
Datahike index names and `:assert/:add/:retract`, `:seon.sci.reader/token-kind`, `:seon.schema/mode`
(compile argument), `:seon.batch/mode` (reader argument), `:seon.render/output` (the consumer key itself),
`:seon.cache/kind` (the name of a derivation, a qualified keyword, not an enum; misnamed but an identity),
`:seon.health/phase` (a boot layer name). Positive JVM class names (`:seon.print/class`, `:seon.id/class`,
`*/exception-class`) are observed values, not discriminators.

Totals: 10 disallowed tags (plus 3 stored mirrors), 21 states, 17 grammar keys.

## 2. Why it happened

The task kind came from the issue system: `seon.issue` tasks were renamed tasks in `1552a2d3a` (2026-09-25)
and the owner's ruling 3b.6 (`docs/research/agent-platform/language-requirements-2026-09-24.md:80`) set task
identity to "its name plus the component or subject kind it concerns", modelled on error signatures.
Because no task held a ref to its subject, the subject's *kind* stood in for the subject, and
`d7139e8e5` hashed it into the handle. Later `:merge` and `:work` were appended as new members of the same
enum rather than as the refs (`:seon.task/merge`, `:seon.task/created-by`) that were added beside them.
The other tags follow the same habit: when a writer knew which path it was on, it stamped that path as an
enum (`origin`, `admission/source`, `mode`, `disposition`) instead of recording the positive fact the path
produced (a file ref, a run ref, a branch, an error type), and the schema then had to restate the
correlation by hand (`seon.fn.edn:178-180`). Specs reinforced it: the 2026-10-07 final revision (`18c3d1286`)
lists an "origin enum", and the task-finish redo proposed `:question` as one more member.

## 3. Replacement design

Rule: a writer records refs and attributes; what the entity is follows from them. The entity's stored
schema is the base (optional members); a function that needs a particular variant names a specific schema
composed over the base. A union is `:or` of specific schemas; a `:multi` is used only where its dispatch
value is a registered schema key, as `:seon.error/type` is. Malli: `:and` and `:or` in
`reference-code/malli/src/malli/core.cljc` (`-and-schema`, `-or-schema`, 825/996); `:multi` evaluates its
`:dispatch` property (`-multi-schema`, 1861-1883), so an EDN resource can dispatch on a keyword only;
`:merge` in `malli/util.cljc:398` distributes over `:multi` (`util.cljc:85-87`). Errors already use
`[:and :seon.error/base [:map ...]]`; the specific schemas below copy that.

### Tasks

- Base `:seon.task/task`: `id sym ns name intent` required; `must-read evidence created-by steps faults
  subject signal outcome` optional. No kind.
- Specific schemas, used as contracts only where needed:
  - `:seon.task/merge-task` = `[:and :seon.task/task [:map [:seon.task/proposals ...]]]` — or, without a
    new forward attribute, the decision functions take a merge-task handle and the writer checks that a
    proposal refs it (`decide!` already takes `:seon.task/merge`, `task.clj:686`).
  - `:seon.task/own-task` = `[:and :seon.task/task [:map [:seon.task/created-by ...]]]` for `my.task/plan!`
    and `step!`.
  - A question task needs no schema: its proofs call `my.task/answer` (Q9). Delete F6's `:question` member
    from the task-finish plan.
  - A task about a function, namespace or schema: the definitions whose `:seon.task/tasks` name it, and its
    `must-read`. The file stem is the namespace (`:seon.task/ns`).
- **Identity without kind.** `task-handle` becomes `seon.id/id` over `[ns name]`, the same pair that already
  makes `:seon.task/sym` unique. The probe found zero ns+name pairs shared across kinds, so no two tasks
  merge. Generated tasks keep their names (`merge-<key>`, `work-of-<agent>`), which already carry what kind
  did. Ruling 3b.6 is amended in place to "name in its namespace (authored) or producer + subject
  (generated)".
- Every handle changes: 657 stored tasks, assignments (`:seon.task/assigned` holds handle values), and 18
  literal handle strings in 7 test files. **RESET NEEDED** after that slice lands (store data is disposable;
  rehashing in place would collide on the unique `:seon.task/sym`).

### Evaluations

Delete `:seon.cluster.eval/origin` and the retired `:seon.cluster.eval/author`. "Did the agent's model
write this?" is `[?form :seon.cluster.eval/run ?run] [?run :seon.turn/attempts _]` today, and the
evaluation's call (its model attempt or MCP request) after the agent-loop cut. `:seon.eval/origin` (a string
naming the generating declaration) is positive data and stays, but should become a ref per "a statement
about a living entity is a ref"; that is a separate rename, not this lane's.

### Program declarations

Delete `:seon.schema.admission/source`. A declaration from files carries its file ref (`:seon.fn/file`;
schemas gain the same ref to their resource file, which the indexer already reads); an agent declaration
carries a ref to the evaluation that defined it (`:seon.program/defined-in`, a new optional ref, data
partition target). Specific schemas: `:seon.program/file-declaration` (`[:map [:seon.fn/file ...]]`) and
`:seon.program/agent-declaration` (`[:map [:seon.program/defined-in ...]]`), the entity schema
`[:and base [:or file-declaration agent-declaration]]` — exactly the `[:or ...]` already at
`seon.fn.edn:178-180`, minus the tag. Lint findings drop it outright (`:seon.lint/file` is required). Open
question for root: a ref from a program entity to a data-partition evaluation must be dropped at merge;
write-back then supplies the file ref.

### The rest

- `:seon.fn/projection-boundary`, `:seon.fn/external-sink`: delete; the reachability check reads each
  function's declared output schema (`:seon.render/ai-data`, `:seon.render/hiccup`, the HTTP response
  schema, `:seon.blob/digest`).
- `:seon.cluster.boot/disposition`, `:seon.operator/disposition`: one error variant per value
  (`:seon.cluster.boot/refused-error`, `/boot-failed-error`, `/operation-failed-error`, each
  `[:and :seon.error/base [:map ...]]`); `cluster/boot.clj:800` tests `:seon.error/type`.
- `:seon.agent/mode`: delete; `(= branch (cluster branch))` where a reader needs it.
- `:seon.program/kind`: delete; readers destructure `[attribute _] :seon.program/identity`.
- `:seon.effect/disposition`: delete; `:seon.effect/interrupted-at` says it.

What disappears: 10 tag keys, their enum vocabularies, the hand-stated correlation at `seon.fn.edn:178-180`,
and one identity input. Cost: every replacement is an attribute or ref read the writer already performs; no
new derivation. Simplest alternative considered: keep the keys and rename them — rejected, the owner's
ruling is about the tags, not the word.

## 4. Slices (lowest risk first, each about 15 minutes, all callers converted in the same commit)

1. **`:seon.program/kind`** — `seon.program.edn`, `src/my/program.clj`, `test/my/program_test.clj`.
   Proof: a `my.program` read result has no `:seon.program/kind` and its `:seon.program/identity` first
   element is `:seon.fn/sym` for a function subject.
2. **`:seon.effect/disposition`** — `seon.effect.edn`, `src/seon/effect.clj`, `src/seon/shell/jvm.clj`,
   `test/seon/shell/jvm_test.clj`, parity EDN. Proof: an interrupted JVM effect pulls with
   `:seon.effect/interrupted-at` and without `:seon.effect/disposition`.
3. **`:seon.agent/mode`** — `seon.agent.edn`, `cluster/agent.clj`, `test.clj`, `my/agent.clj` and 4 tests.
   Proof: `my.agent` handle for an isolated member has a `:seon.agent/branch` unequal to the cluster branch
   and no `:seon.agent/mode`.
4. **Boot and operator dispositions to error variants** — `seon.cluster.boot.edn`, `seon.operator.edn`,
   `fn.clj:145`, `cluster.clj:268,298`, `cluster/boot.clj:281-290,800`, 8 test refs. Proof: a refused boot
   operation's error map has `:seon.error/type :seon.cluster.boot/refused-error` and validates against it.
5. **`:seon.cluster.eval/origin` and `/author`** — `seon.cluster.eval.edn`, `turn.clj` (21 refs),
   `turn/batch.clj:658-663`, 18 test refs. Proof: an outside JVM run's evaluation row has no origin; the
   model-form query (`turn.clj:3363`) finds a model run's form through `:seon.turn/attempts`. Defer if the
   agent-loop cut is in flight on `turn.clj` (one file, one owner).
6. **Projection boundary and external sink** — `seon.fn.edn`, `fn.clj:682-795,2328-2480`, `render.clj`,
   `render/web.clj`, `fn_test.clj`. Proof: the output-reachability report for `seon.render/...` names its
   sink from the declared output schema; no function entity carries either key.
7. **`:seon.task/kind` and handle over `[ns name]`** — `seon.task.edn`, `task.clj`, `my/task.clj`,
   `render/ns.clj`, 543 task-file entries (one scripted sweep deleting `:kind`), 18 literal handles in 7
   tests recomputed by script, `test_support.clj`, `task_ingestion_test.clj`, `task_steps_test.clj`,
   ns/related/walk-graph tests; amend 3b.6 and task-finish F6 in place. Proof: ingesting
   `{:seon.task/name "prove-ingestion" :seon.task/intent "..."}` in `tq.a` stores `:seon.task/id`
   `(seon.id/id ['tq.a "prove-ingestion"])` and no `:seon.task/kind`; a proposal's merge task is found by its
   `:seon.task/merge` ref. **RESET NEEDED** after landing.
8. **`:seon.schema.admission/source`** — the largest: schema resources (8 files), 83 src refs in 14 files,
   157 test refs; two sub-slices. 8a adds `:seon.program/defined-in` on agent admission and a resource-file
   ref on schemas, both written beside the tag; 8b converts every reader to the refs and deletes the tag.
   Proof (8b): an agent `defn` on a branch pulls with `:seon.program/defined-in` and no `:seon.fn/file`; a
   file function pulls with `:seon.fn/file`; neither carries `:seon.schema.admission/source`. Publication
   and adoption on a branch of the live store prove the schema change; **RESET NEEDED** after 8b.
9. **`:seon.schema.advisory/kind`** — `schema/internal.cljc`, `schema/admission.clj`, `fn.clj:2179`: findings
   carry a registered finding schema key. Proof: the permissive-position finding for `[:map [:a :any]]` has
   the `:any` finding key.

`:seon.turn.work/situation` on the turn row goes with the agent-loop cut's deletion of turn entities.
