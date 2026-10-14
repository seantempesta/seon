# Context sanity check: two tasks worked as a Seon agent (2026-10-14)

Owner's question: is the generated context clearly garbage? **Verdict: usable with defects, not garbage.** For both
tasks the block named the task, its instructions, its proofs with their source, and the function to change. An agent
knew where to work after 2 evaluations, `(help)` and the block. The defects below cost tokens and calls, and one of
them misleads, but none of them sends the agent to the wrong owner. Recommendation: do not move context work ahead of
TASK-CHAIN as a whole. Defect 1 is the planned doc-view tier (TASK-CHAIN §3a). Fix defects 2 and 3, which are small,
before the S3a live runs are graded on context.

## Method

Default was HEAD-booted, `:degraded` (16 untested-hot warnings, 13 errored receipts, 1 failed test, 2 agent-error
signatures). I used two fresh agents, `ctx-sanity-a` and `ctx-sanity-b`, each on its own branch. An agent cannot take
a task by itself: there is no agent-side path, and `seon.task/assign!` is root's. So I called `seon.task/assign!` in
root JVM mode with the agent's **branch** connection as `:seon.db/connection`, which writes only that branch. Setup
took 6 calls before the first block.

- **A** `seon.edit/implement-an-unreadable-odd-map-is-not-one-form`: Implement step of a chain; proofs red.
- **B** `seon.program/edn-symbol-text-accepts-a-leading-slash`: a standalone bug; its proof is **already green**.
  d3de85d72 fixed it (chain run 4), and the task was never retired.

## First call, task A: `(seon.context/current-state)` (excerpt)

```
:findings [{:seon.repl/finding :large-value, :seon.eval/value-tokens 10872, :seon.eval/shown-tokens 6038}]
:ms 121, :alloc-mb 139.9      ; request 1243.6 ms (test-step 762.8), step alloc 824.8 MB, gc 899 ms
#:seon.context{:task #:seon.task{:sym seon.edit/implement-an-unreadable-odd-map-is-not-one-form,
                                 :intent "`seon.edit/single-form?` throws instead of answering false ..." (correct, whole)
                                 :instructions "Make the table and the property green by changing `seon.edit/single-form` only ..."}
 :chain #:seon.context{:subject seon.edit/an-unreadable-odd-map-is-not-one-form,
                       :before [#:seon.task{:sym seon.edit/prove-an-unreadable-odd-map-is-not-one-form, :step :prove}]}
 :proofs #:seon.context{:counts {:pending 2},
   :proof-list [{:seon.test/sym seon.edit-test/an-odd-map-anywhere-is-not-one-form, :seon.context/proof-status :pending,
                 :seon.test/source "(deftest ... [\"(f {:a 1 :b})\" false]]]\n "…+128}       ; the proof itself is cut
                {:seon.test/sym seon.edit-test/every-source-holding-an-odd-map-is-not-one-form ... "…+692}]}
 :step {:seon.task/status :working, :seon.task/step :implement,
        :seon.source/refusals [{:seon.error/operation seon.cluster.source/accept-merge!,
                                :seon.error/type :seon.source/red-test-error,
                                :seon.error/message "A test of the named run is not green; a merge commits only when every test is.",
                                :seon.source/refused-test-run "15ce6d7bee8b",
                                :seon.source/red-tests [{... :seon.source/verdict :unknown} ...]}]
        :seon.context/start {:seon.render/operations [(in-ns 'seon.edit) (in-ns 'my.edit-test) (in-ns 'seon.edit-test)]}}
 :must-reads
  [{:seon.fn/sym seon.edit/single-form? :seon.render.ns/source-lines [... whole, 7 lines]}
   {:seon.fn/sym seon.edit/parse-root   :seon.render.ns/source-lines ["(seon.error.refusal/diagnostic (java.util.Date.) :my.edit/"…+28 ...]}
   {:seon.fn/sym seon.sci.reader/read ... :seon.ref/references [#:seon.ref{:name sci/ns, :problem-kind :unresolved,
        :message "Nothing of this name or path exists on your branch. ... tell root with my.message/send ..."}]}
   {:seon.fn/sym seon.edit/single-form  :seon.render.ns/source-lines ["(defn- single-form" "  {:malli/schema ..."…+54 "  [source message]" …17]}  ; the owner, cut
   {:seon.fn/sym clojure.core/count ... :seon.render/operations [(my.program/callers 'clojure.core/count) ...]}   ; + odd?, 4 test.check Vars, 2 rewrite-clj
   {:seon.ns/name seon.edit, :seon.ns/requires [], :seon.repl/rows [...]}        ; requires are [rewrite-clj.zip seon.error.refusal]
   {:seon.ns/name seon.turn, :seon.ns/requires [], :seon.repl/rows [[seon.turn/close-call …2] …64]}    ; cited only in a parenthesis
   {:seon.test/sym seon.edit-test/form-edit-preserves-every-unselected-byte,
    :seon.test/reach-unknown "No admitted execution is recorded for this test." ...} ...]
 :turns {:my.agent/turns-left 100, :seon.task/status :working, :seon.context/namespace user}}
{:seon.turn.loop/tests {:proofs {:seon.test/passed? false, :seon.turn.loop/red [both proofs]}}}   ; contradicts :pending above
{:seon.repl/request #:seon.cost{... about 450 tokens of cost detail on every reply ...}}
```

