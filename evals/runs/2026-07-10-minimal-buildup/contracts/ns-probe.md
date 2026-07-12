<!-- canary: A11FE2C0-0F3B-4D26-8B01-3C9F5A7E6D44 -->

This is a mechanical REPL probe — eval EXACTLY the following forms, in this order, ONE at a time, without modifying them (except form 3: substitute your actual home namespace, the `my.agent.…` one shown at your prompt cursor). Do not add extra forms. Do not skip a form because an earlier one errored — continue the sequence regardless. After the last form, end with `(complete "probe done")`.

1. `(in-ns 'my.probe.conv)`
2. `(defn twice [m] (* m 2.0))`
3. `(in-ns 'my.agent.YOURID)`
4. `(require '[my.probe.conv :as pc])`
5. `(pc/twice 3.0)`
6. `(my.probe.conv/twice 4.0)`
7. `(ns-interns 'my.probe.conv)`
8. `(twice 1.0)`
