<!-- canary: 55E0B7A2-9D1C-4A55-8830-6F2E4B9C1D77 -->

This is a mechanical REPL probe — eval EXACTLY the following forms, in this order, ONE at a time, without modifying them. Do not add extra forms. Do not skip a form because an earlier one errored — continue the sequence regardless. After the last form, end with `(complete "probe done")`.

1. `(in-ns 'my.probe.intr)`
2. `(defn pf [x] (+ x 1))`
3. `(ns-interns 'my.probe.intr)`
4. `(clojure.core/ns-interns 'my.probe.intr)`
5. `(cljs.core/ns-interns 'my.probe.intr)`
6. `(ns-publics 'my.probe.intr)`
7. `(in-ns 'my.agent.YOURID)` — substitute your actual home namespace, the `my.agent.…` one shown at your prompt cursor
8. `(ns-interns 'my.probe.intr)`
9. `(clojure.core/ns-interns 'my.probe.intr)`
