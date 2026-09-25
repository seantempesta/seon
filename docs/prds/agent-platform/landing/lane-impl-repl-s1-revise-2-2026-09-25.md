---
type: landing
status: committed; awaiting root test runs
lane: impl-repl-s1-revise-2-opus (takes over impl-repl-m1-opus, stopped 19:30Z)
created: 2026-09-25
review: docs/research/agent-platform/repl-s1-revision-review-2026-09-25.md (e19adf392, REVISE); custody-and-ingestion-p1-review-2026-09-25.md finding 1
task: definitions-bypass-entry-gate, handle "132fee0467c8"
---

# S1 revision 2: the six S1 findings, custody finding 1 (eval half), duplicates

Evaluation already reads a form, compares held interns, analyzes the form and admits and arms it. This lane keeps
that flow and replaces two things:

- **Provenance** now uses the analyzer's exact byte span plus SCI's root-write mark. The old text search is gone.
- **Restoration** is one capture taken before any mutation. Its error travels as an ordinary value.

## Broken or unproven, first

- **No test of this lane's slices has run.** Under the load rule, root runs the tests (commands below). What was
  proven at the REPL is listed under Evidence.
- **Finding 5 is only half fixed.** `turn/restore-evaluations!` (`src/seon/turn.clj:4828-4838`) still runs a
  `doseq` that throws away each restoration error, so settlement reports only its original refusal.
  - `turn.clj` is outside this lane's row, so I stopped at that boundary.
  - The fix belongs to turn.clj's owner: collect the `:seon.sci.eval/restore` return values and surface a non-nil
    error beside the refusal.
  - The `failure-result` consumer in eval.clj is fixed. It also passes the refusal as the restoration's cause.
- **Finding 4 is proven at the acceptance owner, not through a settlement refusal.**
  - The new regression makes installation throw after the root transfer, using an unreadable contract.
  - An ordinary batch refusal would go through turn.clj settlement. That path is still proven only by the
    earlier regression, which calls the restore member by hand.
- **Custody finding 1 has a regression for the agent-acquisition entrance only.**
  - The regression calls `acquire!` under another branch's custody.
  - The lazy path (`kernel.clj:606` → `acquire!`) uses the same producer, but no test drives it yet.
  - The `agent.clj` wrapper is still in place. Its owner can now remove it.
- **SCI fork 409888f6 has no run of SCI's own suite.** That needs another JVM, so root owns that run.
  - Default (pid 32831, restarted 19:17:38Z) loads 409888f6.
  - On default I checked the rebound mark, consecutive metadata writes, a root write then a metadata write, a
    metadata write then a root write, and an untouched parent (Evidence).
- **Behaviour change for other `seon.fn/source-rows` callers.** Rows now carry `:seon.fn/form-span` relative to the
  submitted source.
  - Only eval's `definition-row` drops it before storage.
  - Test fixtures that transact `source-rows` output (fn, program, schema-in-place, seed tests,
    `test-support/program-row`) will now store a span with no file.
  - The span is excluded from the digest (`program.cljc:378`), and every span reader also requires
    `:seon.fn/file`. Still, a fixture that compares rows exactly may go red. Root runs are listed below.
- **Open defect filed:** every entry-gate refusal renders a trailing "Contract finding: seon.sci.eval/evaluate
  returned this error…" line, because the constructors name `evaluate`. See
  `docs/seon/issues/entry-gate-refusals-name-evaluate-and-render-as-contract-findings.md`.
  - The same issue records a first refused `defn` on a fresh branch: 247 ms and 648 MB allocated.
- **Hook publication reads `:on`** in `bin/seon status` (19:17Z). The preamble says it is paused, so I did not
  rely on it.
- **RESET NEEDED: no.** Root adopts these commits at its checkpoint, after `bin/seon down && bin/seon start`.

## Findings disposition

