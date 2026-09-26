---
type: review
status: completed
reviewer: Astra
date: 2026-09-26
design: 94dad15fa
source-basis: 9be9bd159dcd8e4e5731253f8e5643db2d0a3c0a
verdict: REVISE
---

# Independent re-review: REPL-entry renders, revision 2

**REVISE.** The existing reader, evaluator, recorder and floor are the right
owners. Revision 2 removes the proposed transcript cache and corrects D7,
history direction, namespace ownership and deletion accounting. It still does
not establish read-only execution in the viewing agent's world. Its universal
writer claim is false, its promised `defn` refusal happens at a stage the preview
does not reach, and retained history processing still parses sources and
reconstructs printed results outside the proposed single path.

Broken evidence boundary first: default was alive but faulted, publication off,
running archive **10d6fdff5edf870ad31f7980b098a547d546fcc7**, not the checkout
revision below. During the review the shared REPL disappeared and returned as
pid 39759, start `2026-09-26T03:05:21Z`; I did not restart it. Final observed
health was one error signature and 19 failed tests; readiness was 4,451 ms.
The fault observation named invalid `:seon.db/read-request` values on foreign
branches `dogfood-repl-opus-5` and `dogfood-repl-opus-6`. This is a foreign
installation/evidence boundary, not a reason this source review stopped and
not a passing platform proof.

The reviewed design is all 424 lines at **94dad15fa**, including every §7
ruling. Source citations below are pinned to **9be9bd159**, which includes the
snapshot correction, unless explicitly stated otherwise. Concurrent checkout
changes are not silently attributed to that revision. The original review at
**ebb8ba691** covers revision 1 and retains its original probes in Git history.
Authorities read include all of AGENTS, the `## Now` section of
`docs/research/agent-platform/open-items.md`, the render law introduced by
**e5c19877f**, “The one REPL path,” and the relevant data-oriented Clojure, REPL,
Datahike and testing skills. The revised design's requests for owner confirmation
are proposals; this re-review request does not amend the standing render law.

## Disposition of all ten original findings

“Resolved” below means resolved in the design, not implemented or proved installed.
Numbers remain stable; new findings start at 11.

| Original finding | Status | Ruling verified |
|---|---|---|
| 1. Read-only by removing connections | **OPEN, blocking** | Removal is correctly withdrawn. Positive access is a useful DB-writer input, but does not cover all durable effects, survive the stated pipeline unchanged, or preserve the viewer's world. See findings 1 and 12. |
| 2. Content-invalid transcript cache | **RESOLVED for the proposed new cache** | §2.6 deletes it. The mixed pull/atom counterexample is accepted. Existing `system-plan` reuse still needs the same input-validity audit: finding 11. |
| 3. Second source read | **OPEN, high** | Passing the read event to finalization and deleting `def-note` are correct. Retained `source-events`/`source-key` still reread entries and history. |
| 4. Recursive rendering and transcript grammar | **OPEN, high** | Structural terminal printing uses the existing floor and terminates recursion. Applying it to every AI result while leaving HTML selection unchanged alters the standing render law. Target examples and wrapper retirement are now honestly identified. |
| 5. D7 and snapshot basis | **RESOLVED in design; hook fix pending** | The hook, not the already scoped-first supplier, is the defect. The held-value plan fix belongs in this slice. Snapshot source no longer uses the latest-plan shortcut. |
| 6. History A/B/C | **OPEN in integration; policy direction resolved** | Corrected B, using `system-turn`, is the smallest policy consistent with durable shown text and handles. Its current suppression/diff processing and base-world refresh do not support “no policy change” without further work. |
| 7. Entry grammar and duplicate vocabulary | **OPEN, medium** | Comment-plus-required-source and loop-owned namespace are sound. The old vocabulary has more consumers and its walkthrough performs the very operations this design forbids. |
| 8. Bounds and cost proof | **OPEN as acceptance evidence** | Timings are now labelled feasibility. Aggregate exit ownership is still a requirement without a named implementation; end-to-end cost and memory remain unmeasured. |
| 9. Curated aliases and migration | **RESOLVED in design; D17 prerequisite open** | The viewer's own `:seon.ns/aliases` is the right existing attribute; other namespaces' aliases remain data. Existing namespace adoption is correctly separated from seeding. |
| 10. Claimed ~640-line deletion | **RESOLVED as accounting correction** | Gross removals and conversions are separated; +~93 first-slice lines explicitly delete zero. These are estimates, not an accepted implementation size or a waiver for new machinery. |

