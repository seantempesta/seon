---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, issue, detectors, F7]
---

# Orchestrator review — first-task detectors (`1a42fbfa7`, `10dfe6926`)

Read: the landing note, the `src/seon/issue/detect.clj` diff (+196), the
regression file stat, the two issue notes.

**Accepted.** `public-without-contract` and `public-without-reaching-test`
are in the exact shape of `public-without-doc` (two arities; the scoped one
joins on `:seon.fn.file/relative-root`); exclusions are by fact
(`:seon.fn/macro?`, shared `:seon.fn/form-span`); the reach detector asks
`seon.fn/tests-reaching` once per candidate, treats an unresolved reach as
"every test" and therefore not a subject, and its problem text names the
basis `:t`, the derivation, and that the graph may be incomplete —
"no reach is recorded", never "no test exercises it". The doc detector is
refactored value-identically onto the shared helpers. `generate!` unchanged.
Measured on `default`: contract 68 unscoped / 8 src (0.1 s); reaching-test
166 / 139 (17–18 s, the per-call reference re-derivation the lane filed
against `gate-set`); doc 33 / 2.

**Two issues it filed are real and routed:** `gate-set` re-derives its
declared-reference population per call (the call-graph lane's widening
follow-up must fix this together with the scope); the row drops kondo's
`:defined-by`, so `deftype` constructors and `defprotocol` methods count as
uncontracted (6 of the 8 `src` contract subjects) — the indexer keeps the
fact already; the row owner should carry it (a small slice on `fn.clj`).

**Boundary accepted:** regressions unverified in process (the publication
rejection blocker); the cold gate is the proof.

**Gate requested:** `seon.issue.detect-test seon.issue-generate-test
seon.issue-test` when a slot frees.

---

## 2026-09-17 — the detector line vanished from the AI render, not from the data

**Red (batch 111, `tmp/orchestrator/gate-results/batch-111.log:151`):**
`seon.issue-generate-test/a-generated-issue-names-its-detector-and-promises-no-tests`,
`issue_generate_test.clj:165`. Actual text, verbatim:

```
Issue 1309e932b541: still open.
Public function seon.background-blob-test/binary-capability carries no docstring
```

The two earlier assertions in the same test passed, including

```clojure
(= (list (symbol detector) '(seon.db/db)) (:seon.issue/check-form view))
```

so the data was never wrong: `seon.issue/status` resolves
`:seon.issue/detector` through its ref and derives the exact form. Only the
rendered text lost it.

**Cause — commit `3772e2f68` "Make issue completion and budget own the task
loop".** Before it, `render-ai` derived the detector itself from the row
(`git show 3772e2f68^:src/seon/issue.clj`, `render-ai` → `(detector-symbol row)`
→ `render-floor`). That commit introduced `status-text`, which prints the done
condition from `:seon.issue/check-form`:

```clojure
(when-let [form (:seon.issue/check-form view)]
  (str "\nDone condition: " (repl/source-text form)))
```

and rewrote the two renders asymmetrically. `render-html` got the derivation:

```clojure
view (if-let [database (:seon.db/db unit)]
       (status {:seon.db/db database :seon.issue/id (:seon.issue/id row)}) row)
```

`render-ai` did not — it became `(status-text (or (:seon.render/value unit) unit))`.
`:seon.issue/check-form` exists ONLY on the view `status` derives; a pulled row
never carries it (and a `'[*]` pull resolves `:seon.issue/detector` to a bare
`{:db/id n}`, so `detector-symbol` is nil there too). The AI projection was
reading a pre-read the authority re-decides — AGENTS.md §"No seam may act on a
pre-read or a mirror that its authority will re-decide". The three other
candidate commits (`2d997b88f`, `2ed13625e`, `1a42fbfa7`) do not touch these
functions; `2d997b88f` only changes a prose word in a `status-text` caller.

**Ruling that decides the fix** — the program-facts PRD §1b **T3**: "The agent
sees its tests and their results every turn. The opening names the exact tests
(or the detector) that will run after every turn … an ordinary generated read
(`my.issue/status`) … not a new render path." The detector line belongs in the
status the agent reads every turn, and `status_text` already puts it there. The
test expectation was NOT stale; the render was.

**Fix (root, `src/seon/issue.clj`).** One private `status-view` derives the view
at `status` from the unit's own database, and BOTH renders use it — dissolving
the asymmetry rather than copying the `if-let` into `render-ai`:

```clojure
(defn- status-view
  [unit]
  (let [row (or (:seon.render/value unit) unit)]
    (or (when-let [database (:seon.db/db unit)]
          (let [view (status {:seon.db/db database :seon.issue/id (:seon.issue/id row)})]
            (when (:seon.issue/title view) view)))
        row)))
```

The detector symbol and its form are derived from the `:seon.issue/detector`
ref through `status`'s pull pattern `{:seon.issue/detector [:db/id :seon.fn/sym]}`
and `check-form`; no string constant is introduced anywhere. A unit carrying no
database, or an issue `status` cannot read, still renders the row it was handed,
so no caller narrows. `render-html` keeps its exact previous behaviour.

**Boundary.** `clj-kondo` clean (0 errors, 0 warnings). Regressions NOT run:
`bin/test-fast` refuses a lane ("test runs are orchestrator-only right now, set
2026-09-15 21:05Z"); the cold gate is the proof. No prepl or cluster use.

**Adjacent red, NOT this lane's and NOT fixed:** `seon.issue-test/indexed-issues-replace-facts-and-retain-identities`
(`issue_test.clj:65`) is red in batch 110 with a different root — `member` pulls
to `nil` there, so `render-ai` refuses its argument before the assertion runs.
That assertion also expects `"(my.issue/status"` in the AI text, a string
`status-text` has not emitted since `3772e2f68`; whoever owns the batch-110 red
should decide that expectation against T3 in the same pass.

**Gate requested:** `seon.issue.detect-test seon.issue-generate-test seon.issue-test`.
