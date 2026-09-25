---
type: landing
status: R3 landed; R7 blocked on owner refusals; R6 outside lane files
created: 2026-09-25
---
# Lane delete-mcp-bridge-opus (audit R7, R3, R6), 2026-09-25

The MCP bridge (`script/seon/dev/mcp.clj`) is meant to be a view into the agent's world
(AGENTS.md ba41af0c0). It forwards source into `seon.sci.eval/evaluate` or the host PREPL and
returns what that path printed. This lane removes the bridge's own reprint. Two of the three
cuts are blocked, as described below.

## Broken or blocked first

- **R7 blocked (owner output worse).** The one reader's refusals are worse than the bridge
  pre-read it would replace. Issue: `docs/seon/issues/one-reader-refusals-lose-the-bridge-pre-read-positions.md`.
  - A multi-form input gets "Evaluation requires exactly one reader event." with no position or remedy.
  - `1 2` crashes the reader with "Range [2, 1) out of bounds".
  - `""` gets "expected a string, got a string".
  - The owners are `src/seon/sci/eval.clj` `one-event` and `src/seon/sci/reader.cljc`, both held by
    impl-repl-s1-revise-2-opus. JVM mode has a second problem: `clojure.core/read-string` reads
    only the first form, and the edamame reader's syntax-quote resolver has no host
    class/refer mapping. Options are in the lane reply.
- **R6 not in this lane's files.** `nil-deref?`, `first-seon-frame`, `exception-summary` and
  `mcp-projection-error` live in `src/seon/cluster.clj:334-386`. `mcp-project` feeds only the
  summary message to admission at `:439-442`. The bridge itself holds no error reduction beyond
  its transport-failure envelope.
- **SCI error shown text is garbled at the owner.** `(/ 1 0)` in SCI shows "seon.sci.kernel/failure-value
  refused  at []: expected ... got nil nil. Fix: Divide by zero ... none of its declared error
  alternatives matches it." This is the same class as
  `docs/seon/issues/entry-gate-refusals-name-evaluate-and-render-as-contract-findings.md`.
- **SCI `(vec (range 300))` allocated 457,482,568 bytes** in 205 ms. The first trivial SCI
  evaluation on a fresh branch took 789 ms; warm ones take 24 ms. Not measured further here.

## R3, landed as 1ae366d7f

Deleted from `script/seon/dev/mcp.clj`:
- `elision-value` (10 lines)
- `declare` (1)
- `enrich-sequential-tail` (12)
- `enrich-collection-tail-elisions` (18)
- `enrich-projection-elisions` (17)
- `printed-value-event` (8)

The enrichment walked for `:seon.sci.admit/elided`, and no producer writes it any more (the only
matches in `rg` were in the bridge and its tests). `printed-value-event` re-read the cluster's
printed EDN and printed it again.

The replacement, `shown-event` (10 lines), passes JVM returns through as the bytes the cluster
printed. For SCI it lifts the shown text (`:seon.dev.mcp/text`) out verbatim as the event's
`:val`, beside the evaluation keys.

Tests: deleted `capped-list-tail-is-a-declared-elision-value` and
`a-raw-prepl-ret-value-passes-the-enrichment-untouched`, which tested the deleted walk. The
printed-value assertion is replaced with `shown-event` assertions.

Net: source −73/+10, test −51/+9.

### Live comparison (default pid 37460, bridge autoreloaded after commit)

| Case | Before | After |
|---|---|---|
| SCI `{:a 1 :b [1 2 3] :c "x"}` | shown text nested in a reprinted EDN string: `\\\"x\\\"`, three escape levels | `"val":"{:a 1, :b [1 2 3], :c \"x\"}"`, one JSON level; record keys beside it |
| JVM `(vec (range 300))` | value reprinted by the bridge; digest as JSON fields | the cluster's printed map verbatim: 32 items, elision with requery-id, digest b4cab843…, size 15268 |
| SCI `(/ 1 0)` | garbled owner text, triple-escaped | the same owner text, now readable (the owner defect is above) |
| JVM `(/ 1 0)` | exception-summary map reprinted | the same map as the cluster printed it; `isError` unchanged |
| Multi-form `1 2` | bridge refusal with position | unchanged (R7 blocked) |

`bb` load of `seon.dev.mcp` with the committed file: 0.04 s, and `shown-event` answered both
shapes. `clj-kondo` on both files: 0 errors. The warnings are in older code (`execute-tool`
shadowing `name`, test futures).

## Next cuts under the "MCP is a view" law (bridge-only models that remain)

1. JVM mode is a second evaluator: `jvm-evaluation-form` → `clojure.core/read-string` + `eval`,
   projected by `cluster/mcp-project` (admission, `semantic-value` reconstruction, its own
   artifact) and printed by `admit/canonical-edn`. This is render-design slice 2(i) in `cluster.clj`.
2. R6's `exception-summary` family in `cluster.clj`.
3. The `branch-errors!` / `window-form` error window, with a `max-window-errors` 20 clip and the
   bridge atom `window-bases`. This is render design row 20 D (`seon.cluster/render-branch-errors`).
4. `branch-form` builds its own `refuse#` error maps in the bridge. They should be declared errors
   of the registry owner.
5. The `get_value` / MCP blob artifact store, still undecided in audit §1 finding 4.

## Root runs

Root's run on adopted HEAD gave 161 pass and 2 fail (tmp/orchestrator/verify-2055.out.d/012.txt). Both fails were in the branch fixture: its `defn` had no contract, so the entry gate refused it. b82d6374d gives it `{:malli/schema [:=> [:cat :int] :int]}`. Proved live on a disposable branch: the def returns `#'user/lane-mcp-probe` and `(lane-mcp-probe 1)` returns `42`. That trivial call allocated 515,823,848 bytes in 199 ms and the defn allocated 653,931,880 bytes, an owner cost defect in SCI evaluation.


- `bin/test-check default --ns seon.dev.mcp-bridge-test`

## Verification limits

Lanes run no test requests (LOAD RULE), so the bridge test namespace was not run. The live
proof above comes from the autoreloaded MCP server.
