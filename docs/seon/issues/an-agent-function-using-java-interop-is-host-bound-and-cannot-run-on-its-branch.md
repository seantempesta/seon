---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, sci, acquisition, branch, dogfood]
---

# An agent function that uses Java interop is classified host-bound and cannot run on its own branch

Found by dogfood lane dogfood-author-opus through MCP only.

## Observed

On branch `dogfood-author-opus-2` (default pid 27723, HEAD 6a8e46ec6), in a namespace that exists only as
rows on the branch:

```clojure
(ns notes-index)
(defn now-inst "Now, from the host clock."
  {:malli/schema [:=> [:cat] :notes-index.note/created-at]} [] (java.util.Date.))
;; => #'notes-index/now-inst          (the call works inside this run)
```

Next run:

```clojure
(notes-index/now-inst)
;; seon.sci.eval/install-row! refused  at []: expected the declared requirement
;; (:seon.sci.eval/interpretable-definition), got nil nil. Fix: Cannot interpret notes-index/now-inst:
;; Host-bound declaration notes-index/now-inst is in a namespace this JVM has not loaded; load its source
;; files to call it.
```

The row carries `:seon.fn/host-bound? true`. A sibling function without interop in the same namespace
(`put-raw!`, calling `seon.db/transact!`) carries `:seon.fn/host-bound? false` and works. SCI evaluated
`(java.util.Date.)` without complaint in the defining run, so the interpreter supports the class.

On the earlier HEAD (default pid 19859, before be96a64ba), the same row made **every** later run on the
branch report a `seon.sci.eval/install-row!` branch error (occurrence count 2 → 14 over eight runs) until I
removed it with `my.program/ns-unmap!`. After be96a64ba the refusal comes only when the function is called.
The function is still unusable.

## Expected

A function written at the agent REPL that the agent's SCI can evaluate is callable in later runs on the same
branch. At `clojure.main`, `(defn now [] (java.util.Date.))` works. An agent has no source file to load, so
"load its source files to call it" is advice it cannot follow.

## Model that is wrong

Host-bound classification treats any host interop as "must run compiled from a loaded file". That is right for
first-party namespaces the JVM compiled. It is wrong for a namespace that exists only as agent rows, where SCI
is the only evaluator and its class allowlist decides what may run. The classifier should answer "host-bound"
only for what SCI cannot interpret (for example, a JVM macro it does not carry). The refusal should also name
the construct that made the row host-bound: here it was `java.util.Date.`, which I found only by bisecting.

## Workaround used

Take the time as an argument (`:notes-index.note/created-at` supplied by the caller), or derive it from the
transaction's `:db/txInstant`.
