---
type: research
status: in-progress
created: 2026-09-16
tags: [research, seon-hook, publication, operator, workarounds]
---

# Hook progress is a value

Assignment: remove ranked items 18 and 6, the `publication-exception`
row, and the shell/console row in
[workaround-inventory-2026-09-16.md](workaround-inventory-2026-09-16.md).
Read that inventory end to end, AGENTS.md §§0–3 and §§5–7, all 1,436
original lines of `bin/seon-hook`, and the operator's complete publication
form, prepl receive loop, initialization result handling, and command boundary.
Also read the active plan entry and the data-oriented Clojure, REPL, and
Clojure testing skills.

## Dependency ledger and decision

- `reference-code/clojure/src/clj/clojure/core/server.clj:228`: prepl already
  returns one terminal `:ret` value, independently of arbitrary `:out` chunks.
- `script/seon/fresh_operator.clj:1584`: `prepl-eval!` owns that transport;
  `read-prepl-reply` reads its terminal EDN value. The hook must not decode
  the operator's console into a second interpretation of the same operation.
- `src/seon/cluster.clj:86`: `report-source-progress!` invokes the existing
  dynamically bound callback. The operator supplies that callback in
  `init-form`; no new cluster mechanism or emission edit is necessary.
- `bin/seon-hook`: Babashka's process handle remains the bounded child owner.
  The hook reads the result only after child completion. Killing or losing
  the child without a result is unavailable evidence, never convergence.

A read-only JVM probe on default bound the real progress callback and called
`report-source-progress!` with `"renamed phase"`. It returned exactly
`["renamed phase"]` in 2 ms. Runtime status reported default PID 41413 alive
and all three shared Flow procs replying. Existing problem counts were not
treated as evidence of health or repaired by this assignment.

## Result contract

`bin/seon init --result-file PATH [init arguments]` writes the operator's
terminal EDN value to the explicitly selected path. It retains the existing
prepl transport, lifecycle lock, publication owner, and console diagnostics.
The progress callback adds one map per callback invocation:

```clojure
{:seon.source/progress "any phase wording, including\na newline"}
```

These values travel in `:seon.fresh-operator/progress` on the publication
result, beside the actual `:seon.source/commit-id`. A failure carries
`:seon.error/kind`, `:seon.error/message`, original exception data and stack
evidence, and the progress observed before failure. Absent typed evidence is
its own kind, `:seon.hook/publication-result-unavailable`; the advisory then
names the operator exit and says the result carried no typed failure, rather
than printing `nil` as if a cause had been read. The hook reads keys;
it never selects a phase by its English wording, reads EDN from arbitrary
console lines, or calls the last stdout line publication evidence.

The hook retains raw console diagnostics at `logs/current-source-failure.log`;
those bytes are diagnostic text, not the failure envelope. The typed result
is retained at `tmp/source-publications/<publication-id>-operator.edn` beside
the existing batch feedback artifact. Missing or malformed result evidence
cannot produce `converged:`.

The required numeric decisions are declared once in `.claude/seon-hook.edn`:
publication timeout 180 seconds, review document 40,000 characters, batch
200,000 characters, rubric 60,000 characters, and — the §2
`bin/seon-hook:1123` row, adjacent and in the same file — the review call's
own bound, now `:review :call-timeout-seconds 60` instead of a literal
`60000` sitting beside a config block that already declared
`:interval-seconds`. `required-positive-config` validates each supplied
value at its read and refuses by exact key path; the hook substitutes no
literal.
`SEON_HOOK_CONFIG` and `SEON_HOOK_STATE_DIR` remain supported; the review
fixture reads the shipped limits and overrides its own interval and skills.

## Verification

The first HEAD-plus-owned-paths `bin/test-fast` run executed 11 tests / 93
assertions: one failure and one error, both in the review fixture that had
relied on omitted numeric limits. The fixture now reads the declared config.
The publication and phase-renaming regressions passed that first run.