| Finding | Stopped lane's work | This lane |
|---|---|---|
| 1 P0 provenance | SCI `:sci/rebound` mark on `bind-root!` (a def clears it); `program-change` refuses a rebound Var. | `source-rows` keeps the analyzer's byte span within the submitted source (prelude subtracted). `source-offset` converts the Var's `:line`/`:column` exactly; `source-position` (substring search) retires. The span is dropped before storage. The refusal message names root writes. |
| 2 P1 metadata copy-on-write | SCI `owned-var` resolves the context's current binding at the Var's name. | Verified on default's real SCI. A detached handle writes to the context's binding at its name. That is the explicit rule in the `owned-var` docstring. |
| 3 P0 unbound restoration | `::unbound` step with `sci.vars/unbind`, schema alternative. | Verified on the installed path. Collapsed into one `var-step` helper that both producers use. |
| 4 P0 recipient restoration | Capture before transfer; `try` around transfer and installation. | Refusal cause threaded through; regression added. |
| 5 P1 restoration error value | `failure-result` makes an unavailable error the evaluation's value. | `restore!` takes the failure it is restoring. The recorded and answered error's chain is restoration, then refusal. turn.clj half open (above). |
| 6 P1 reader recovery | none | Recovery re-reads the same text from its offset (`advance-to!`, shared with `skip-fences!`). Every event, nested form and error fact is absolute; `shift-event` retires. |
| custody 1 | none | `record-acquisition-refusals!` binds its connection's custody around its one transaction. |

## Evidence (REPL on default, pid 32831, SCI 409888f6)

- **Finding 6, before the fix.**
  - `")\n(do\n  (defn recovered …))"` gave the nested defn `{:line 2 :column 3}`; the true line is 3.
  - After the fix it gives line 3. Checked on a scratch copy of the reader (`lane-s1r2.reader`).
  - Nine recovery inputs compared old against new:
    - Event kinds, spans and sources are identical.
    - The differences are all corrections: nested metadata lines, and the error-data `:line` of suffix
      failures (for example `"(defn f [)\n(defn g [] (h 1))\n)"`: nested `[2 12]`, where it was `[1 12]`).
- **Finding 1, before the fix, on the installed path** (branch `impl-repl-s1-revise-2`):
  - The `defn` then `alter-var-root` form refuses as unindexed. The rebound mark alone gives this.
  - Scratch copies of the new `source-rows`, `source-offset` and `program-change` gave:

    | Form | Result |
    |---|---|
    | defn + `alter-var-root` | unmatched |
    | comment holding identical bytes, then the declaration | matched; span `[68 126]` equals the Var's offset 68 |
    | `"é😀"` docstrings, two defns | `h` at 4, `k` at 24 bytes; both match their analyzer spans |

  - Analysis took 10–32 ms per form.
  - `source-offset` edge cases: a start line and column give 0; the next line gives 4; a multibyte character before
    the position gives 5. Positions before the start, nil, or past the end give nil.
- **Finding 3, installed path.**
  - `(declare pending)`, then a refused `(defn pending …)`, leaves `(bound? #'pending)` = false.
  - Refused redefine-then-unmap: `(do (defn pending …) (ns-unmap 'user 'pending) (defn bare …))`. Afterwards
    `pending` resolves again, it is unbound, and `bare` is absent.
- **Finding 2, real SCI.**
  - Consecutive `alter-meta!` through one handle gives `{:a 1 :b 2}`.
  - `alter-var-root` then `alter-meta!` gives `[:fork "Fork." true]`.
  - `alter-meta!` then `alter-var-root` gives `[:fork 1]`.
  - The parent is unchanged: `[:parent "Parent." nil nil]`.
- **Finding 5.** On a scratch compile, `execution-unavailable` with both causes gives:
  - chain messages `["Restoring a refused program change threw." "refused"]`;
  - the inner chain `["planning broke"]`;
  - a value that validates as `:seon.sci.eval/execution-unavailable-error`.
- **Compile checks.**
  - Each committed `eval.clj` state compiled in a scratch namespace: 172 and 173 forms, 0 errors.
  - `fn.clj` compiled apart from one scratch-only private-Var artifact.
  - The reader loaded as a copy.
  - clj-kondo: 0 errors on every touched file.
