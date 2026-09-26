---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, mcp, repl, dogfood]
---

# MCP SCI replies drop the evaluation's printed output

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 33ea69298, branch `dogfood-repl-opus-2`, 2026-09-26)

```
(do (println "hello-out") (binding [*out* *err*] (println "hello-err")) 1)
```

The MCP reply had exactly one event: `{"tag":"ret","val":"1", …}`. There was no `out` event. The
evaluation recorded the output. A query on the same branch:

```clojure
(seon.db/q '[:find ?src ?out :where [?e :seon.cluster.eval/source ?src]
             [?e :seon.cluster.eval/output ?out] [(clojure.string/includes? ?src "hello-out")]])
;; => [["(do (println \"hello-out\") …)" "hello-out\nhello-err\n"]]
```

`(println (:seon.fn/source …))` therefore shows `nil`. This is why `clojure.repl/source` looked
like it "prints nothing" (repl-entry-renders design D8). The agent's own `help` teaches
":out is printed text", which this entrance never shows. `*err*` is merged into the same
output string, with no marker of which stream wrote it.

## Which model is wrong

The MCP bridge builds its own projection of the evaluation. `seon.eval.drive/run-receipts`
(`src/seon/eval/drive.clj:123-148`) pulls source, shown text, error and time, and
`run-events` (`script/seon/dev/mcp.clj:427-443`) maps each receipt to `{:tag :ret :val shown}`.
Any member the bridge did not think of, such as `:seon.cluster.eval/output`, disappears. That is
a second printer beside the one REPL path, which the laws forbid ("MCP … enter through the
agent's path and render its values"). The fix is not to add `:output` to the bridge's pull. The
bridge should render the recorded evaluation entity through its declared render, the same one
the page and the transcript use, so the output, the duration and every later member arrive with
it.

## Expected

An MCP SCI reply shows each evaluation as the transcript does: its printed output, then its
value. Stdout and stderr stay distinguishable.

## Regression

A branch run of `(do (println "x") 1)` answers the shown `x` before `1`, and a run of
`(binding [*out* *err*] (println "e"))` answers `e` marked as stderr.
