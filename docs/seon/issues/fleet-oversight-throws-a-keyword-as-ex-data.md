---
type: issue
status: open
severity: blocker
tags: [issue, render, oversight, errors]
---

# Fleet oversight throws, and throws a keyword as ex-data

## Problem

`seon.oversight/projection` takes the render router's flat error value and
throws it. Two rules break in three lines.

First, it faults where a value belongs. `seon.render/render` is total precisely
so a broken projection is reported rather than propagated; this call site undoes
that, on the root page's render path, inside the cluster graph's render proc. A
failing fleet block therefore becomes a core fault instead of a rendered error
card.

Second, the throw cannot work. It passes `(:seon.error/kind rendered)` — a
KEYWORD — as `ex-info`'s data map. `ex-info` requires an `IPersistentMap`, so the
line raises a `ClassCastException` naming Keyword, and the real failure is never
reported at all.

## Evidence

`src/seon/oversight.clj:266-274`:

```clojure
(defn- projection
  [source kind]
  (when-let [built (unit source)]
    (let [rendered (render/render {:seon.render/unit built
                                   :seon.render/kind kind})]
      (if-let [failure (:seon.error/kind rendered)]
        (throw (ex-info (:seon.error/message rendered) failure))
        (:seon.render/output rendered)))))
```

`failure` is bound to the KIND keyword, not to the error value.

REPL falsification:

```clojure
(try (throw (ex-info "boom" :seon.render/unroutable))
     (catch Throwable t [(class t) (.getMessage t)]))
;; => [java.lang.ClassCastException
;;     "class clojure.lang.Keyword cannot be cast to class
;;      clojure.lang.IPersistentMap"]
```

Contrast `src/seon/render.clj:117-174`, which is total by contract, and
`src/seon/render/block.clj:365` (`surface`), which carries a failed projection as
a `:seon.error/value` sibling instead of throwing.

Both `oversight/block-ai` and `oversight/block-html` are seeded into root's
block set (`src/seon/render/root.clj:236-240`), so this sits on the live page.
`test/seon/oversight_test.clj` has two deftests, neither of which reaches the
failure branch.

## Owner

`seon.oversight`.

## Acceptance

- `block-ai` and `block-html` return a value on every path — omission when there
  is no cluster, the projection when it succeeds, and the router's flat
  `:seon.error/value` when it fails. No `throw` remains in the namespace.
- The failure is carried in the shape `seon.render.block/surface` already
  established, so root's page renders a failed fleet block the same way it
  renders any other failed block.
- A regression seeds a fleet block whose projection symbol does not resolve and
  asserts the page still renders, carrying the router's error value.
