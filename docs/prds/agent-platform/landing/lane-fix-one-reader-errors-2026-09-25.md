---
type: landing
status: committed; awaiting root adoption and test runs
created: 2026-09-25
---
# Lane fix-one-reader-errors (2026-09-25)

The one reader (`seon.sci.reader/read`, SCI/edamame over tools.reader) already
produces every event's line, column and source. Its callers now use them. The
multi-form refusal comes from `seon.sci.eval/one-event` and names the second
event's position. The EOF span defect is fixed where it happens, in `read-events`.

## Still broken

- `""` is still refused by `evaluate`'s input contract (`[:string {:min 1}]`)
  with "expected a string, got a string". That text comes from
  `seon.error/schema-expectation` (`src/seon/error.clj:759`), which skips Malli's
  "should be at least 1 character". The file is outside this lane. It is filed as
  `docs/seon/issues/contract-refusals-describe-bounded-strings-as-plain-strings.md`.
  The refusal is still loud and names the member, so R7 is not blocked.
- **Not installed.** Hook publication is paused, so root adopts these commits.
  No test request was run (LOAD RULE).

## Root cause of `1 2` crashing

edamame `read-number` (`reference-code/edamame/src/edamame/impl/parser.cljc:190`)
calls `(r/unread rdr nil)` at EOF. tools.reader 1.6.0 `IndexingPushbackReader/unread`
(`reader_types.clj:137-141`, the jar on default's classpath) decrements the column
even when `ch` is nil. So a number that ends the text reports an end cursor one
character short. Probe (raw `sci/parse-next+string`): `"1"` ends at column 1,
`"1 2"` → `2` ends at column 2, and `"a b"` (a symbol) is correct. `read-events`
then computed `subs text 2 1` and the recovery path turned that into
"Range [2, 1) out of bounds". The same defect gave `"1"` the source `""`.

Fix: when `(rt/peek-char source-reader)` is nil, the reader is exhausted and the
event's end is `text-length`. That answer is exact, and it adds no hand-rolled
position logic.

## Evidence (default pid live, JVM mode, scratch ns `lane-fix-one-reader-probe`)

A scratch copy of the edited reader was loaded as `lane-fix-one-reader-probe.reader`,
so none of default's Vars were touched.

| text | before (sources) | after (sources) |
|---|---|---|
| `1` | `""` | `"1"` |
| `1 2` | one error event, "Range [2, 1)…" | `"1"`, `"2"` (1:3) |
| `1\n2` | same crash | `"1"`, `"2"` (2:1) |
| `:k 1`, `"s" 3`, `12 (a)`, `-1.5`, `1 ;c`, `(+ 1` | – | correct; `(+ 1` keeps its EOF error |

The refusal value was built exactly as the new `one-event` builds it, then rendered
through `#'seon.sci.eval/shown-result`. It validates as
`:seon.sci.eval/reader-event-count-error` (true for all four cases). Shown text:

> seon.sci.eval/one-event refused :seon.cluster.eval/source at []: expected the declared requirement (:seon.sci.eval/one-reader-event), got a string "(+ 3 4)". Fix: Evaluation accepts exactly one form; the second form starts at line 1, column 9. Wrap sequences in `(do ...)`. …

Before, the shown text was: "Evaluation requires exactly one reader event."

Comment-only input keeps its message. It now also names the member, and the offending value is the whole source.

## Read cost (µs per call, 2000 warm calls, three alternating rounds)

| input | before | after |
|---|---|---|
| `(+ 1 2)` | 12.0 / 10.6 / 10.8 | 12.4 / 11.2 / 10.7 |
| `(+ 1 2) (+ 3 4)` | 15.9 / 16.3 / 16.1 | 16.5 / 15.1 / 15.4 |

The two are the same within noise. The added cost is one `peek-char` per form.

## Changes

- `src/seon/sci/reader.cljc` +6 −1: the exhausted-reader end.
- `src/seon/sci/eval.clj` `one-event` +21 −8: the empty and multi-form branches
  are now one declared refusal. It carries `:seon.error/member`, `:expected`,
  `:offending` and `:fix`, plus the second event's reader line, column and source
  under `:seon.error/data`.
- `test/seon/sci/reader_test.clj` +29: one regression per class
  (`a-number-ending-the-text-keeps-its-source-and-span`,
  `evaluation-refuses-a-second-form-by-its-position`).
- Net source +18, tests +29. The deletion this enables is R7:
  `script/seon/dev/mcp.clj` `require-single-clj-form!` and its helpers, about 80 lines.

## Root runs

- `bin/test-check default --ns seon.sci.reader-test`
- `bin/test-check default --test seon.sci.eval-test/a-refusal-keeps-its-declared-declared-schema-at-both-entrances`
- `bin/test-check default --ns seon.sci.entry-gate-test`
- `bin/test-check default --ns seon.cluster.reply-test`
- `bin/test-check default --ns seon.run4-reader-test`
