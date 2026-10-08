# RED-1: the 13 reds in `seon.cluster.host-test` at HEAD 65cf8c26d

`bin/seon check --test seon.cluster.host-test`: run 9cf9ef8a7e01, pass 57, fail 13, error 0 (5 deftests).
All times 22 s run; long-tagged tests are excluded by default.

| Test (assertion lines) | Expected / actual | Class | Change |
|---|---|---|---|
| `a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged` :137 | shown `#'my.agents.host-probe/host-probe` / `{:seon.render.var/symbol my.agents.host-probe/host-probe}` | (b) | b32074469 "rev 6: renders return data": the Var render's AI face is data |
| same :144 | `[?tx :seon.db/receipt ?r]` on the form's task write finds nothing | (c) | `seon.receipt-write-carrier-test/evaluation-receipt-is-transaction-metadata` is red the same way; task `seon.cluster.host/a-hosted-forms-write-names-its-evaluation-receipt` |
| `a-definition-whose-body-expands-macros-persists` :166 | same Var shown text | (b) | b32074469 |
| `a-namespace-switch-declares-nothing` :339 :344 :350 | same Var shown text | (b) | b32074469 |
| same :347 | `:seon.ns/source` of a switched-into namespace is `"(ns my.agents.switch-fresh)"`, not nil | (b) | 5ced0160b: `seon.sci.eval` stores a generated `ns` form for a namespace with none (never the REPL form) |
| `an-agent-creates-a-task-at-its-repl-and-it-is-a-cluster-task` :398 :399 :401 :405 | `create!` answers `[:invalid-name nil nil]` | (b) | 0daf87818 task-tags (A): a task name must read as a symbol name; the test's "trim titles" has a space |
| `a-refused-deletion-is-its-forms-refusal-and-the-run-closes` :505 :506 | no `:seon.cluster.eval/error` on the deleting form, no diagnostics | (c) | task `seon.cluster.host/a-refused-deletion-reaches-the-deleting-forms-receipt`; a lonely function's unmap IS refused ("leaves 1 surviving referrers") while the helper with a surviving caller is not |

Probe evidence (disposable probe test, deleted): after `(ns-unmap ... 'refused-helper)` receipts carry shown "nil" and no error; the helper stays in the database and in SCI; no error occurrence exists. `(ns-unmap ... 'lonely)` receipt error: "Program deletion leaves 1 surviving referrers (:seon.error/offending). Repair or retract them in the same transaction."
Not classified in the time box: why the refusal is misattributed, and the receipt stamp's cause.
