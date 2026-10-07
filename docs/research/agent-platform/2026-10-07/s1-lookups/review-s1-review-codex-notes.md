---
title: S1 lookup design review with root decisions applied
type: research
status: reviewed — revisions required before implementation
created: 2026-10-07
lane: s1-review-codex
---

# S1 review

**Verdict: revise before implementation.** The deletion direction is right. The task lookup is not yet sufficient
for the opening, and several proposed contracts contradict the values the design promises. Keep the existing
SCI/DB owners; these gaps do not need another context builder, resolver registry, or proof engine.

Root's decisions are applied throughout: qualified `clojure.repl/doc|source|dir|find-doc` return data; declared
contracts in `doc` are a separate slice; delete the unwired `seon.agent.loop` in S1. These are settled, not questions.
This review changes only this note. No model/provider calls, test runs, source adoption, reload, restart or reset.

## 1. Findings, ranked

### P1 — task documentation reads the wrong world and loses the opening's references

Design §4.3 and the retained patch's `var-documentation`/`task-documentation` read the task row from the evaluating
branch, pull only symbol and intent, and return `task-edges` subjects/proofs. Two distinct omissions:

- Tasks live at their task home. `src/seon/task.clj:340-357` already provides `home`/`home-connection`;
  `src/my/task.clj:78,106` uses it. A branch forked before a task was created does not hold that task, and a task
  revised at home remains stale on the branch. The stopped loop-pass patch explicitly reads task declarations at
  home (`stopped-wip/loop-pass.patch:126-139,257`). S1 drops that distinction.
- `task-edges` reads reverse task tags, not the task's explicit `:seon.task/must-read` (`task.clj:280-297`).
  The existing context producer unions them (`src/seon/agent/loop.clj:182-198`). A live specimen has two must-read
  references but empty subjects/proofs; see §5. Returning only subjects loses both references.

**Smallest correction:** read the declaration once at its declared task home, preserve its `must-read` values,
and read program subjects/proofs/results against the worker's program world. Make those two database inputs
explicit in the owner composition; do not copy task declarations onto branches or recreate loop-pass's task API.
Let the ordinary task `doc` carry the references that S4 uses. A current explicit historical-DB lookup must remain
possible; define how an explicitly supplied database differs from the ordinary worker default.

**Proofs:** task created after branch fork; task revised at home; task with must-read and no reverse tags; branch
program proof changes without copying home proof results. Through the lookup entrance, assert the data and custody.
Existing task: `seon.task/tasks-have-no-done-when-and-name-subjects-only-through-must-read`.

### P1 — “current green” is not what `latest-results` proves

Design §4.3/§8 retains `proof-status` and the schema describing a test's **current status**. The retained patch
(`stopped-wip/repl-lookup.patch:300-325`) calls a result green whenever fail+error is zero. It does not verify the
program/input evidence, and does not require positive assertion evidence.

`src/seon/test/runner.clj:2132-2183` selects the latest native admitted execution on the branch. It does not compare
its tested reach with today's program. `src/seon/test.clj:1562-1592` (`verified?`) performs the missing checks:
positive pass count, input digest, current reach digest, source and fixture-observation rules. Changing a reached
definition after a green result leaves a latest green execution; it does not establish that the new program is green.
This matters directly to the agent's “done means proofs green” loop.

**Smallest correction:** show the existing result as historical evidence with its tested basis and an accurate
label, or compose with the existing proof owner for a current verdict. Do not implement another green predicate
inside the lookup owner. Preserve typed unknown/incomplete results. If current proof is selected, batch the
owner's work and measure it; the design's 0.16 ms latest-results number cannot price verification.

**Proof:** green result → reached definition change → lookup must not say current green; incomplete latest
execution must not expose an older green. No tests were run here. This is a proposal defect demonstrated by source,
not a claim that a new implementation was installed.

### P1 — the contracts reject promised lookup cases before their bodies can answer

The schema fragment retained by design §8 is not ready to keep wholesale
(`stopped-wip/repl-lookup.patch:31-54,443-455`):