## Findings ranked by severity

### 1. Blocking, OPEN: one positive DB access value does not establish the claimed read-only world

The design says every durable write an agent form can cause passes through
`seon.db/transact!` (§2.3, lines 173–179), then acknowledges registry/konserve
writers as residual host authority (188–195). Those are durable writes too.
This is a contradiction in the guarantee, not merely a JVM-atom caveat.

- `src/seon/cluster/registry.clj:192-222`, `branch!`, calls Datahike `d/branch!`
  directly. It does not pass the proposed DB transaction guard.
- `src/seon/blob.clj:318-326`, `put!`, stages content and calls
  `with-publication!`; `:296-316` publishes before calling the root-commit
  callback. `publish-staged!` at `:185-195` calls `k/bassoc` directly. In `put!`
  the callback just returns the digest: there is no later DB transaction to
  refuse at all.
- Live probe P2 resolves **both writers in a fork of root's base program SCI
  context**, along with `seon.shell.jvm/run`, `seon.effect/request!`,
  `seon.db/call-with-custody` and `seon.db/call-without-custody`. Not resolving
  `datahike.api/transact` does not prove that callable Seon wrappers cannot
  write. I did not invoke these effects.

There is a separate propagation defect. At the pinned source,
`resources/seon/schemas/seon.db.edn:179-182` has the custody request but no access
member. `src/seon/db.clj:383-410` binds connection, read basis and receipt, and
`:412-434` supplies empty custody. `src/seon/sci/eval.clj:4101-4102` reconstructs
custody with **only a connection**. Consequently the stage table's “evaluate:
none” does not carry the proposed access value. Simply adding another binding
that reads its request resets it on this call and on nested empty-custody calls.
A form can resolve those wrappers itself. Access must not widen merely because
a nested stage supplies an incomplete request or a different connection.

The proposed check has not been installed: P3 found no `:seon.db/access` in the
live projection. The author's probes verify the old failure and base-fork
behavior; they do not test the proposed refusal. This is legitimate design
work, but “every finding REPL-verified” must not be read as a candidate proof.

**Effects, specifically.** The normal `effect/request!` route really does open
through `db/transact!` before dispatch (`src/seon/effect.clj:837-844`). A correctly
carried access check can prevent that admission. However, the caller recognizes
refusal through three specific keys (`:845-847`); a new error must go through
the writer's existing refusal envelope (`src/seon/db.clj:4923-4929`), not bypass
it and fall through to dispatch. Direct handler calls are another route:
`src/seon/shell/jvm.clj:431-444` calls `execute` without effect admission, and P2
resolves that private handler in base SCI. This is source evidence of a bypass
route, not a claim that a shell command was executed successfully. Also the
proposed effect regression, `my.message/send`, calls `message/send!` directly
(`src/my/message.clj:31-49`); it does not exercise `effect/request!` admission.

**Base-world semantics.** AGENTS says private definitions/objects belong to the
agent's context (`AGENTS.md:125-128`) and render entries run in the viewing
agent's own world (`:240-250`). A base fork intentionally removes those values;
that prevents one demonstrated private-atom mutation by making its name
unresolvable, but changes the requested world. Worse, §4 retains refresh of
agent-authored reads. Such a read can use a private value, alias or `result/eN`
handle. `system-plan` selects those reads (`src/seon/turn.clj:2017-2026`); the
new base fork cannot reproduce their environment. A program-level host atom is
not deep-copied either. The prior shallow-fork counterexample remains relevant.

“Trusted merge-gated renderers” does not enforce the read-only guarantee against
mistakes and also excludes the law's inline/value overrides. The design itself
says transitive reach cannot see higher-order/interop calls. Neither option
establishes the stated guarantee; neither is authorized by the standing law.

