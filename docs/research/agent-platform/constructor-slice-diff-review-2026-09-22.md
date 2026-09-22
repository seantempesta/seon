---
type: review
status: complete; read-only, no JVM, no tests, no operator action
created: 2026-09-22
reviewer: Claude Fable 5.1 (Opus), read-only second perspective
subject: README §4 step 1.1 / B3 commits 1-3, the one constructor and caller slice
commits: 20ee7864b, e569532dd, 9bf22ecd9 (evidence 92a97636c)
range-reviewed: git diff 20ee7864b~1 92a97636c
landing-note: docs/prds/agent-platform/landing/lane-b3-constructor-2026-09-22.md
ruling: docs/prds/agent-platform/plan/lane-b3-errors-tasks-dials.md §2a; README §7 "Errors are explicit named schemas"
tags: [agent-platform, lane-b3, errors, review]
---

# B3 constructor and caller slice — diff review

Read end to end before reviewing: `AGENTS.md` (in particular §2.2 facts over
inference, §2.4 total honest bounded boundaries, §2.5 one mechanism accreted in
place, §3 data and schema), `docs/prds/agent-platform/plan/README.md` §4, §6 and
§7, `docs/prds/agent-platform/plan/lane-b3-errors-tasks-dials.md` §0, §2a and §5,
and the landing note. Every `src/` hunk in the range was read; `test/` hunks were
read in full for `error_test`, `refusal_test`, `db_test` and `await_test`, and
sampled elsewhere. No JVM was started, no test was run, no branch was switched,
and no file outside this report was written. The working tree carries a foreign
lane's uncommitted `src/` and `resources/` edits; every finding below was
re-verified by reading `92a97636c` itself (`git show`), not the working tree, and
all line numbers are that commit's.

## What the slice got right

These are verified, not taken from the note.

- **The constructor matches the ruling exactly.** `src/seon/error/refusal.clj:4-25`:
  open input, `at`/`layer`/`operation` required, `message` and `throwable`
  optional, only `:seon.error/throwable` consumed, `:seon.error/frame` and
  `:seon.error/exception-class` derived, output `:seon.error/base`. A Throwable
  with an empty stack trace adds no frame.
- **The facade is gone.** `ns-resolve 'seon.error 'diagnostic` finds nothing at
  `92a97636c`; `test/seon/error/refusal_test.clj:146` asserts it.
- **The seven keys are gone everywhere.** `git grep ':seon.error/diagnostic-'`
  over `src test resources script bin` at `92a97636c` returns zero lines, as does
  the `:seon.refusal/diagnostic-` spelling.
- **No Throwable is returned in a map.** All eight value-position
  `:seon.error/throwable` writes (`src/seon/edit.clj:130,290`,
  `src/seon/ai.clj:1500`, `src/seon/maintenance.clj:454`,
  `src/seon/effect.clj:649`, `src/seon/shell/jvm.clj:458`,
  `src/seon/sci/kernel.clj:582,689`) sit inside a `refusal/diagnostic` call that
  consumes it. `src/seon/effect.clj` no longer puts the Throwable in
  `:seon.error/offending` — the flagship case in B3 §0 is genuinely fixed.
- **`:seon.error/cause` is untouched** and still `:seon.db/ref`
  (`resources/seon/schemas/seon.error.edn:306-307`); nothing writes a Throwable
  to it.
- **Hunt item (6) is clean.** No `:any` widening, no new `[:maybe X]` on a domain
  value (one `[:maybe [:enum :analysis :adoption]]` on a private predicate,
  `src/seon/cluster.clj:678`), no new predicate family, no catch-nil, no
  `Thread/sleep`, no regex, no new tuned constant.
- **The landing note's own arithmetic is exact.** The three implementation
  commits touch exactly 100 distinct files, 2,273 inserted and 4,249 removed,
  net 1,976 removed — all four numbers reproduce.
- **The four new constructor regressions are specific and well chosen**
  (`test/seon/error/refusal_test.clj:93-151`): plain and sorted-map preservation,
  Throwable consumption with the stored ref preserved, producing-caller union
  enforcement through a real `wrap-interpreted` wrapper, facade absence, and a
  literal-map reader path.