1. `reference` admits symbol/qualified keyword only; `reference-in` and `documentation-value` use it. But §4.2
   promises `(doc 'x)` returns `documentation-unavailable`. The macro passes `(quote x)` as a list, outside the
   contract, and the widened error member has the same problem. Live `(doc 'seon.id/symbol-in)` currently yields
   the recorded ClassCastException (§4); the proposal must specify where arbitrary authored syntax is classified.
2. `lookup-pattern` is a nonempty string, although §3 promises SCI's string-or-regex behavior. The live qualified
   `apropos` accepts `#"^symbol-in$"` and returns `[seon.id/symbol-in]`. Empty strings are also supported by SCI;
   no correctness failure justifies the new minimum length restriction.
3. `var-summary` requires a qualified symbol, but §3/§8 now require `find-doc` to include namespace metadata.
   A namespace's name is a simple symbol. The library's namespace branch is at
   `reference-code/sci/src/sci/impl/namespaces.cljc:2359-2361`; a scratch namespace with a docstring really appears
   in that walk (§5). Keeping the proposed var-only output contract makes that result fail instrumentation.

Malli validates input before entry and output after the body (`reference-code/malli/src/malli/core.cljc:2210-2245`,
`-instrument-f`; public wrapper `-instrument`, :3166-3187). A body-level fallback does not cure an input refusal.

**Smallest correction:** one syntax-classification boundary at the macro/data-function entrance; name the actual
accepted pattern grammar; let the existing summary shape represent both Var and namespace symbols. Use existing
registered predicates where available. No second parser and no broad catch. Add these rows to the one lookup table.

### P2 — namespace rendering rebuilds the directory's expensive work, and §4.4 has been split out

Design §4.6 proposes namespace doc → public names → one `documentation-value` call per name. The current owner
already pulls the names (`src/seon/render/ns.clj:219-233`); the retained patch repeats those reads and then pulls and
processes every function separately. With root's split, `function-doc-map` still executes `arglists-reader`,
`agent-documentation-contract`, the contract cache lookup and schema postwalk (`eval.clj:1916-2017`). The proposed
`:contract` column is absent until the other slice lands. The claim “contract compile gone” and the first-call
`doc <2 ms` prediction in §7 no longer apply to S1.

**Smallest options for root:** (a) recommended: make the namespace's listing as small as `dir` (names, summaries,
arglists) and leave contracts to individual `doc`; one bounded-by-results batch read from the existing DB owner
serves the JVM renderer. (b) preserve the current contract-rich namespace behavior using one pull of the rows and
the existing transformation; accept and measure the contract cost until the separate slice. Neither option needs
N new DB reads. Do not force an ambient SCI context into the JVM renderer.

A namespace-render measurement is missing from §7. S1 must measure it separately; making bare `dir` cheap does not
prove this caller cheap. Current single-call measurements are in §5. Existing allocation task:
`seon.sci.eval/dir-and-help-allocate-tens-of-megabytes-per-call`.

### P2 — refresh the qualified aliases with the bare ones, and prove actual adoption

With root's qualified-name decision, §5.2's “one extra assoc-in per name in build-base-ctx” is incomplete as an
installation proof. `build-base-ctx` copies host Vars (:348-430); acquisition installs first-party namespace Vars
(:2550-2554) and refreshes bare help specifically to avoid retaining the boot copy (:2564-2568). The proposed
`refer-declared-bindings!` refreshes bare names, while the qualified copies are described only at base construction.
SCI `add-namespace!` merges namespace entries (`reference-code/sci/src/sci/core.cljc:669-673`); a refer is not a
promise to dynamically resolve a replacement Var later.

**Smallest correction:** have the same installer point both surfaces at the current acquired target Var at the
same lifecycle boundary. No second list of implementations. Verify both roots after a real target change and
retained-context acquisition, including interpretation on a branch. Merely invoking the installer twice is not
proof: the old test asserts the fresh macro constructor changes identity; that constructor is being deleted
(`test/seon/sci/documentation_test.clj:155-173`). Rewrite its setup around the behavior that survives.