**Smaller correction:** retain the existing execution stages and define access
there as authority carried through the entire execution, with no widening at
nested custody, effect admission or lifecycle boundaries. Audit the actual
nontransaction write owners instead of calling them harmless residual state.
Keep the initial change a common execution/preview fix; do not migrate renders
or invent a parallel restricted interpreter. If preserving the viewing world
requires an owner decision, expose that contradiction before implementation.

### 12. High, NEW, OPEN: a preview `defn` has no install transaction for the proposed guard to refuse

The design promises that a `defn` under read access “is refused at its install
transaction” (§2.3, 185–187). The existing composition does not do that.

`evaluate-sources` explicitly does not settle (`src/seon/turn.clj:4742-4747`).
Its function gate invokes `evaluate-candidate` (`:3250-3293`), and
`src/seon/sci/eval.clj:4483-4500,4502-4512` creates and accepts candidate roots
into the disposable evaluation context. That can produce a program row and a
successful callable value without issuing an installation transaction.

P1 called the real preview owner on this lane's branch using its base context.
A contracted `defn` returned `#'user/astra-r2-preview-id`; the following call
returned **7**. The definition outcome contained a program row. The branch
commit was unchanged and pulling its function identity returned nil. This
falsifies the proposed refusal mechanism, not just its installation status.

Ordinary runs settle separately (`src/seon/turn.clj:5084` → `settle-batch!` at
`:3628-3688`) and only install committed rows afterward (`:5091-5108`). Yes,
those program-row transactions use `db/transact!`; no, an evaluation-scoped
binding alone does not automatically govern later lifecycle settlement.
`system-turn`'s `record-evaluated-tx` explicitly does **not** install definitions
(`:1536-1542`). Do not fix this by making the recorder install them or by
refusing its authorized history writes.

**Required correction:** put the program-change decision at the existing
admission/settlement boundary, with its authority carried as data. Decide
explicitly whether disposable local definitions are allowed. If they are,
remove the promised refusal; if not, refuse before returning success through
the ordinary program owner. A render-only `defn` name check is not a solution.

### 3. High, OPEN: retaining `system-plan` retains another source-reading path

Passing the existing event into disposition finalization and deleting
`repl/def-note` correctly removes the two readers identified in revision 1.
Bypassing `planned-sources` for render entries also avoids the demonstrated
alias loss. But “one read per form” still does not follow:

- `src/seon/turn.clj:1866-1877`: `source-events` calls `reader/read`;
  `source-key` calls it to derive source identity.
- `:1934-1950`: historical evaluations are reread through `source-key`, twice
  in one reduce step.
- `:1968-1975`: classifying a retained read calls `source-events` again.
- `:1986-2026`: `system-plan` invokes these helpers for deduplication,
  eligibility and status; `:2145,2173,2199,2214,2224` invokes them again.

This is the same reader library called through a second parsing path, not a
new reader implementation. It still violates read-once and is not in the
claimed deletions. These reads receive a namespace name, not the sequential
SCI alias table. Therefore the successful direct-entry alias probe does not
prove the retained system-turn path.

**Smaller:** derive source identity without reparsing, and carry classification
from the real read/evaluation as data. If identity requires the form, consume
the existing read event. Include these callers in the common-path retirement;
prove sequential `require`, alias-qualified keywords and syntax-quote through
`system-turn`, not only `evaluate-sources`.

### 4. High, OPEN: a terminal floor is sound; skipping selection for all AI results changes the render law

The structural option exists (`src/seon/render/value.clj:628-635`) and uses the
same floor. It is a promising terminal stage after selecting a renderer and
evaluating its entries. It is not a second printer.

The broader rule in §2.4 says **every AI printed result** has no declared pair,
with producers invoked only for walk nodes; HTML keeps its existing pair
selection. An ordinary evaluated value can have its own AI override, a viewer
namespace default or a schema render pair. Such a value is not necessarily a
separately selected graph node. Bypassing selection at `shown-result` makes
those attachments ineffective for that result. Keeping HTML's nested selection
unchanged also does not establish the common selection/node-tree law
(`AGENTS.md:240-256`). The cited precedent in `src/seon/repl.clj:460-472` is
conditional HTML recovery of a live result without a recorded renderer, not a
rule to skip AI selection universally.

