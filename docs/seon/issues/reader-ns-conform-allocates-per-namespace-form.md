---
type: issue
status: resolved (file ns context from clj-kondo analysis, 9bba9e000; the reader conform serves REPL forms only)
severity: friction
tags: [issue, reader, performance]
---

# The reader's `ns` conform allocates 0.7 MB per namespace form

## Problem

`seon.sci.reader/namespace-info` conforms every `ns` form with
`:clojure.core.specs.alpha/ns-form` (b5a76953a). Since 78161085b the program
indexer's `seon.fn/namespace-context` reads namespace clauses through
`seon.sci.reader/read` too, so every indexed file pays that cost.

## Evidence (2026-09-24, lane fix-fn-correctness)

Measured in a `git archive` snapshot (`clojure -M:test`). The probe ran
`namespace-context` over the `ns` forms of all 437 `src`/`test` files, after 5
warm passes, averaged over 10. The machine load average was about 16, so the
times vary between runs. Allocation is stable.

| | per 437-form pass | allocation per pass |
|---|---|---|
| old fn.clj parser (HEAD 7c761e0c9) | 7–34 ms | 13.3 MB |
| reader (78161085b) | 133 ms best, up to 1.25 s under load | 310 MB |

- `namespace-info` alone takes 0.32 ms on `fn.clj`'s own `ns` form (1000 runs),
  so the spec conform accounts for most of the cost.
- A whole `seon.sci.reader/read` of the same form takes 0.92 ms.

## Owner

`seon.sci.reader/namespace-info` and `conformed`. Candidates to check:
`s/conform` of `::ns-clauses` (the regex specs) compared with conforming each
clause's `::ns-require` alone, or a memoized conform keyed by the form value.

## Acceptance

- A full 437-form pass is back within 20% of the old parser's allocation.
- Namespace rows are unchanged for all `src`/`test` files.