Analysis evidence must cover both spellings and a `clojure.repl :refer` caller. The declared bare refer rows
(`src/seon/fn.clj:878-896`) do not by themselves establish what a qualified call's recorded edge names. This is an
unproven acquisition/analysis requirement, not a demonstrated failure of unbuilt code.

### P2 — deletion closure omits resources and surviving documentation

Root's loop deletion should also delete `resources/seon/schemas/seon.agent.loop.edn` (all four keys are consumed
only by the retired namespace/test), besides `src/seon/agent/loop.clj` and `test/seon/agent/loop_test.clj`.
`rg` confirms no production caller outside that namespace. The schema was absent from S1's deletion inventory.

`my.program/source` is also mentioned by surviving `search` and `declaration-diff` docstrings
(`src/my/program.clj:918,1039`), not just help. `resources/seon/tasks/seon.sci.eval.edn:123,190,204` holds must-read
references to deleted lookup producers. Audit these task references in publication; do not leave dangling current
references or claim deletion closes a task whose remaining subject survives. Keep private `declaration`:
`my.program/declaration-pair` still uses it (:981). The claim that `nearest` duplicates `apropos` is behavioral
shorthand: `nearest` also reverse-matches substrings and ranks a capped set; deleting it is a behavior change, not
an equivalent algorithm replacement.

## 2. Composition and library audit

The core proposal serves the right step: forms in an agent opening or later lookup return values, then the existing
value printer prints them. It adds no evaluation, recording, cache or loop. Deleting directory/help render wrappers
also deletes the help HTML renderer's embedded EDN scanning (`bootstrap.clj:73-97`), an additional parser the design
did not count. Deleting the unwired loop removes another context/text producer. Good deletions.

Verified against design baseline `ec5f13d2f` and archive `87e6991a2`:

| Claim / seam | Evidence and limits |
|---|---|
| Declared bindings plus duplicate installs | `seon.sci.binding.edn`, `program.cljc:184-201`, `fn.clj:878-896`, `eval.clj:348-433,2079-2150,2391,2566`. Real duplication. Declaration discovery is not itself another evaluator. |
| DB docs repeat SCI metadata | `function-doc-map` and `directory-value` versus copied Var metadata. Real overlap, but not interchangeable worlds: JVM rendering has DB facts and no SCI context; scratch `def`s can be SCI-only. |
| Source duplication | `eval.clj:2106-2135`, `my/program.clj:837-893`. Stored branch source remains necessary. Clojure `source-fn` (:147-170) supplies classpath extraction for actual host/library Vars; do not copy that reader. No claim that it supplies an interpreted scratch definition's source. |
| SCI lookup walk | SCI pin `ce0aa53b9bf35029e0bc27a962d799c196a37874`; `namespaces.cljc:2303-2382,2409-2440` matches the named owners. `apropos` already returns data. `find-doc` lacks a data-returning callback seam: a small adaptation is justified. |
| Resolver helpers | SCI public `var->symbol` (:698-703), `namespace-bindings` (:705 onward), `find-ns` (:675), `all-ns` (:680) are existing seams. Prefer them to reaching into `:env`; do not use `var->symbol` to hide the known malformed interrupt metadata. |
| Unknown `dir` namespace | SCI's `dir-fn` delegates to `sci-the-ns*`; checking existence first is justified. Namespace aliases must be expanded before that check. `doc` of an alias alone is not promised by SCI's own doc; either document that scope or test the new behavior explicitly. |
| `load-host-namespace` mismatch | Installed contract requires `:ctx` (`eval.clj:263`), SCI's source reader passes only namespace (:2424-2425). Static mismatch confirmed; not invoked directly or claimed as a new live reproduction. |
| Malli | Pin `43c6dca8d2d8df3162b13725939954807be4a4ed`; `m/function-schema`, `m/-function-info`, `m/form` already own contract structure. Preserve them until the separately approved declared-contract slice removes that display. |

The exact source citations for the central lookup/install owners still hold. HEAD advanced during review with
file-reader's `e843f1d52` changes to `task.clj` and the static checker; those did not change task-home or task-edge
semantics. Test call sites were searched in src/resources/script/test, including quoted names. The design's broad
caller list is substantially correct; the missed closures above are actionable additions. Historical timing/token
rows are measurements from another window, not invariant costs. Their whole-pass allocations/calls/store bytes,
all input kinds and cold-cache claims were not independently reproduced here. No after-change budget is proven.