- **Timings.** Admitting `(declare pending)` took 6 ms. A refused unbound redefinition took 247 ms (first) and 26 ms
  (second). No parent/change comparison exists for the admission hot path. The added cost is one exact offset
  conversion per changed Var, O(form length), and it replaces the substring scan. Root should time
  `(defn g … contract)` on parent 08349c14a against e8cada147.

## Commits

- `c09aad058` reader: recovery resumes the same text (finding 6).
- `10887f9a9` entry gate: provenance on the analyzer span and the rebound mark; unbound restoration; SCI gitlink
  409888f6 (findings 1–3). The fork is pushed to `seantempesta/sci` `seon-env-hook`.
- `c21a89118` restoration: capture before acceptance, the restoration error as a value with both causes, one
  capture (findings 4–5, duplicates).
- `e8cada147` acquisition refusal recording binds its own custody (custody finding 1, eval half).

## Deleted and net lines

Deletions:
- `source-position` (10 lines, text search), replaced by the exact `source-offset`.
- `shift-event` (21 lines) and the suffix re-anchoring in `recovering-events` (13 lines).
- The read-char loop in `skip-fences!`, now the shared `advance-to!` (0 net).
- The duplicate `before-namespaces`/`before-owned` walk (2 lines; the stopped lane's `context-capture`).
- The second `namespace-state` read (3 lines, now 1).
- The stale reader comment (3 lines, now 1).
- The duplicate unbound/root step (8 lines, now one 9-line `var-step`).

Net, from `c09aad058~1` to `e8cada147`. These include the stopped lane's uncommitted +61/−35 in eval.clj and
+18/−3 in the schema.

| File | Lines added | Lines removed | Net |
|---|---:|---:|---:|
| `src/seon/sci/eval.clj` | 133 | 73 | +60 |
| `src/seon/fn.clj` | 16 | 7 | +9 |
| `src/seon/sci/reader.cljc` (whitespace ignored) | 27 | 54 | −27 |
| `resources/seon/schemas/seon.sci.eval.edn` | 22 | 5 | +17 |
| **Production total** | | | **+59** |
| Tests (four files) | 140 | 2 | +138 |
| SCI production (e663d9e2..409888f6) | 29 | 15 | +14 |
| SCI test | 33 | 0 | +33 |

## Root runs

Run these serially:

```
bin/test-check default --ns seon.sci.entry-gate-test
bin/test-check default --ns seon.sci.restoration-test
bin/test-check default --ns seon.sci.reader-test
bin/test-check default --ns seon.cluster.reply-test
bin/test-check default --ns seon.cluster.evaluate-sources-test
bin/test-check default --ns seon.sci.eval-test
```

Then the `source-rows` callers:

```
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.program-test
```

SCI's own suite (another JVM, root's call):

```
cd reference-code/sci && clojure -M:test:clj-test-runner -n sci.core-test
```

Named regressions:

- `seon.sci.entry-gate-test/a-definition-is-the-root-its-analyzed-declaration-ran`
- `seon.sci.restoration-test/acceptance-that-throws-after-transfer-restores-the-recipient`
- `seon.sci.restoration-test/a-restoration-that-cannot-run-leaves-the-execution-unavailable`
- `seon.sci.reader-test/recovered-forms-carry-the-coordinates-of-the-whole-source`
- `seon.sci.eval-test/acquisition-records-its-refusals-on-the-acquired-branch-under-foreign-custody`

## Released paths

`src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`, `resources/seon/schemas/seon.sci.eval.edn`,
`reference-code/sci` + gitlink, the `src/seon/fn.clj` `source-rows` hunk, the four `test/seon/sci` tests above, the
new issue file, and this landing. Branch `impl-repl-s1-revise-2` is retired, the scratch namespaces are removed,
and `tmp/impl-repl-s1-revise-2/` is deleted.