The returned-error case needs the same explanation: the law composes error
base and satisfied schema renderers, whereas §2.4 declares their keys alone
the story. Avoiding recursive refusal rendering is required, but it does not
authorize deleting the first legitimate selection.

**Required correction:** show selection → entry evaluation → terminal print
once on the common ordinary-value path, including an inline override, a viewer
default, a self-reading entity, a returned declared value and a renderer
failure. State which already-selected value the terminal structural flag
applies to. Preserve the two faces' shared selection rather than creating an
AI-only exception. Wrapper removal and machine comments are otherwise a
coherent transcript target; the design correctly stops calling examples
installed output.

### 11. High, NEW, OPEN: the retained history algorithm still derives from shown strings and incomplete inputs

The history owner is correctly reused, but §4 cannot describe it as unchanged
while promising one terminal print of real values and content-valid reuse.

`src/seon/repl.clj:78-89`, `shown-value`, EDN-reads previously printed text (or
falls back to the entire string). `latest-evaluations` reconstructs values from
it (`src/seon/turn.clj:1940-1948`). `system-turn` compares that reconstruction
with a newly printed result (`:2148-2150`), then computes a diff from two shown
values and **overwrites `:seon.eval/shown` with `pr-str` of a changes map**
(`:2196-2209`). This is not another source evaluator, but it is another
print/interpret cycle outside `shown-result`, with no common floor budget at
that `pr-str`. The printed value may already be cut or may be non-EDN. It is
not the live value required before the print edge. Merely changing
`seon.repl/text` cannot remove this transformation.

Separately, `system-plan` marks a previous entry unchanged using
`read-evidence-current?` (`:1994-2000`) and skips it (`:2115-2116`). Its
eligibility test requires nonempty DB evidence and no recorded writes/effects
(`:1968-1982`); that still admits the mixed DB-read/private-value example from
finding 2. §2.6 accepts that evidence is incomplete, then retains this same
validity assumption. Calling it history rather than a new cache does not make
it content-valid. Suppressing based only on reconstructed shown value also
ignores changed stdout (`:2148-2184`).

An explicit special case also survives: `issue-origin-read?` identifies reads
whose origin issue has an agent (`:2028-2031`); those reads bypass both the
turn-dependency refusal (`:2077`) and the shown-value diff (`:2198`). Revision 2
retains this owner without retiring or justifying those exceptions. Its “no
list of special cases” claim is therefore too strong even before the new
disposition exclusion. Resolve the underlying read/refresh model in this owner.

**Smaller:** retain the existing recorder, receipt IDs, exact historical bytes
and post-commit handle binding. Make refresh operate on real retained values
or explicit read results under the common result/print stage, with complete
input validity; never parse a bounded display back into its authoritative
value. If no complete validity evidence exists, do not claim reuse is sound.
This is a correction at an existing owner, not justification for another cache
or result store.

### 6. High, OPEN integration: corrected B is right, but the recorder is not already the proposed single ordered batch

I accept §4's policy direction: lifecycle recording can write while the entry
is read-only. Existing `record-evaluated-tx`/`db/transact!` and post-commit
`bind-result!` (`src/seon/turn.clj:2227-2258`) avoid a new result store. Historical
shown text must not be rerun; agent-authored changed reads must still refresh.

However, existing `system-turn` reduces over sources, invokes
`evaluate-sources` with **`[source]`**, resets starting namespace from each
source, and uses a separate preview per source when not writing
(`:2122-2144`). “One entries vector per prompt, one fork, one loop” is a
necessary change to this owner, not the stage table's “record: none.” The
single batch must preserve sequential namespace/alias state and evaluation
ordinals while supporting its existing history selection. Skipping an
unchanged `require` must not remove the alias needed in a fresh fork. The
base-world refresh conflict is finding 1; the current diff/suppression policy
is finding 11.

Do not create a second preview recorder. Revise the existing owner's data flow
and prove exact recorded text, stdout, handles, sequential aliases and changed
agent reads together. A/B/C no longer needs another abstract option exercise:
B has the right owner, with these concrete corrections still open.