Direct execution of the existing Babashka source-worker probe produced two
batches, `["a.clj"]` followed by `["a.clj" "b.clj" "c.clj"]`; all three
in-flight edits shared one successor id, both transports used the exact
advertisement, and pending/worker state was removed. Removing the advertisement
returned `:seon.fresh-operator/live-advertisement-unavailable`.

A direct Babashka configuration probe returned
`[180 40000 200000 60000 60]` for the five declared bounds; an absent key
refused with `{:seon.hook/config-path [:review :absent],
:seon.hook/config-file ".../.claude/seon-hook.edn",
:seon.hook/config-value nil}`. A separate Babashka round-trip through
`init-result!` and `publication-result` retained a renamed multiline phase;
`{}` and malformed EDN both refused.

A Babashka probe drove `publish-source-paths` itself with `process/process`
and `log!` redefined (`tmp/hook-progress-probe/probe.clj`), the child's
console deliberately set to the unreadable `"not EDN {[ console noise"`.
Three outcomes, verbatim:

- a result carrying `:seon.source/commit-id "abc123"` and the phases
  `"completely renamed phase\nwith a newline"` and `"another"` produced
  `converged: #:seon.source{:commit-id "abc123"}` and logged both phases as
  `SOURCE_PROGRESS`. The rename changed the log payload and nothing else;
  neither phase appears in any hook predicate.
- a typed failure produced `refused: ADVISORY … :seon.fresh-operator/publication-failed
  missing schema {:schema :example/input}.`
- no result file at all produced `refused: ADVISORY …
  :seon.hook/publication-result-unavailable Operator returned no result;
  exit 1.` — absence is a named refusal, never convergence (AGENTS §2.4).

The live command was:

```sh
bin/seon init --result-file tmp/hook-progress-probe/live-operator.edn --dev default --changed src/seon/ai/tokens.clj
```

It waited behind the existing operator lifecycle lock, then returned exit 1
and a readable typed result file. Exact selected result:

```clojure
{:seon.error/kind :seon.fresh-operator/publication-failed
 :seon.error/message "Source changed while incremental publication was being analyzed."
 :seon.fresh-operator/progress
 [{:seon.source/progress "request accepted"}
  {:seon.source/progress "bootstrap configuration"}
  {:seon.source/progress "store acquisition"}
  {:seon.source/progress "source build"}]}
```

The original offense named digest-before
`66768d17507fa1ca449182099f1d41e2d7860183340d3529aa535918b36da2c6` and
digest-after
`c10fff0a7c7a714329241eaba26b13aa3498a1d150a060ab19c46322ff50c52f`.
This proves the live operator/prepl/file failure path against default's
loaded publication owner, not successful development adoption. No claim is
made about which concurrent edit changed that snapshot. The full stack and
original exception data remained in the result envelope.

The continuing session's own `bin/test-fast seon.dev.hook-test
seon.dev.edit-feedback-test` was launched at 16:29 and sat in
`bin/_test-slot`'s queue behind ten other lanes' invocations (both slots
held throughout; `bin/test-fast seon.cluster.wake-test
seon.issue-settlement-test` alone held one for 27 minutes). That wait is
the declared bound working, not a failure. The code was committed at that
point rather than held hostage to the queue; the run's result is appended
below when it lands.

## Ownership and boundary

Owned paths: `.claude/seon-hook.edn`, `bin/seon-hook`,
`script/seon/fresh_operator.clj`, `test/seon/dev/hook_test.clj`,
`test/seon/dev/edit_feedback_test.clj`, and this note.

`src/seon/cluster.clj` was held by `incremental-publication-is-the-rule` at
entry and was never edited. Its existing callback was sufficient, so there
is no deferred emission hunk. Foreign edits were excluded through
`bin/test-fast --paths`; no canonical `bin/test` gate was requested or run.
Default was never stopped, restarted, or reforked. No other lane's session
was operated. Stop for owner review after the path-limited commit.

The Markdown hook also reported 30 repository-wide pin diagnostics in
other documents, beginning with the historical
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
Those diagnostics are outside this slice; they are not test failures or
evidence against the publication protocol.