## 3. Cross-design reconciliation

- **repl-lookup:** its old installer, printing qualified forms, var-only find-doc walk and declaration deletion
  are superseded by this S1 design/root decisions. Do not replay the stopped patch wholesale; findings above apply
  to the pieces §8 says to keep.
- **loop-pass:** there is no `loop-pass/design-notes.md` in this checkout; the stopped patch and saved runs are
  evidence, not installed APIs. Its task-home/must-read behavior is useful evidence for finding 1, but its
  `seon.agent.loop/task`, context builder, opening transaction and separate help entry shape must not be revived
  by S1. S4 should consume the one corrected lookup surface and ordinary pass.
- **walk-delete:** its design §2-3 still expects `seon.agent.loop/context` to replace the prompt and assigns
  bootstrap/help to loop-pass. Root's deletion and the approved spec supersede that dependency. Reconcile the
  S4 seam in place before work resumes. Shared `web_debug_test` history coverage survives even when old context
  views disappear; coordinate that conversion once.
- **reader-prose:** no standalone design note exists here; its stopped patch touches `forgive`/batch.
  Approved spec §4.3 controls. S1 may teach fenced code now; do not teach “lone symbols evaluate outside fences”
  as the future model-reader rule. `(help)` resolves-head testing is necessary but does not prove prose instructions
  or example syntax are true. S2b-1 owns the later wording change.
- **Current ownership:** `s5a-reply` owns `src/seon/sci/eval.clj` and adjacent turn/recording files; its uncommitted
  tree was not loaded. `s3-prep` owns its own design. This review has no file overlap and starts no lanes.

## 4. Questions for root

1. Should namespace rendering keep contract-rich rows until the declared-contract slice, or take the recommended
   smaller listing now? The latter removes work; neither choice needs a new directory subsystem.
2. Should task/test `doc` show an explicitly historical last result, or promise current verified proof? Please pin
   that meaning before retaining `proof-status`; a bare green label is ambiguous for an agent deciding done.
3. Confirm S4 consumes S1's task-home declaration plus branch proof view, so the stopped loop-pass task reader
   disappears instead of becoming another lookup API. How should an explicit historical DB argument select the
   task declaration? The default opening must still see the current home revision.

## 5. Live evidence and health

Default: pid 75294, start `2026-10-07T11:57:29.639Z`, source archive `ec5f13d2f`. Both `bin/seon status` and
MCP `runtime_status` reported **faulted**; no missing readiness layers. Heap used ~576.6 MB / live ~501.1 MB in
runtime_status; shared cache ~57.0 MB. Health included recording custody failure (`seon.turn.world/record-fault`,
task 3d2fb1b41dc8), `shown-failure` called with nil (`seon.turn/show-row`, existing task 7f5dfb6ad7a2), and the
`entity-emission` missing-DB failure (eight existing tasks). `call-with-projection-state` max 145475 ms is inclusive
scope time; existing `seon.profile/scope-wrappers-report-callback-time-as-work` distinguishes scope time from work. `analyzed-files` max 2365 ms was a status observation,
not measured lookup work. No new reset is indicated by this review.

All SCI forms ran as `s1-review-codex` in `scratch.s1-review-codex`, with one disposable namespace
`scratch.s1-review-codex-doc`. JVM reads explicitly used `(seon.cluster.boot/connection "default")`.