### 7. Medium, OPEN: the vocabulary retirement and walkthrough still require a semantic decision

The revised entry shape is sound: every entry has source, prose belongs above
that source, and namespace changes are actual forms. No comment-only schema
exception or per-entry namespace implementation is needed.

The caller audit is incomplete. `run/usage-form` is not the only producer of
the old shape: `src/seon/run.clj:14-56`, `walkthrough`, returns old entries under
`:seon.repl/entries`, and `usage-form` composes it at `:84`. The old entry schema
is referenced by `:seon.repl/episode` and `:seon.repl/candidate`
(`resources/seon/schemas/seon.repl.edn:82,88-93`) and by render forms
(`resources/seon/schemas/seon.render.edn:162-163`). Deleting the two named schemas
requires converting or deleting all of those consumers.

More importantly, the walkthrough executes two `defn`s, a `deftest`, a call to
the defined function and `my.turn/complete` (`run.clj:21-56`). Mechanical
conversion to source strings would now demonstrate refusals rather than the
advertised successful lifecycle. §2.5 says entries never contain dispositions
and admission refuses them, which conflicts with this existing producer.
The loop currently interprets returned `:my.turn/disposition` values
(`src/seon/turn.clj:4880-4882`), including values produced indirectly; inspecting
a top-level callee is not a complete admission rule. This remaining special
case needs a common execution policy, not an exemption for help/walkthroughs.

**Smaller:** retire the executable write walkthrough from read-only opening
production, or present its example forms as ordinary quoted data through the
same entry path. Do not add another evaluator to execute demonstrations. Name
this semantic conversion and all schema consumers in the slice.

### 8. Medium, OPEN proof: bounds and one-second acceptance remain requirements, not evidence

The revised source-byte and entry-count admissions are reasonable uses of
existing bounds. Graph nodes and entries are different counts, so explicitly
applying the count bound to the final vector is necessary. The design correctly
admits there is no aggregate deadline in `evaluate-sources`; the loop's
`await-turn-part!` (`src/seon/turn.clj:4789-4791`) does not itself prove interrupt
exit. Name the enforcing system-turn owner and its terminal event in the slice.

§6's 42–45 ms fork, 168–201 ms/60 forms and 79 ms/7 forms are feasibility
measurements, not a prompt/recording benchmark. Reusing one batch could be
sub-second, but current per-source system-turn processing does not prove that.
The reported 3.40/3.50 s debug operation remains over the law with responsible
armed costs unprofiled. The revised design honestly lists missing graph,
render, record, memory and many-entry measurements; acceptance still requires
them and parent/candidate comparison. No invented speedup or retained-memory
number is credited here.

## Resolved rulings and smaller accepted changes

**5 / D7.** Keep the corrected hook fix in the same read-only slice.
`src/seon/call_preparation.clj:1142-1152` obtains a DB only from the environment
connection and silently passes arguments through otherwise. Its plan basis can
therefore disagree with the held database, while `src/seon/db.clj:2278-2301`
already supplies the scoped database first. Changing that supplier again is
unnecessary. Choose the held value for the plan, falling back to the connection
only when absent, without losing explicit caller arguments. The snapshot
implementation at `call_preparation.clj:506-540` now answers each held value's
own basis, reusing dependency revisions and a projection-owned memo. The source
change is **a44538acc**; **10d6fdff5** is its landing evidence, not the code diff.
No new latest-plan atom or render cache is needed. P3's temporal probe did not
distinguish two basis values, so it is not credited as a historical regression
pass.

**9 / aliases.** `:seon.ns/aliases` and the existing alias component are the right
attributes (`resources/seon/schemas/seon.ns.edn:2-8,26`). They represent the
namespace's actual require aliases. Restricting executed requires to the
viewer's own namespace avoids cross-namespace alias collision without another
registry. D17 is correctly a prerequisite for existing root adoption;
`namespace-seed-call` is only for new namespace rows. Keep toolkit consumers
until their conversion. The required output remains `(require '[ns :as alias])`
plus `dir`/`doc` and contracts, not an invented capability manifest.

