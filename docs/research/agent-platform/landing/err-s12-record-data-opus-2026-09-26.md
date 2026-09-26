---
type: landing
created: 2026-09-26
lane: err-s12-record-data-opus
status: committed, unadopted and untested (RESET NEEDED; two patches for root)
commits: 83cf6ea2d e0e730dfb 9bdf9b1c9
---

# Landing: err-s12-record-data-opus (audit slices 1+2: errors as data on the evaluation record), 2026-09-26

What exists: `seon.sci.kernel/failure-value` normalizes an evaluation's throw, and `seon.sci.eval/shown-result` prints the
final value once. Settlement (`seon.turn`) saves that print as `:seon.eval/shown`, and `seon.repl/response` builds the
reply. MCP and the next context both read that reply (err-s4).

Approach: the error value is already the complete flat data. So a failed evaluation's shown text is that value printed
**by the floor as data** (`:seon.render.value/structural?`), not the error renderer's prose. Any failure after
evaluation is saved as a declared key beside the unchanged value. The stored triage copy and the reply's triage
re-parse are deleted.

## Broken or unproven (read first)

1. **Nothing is proven live.** Default (pid 21381) runs an older archive, and loading my namespaces would redefine
   default's Vars. **RESET NEEDED.** Root owes the acceptance probes P0–P3 and P7 through MCP after adoption, plus the tests
   listed below. What I did prove at the REPL, in scratch namespaces or with plain SCI:
   - The new `failure-value` on a real SCI throw gives the complete chain with both ex-data maps and
     `:seon.sci.eval/stacktrace [[user/f 1 13] [user/f 1 1] [user 2 1]]`, with no callstack anywhere.
   - An instrument-style refusal keeps `:seon.error/expected` and `:seon.error/offending`, and gains its chain.
   - The structural floor print of that error reads back `=` to the value (3.5 ms).
   - The new `repl/response` gives:
     - `:error {data}` for a failed evaluation;
     - `:value … :diagnostics [...]` for a refused settlement, readable by `read-string`;
     - a quoted string for legacy prose.
2. **The value text still carries the diagnostic line until root applies `tmp/err-s12-record-data-opus/render-value-text.patch`.**
   The patch is for `render/value.clj` and `seon.render.value.edn`, which are not my files. It adds
   `:seon.render.value/value-text`, the text without the appended `;; renderer failed …` line.
   - Until then, P4/P7 show the value plus the diagnostic comment inside `:value`, which is the old unreadable reply,
     and the diagnostics are also saved in `:seon.cluster.eval/diagnostics`.
   - After the patch, `:value 42` is followed by `:diagnostics [...]`.
3. **The saved fault window is written, but not yet read, until root applies
   `tmp/err-s12-record-data-opus/transcript-saved-fault-window.patch`.**
   - The patch replaces `transcript/branch-errors`' history scan and as-of reconstruction with a read of the saved
     `:seon.cluster.eval/faults` refs, reading `latest-fact` and `resolution` of each.
   - It also carries diagnostics through the receipt entry, detects agent-written replies from `:seon.eval/shown`
     instead of triage, and converts the transcript and web-debug fixtures.
   - Transcript src: +33/−48.
   - Until the patch is applied, the old reconstruction keeps delivering.
4. **Not done:**
   - (a) JVM-mode evaluations still carry no fault window. That needs err-s4's option B, or a bridge-side read in
     mcp.clj (not mine); it needs a decision.
   - (b) Renderer diagnostics are saved and delivered beside the value, but are **not** recorded as core-fault
     occurrences, because `error/recording` needs a declared schema per diagnostic (task `1bf47dae6f04`).
   - (c) `settle!`, the single-evaluation path, saves no fault window. Only `settle-batch!` saves one.
   - (d) A form that failed *and* whose batch settlement was refused keeps its own error without the refusal.
5. **Size.** src +279/−141 over the two slices, which is over the ~100 unmatched bound.
   - Most of it is `shown-result`'s total restructure (re-indentation), the fault window (+45 in turn), and docstrings.
   - The transcript patch removes a net 15 more.
   - Net for the error work, including the patch: about +90.

## Blocking item (root's message): a defn then a require in one run was refused. Fixed in 83cf6ea2d.

- Reproduced on branch `err-s12-record-data-opus`, turn `97b323811769`. The result was "Transaction report validation
  rejected."
- The reply also showed `#'user/s12-inc` with `exception:true` and no refusal. That was item 2 below.
- Fix at the producer: `turn/final-namespace-digests`. `settle-batch!` digests each function/test row against the
  namespace row that a LATER receipt of the batch writes, using `db/definition-digest-of`, the one digest owner.