| Form / observation | Returned value; timing; handle |
|---|---|
| Resolve bare and qualified lookup Vars | bare doc/dir/help present; source/apropos/find-doc absent; SCI ctx bound after require; 0 ms; `e043e91385e5f` |
| `(doc seon.id/symbol-in)` | doc parts, arglists, `:in/:out`; 7 ms, 417 calls; `e74abf776ce92` |
| `(clojure.repl/source-fn 'clojure.set/union)` | nil; 2 ms, 173 calls; `ea8fe09c00ea4` |
| `(doc 'seon.id/symbol-in)` | expected current ClassCastException PersistentList→Named, 0 ms; `e4f8d25b99111` |
| `(doc sets)` after require alias | expected current documentation-unavailable; 0 ms, 127 calls; `eb526d95b4537` |
| `(clojure.repl/dir-fn 'sets)` | 12 set-operation names; 0 ms; `ef656a1ebb598` |
| `(clojure.repl/apropos #"^symbol-in$")` | `[seon.id/symbol-in]`; combined metadata probe 4 ms; `eaf24e5553887` |
| Pull task `seon.task/tasks-have-no-done-when-and-name-subjects-only-through-must-read`, then `task-edges` | `:must-read [seon.task/assign! seon.task/task-edges]`, but subjects/proofs/referrers all empty; `eec33a5b33e48` |
| Pull installed schemas `:seon.sci.eval/reference`, `:seon.sci.binding/doc` | new reference absent; binding target is seon.bootstrap/doc; same handle |
| Create docstring scratch namespace, inspect `all-ns` | `[scratch.s1-review-codex-doc "s1reviewnamespaceprobe"]` exists in metadata walk; `e3152ee6cc737` |
| Pull newly filed config task from own branch and task home | branch nil, home has its symbol; 2 ms, 144 calls; `e3152ee6cc737` |

The task-home probe was exactly a pull by `[:seon.task/sym task-sym]` on `(seon.db/db)`, followed by the same pull
on `(seon.db/db (seon.task/home database))`; task-sym was
`seon.config/contract-check-require-enters-config-flow-load-cycle`, created after this agent's branch fork.
This demonstrates the missing task row without changing program code or running any tests.

JVM cost probe (handle `edff066f0735f`): held database, `ThreadMXBean/getThreadAllocatedBytes` on the calling
thread before/after each owner call, and `System/nanoTime`. One call each, no forced cache clearing:

| Exact owner call (database = explicit default DB) | ms | allocated bytes |
|---|---:|---:|
| `(seon.sci.eval/documentation-value database 'seon.id/symbol-in 'seon.id/symbol-in)` | 3.172 | 1,952,904 |
| `(seon.sci.eval/directory-value database 'seon.task true)` | 66.179 | 83,966,696 |
| `(seon.bootstrap/help-value database "root")` | 21.445 | 20,105,856 |
| `(seon.sci.eval/stored-source database 'seon.id/symbol-in)` | 1.235 | 523,824 |

These are owner-call costs, not whole passes. JVM reply omitted per-form timing/call counters, so those counters,
commits and store bytes were not inferred. Allocation confirms the existing dir/help defect. It does not validate
S1's guessed output sizes or the <5 MB whole-pass target. Probe output was small structured data except the task
search, whose long intents the value printer cut with runnable digs; no second text printer was introduced.
My initial `sci.ctx-store/get-ctx` probe omitted its require and returned Unable-to-resolve; corrected immediately,
0-2 ms, no task for my call mistake. Expected quoted-symbol/alias failures above were deliberate reproductions.

## 6. Verification, tasks, and landing

- Archive: `87e6991a2da911099cb0a7dd2b2438b997ef2672` plus this documentation-only diff; no program inputs differ.
  `clj-kondo --lint src script test --cache false --parallel` on the archive with its config: **0 errors,
  1482 warnings, 5197 ms** (exit 2 for warnings). This is not a zero-warning pass; no warning came from this note.
- Standalone **compile check, not test run**: requiring `seon.contracts-compile-test` in the archived JVM failed
  through `config.clj:595` → schema predicate `seon.flow/proc-launcher?` → `seon.fault:182`, with
  `No such var: config/effective`. No default mutation. Process exited; its exact failure is retained in the
  new task and this note. We do not claim that this direct namespace-load route proves canonical boot broken.
- Completed compile check using the already installed checker via MCP JVM, on all **475 archived src/test files**
  and the archive's packaged schemas, with the explicit default carried projection: **findings []**, 1243.213 ms
  (evaluation slow finding 1248 ms). No `load-file`, reload, intern, or root alteration. Checker is the older loaded
  revision (line 849 vs archive line 883); this is static compilation evidence, not a fresh candidate boot proof.
  The corpus cost is proportional to all files and links the existing
  `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`; allocation was not
  measured for this check. The newer checker is not installed in default.