**10 / size.** The old ~640 deletion and +120 implementation claims are withdrawn.
The revised gross estimate is about **225 lines** (57 + 93 + 60 + 3 + 12), with
producer changes explicitly classified as conversions. The initial estimate is
**+93 lines, zero deletions**, including ~40 test lines. That satisfies the need
to state growth honestly, not proof that those numbers are achievable. Findings
1, 6 and 7 expose missing work in the estimate. The smallest justified initial
slice remains D6/D7 in the existing execution owners before renderer migration.

## Stage-by-stage verdict on §2.3

| Stage | Source evidence at 9be9bd159 | Verdict |
|---|---|---|
| `fork-for-turn` | `src/seon/sci/eval.clj:2971-3002` uses SCI fork and existing environment/state carriers | Existing fork, no new evaluator. Shallow objects and base/private-world semantics remain unresolved. |
| `evaluate-sources` | `src/seon/turn.clj:4742-4886` threads namespace, requests, outcomes and dispositions | Existing loop. Carries writable cluster inputs; does not settle. Must carry access and be called as one ordered batch. |
| `evaluate-for-install` / reader | `src/seon/sci/eval.clj:4483-4498` obtains one event and hands it to `evaluate` | Correct reader seam; surrounding identity/history/finalization reads must also retire. |
| `evaluate` | `src/seon/sci/eval.clj:4101-4109,4234-4243,4283-4284` binds custody/read DB, consumes event and calls SCI | Existing evaluator, not a replacement. “No changes” is false for access propagation. |
| `shown-result` | `src/seon/sci/eval.clj:3683-3732`; `src/seon/render/value.clj:628-647` | Existing printer preparation; structural terminal stage possible. Preserve first selection and remove history reprinting. |
| `seon.repl/text` | `src/seon/repl.clj:119-142,169-237,264` | Existing transcript owner; wrapper/def-note retirement and raw transcript grammar are proposed, not installed. |
| `preview-sources` | `src/seon/turn.clj:4888-4924` forks, plans text, calls the same loop without settlement | Correct composition owner. Its current “persisting nothing” docstring does not prevent form effects; accept entries here instead of another parser. |
| `system-turn` recording | `src/seon/turn.clj:2086-2261` | Existing store and lifecycle are sufficient. Per-source calls, shown-string diffing and incomplete reuse remain. |

There is **no need for a second evaluator, printer implementation, cache or
store**. The retained code still has additional parsing calls and a separate
print-after-diff branch. Revision 2 removes the proposed new transcript cache,
but it does not make existing historical reuse content-valid automatically.

## Reproducible probe evidence and limits

### P1: preview succeeds locally without an install transaction

Run through MCP `eval_clj`, explicit root `/Users/sean/src/seon`, cluster
`default`, JVM mode; hosted branch `:astra-repl-render-r2`, agent
`mcp/astra-render-review-r2`. The held handle and context came from the real
host/acquire owners, not a fabricated fixture. The essential complete owner
request was:

```clojure
(let [started (System/nanoTime)
      instance (get @seon.operator.runtime/running-instances "default")
      hosted (seon.cluster.host/host!
              {:seon.turn.loop/cluster
               (assoc (:seon.turn.loop/cluster instance)
                      :seon.store/store (:seon.store/store instance))
               :seon.agent/branch :astra-repl-render-r2
               :seon.agent/id "mcp/astra-render-review-r2"
               :seon.ns/name 'user})
      handle (:seon.turn.loop/cluster hosted)
      held (seon.cluster.agent/acquire-context! handle "mcp/astra-render-review-r2")
      connection (:seon.db/connection held)
      database @connection
      before (seon.db/commit-id database)
      preview (seon.turn/preview-sources
                {:seon.turn.loop/cluster handle
                 :seon.db/db database
                 :seon.sci.eval/ctx (:seon.sci.eval/base-ctx held)
                 :seon.agent/id "mcp/astra-render-review-r2"
                 :seon.ns/name 'user
                 :seon.cluster.reply/text
                 "(defn astra-r2-preview-id {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(astra-r2-preview-id 7)"
                 :seon.sci.admit/caps (:seon.sci.admit/caps handle)})]
  {:elapsed-ms (/ (- (System/nanoTime) started) 1e6)
   :commit-unchanged? (= before (seon.db/commit-id @connection))
   :stored-function (seon.db/pull @connection [:seon.fn/sym]
                                [:seon.fn/sym 'user/astra-r2-preview-id])
   :outcomes (mapv (fn [item]
                     (let [e (:seon.sci.eval/evaluation item)]
                       {:shown (:seon.eval/shown e)
                        :error (:seon.cluster.eval/error e)
                        :program-row? (boolean (:seon.program/row e))}))
                   (:seon.turn.loop/evaluated-sources preview))})
;; {:elapsed-ms 158.988458, :commit-unchanged? true,
;;  :stored-function nil,
;;  :outcomes [{:shown "#'user/astra-r2-preview-id", :error nil, :program-row? true}
;;             {:shown "7", :error nil, :program-row? false}]}
;; io-prepl: 162 ms.
```