`(help)` costs about 1.9k tokens, 0 ms. It teaches `my.task/refine!`, which matches the installed `my.task` (the
`update!` in TASK-CHAIN §3a has not landed).

## Calls to understanding

- **A.** At call 2 (help, block) I knew the owner; the instructions name it exactly. Call 3,
  `(my.program/source 'seon.edit/single-form)` with a probe of `seon.sci.reader/read` on `"{:a 1 :b}"` (1 ms, 5 ms),
  confirmed the plan: the reader answers an event carrying `:seon.sci.reader/error` with `:error-kind :odd-map`. A
  doc view (TASK-CHAIN §3a) would have saved that call.
- **B.** The block showed the whole source of `seon.program/edn-symbol-text?`, but the work was already done. The
  first block said `:working` and `:pending` while the same reply's trailer said `passed? true`. The status turned
  `:done` only on the next call.

## Cost

| Reply | Form | Request | Alloc |
|---|---|---|---|
| First held-task block, A | 121 ms | **1244 ms** (test step 763 ms) | 140 MB form, 825 MB step |
| First held-task block, B | 71 ms | 924 ms | 92 MB form |
| Later block reads | 45–72 ms | 250–340 ms | 45–96 MB |
| Ordinary forms | 0–20 ms | 110–250 ms | |

The settle stage takes 100–170 ms of every request. The very first eval request of a fresh agent spent 273 ms in
custody (branch acquisition).

## Top 5 defects, ranked by cost to an agent

1. **The must-reads spend the budget on the wrong things.** The first block is 10.9k tokens, cut to 6.0k. The cut
   falls on the two things the agent needs whole: the proof's source and the function to change
   (`seon.edit/single-form`, shown as 3 lines and "…17"). The kept space goes to 10 library Vars (`clojure.core/count`,
   `clojure.core/odd?`, the test.check generators), each offering `(my.program/callers 'clojure.core/count)` and
   `(my.program/tests-reaching …)`, and to 4 whole namespace directories (`seon.turn`, 65 rows) that the instructions
   cite only in a parenthesis. Every source line is also clipped mid-string ("…+28"), so it cannot be read.
   - **Producers:** `seon.context/task-sections` (selection and distance), `seon.render.ns/function-ai` (operations
     for library Vars), `seon.render.ns/render-ai` (namespace rows).
   - **Fix:** the TASK-CHAIN §3a doc-view tier. Place the proofs and the step's named change targets first and whole.
     Show a library Var as its doc line with no callers or tests-reaching operations, and a namespace as its name
     and doc only.
