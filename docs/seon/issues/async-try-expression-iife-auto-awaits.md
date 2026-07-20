---
type: issue
status: open
tags: [issue, agent, architecture]
---

# try in expression position inside a compiled ^:async fn auto-awaits

## Observed

In pod `.cljs` compiled by the JVM CLJS compiler (1.12.145), a
`try/catch` in *expression position* inside a `^:async` fn is emitted
as an async IIFE that is `await`ed — even when the try body contains no
`await`. Consequence: a Promise VALUE produced inside that try is
silently awaited before the binding sees it.

Reproduced 2026-07-20 in the sci feasibility harness
(`tmp/sci-probe/src/probe/main.cljs`, probe-async! first version):

```clojure
(defn ^:async f []
  (let [r (try (returns-a-promise) (catch :default e e))]
    ;; r is the RESOLVED value here, not the Promise
    (instance? js/Promise r)))  ; => false
```

The identical expression outside a try binds the Promise itself. This
made a Promise-detection probe report `#object[Number]` where a
`js/Promise` was expected; cost ~30 minutes of misdiagnosis against
sci, which was behaving correctly.

## Why it matters

Any pod code that (a) is `^:async`, (b) wraps a Promise-producing call
in `try` for errors-as-values conversion, and (c) intends to pass the
Promise on unresolved (e.g. to stash, race, or batch it) gets the
resolved value instead. `seon.eval`'s Promise plumbing is the obvious
audit surface.

## Acceptance

- Confirm the emission in `reference-code/clojurescript`
  (`cljs/compiler.cljc` async fn + try-as-expression path) and record
  the exact mechanism.
- Grep pod `^:async` fns for try-in-expression around
  Promise-producing calls; either none are affected (close with
  evidence) or fix the affected sites to hoist the try or detect
  thenables before the try.

## Owner

`src/seon/eval.cljs` / pod CLJS conventions (`clojurescript` skill
should gain the gotcha once confirmed against compiler source).
