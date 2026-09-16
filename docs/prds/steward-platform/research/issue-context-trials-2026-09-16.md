---
type: research
status: measured, first round only — the second round was NOT run
created: 2026-09-16
tags: [research, steward, issue, context, trial, measurement]
---

# Issue context trials — first round, one real issue, seven renderings

Plan: [issue-context-trials](../plan/issue-context-trials-2026-09-16.md).
Everything below was measured on an ISOLATED scratch cluster (`trials`,
operator root `tmp/issue-trials-root`, checkout `tmp/issue-trials-wt` at
`db65dc542`), never on `default`. Model `deepseek-flash`, budget 20 provider
turns per session, seven sessions, 131 provider calls.

## 0. Broken things first

The trials found four platform defects. Two of them make the loop the plan
assumes IMPOSSIBLE to close, which is why the second round was not run (the
plan's own stop rule: stop and report if any session shows a platform defect).

| # | Defect | Note |
|---|---|---|
| 1 | A worker `seon.issue/start!` creates while the cluster runs is never armed: work is derivable, no graph exists, nothing runs until the next boot. `arm!` by hand unblocks it. | [note](../../../seon/issues/a-worker-started-while-the-cluster-runs-is-never-armed.md) |
| 2 | An agent cannot make its own source edit live. `my.test/check` reloads only test namespaces; there is no agent-facing adoption; the taught shell workaround exceeds `:seon.config.shell/time-limit-ms` (30 s vs a 60–90 s adoption). | [note](../../../seon/issues/an-agent-cannot-make-its-own-source-edit-live.md) |
| 3 | Pulling ANY `:seon.fn` row from SCI returns the sentence `Restart the JVM to remove stale loaded Var …; it is absent from the published program graph.` instead of the row. Reproduced on `default` too. | [note](../../../seon/issues/pulling-a-function-row-in-sci-returns-a-restart-the-jvm-sentence.md) |
| 4 | `seon.plan/plan` refuses with an unbound rule variable on this cluster, and the refusal reaches the agent's opening as a BARE exception line where its instructions belong. | [note](../../../seon/issues/plan-derivation-refuses-with-an-unbound-rule-variable.md) |

Defect 3 is the one that most damages the design: the issue family's shape is
"the block LINKS, the agent reads source with `doc` or `seon.db/pull`"
(issue-family spec §6). An agent cannot read a function row at all today.

Two further findings, not filed as platform defects:

- **`my.edit/edit` is what the model reaches for.** Three of seven sessions
  called a function that does not exist — `(my.edit/edit {:my.edit/path …})`,
  `(my.edit/edit {:file …})`, `(my.edit/dir-edit! {})` — before finding
  `my.edit/exact!`. The one session that eventually wrote the fix burned a
  turn on `my.edit/edit` first. An `edit`/`old-string`/`new-string` alias is
  the cheapest possible win, or `dir my.edit` must be in the agent's face.
- **The red test is weaker than the issue's acceptance text.** The test asserts
  `(not (:seon.error/kind value))` and `(vector? (:arglists value))`; the
  acceptance says "preserving resolved keyword identities". The fix the worker
  wrote satisfies the test but reads `::text` against the CALLING namespace, so
  it would pass while losing the identity the acceptance demands. Tests define
  done — so the test, not the prose, has to carry that.

## 1. What the trial actually proved

**An agent wrote a real, correct-shaped fix for a real issue.** Candidate
`namespace-picture` diagnosed the bug, located both decode sites, and landed
this with two successful `my.edit/exact!` calls (diff from the scratch
checkout):

```clojure
(defn- read-arglists
  "Decode stored arglists. The value is Clojure reader text produced by pr-str,
  which may carry auto-resolved keywords such as ::text; EDN cannot read those
  tokens. Read it with the Clojure reader, resolving no form."
  [stored]
  (binding [*read-eval* false]
    (read-string (or stored "()"))))
```

applied at `function-doc-map` and at `directory-value`'s `:arglists`. It then
ran the adoption call exactly as taught and was killed by the shell bound
(defect 2). No session reached `resolved-tx`; the ceiling was the platform,
not the model.

While fixing the arglists bug that session hit the arglists bug twice — two of
its five evaluation errors are `Invalid token: ::text`, thrown by the very
`doc`/`dir` path under repair.

## 2. Measures

All from recorded facts. "First test read" and "first edit call" are indices
into the agent's own evaluation sequence (opening included). Cost in dollars
is NOT recorded anywhere — `:seon.ai.attempt` carries token usage but no
price — so tokens are reported instead; at deepseek-flash rates the whole
round is cents, far under the $5 stop threshold.

| Candidate | Block bytes / lines | Turns | Evals | Eval errors | Shell calls | First test read | First edit call | Edit calls | Fix written | Resolved |
|---|---|---|---|---|---|---|---|---|---|---|
| A bare | 84 / 2 | 22 | 37 | 9 | 9 | 25 | 34 | 3 | no (wrong fn name) | no |
| B plan-first | 829 / 16 | 21 | 40 | 13 | 7 | — | 23 | 3 | no (wrong fn name) | no |
| C evidence-first | 2229 / 49 | 22 | 42 | 6 | 8 | 3 | — | 0 | no | no |
| D walkthrough | 1795 / 45 | 23 | 44 | 12 | 7 | 3 | — | 0 | no | no |
| E questions | 863 / 24 | 27 | 45 | 9 | 2 | 14 | — | 0 | no | no |
| F namespace-picture | 160 / 4 | 21 | 32 | 5 | 12 | 20 | 25 | 3 | **YES** | no (adoption bound) |
| G minimal-retrieval | 231 / 4 | 21 | 32 | 4 | 9 | — | — | 0 | no | no |

Provider usage (19 calls each except C and G with 18):

| Candidate | Prompt tokens | Cache-hit tokens | Completion tokens |
|---|---|---|---|
| A bare | 170,052 | 154,880 | 3,660 |
| B plan-first | 160,866 | 144,384 | 5,069 |
| C evidence-first | 191,290 | 172,800 | 3,282 |
| D walkthrough | 173,286 | 155,904 | 3,594 |
| E questions | 169,626 | 152,576 | 3,262 |
| F namespace-picture | 179,234 | 162,688 | 3,988 |
| G minimal-retrieval | 136,752 | 123,136 | 1,877 |

Totals: 1,181,106 prompt tokens of which 1,066,368 were cache hits (90.3%),
24,732 completion tokens, 131 calls. Only the FIRST call of each session
misses the cache — which is the cheapest evidence that the prompt is stable
and additive, exactly as the turn model intends.

Evaluation errors by kind, pooled across the seven sessions (58 total):

| Class | Count | Example bytes |
|---|---|---|
| reply with no form, only prose | 16 | `Your reply had no form; only comments/prose. Send a form.` |
| unresolved symbol | 15 | `Unable to resolve symbol: my.edit/edit`, `my.db/q`, `my.fs/ls`, `my.test/pull` |
| reader failure in the reply text | 10 | `Unmatched delimiter: ) in reply text ")`. `pr-str` writes au`, `EOF while reading, expected ) to match ( at [1,1]` |
| request refused by contract | 7 | `my.note/add! refused request at [:my.note/about]: expected a` |
| the issue under repair | 3 | `Invalid token: ::text`, `Invalid token: ::evaluated?` |
| bound fired | 1 | `The foreign process was terminated when its evaluation reached its time limit.` |
| plan derivation | 1 | `Insufficient bindings: none of #{?leaf__auto__r25775} is bound in …` |
| other | 5 | `Invalid number: 57475-ish.`, `Method getMessage on class java.lang.RuntimeException not al…` |

Two of those classes are worth more than the trial: **prose-only replies (16)**
and **reader failures in the reply (10)** are a quarter of all errors and cost
a full paid turn each. The model writes markdown fences and back-quoted prose
around its forms; the reply reader refuses the whole turn.

## 3. The seven openings

Lines 1–18 of every turn-0 prompt are IDENTICAL: the `(help)` block, the
identity pull, the plan block (which renders defect 4's bare exception line),
and then the issue block. Only the issue block differs, so it is quoted here
verbatim from each session's stored opening evaluations; the complete
124-line prompt of a live worker is in the landing evidence below.

The shared head, once:

```
my.agents.<id>=> (help)
The prompt shows your namespace my.agents.<id> and is drawn for you. Send only ;; thinking comments and forms.
… 15 more lines …
Tools: my.agent, my.background, my.edit, my.fs, my.issue, my.message, my.note, my.plan, my.shell, my.test, my.turn, my.web. Inspect one with dir.

my.agents.<id>=> (seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}] [:seon.agent/id "<id>"])
#:seon.agent{...}

my.agents.<id>=> ;; My plan is my instructions. No step is selected. my.plan/current! selects; completing clears the selection.
(seon.plan/plan {})
Insufficient bindings: none of #{?leaf__auto__r25775} is bound in clojure.lang.LazySeq@bc46c885
```

### A — bare

```
;; My issue. Make its tests pass.
(my.issue/status {:seon.issue/id "6b81305b6804"})
```

### B — plan-first

```
;; My plan's steps carry this issue; its tests decide done.
;; The one call that proves it finished:
;;   (my.test/check
;;     {:seon.test/changed
;;      ["seon.sci.eval/directory-value"
;;       "seon.sci.eval/documentation-value"
;;       "seon.sci.eval/function-doc-map"]})
;; Change source with the digest you just read:
;;   (my.edit/form! {:my.edit/path "<path>"
;;                   :my.edit/expected-digest "<the file's current digest>"
;;                   :my.edit/form {:my.edit.form/head 'defn
;;                                  :my.edit.form/name '<name>}
;;                   :my.edit/operation :replace
;;                   :my.edit/source "<the whole new form>"})
;; The file this issue is about: …/src/seon/sci/eval.clj
(my.issue/status {:seon.issue/id "0d07dcb0f1e9"})
```

### C — evidence-first

```
;; The red test, first: its own source and its last failure.
(seon.db/pull (seon.db/db)
  '[:seon.test/sym :seon.test/source :seon.test/pass-count :seon.test/fail-count
    :seon.test/error-count {:seon.test/failures [:seon.test.failure/message :seon.test.failure/actual]}]
  [:seon.test/sym "seon.sci.eval-documentation-trial-test/documentation-reads-arglists-with-auto-resolved-keywords"])
;; Then each function the test reaches.
(seon.db/pull (seon.db/db)
  '[:seon.fn/sym :seon.fn/doc :seon.fn/source :seon.fn/form-span {:seon.fn/file [:seon.fn.file/path]}]
  [:seon.fn/sym "seon.sci.eval/directory-value"])
… two more, one per function …
;; The problem, last:
;; <the problem text, verbatim>
(my.issue/status {:seon.issue/id "19ee38cbd795"})
```

Each of the three function pulls answered `Restart the JVM to remove stale
loaded Var …` (defect 3). This candidate's opening is the largest and told it
the least.

### D — walkthrough

```
;; One worked way through an issue like this one, in order.
;; 1. Read the test:
;;   (seon.db/pull (seon.db/db) '[…] [:seon.test/sym "…"])
;; 2. Read each function it reaches:
;;   (seon.db/pull (seon.db/db) '[…] [:seon.fn/sym "seon.sci.eval/directory-value"])
;; 3. Read the file around the form:
;;   (my.fs/read {:my.fs/path "…/src/seon/sci/eval.clj"})
;; 4. Replace one form with (my.edit/form! ...), using that read's digest.
;; 5. Make the edit live: the problem above names the adoption call.
;; 6. Observe green:
;;   (my.test/check {:seon.test/changed [… three symbols …]})
;; Every step is a call; nothing here is a summary of a call.
;; The file: …/src/seon/sci/eval.clj
;; Start at step 1.
(seon.db/pull (seon.db/db) '[…] [:seon.test/sym "…"])
(my.issue/status {:seon.issue/id "1c3bf7c4948f"})
```

### E — questions

```
;; Three questions. Answer each one with a form, not a guess.
;; Which function throws?
;;   (seon.db/pull (seon.db/db) '[…] [:seon.fn/sym "seon.sci.eval/directory-value"])
;; What does the test expect?
;;   (seon.db/pull (seon.db/db) '[…] [:seon.test/sym "…"])
;; What else lives beside it?
;;   (dir seon.sci.eval)
(my.issue/status {:seon.issue/id "9f84add56590"})
```

### F — namespace-picture

```
;; I am the steward of these namespaces. The picture first.
(dir seon.sci.eval)
;; One of its issues is mine.
(my.issue/status {:seon.issue/id "6effaf777327"})
```

### G — minimal-retrieval

```
;; My issue. Make its tests pass.
;; One hop more is available, but only if I ask for it:
;;   (seon.issue.opening/context {:seon.issue/id "553f9cc77d73", :seon.render/distance 2})
(my.issue/status {:seon.issue/id "553f9cc77d73"})
```

Nothing ever called `seon.issue.opening/context`. The retrieval offer was
ignored in all twenty turns, and this session did the least work of the seven
(fewest completion tokens, no test read, no edit).

## 4. What one session looked like

Candidate A, abridged from its stored evaluations: read the file, grep with
`my.shell` for the decode sites, `sed` the exact window, confirm
`clojure.edn/read-string "[::text]"` fails while the Clojure reader succeeds,
then at eval 34 of 37 call `my.edit/edit` — which does not exist — and spend
the last turns writing a note for the next session. Fifteen evaluations of
reading before the first attempt to change anything.

Candidate F, the one that wrote the fix: twelve shell calls (grep and sed, not
the offered pulls), the failing `my.edit/edit`, then two `my.edit/exact!`
calls that both applied, then the adoption call that the shell bound killed.

Both reached for the shell over the database. The one opening that pointed at
the namespace and then got out of the way (F) produced the most acting and
the fewest errors; the two openings that scripted the whole path (C, D)
produced the most reading and no edits at all.

## 5. What I would keep, merge, drop — the owner decides

- **Keep `namespace-picture` (F)**, and make it the shape: name the
  namespace, render its picture, name the issue, stop. Smallest block that
  produced work, fewest evaluation errors (5), the only fix written.
- **Merge into it, from `plan-first` (B)**, the two exact completing calls as
  comments. B's block is the only one that put a correct, executable
  `my.test/check` in front of the model; B reached an edit attempt earliest
  (eval 23) of the three that tried.
- **Drop `evidence-first` (C) and `walkthrough` (D)**. The two largest blocks
  produced zero edits. Both spend the opening on reads the agent can make for
  itself, and both are hostage to defect 3.
- **Drop `minimal-retrieval` (G)**. An offer the model never takes is bytes
  that cost cache.
- **Drop `questions` (E)**. Most turns (27), second-most errors, nothing to
  show. Questions without a target invite prose, and prose costs a whole turn.
- **Keep `bare` (A) as the floor it is.** It is within noise of the elaborate
  candidates on every measure, which is the finding: on this issue the opening
  mattered far less than (i) whether `my.edit`'s real function names were
  discoverable and (ii) whether the agent could close the edit→adopt→test loop
  at all.

The honest summary: **one round of seven cannot separate these openings,
because the platform ceiling is lower than the differences between them.**
Fix defects 1–3, add a `my.edit/edit` alias, strengthen the test to the
acceptance text, and re-run; the variance round is worth nothing until then.

## 6. Reproducing

- Authoring script: [issue_context_trials_2026_09_16.clj](issue_context_trials_2026_09_16.clj)
  (`write-test-file!`, `install!`, `start-candidate!`).
- Candidate renderings: `src/seon/issue/opening.clj`; the dial is
  `:seon.config.render/issue-opening` in
  `resources/seon/schemas/seon.config.render.edn`; `seon.issue/render-ai`
  dispatches on it and keeps `:bare` as its floor.
- The scratch cluster, its worktree, and the raw session logs were deleted
  after the measurements above were recorded.

## 7. Deviations from the plan, and why

1. **Seven issue entities, not one.** `start!` derives the worker identity as
   `(seon.id/id [issue-id])`, so one issue admits exactly one worker. Seven
   sessions need seven issue identities; content, refs and test are identical.
2. **The units around the issue were held constant.** The plan varies them per
   candidate; `:seon.render/units` is declared on the schema, not per agent,
   so varying them per candidate would need a second dial in a file this lane
   does not own. Each candidate varies what its own block EMITS instead.
3. **The adoption call lives in the issue's problem text**, identical for all
   seven, because of defect 2. Putting it in a candidate would have biased the
   comparison.
4. **Each worker was armed and kicked by hand** (`arm!` plus one fixed
   message, `"Your issue is assigned."`, from `root`) because of defect 1 and
   because `start!` leaves no unanswered wake for the worker to answer.
5. **`seon.cluster.prompt/prompt`, not `seon.context.capture/prompt`** — the
   name the plan gives does not exist.
6. **The second round was not run**, per the plan's own stop rule.