2. **A phantom merge refusal appears in the step section.** Before the agent has done anything, the block shows an
   error map from `seon.cluster.source/accept-merge!` ("a merge commits only when every test is") with verdict
   `:unknown`. Both agents' branches showed the same run id, `15ce6d7bee8b`. The same red tests then appear a second
   time under `:seon.context/red-tests`, with their failures repeated in the next block.
   - **Producers:** `seon.context/step-status`, which labels a `seon.cluster.source/results-verdict` preview as a
     merge error, and `seon.context/step-section`.
   - **Fix:** the step section reports only status. Red proofs appear once, under proofs. A preview is not an error
     value.
   - Unverified: why two branches show one run id.
3. **The block is stale within its own reply, and a done task stays filed.** The block renders before the eval
   request's test step, so the first block says `:pending` or `:working` while the trailer says red or green. Task B
   was green on HEAD yet was filed and assignable: the merge in d3de85d72 never retired it.
   - **Producers:** `seon.context/current-state`, which runs before the `seon.turn.loop` test step; task retirement
     at merge (`seon.merge/merge!`).
   - **Fix:** build the block after the test step. Retire a task in the merge that turns its proofs green.
4. **Nested docstring references show up as task problems.** `sci/ns` (alias-qualified, in the
   `seon.sci.reader/read` doc) and `:clj-kondo/unknown-namespace` (in the `seon.program/edn-round-trip-symbol?` doc)
   appear as `:unresolved`. Each comes with advice to "tell root with my.message/send", although the task never cites
   them.
   - **Producers:** `seon.ref/resolve-name`, the inner level, and `seon.ref/problem-messages`.
   - **Fix:** a problem found one level in is a problem on that var, not in the task block. Correct the two
     docstrings to fully qualified references.
5. **Every reply carries overhead and costs too much.** Each reply ends with a `:seon.repl/request` map of about 450
   tokens (`:jvm-window`, `:shared`, `:stages`, `:writer`, `:commit`), even on a value of `false`. The first held-task
   request runs over 1 s, and each block read allocates 45–140 MB.
   - **Producers:** the reply assembly in `seon.mcp/eval-clj`; `seon.context/current-state`, whose units are not yet
     cached (TASK-CHAIN §3 "Context assembly costs what changed").
   - **Fix:** the reply carries `:ms` and `:alloc-mb`, and the detail sits behind a result handle.

## Smaller findings

- **Filed:** `seon.render.ns/a-namespace-must-read-shows-empty-requires`. A cited namespace shows
  `:seon.ns/requires []` although the database holds `[rewrite-clj.zip seon.error.refusal]` for `seon.edit`, because
  the resolver's pull omits `:seon.ns/requires`.
- **`resources/seon/help.md` disagrees with the block.**
  - It says must-reads carry `:seon.fn/source`; the block uses `:seon.render.ns/source-lines`.
  - It promises `:seon.context/merged` on the steps before; task A's prove step shows none.
  - It still describes the subject/children chain model, which TASK-CHAIN supersedes.
- **Stay-green tests have no status.** The tests the instructions say must stay green show only "No admitted
  execution is recorded for this test", so their status is unknown to the agent.
- **`my.program/source` prefixes a comment.** Its value starts with `;; src/seon/edit.clj:148`, a comment line next
  to data with a file:line.
- **No agent-side task acquisition.** An outside Claude Code agent needs root's `assign!` to get a block at all,
  which matters for the plan's §0 "Live runs (2)".

## Cleanup

Both probe branches were retired through `my.agent/retire-branch!` (`:agent-f73dac5c8012` and `:agent-954f70c2a276`;
`retiring? true`, so each is deleted when its run exits). The assignment rows were written on those branches only.