- Created after searching stored tasks and task files: `seon.config/contract-check-require-enters-config-flow-load-cycle`
  (owner-minted id `b7bb2aae3cb2`). It names a proposed isolated load-order regression and this note as evidence.
  Design-only findings remain in this review for root reconciliation; they are not invented installed failures.
- Existing issues linked: missing task references (finding 1), dir/help allocation, SCI interrupt metadata
  (`seon.sci.eval/sci-interrupt-string-vars-carry-core-metadata`), help's false tools and code-evidence test,
  contract-display defects reserved for their own slice. No attempt was made to fix another lane's files.
- Skill limitation: REPL skill still claims an uncontracted defn is refused at installation; the current lane rules
  and design's already-filed `seon.sci.eval/an-uncontracted-defn-returns-no-warning` contradict that. Do not carry the
  stale sentence into S1's help. Skill edits are outside this notes-only assignment.

**Focused central tests requested; not run here:**

1. Convert `seon.sci.documentation-test/retained-context-receives-the-current-repl-macro-through-its-core-alias`,
   `retained-context-receives-new-bare-test-referrals`, `bare-test-macros-resolve-without-namespace-referrals`,
   `directory-observes-a-function-installed-after-context-acquisition`,
   `documentation-is-returned-data-without-a-second-printed-copy`, and
   `source-at-the-agent-repl-prints-the-stored-declaration` (now data).
2. New proposed `seon.sci.documentation-test/each-lookup-answers-its-reference-kind-as-data`: add regex/empty
   pattern, quoted/non-reference syntax, namespace find-doc hit, fresh and retained qualified aliases,
   program/library/private/test source and errors, and actual stored analysis-edge rows.
3. New proposed `seon.sci.documentation-test/task-doc-reads-home-declaration-and-branch-proofs` and
   `seon.sci.documentation-test/lookup-does-not-certify-a-stale-test-result` for findings 1-2.
4. `seon.render.ns-test/a-namespace-renders-as-one-graph-node`,
   `seon.render.web-debug-test/passive-ledger-never-reconstructs-directory`,
   `seon.render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups`, and
   `seon.help-test/an-agent-identity-offers-help-as-its-operation`;
   proposed `seon.help-test/help-names-only-installed-forms`. Retire loop tests with their namespace/schema.
5. Root's publication/adoption check of binding additions and schema retirements, followed by retained-context
   probes. Keep the separate declared-contract slice's tests and performance claims out of S1.

Design check: the composition held for data-returning lookups and deletion of extra render/context machinery.
It did not hold for task custody/references, current proof semantics, or the retained schemas. Root's contract
split also invalidates part of the cost/deletion inventory. The smallest corrections are existing owner composition,
accurate contracts and fewer directory fields; no new subsystem is justified.

Net source/test lines: **0/0**. Commit only this note through bin/lane, then release. Review needs no default restart
or reset; root's eventual S1 adoption/restart is a separate implementation action. Cleanup and change size are recorded below.

Cleanup finding: the own-branch call
`(my.agent/retire-branch! {:seon.store/branch (get-in (seon.db/db) [:config :branch])})`
answered only `{:seon.error/message "run transition refused: admission-closed"}`, with no result handle or timing.
Existing tasks: `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` and
`my.agent/a-refused-retire-leaves-the-agent-admission-closed`. The root-side read still named this review's branch
`:agent-4bbf7bf20fcd`; cleanup retried once through the ordinary root-side retirement entrance for this owned
branch only. No other agent's session was touched.

Root-side cleanup returned `{:seon.store/branch :agent-4bbf7bf20fcd, :my.agent/retired? true}` in **32 ms,
810 calls** (`result/ecb246a19c463`). Both owned shell processes exited; the disposable archive was removed without
following its dependency/config symlinks. Only this Markdown file is committed; no tests are needed for the note
itself. The exact source/test list for S1 is above, for root's central run.
