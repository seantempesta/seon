# reader-fix — LR-READ and LR-REPAIR (2026-10-08)

Evidence: `docs/research/agent-platform/2026-10-08/review/landed-review-sol-landed-review.md` R1, R2,
rows LR-READ and LR-REPAIR.

## Reproduced (default, installed HEAD 118358b2a, agent reader-fix SCI)

```clojure
(seon.sci.forgive/segments "#_ #_ 1 2 3" :code)       ;=> sources "2" "3" (plain SCI reads only 3)
(seon.sci.forgive/segments "true\nfalse\nnil\n42" :reply) ;=> source "42", the rest comments
(seon.sci.forgive/segments "(inc 1)) (inc 2)" :code)  ;=> [1 :dropped ")"] on (inc 2)
```

and `seon.turn/repair-text` stored the repairs as printed text (`:seon.cluster.eval/repairs`, a string).

## Causes (producers)

- `seon.sci.forgive/scan`: `#_` counted as a prefix needing one form (`prefix! 1`), so a discard whose
  operand is a discard ended after the inner operand. Proportional to nothing; a wrong arity.
- `seon.sci.forgive/literal-line?`: the symbol-shaped literals `true`, `false`, `nil` fell through to the
  qualified-symbol test.
- `seon.sci.forgive/owned`: ownership by start *line*, so two forms on one line were indistinguishable;
  also O(segments × repairs) by `take-while`.
- `seon.turn/repair-text`: printed copy stored in place of the data.

## Fix

- A discard that starts a top-level form is one no-value construct (needs its one operand); every further
  discard in a form needs one more item. That is exactly the reader's consumption, so each span is what
  SCI's `parse-next` consumes and `no-value?` stays correct. Two characters of logic, no new case.
- `literal-line?` accepts the three literals.
- Each repair carries `:seon.batch/at`, its offset in the forgiven text (closers inserted earlier move later
  repairs' offsets); `segments` sorts once and `owned` binary-searches segment starts: O(r log f).
- Stored data: `:seon.cluster.eval/delimiter-repairs`, `[:vector [:tuple line kind text]]` (the vector
  bridge keeps order and duplicates). A new name because Datahike refuses a valueType change of the
  installed string attribute (`d/with` probe: "Update not supported for these schema attributes"). The reply
  prints it at the edge (`seon.repl` response entries, `value/core-text`), still as `:seon.repl/repairs`.

## Design check

The reviewer's direction was a live SCI cursor over the whole forgiven text instead of spans. Turn and MCP
runs are pre-segmented into rows (`seon.turn/segmented-sources`, P7 moved after P14), and segmenting before
evaluation cannot use the semantic reader (aliases, tags and `*ns*` are only right at evaluation). So within
this slice the scan still produces spans; the fix makes the span arity equal SCI's consumption rather than
adding a case. Remaining hand grammar that can still disagree with SCI: `#_ #?(:cljs 1) 2` (a discard whose
operand is a conditional selecting nothing) — the scan ends the construct after the conditional, SCI also
discards `2`. That disappears with the rows (RP-11, live cursor after P14); not patched here.

## Proof

- `seon.repl-differential-reader-test` (new, through `seon.mcp/call`; reply mode through the same pass with
  `seon.turn/planned-sources`): nested discards equal plain SCI value-for-value, the discarded `swap!` never
  runs; `true`/`false`/`nil` lines are sources in a reply; `(inc 1)) (inc 2)` carries `[[1 :dropped ")"]]`
  on the first form; stored repairs pull back as `[[1 :dropped ")"] [1 :dropped ")"]]` and
  `[[2 :inserted "))"] [2 :dropped "]"]]` exactly.
- `bin/seon check` (publication and adoption of the schema change on disposable branches, throwaway JVM):
  forgive-test, reader-test, p6, p7: 14 tests, 194 pass, 0 fail.
- Reaching: evaluate-source, mcp-entrance, run4-reader, repl-differential with long tests: 23 tests,
  148 pass, 9 fail — all 9 in `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent`,
  the identical 9 assertions fail on HEAD (check of HEAD alone, run a6a702c16836): stale expectations of
  earlier slices (Var render `{:seon.render.var/symbol …}` vs `#'…`, a bare `1` reply now code, refusal
  shape). Not this change; reported to root for classification.

## Cost (throwaway JVM, unarmed, platform thread, median of 15 after 200 warm)

28,600-char reply built from `test/seon/sci/forgive_replies.edn`:

| | HEAD | change |
|---|---|---|
| `segments` `:reply` | 0.62 ms, 1.15 MB | 0.57 ms, 1.07 MB |
| `segments` `:code` | 0.83 ms, 2.79 MB | 0.77 ms, 2.18 MB |

Within P6's budget (≤ 3 ms, ≤ 8 MB). Armed numbers not measured in this slice.