### P2: base-world callable resolution

This is a diagnostic of the installed SCI surface, not an alternative product
evaluator or a claim to have executed any write:

```clojure
(let [started (System/nanoTime)
      instance (get @seon.operator.runtime/running-instances "default")
      handle (:seon.turn.loop/cluster instance)
      held (seon.cluster.agent/acquire-context! handle "root")
      ctx (:seon.sci.eval/base-ctx held)]
  {:ctx? (boolean ctx)
   :resolved
   (when ctx
     (sci.core/eval-string*
       (sci.core/fork ctx)
       "(mapv (fn [s] [s (some? (resolve s))]) '[seon.db/call-with-custody seon.db/call-without-custody seon.shell.jvm/run seon.cluster.registry/branch! seon.blob/put! seon.effect/request!])"))
   :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
;; :ctx? true; all six symbols resolve true.
;; :elapsed-ms 5.42825; io-prepl 9 ms.
```

An earlier ordinary SCI run on the probe branch also resolved the custody,
branch and shell owners, while `datahike.api/transact` did not resolve. A
private atom definition succeeded on that branch, but the following private vs
base comparison was interrupted by shared JVM replacement; no result is claimed
for that attempted comparison. The author's base/private comparison and the
source establish the distinction; P2 establishes that the base retains the
named host routes.

### P3: access installation and snapshot probe limit

```clojure
(let [started (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      older (seon.db/as-of database (dec (seon.db/basis-t database)))
      current-plan (seon.call-preparation/snapshot database projection)
      older-plan (seon.call-preparation/snapshot older projection)]
  {:current-basis (seon.db/basis-t database)
   :current-plan-basis (:seon.call-preparation/checked-through-t current-plan)
   :older-basis (seon.db/basis-t older)
   :older-plan-basis (:seon.call-preparation/checked-through-t older-plan)
   :access-schema-present?
   (contains? (:seon.schema.projection/forms projection) :seon.db/access)
   :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
;; All four basis values: 536871144. :access-schema-present? false.
;; :elapsed-ms 707.712209; io-prepl 711 ms.
```

The `as-of` wrapper's reported basis did not distinguish old/new here; this is
**not** credited as proof that two different held plans remain isolated. The
source audit and the existing snapshot landing evidence support the design
correction; its real regression remains an implementation proof obligation.

These bounded probes all completed under one second. No allocated/retained
memory measurement was taken in this re-review; they are not render-cost
benchmarks. Default's unrelated over-second profile rows are not attributed to
these calls. No candidate access implementation exists in this review, so no
candidate publication/adoption, new behavior test, `seon.test/run`, cold gate or
browser proof is claimed. No foreign source repair or archive build was needed
for this documentation-only review; source remained inspectable and the live
REPL returned. No `bin/test` gate or git worktree was used.

Cleanup: MCP retirement returned
`{:seon.store/branch :astra-repl-render-r2, :seon.dev.mcp/retired? true}`.
No other lane's sessions, branches or files were operated on. The only changed
path is this review: **0 source lines, 0 test lines**. Its commit is the handoff;
release this path afterward. `git diff --check` and the canonical
`seon.dev.markdown/validate-file` passed (valid true, no violations; 232 ms
command wall time). Final verdict remains **REVISE** until the open
model and common-path findings above are resolved.