## Ranked findings

Severity: **blocker** = information an agent needs to act is gone and the
mechanism survives; **friction** = wrong but recoverable; **cleanup** = residue.

| # | Sev | Site (at `92a97636c`) | What | Rule | Smallest fix |
|---|---|---|---|---|---|
| F1 | blocker | `src/seon/db.clj:2777-2787`; callers `:2867 :2874 :2881 :2891 :2970 :3052 :3083 :3155 :3169`; `test/seon/db_test.clj:1480-1486` | `diff-refusal` still takes `cause`, and the body no longer reads it. Ten call sites pass distinct, non-duplicate values (`::function-not-indexed`, `::database-input-absent`, `::call-shape-absent`, `::ambiguous-call-shape`, `::row-identity-absent`, `::external-sink-reachable`, …). All ten now return the same shape with the same `layer`/`operation`, differing only in prose. The three assertions that named those causes were deleted in the same commit. | Ruling: a `diagnostic-*` value that does **not** copy a sibling is kept under its owning declared member. README §6: a test leaves only with its machinery. | Carry the disposition as a declared member (a bounded closed enum, AGENTS §3) — e.g. `:seon.db/diff-refusal cause` — and restore the three `db_test` assertions. |
| F2 | blocker | `src/seon/db.clj:3629`; producers `:3706 :3711 :3713 :3716 :3743`; deleted assertion in `test/seon/error_test.clj` | `write-owned-values-error` dropped `::diagnostic-cause cause` from `:seon.error/data`. Five distinct component-validation refusals (`::component-cycle`, `::missing-component-schema`, `::multiple-component-owners`, `::missing-component`, `::unowned-entity`) now survive **only** as `(name cause)` interpolated into `:seon.error/message`. The `:seon.db/unowned-entity` assertion went with it. | AGENTS §2.2 — the three banned substitutes include a regex over text; a refusal class must be a query. | Restore under a declared closed-enum member on the refusal and restore the assertion. |
| F3 | blocker | 33 new writes; e.g. `src/seon/shell/jvm.clj:53` (top level), `:64 :422 :461`, `src/seon/ai.clj`, `src/seon/config.clj`, `src/seon/db.clj:1436 :1966 :2607 :2665 :2996`, `src/seon/schema.clj`, `src/seon/test.clj`, `src/seon/maintenance.clj`, `src/seon/render/web.clj` | `:seon.error/source` is repurposed as the replacement `diagnostic-cause` slot. It is declared (`resources/seon/schemas/seon.error.edn:78-85`) as the **normalizer's** `:any` polymorphic input — admission reason: "The error boundary must normalize any value or Throwable that escaped a failing subsystem." Zero of these files wrote it before this cut. `src/seon/shell/jvm.clj` is internally inconsistent: `:53` writes it top-level and `:64`, the adjacent branch of the same `cond`, nests it in `:seon.error/data`. | Hunt item (3) — a renamed key that reintroduces the retired shape. AGENTS §2.5: "different semantics means a NEW KEY with a new name." AGENTS §3: no `:any` outside a proven polymorphic boundary. | Give each retained observation its owner's own declared domain member; if a shared slot is genuinely wanted, declare a new key with its own non-`:any` form. Do not overload the normalizer's input. |
| F4 | blocker | `src/seon/error.clj:517-520` and `:929-931`; nesting sites incl. `src/seon/call_preparation.clj:1283`, `src/seon/cluster.clj:329`, `src/seon/cluster/source.clj:268 :299 :348`, `src/seon/test.clj`, `src/seon/test/runner.clj` | 89 added lines nest a canonical `:seon.error/*` member inside `:seon.error/data`. The reader compensates with `(merge (:seon.error/data source) (select-keys source [:operation :member :expected :offending]))` — **top level wins**. So every site that nested a *deliberately different* value (the landing note's "A conflicting canonical member is retained as data rather than overwriting the caller's existing member") has that value silently overwritten at the one seam that reads it. `call_preparation.clj:1283` is the clearest: top-level `:seon.error/offending` is the candidate set, the nested one is the arguments, and the arguments never reach the render. | Hunt (1) and (3): the retained information does not arrive. | Promote to a declared domain member of the owning error schema (`:seon.call-preparation/offending-arguments` etc.); stop the nesting and drop the compensating merge. |
| F5 | blocker | `test/seon/error_test.clj` (≈180 lines deleted); subjects still present in `src/seon/error.clj` | The deleted `the-default-renderers-accept-an-attribute-shaped-error`, `the-default-html-face-links-committed-evidence`, `specialist-renderers-use-their-declared-evidence` (ten `testing` blocks) and `the-log-line-is-one-line-and-derived` are the **only** coverage of `render-ai`, `render-html`, `instrumentation-prose`, `refusal-prose`, `ai-prose`, `time-limit-prose`, `edit-prose`, `elision-prose`, `elision-html`, `unclassified-prose`, `mcp-prose`, `index-refusal-prose`, `notice` and `log-line`. All fourteen still exist and are **unchanged** by this cut; B3 §2a "Load cycle" schedules them to MOVE to `seon.render.error` in a later slice. | README §6's three questions: this is (c), wanted behaviour of a surviving seam. "Ugly output is a defect" (AGENTS §2.4) has no regression left behind it. | Restore the blocks — only their `diagnostic-*` *inputs* needed retargeting, not their assertions — or move them with the functions in the same slice that moves them. |
| F6 | friction | `src/seon/schema.clj:1798 :1804 :1814 :1819` | `render-contract-refusal!` still destructures `render-contract-cause` and still declares it **required** in its input contract, but the body never reads it. Its two values (`:seon.schema/render-input-does-not-accept-declaring-shape` and `:seon.schema/render-function-has-no-declared-contract`) are the only thing separating the two causes, and the hard-coded message asserts the first — so a render function with **no declared contract** now gets a publication refusal saying its declared input `nil` "does not accept the declaring shape". | Hunt (1); AGENTS §2.4, a refusal names what was missing. | Add `:seon.schema/render-contract-cause render-contract-cause` and branch the message on it. |
| F7 | friction | `src/seon/db.clj:3500` (was a cause compare) vs `invalid-write` `:3348 :3350 :3437` | `(= ::attribute-not-installed (get-in failure [… :seon.error/diagnostic-cause]))` became `(not (get forms (::attribute failure)))`. `::attribute-not-installed` is raised when the **Datahike** schema lacks the attribute; `forms` is the **projection**. An attribute declared in the projection but not installed on the branch — exactly the schema-drift case the "use this missing declared key" hint exists for — no longer reaches the candidates branch. Its regression (`test/seon/db_test.clj`, the `:seon.db/attribute-not-installed` assertion) was deleted in the same commit. | Presence-inference replacing a declared fact (AGENTS §2.2); absence read as a different class. | `(= :seon.error/unknown (:seon.schema/form failure))` — `invalid-write:3348` sets exactly that for this class — and restore the assertion. |
| F8 | friction | `src/seon/cluster.clj:677-686`; producers `src/seon/fn.clj:159-165` and `src/seon/cluster.clj:1907` | `source-change-phase` replaced a kind lookup with a presence conjunction. The `:analysis` branch is correct (`span-refused!` does write all three of `:seon.fn/index-refused`, `:seon.fn/source-path`, `::analysis-span`). The `:adoption` branch is a **behaviour change**: at the baseline `::source-changed-during-adoption` had no producer in `src/` at all (only `test/seon/cluster_test.clj:356` hand-built it), so that retry never fired; keying on `:seon.source/digest-before` matches the live producer, so a publication-digest change now retries once where it previously threw. Probably wanted, but it is unnamed in the landing note, unmeasured, and landed inside a key-rename slice. Also dissolves the one named declaration of "the source changed under this publication" into an inline heuristic. | AGENTS §2.2 facts over inference; README §6 records exact behaviour in the landing note. | Name it in the landing note and add one assertion that a digest-change refusal retries exactly once; consider keeping a declared phase member at both producers. |
| F9 | friction | `script/seon/operator.clj:51` (callers `:62 :337 :417`); `src/seon/cluster/boot.clj:197` (callers `:211 :269 :358 :446`); `src/seon/cluster/reply.clj:46` | Three more dead parameters. `operator` and `boot` discard `:refused` / `:boot-failed` / `:client-failed` / `:argv-failed` while every other member of those maps is identical, so four dispositions collapse into one indistinguishable value. `reply`'s `kind` is benign — `marker` carries `::unreadable`/`::refused-tag`/`::no-forms`, so the three declared schemas stay distinguishable — but the arity still declares a `:qualified-keyword` input nothing reads. | Same as F1. | operator/boot: keep the disposition as a declared member. reply: delete the parameter and narrow the arity. |
| F10 | friction | `test/seon/db_test.clj:1479 :1561 :1689` | `(is (= diagnostic-fields (set (keys (:seon.error/data …)))))` was replaced by `(is ((projection-validator … :seon.error/base) …))`. `:seon.error/base` requires only `at`/`layer`/`operation`, so the replacement asserts almost nothing the original asserted — it is a rewrite to whatever the code now does, not a fix at a retired expectation. | Hunt (7); README §7 "the caller's arity declares its explicit error union … validate against THAT". | Assert the arity's declared error schema (`:seon.db.read/error`, `:seon.db/error-result`) plus the specific distinguishing members. |
| F11 | friction | `test/seon/await_test.clj:77-80`; `src/seon/await.clj:36-55` | The `::backstop-fired` vs `::completion-closed` assertion was deleted with the cause key. No information is lost — the distinction is still derivable from `:seon.await/closed-operation` in the outcome, and `a-port-closing-before-publication-is-not-health` still covers the close path — but nothing asserts the bound-fired case any more. | Hunt (7). | One assertion on `:seon.await/closed-operation` in the expiry test. |
| F12 | cleanup | `src/seon/render.clj:14`, `src/seon/test.clj:3`, `src/seon/config.clj:14`, `src/seon/turn.clj:7`, `src/seon/sci/eval.clj:99`, `src/seon/schema.clj:22`, `src/seon/issue.clj:10` | Seven `seon.error.refusal` requires with zero uses at `92a97636c` (five bare, two aliased). They re-add exactly the namespace load edge B3 §2a "Load cycle" exists to remove. | AGENTS §2.5 accretion in place; standing order to retire drift on sight. | Delete the seven requires. |
| F13 | cleanup | `src/seon/error/refusal.clj:33-41`; `docs/seon/issues/{a-call-preparation-facet-requires-unstorable-candidates,sci-error-unions-name-indistinguishable-test-facets,stored-runner-facets-require-an-unstorable-offending-value}.md` | "facet" in new material. The `refusal` docstring still says "the COMPLETE canonical facet population" and names `seon.error/facet-keys` (the claim itself is still true — the drift test survives at `test/seon/error/refusal_test.clj:47`). More pointed: the three issue notes above were **created by `2ccf5bc90`**, the commit whose subject is "docs: retire 'facet'". | Owner ruling 2026-09-22; errors are explicit named schemas. | Reword the docstring; rename the three notes' titles and bodies, or record why the source-side spelling stays until B3 commit 5. |
| F14 | cleanup | `src/my/program.clj:527-530` | Leftover `(merge (:seon.error/data (refusal operation report affected)) {})` after the `:schema-retraction-unavailable` cause was deleted. | Residue. | Drop the `merge`. |

## Established versus unproved

**Established by reading the tree at `92a97636c`:** the constructor's contract and
body; the facade's deletion; zero surviving `diagnostic-*` keys in
`src test resources script bin`; all eight Throwable sites consumed by the
constructor; `:seon.error/cause` unchanged as `:seon.db/ref`; no `:any`/`:maybe`
widening, sleep, regex, new predicate or tuned constant; the 100-file /
2,273 / 4,249 / net-1,976 accounting; every finding F1–F14 including the exact
producers, call sites and the merge direction in `src/seon/error.clj:517`.

**Unproved here, and not provable read-only:** every run id
(`d072f5ac08a1`, `fbfe245b69f2`, `75efe05d99a2`, `1b8879d8640a`, `cd3b4e19857e`,
`bc5b325de5ed`, `16c5872003db`) — those are database facts on `default` and this
review started no JVM; the three claimed clean-archive HEAD loads
(`tmp/b3-head-first-load.log`, `-blob-`, `-constructor-`, `-evidence-`); the
167 ms after-probe result; and the `bin/seon status` / MCP readiness observation.
The `tmp/b3-*` artefacts (46 files) are present on disk and were not opened.

**Two note claims that a reader should not take at face value.** (i) The
unchanged-HEAD baseline (`tmp/b3-baseline-owner-reds.log`, 92 tests / 11 failures
/ 7 errors) covers only `seon.error-test seon.instrument-test`; the final focused
request reports 26 failures and 11 errors across 96 tests, so most of that red
list has **no** attribution baseline and the note's "these are existing reds" does
not extend to them. (ii) The note says "no input/output validation was relaxed in
response to a red test" — true of production contracts, and F10 shows it is not
true of the test expectations.

## The reds, through README §6's three questions

The note names five red areas. Classifying each by "deleted machinery / retired
assumption / wanted behaviour of a surviving seam":

1. **Stale published-fixture contracts** — the copied database fixture supplies
   the old constructor, registration and await contracts in database scopes
   (`seon.error-test`, `seon.instrument-test`, `seon.await-test`,
   `seon.call-preparation-test`, `seon.cluster.armed-test`). **(b) retired
   assumption**, correctly classed, and correctly assigned to the ambient
   projection transport cut (A1-12 / step 1.4) rather than repaired by widening.
2. **Schema-shape checks and the registry-derived complete-union checks**
   (`seon.schema-test`, `seon.error/refusal_test`'s union drift entries,
   `seon.contracts-*`) — **(a) deleted machinery**: A1-13 retires the schema-shape
   family and README §7 retires the registry-wide union derivation. Correct.
3. **Rendering-prose expectations** (`seon.error-test`, `seon.render*-test`) —
   **(c) wanted behaviour of a surviving seam**, not (a). The fourteen render and
   prose functions are unchanged in `src/seon/error.clj` and are scheduled to
   MOVE, not to die. The slice resolved this red by deleting the tests (F5). This
   is the one classification the note gets wrong.
4. **"The read wrapper's declared error schema refusals prevent several
   recording/occurrence tests from reaching their subject"** — **(c)**. The
   recording and occurrence path is B3's own surviving seam and the refusing
   wrapper is its own arity declaration; a wrapper refusing its owner's return is
   a defect to fix at the owner, not a verification limit. The note files it
   under "Verification limits and foreign boundaries".
5. **The SCI-only argument-list expectation** under the published fixture —
   honestly recorded as unresolved evidence, unclassified. Leave it; it belongs
   with (1).

So: two areas correctly retired, one honestly parked, and **two (items 3 and 4)
that the three questions put in class (c) and the note puts in "verification
limits"**. Item 3 was closed by deleting the tests; item 4 is open.

## Verdict

**Keep with fixes.** The core of the slice is right and is the hardest part to get
right: the constructor's contract is the ruling byte for byte, the facade is gone,
all 302 sites converted, every Throwable consumed at the leaf with the stored
`:seon.error/cause` ref untouched, no union broadened, no wrapper exemption
widened, the arithmetic honest, and four sharp new regressions. Reverting would
cost that and buy nothing. But the cut leaked in four places that the ruling
explicitly guarded against, and in each of them a regression was deleted in the
same commit so nothing will catch it: the discriminating value of a non-duplicate
`diagnostic-cause` was **dropped** rather than kept under its owning declared
member at `db/diff-refusal` (ten classes), `write-owned-values-error` (five
classes), `schema/render-contract-refusal!` (two), and `boot`/`operator` (four) —
F1, F2, F6, F9 — leaving four dead parameters as the visible scar. Where it was
kept, it was kept under the normalizer's `:any` input key (F3, 33 sites) or nested
inside `:seon.error/data` where the reader's own merge overwrites it (F4, 89
lines): the retired seven-key shape reconstituted under two new spellings, which
is precisely hunt item (3). And F5 removes the only coverage of fourteen surviving
render functions. Land F1, F2, F5, F6 and the F3/F4 key decision before the next
step in the cut depends on these values; F7–F14 can follow in the same repair
slice. The F3/F4 choice is the one that needs the owner, because it is a data-model
question for every later error site, not a cleanup.