- Regression: `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent`. It covers both orders.

## What changed

- **Error data (kernel).** `failure-value`:
  - removes SCI's location-wrapper links (`:type :sci/error` + `:sci.impl/callstack`, via `refusal/without-links`);
  - reads the author's location once through `sci.core/stacktrace` (sci/core.cljc:419) as `:seon.sci.eval/stacktrace`
    `[[fn-or-ns line column] …]`, innermost first;
  - drops a non-first-party `:seon.error/frame` (`sci.lang.Var`, `instrument.clj:682`);
  - gives an existing refusal the cause chain. The link that carried the refusal as its ex-data names only its class
    and message (B05).
  - Deleted: the `:seon.sci.eval/data` copy of link 0's data, which was the source of the callstack volatile.
- **Record.**
  - Failed evaluations, refused installs and unrun refusals print structurally, so `:seon.eval/shown` IS the flat error.
  - `:seon.cluster.eval/diagnostics` (declared once, a string of floor-printed flat errors) holds any later-stage
    failure beside the value: profile, contract-explanation or doc-read failure while printing, renderer diagnostics,
    and settlement refusal.
  - `shown-result` no longer throws on a refused profile.
  - `refusal-terminal-data` and `settle-batch-refusal!` keep the evaluated shown value and put the refusal in
    diagnostics. `:seon.cluster.eval/error` stays, so the receipt still reads red.
- **Response.**
  - `:error <shown data>`, or `:value` followed by `:diagnostics`.
  - The declared-renderer arm carries diagnostics too.
  - Deleted: `repl/error-text`, which re-triaged stored triage into a wrapper frame, and the `shown-error` special case.
- **Fault window (option A).**
  - `turn/fault-window` reads the window once at the writer: the agent's previous closed run's `closed-tx` (else this
    run's `opened-tx`), up to now, through `error/occurred-since`.
  - The window is saved as `[:seon.error/signature …]` refs in `:seon.cluster.eval/faults` on each run's last receipt.
  - The transaction is now built inside `phase`, so a refused read becomes a refused settlement, never an escape.
- **Schemas.**
  - `seon.cluster.eval.edn`: `diagnostics` and `faults` are declared. `triage-edn` is marked retiring and removed from
    the receipt and settle-request maps.
  - `seon.sci.eval.edn`: `stacktrace` is declared; diagnostics are added to the shown and evaluation maps.
  - `seon.repl.edn`: `diagnostics` is declared.
  - `seon.turn.loop.edn`: `triage-edn` is replaced with diagnostics.
- **err-refs-sweep hunks** for my files were applied as commit 9bdf9b1c9.

## Deleted

| Item | Lines |
|---|---|
| `repl/error-text` + `shown-error` | ~22 |
| triage-edn production in eval.clj | 5 |
| `failed-evaluation` triage arm | 3 |
| dead `:seon.cluster.eval/error` copy in `success-evaluation` | 2 |
| kernel `:seon.sci.eval/data` copy | 2 |
| turn triage copy | 3 |
| "shown-result throws on refused profile" | 3 |
| repl `clojure.main` require | 1 |
| transcript `branch-errors` reconstruction (in the patch) | 48 |

## Static checks

- `seon.contracts-compile-test/check` over turn, sci/eval, sci/kernel and repl against the checkout: `[]` (748 ms).
- The edited schema EDN files all read.
- clj-kondo reports no new errors. The `render.value/*` unresolved-var errors are the known stale cache.
- `seon.issue/tasks-tx` validates the two new tasks: `0ae6b217abdd` (`seon.sci.eval`) and `b331d31540bb` (`seon.turn`).

## Root runs (after applying both patches and adopting)

```
bin/test-check default --ns seon.repl-test
bin/test-check default --test seon.sci.eval-test/a-failed-evaluation-shows-its-whole-error-as-data
bin/test-check default --ns seon.sci.eval-test
bin/test-check default --ns seon.turn-test
bin/test-check default --test seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.render.web-debug-test
bin/test-check default --ns seon.dev.mcp-bridge-test
```

Then run P0–P3 and P7 through MCP on a fresh branch. Expected results:

- P1: `:error {… :seon.error/chain [outer {:audit/offending 7}, inner {:audit/detail :sentinel}] :seon.sci.eval/stacktrace [[user 1 1]] …}`
- P2: `:seon.error/expected [:int {:min 1 :max 64}]`
- P4/P7: `:value {:audit/value 42 …} :diagnostics [...]`
- a defn followed by a require settles
