# Batch D review — opus (lane review-batch-d, 2026-10-07)

Reviewed 910b70717 (0c833730c, 3fab5b880, 83b8ecfe8, 108dd1c3e, 008c909a7, fa31a1a97). Default runs 108dd1c3e:
probes below on default touch only code installed there; everything else is source plus `bin/seon check`.
Fixes landed: 8421fa826 (tests), 2905b7599 (printer, db). Astra's review
(review-batch-d-review-astra-batch-d-notes.md) verified finding by finding below.

## Findings, severity order

1. **P1, fixed 8421fa826 — four long tests red on HEAD (pre-existing since 706e40739, not fa31a1a97).**
   branch_execution_test (override, adoption-record, unoverridden) and agent_test
   a-changed-core-entity… assert `seon.render.value/bounded-text` is host-bound; it has had no proxy since 706e40739
   (default's index: `:seon.fn/host-bound? false` for render.value/bounded-text, print.value/bounded-text and
   datum). They loaded and ran: `bin/seon check --include-long` on 910b70717: 13 fails in 4 tests. Subject is
   now `seon.cluster/warn-low-space!` (host-bound by its own log/warn macro call; closure 9), helper
   `seon.fs/filesystem-space`. `seon.sci.kernel/watch-memory!` was tried first and refused by the declaration
   writer (`[:fn clojure.core/ifn?]`, unproved predicate purity). The unoverridden test's two other assertions
   were stale against current `seon.sci.eval` (install of a host-bound row, eval.clj ~1276-1290: an unchanged
   host-bound caller of a branch-changed definition binds its compiled Var; an unchanged core row SCI cannot
   interpret keeps its compiled Var): renamed
   `an-unchanged-host-bound-caller-of-a-changed-definition-runs-as-compiled`, asserting the compiled root.
   graph-definition's refuse-by-name assertion is deleted (it contradicts the same rule). 5 tests, 37 pass,
   9.7 s.
2. **P1, fixed 2905b7599 — core-text walked whole ex-data before printing (Astra P1 #3, confirmed).**
   `refusal/chain value nil` → `referenced` visits every member: on default 1k/100k/1M members cost
   3.5/72/194 ms for one display. core-text now shows `shown-chain` (≤8 links, class, message, ex-data as held,
   ≤10 non-host frames `[class method file line]`); the selection bounds the data. Regression: 1M-member ex-data
   under 100 ms, armed, in floor-test.
3. **P1, fixed — hostile Throwable escaped all four entries (Astra P1 #1, confirmed by source).** `causes`
   called `.getCause` unguarded and core-text's catch called `throwable-text` unguarded. `causes` ends at a
   throwing getCause; the last resort falls to class + identity. Regression through core-text/core-html/text/html
   with throwing getCause, getMessage, getStackTrace.
4. **P1, fixed — `:seon.print/full` meta lifted the core's budget (Astra P1 #2, confirmed by source:
   prepare's `full?` reads root meta).** core-text drops the opt-in on the root (IObj only). Regression.
5. **P1, fixed — unbounded diagnostic (Astra P1 #4, confirmed by source).** text/html concatenated the whole
   `ex-message`; now the failure stays a value and `unavailable-line` prints it through core-text. Regression with
   a 100,000-char message from a throwing cursor path; one diagnostic asserted.
6. **P2, fixed — retention refusal lost the removed tests' identities (Astra P2, confirmed: fa31a1a97 diff
   db.clj removed `(mapv … :seon.db/value-identities)`).** Restored as data `:seon.db/value-identities`. No test:
   no shipped schema declares `:seon.db/append-only-after`, so `retention-check` has no producer to exercise —
   candidate deletion (owner/root).
7. **P3, fixed — core-text docstring claimed the selection is refused without a database.** False: probed on
   default, the same unit (`:seon.render.value/root` given, structural, fixed profile) answers text with no db or
   config: {:a 1} 1.2 ms, 1M vector 1.9 ms, 5000-deep map 2.9 ms, `(range)` 1.9 ms, 10M-char string 2.6 ms.
8. **P3, open — stale references (Astra P2, confirmed).** `resources/seon/tasks/seon.print.value.edn` (:must-read
   `seon.print.value/bounded-text`), `seon.render.value.edn:58-59` (done task; must-read deleted symbols),
   `seon.fault.edn:10`, `seon.test.runner.edn:26,41` (`render.value/bounded-text`, now private),
   `docs/seon/schedule/README.md:99,296`, `triage.tsv:31`, `render-dedup.md:189`. Task and schedule data are the
   owner's index; not edited here.

## Verified without finding

- (1) No `seon.print.value` require/call in src, test, script, bin, .clj-kondo or skills; flow_test comment updated.
- (2) core-text/core-html read no db/config (explicit profile and root; `render-profile` skips config-defaults).
- (4) Converted messages keep their values as data (Astra's table matches source), except item 6.
- (5) `chain` keeps ex-data whole by identity; the recorder's walk remains (needed to reference worlds).
- (6) Lint of both candidates: 0 errors (1,488 pre-existing warnings); `bin/seon check` published and adopted both.

## Rejected / not done

- Astra: count diagnostics in value_test by map layer instead of text. Tried; the render-layer map is nested in
  the diagnostic (top-level filter found 0), so the text count stays. Not a defect of this batch.
- The four turn/run/instruction/config/db/web reds listed as identical on HEAD in the batch notes: not re-run.

## Limits

Not installed on default (root restart). seon.db-test, render.web-test, config-test not run (whole-namespace
size); db.clj change is reached by no test. No browser paint.
