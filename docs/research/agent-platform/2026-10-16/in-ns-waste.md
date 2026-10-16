# in-ns waste in the nine live DeepSeek chains

Read-only, JVM mode, on the nine kept chain branches (`seon.db/branch-database` over `:seon.cluster.eval/origin :model`
evaluations; `:seon.cluster.eval/ns` is the namespace a form ran in, `:seon.turn/starting-ns` the one a request began in).

## Counts

| Question | Count |
|---|---|
| model requests / forms | 275 / 835 |
| `(in-ns ...)` forms | 195 (23%) |
| already in that namespace (redundant) | 123 (63% of in-ns) |
| not redundant, next form a fully qualified call (`my.program/source 'ns/x`, `seon.db/q`) | 63 |
| not redundant, next form a `defn`/`def`/`deftest` (needed) | 4 |
| not redundant, other | 5 |
| redundant ones that preceded a `defn`/`deftest` | 23 |
| in-ns as the first form of a request | 167 of 195 |
| consecutive requests of one agent where start ns = previous end ns | 266 of 266 |

Per chain (in-ns / redundant): 42e0 45/22, 91a8 44/13, fd3b 41/40, ce9d 29/24, dcc8 24/19, fddd 7/3, 8d9e 3/2, 05db 1/0, 419a 1/0.

## Conclusion

- The namespace persists between requests every time (266/266), so the MCP and runtime behave as documented; no
  runtime defect.
- Only 4 of 195 in-ns forms were needed (a non-redundant move followed by an unqualified definition). 123 re-entered the
  namespace the agent was already in, and 63 moved before a call that names its owner in full.
- The producer is `resources/seon/help.md` step 2, which taught "Enter the namespace that owns it" as a numbered step
  of every definition. The context already omits `:seon.context/start` when the agent is in the owner's namespace
  (`seon.context/step-start`), so it is not the cause; the help overrode it.
- Qualified `(defn ns/name ...)` is not Clojure and is not needed: one in-ns per move is the whole cost.

## Fix

Help step 2 now says the REPL stays in the namespace its last form left, that `:seon.context/start` appears only when it
is elsewhere, that a qualified call needs no in-ns and that a repeated one is a wasted form. Proof:
`seon.bootstrap-test/help-teaches-that-the-namespace-persists`. Re-measure on the next live run: the target is the
in-ns count falling from 23% of forms to the 4 needed ones per nine chains. The filed task
`seon.bootstrap/help-teaches-in-ns-first-and-no-probing` keeps only its probing lesson.
