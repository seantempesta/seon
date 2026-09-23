---
type: issue
status: open
severity: platform
created: 2026-09-23
tags: [issue, indexer, sci, acquisition, agent-platform]
---

# `:as-alias` is recorded as a require, so interpreted acquisition refuses a false cycle

Observed 2026-09-23 ~15:05Z on `default` (pid 94821, source `/Users/sean/src/seon`),
by lane outside-agents-design. Every acquisition that interprets both `seon.fault`
and `seon.flow` refuses with "Program acquisition found a namespace binding cycle."
(`src/seon/sci/eval.clj:2053`). At that moment this covered:

- MCP `eval_clj` in SCI mode on `default`'s shared context (860 ms, 3.37 GB allocated,
  refused before evaluation);
- MCP `eval_clj` with `branch` on a branch off `default`'s head (1,672 ms, refused);
- `bin/test-check default --test my.program-test/program-reads-name-the-referrers-and-propose-without-writing`
  ("check unavailable: … namespace binding cycle").

## Cause (verified)

`seon.fault` declares `[seon.flow :as-alias flow]` (`src/seon/fault.clj:14`); `seon.flow`
requires `seon.fault` (`src/seon/flow.clj:19`). `:as-alias` loads nothing, so Clojure has no
cycle. The indexer's `namespace-context` (`src/seon/fn.clj:246-270`) adds every require
target to `:requires`, including `:as-alias` ones (`fn.clj:258-261`). The stored row:

```clojure
(#'seon.sci.eval/row-bindings (seon.db/pull d '[*] [:seon.ns/name 'seon.fault]))
;; => {:requires ["seon.flow" "seon.blob" "clojure.core.async.flow" "seon.db" "seon.config" "seon.error"] …}
```

The acquisition's topological order (`sci/eval.clj:2025-2060`) then sees
`seon.fault ⇄ seon.flow`. It fires only when both namespaces are interpreted. On
2026-09-23, 275 of `default`'s rows differed from the loaded commit's rows
(`6ab3e63a…` loaded vs head `6ab3eaed…`; 128 in `seon.sci.eval`, 53 in `seon.flow`,
39 in `seon.cluster.agent`), so the affected closure covered 133 namespaces, both among them.

## Fix at the owner

The producer records only loading requires: in `fn.clj` `namespace-context`, conj the
target to `:requires` only when the spec has no `:as-alias` option. The alias still
goes to `:aliases`. It is one line and needs no new key. The regression: index a
namespace pair where A `:as-alias`es B and B requires A, then acquire a context that
interprets both. The acquisition succeeds, and A's row has no `:seon.ns/requires` edge
to B. `fn.clj` is held by b1-adoption on the 2026-09-23 ledger.

The second question is why 275 rows lead the loaded record. That is B1 §2a′'s rows/load/record
split, owned by b1-adoption. The cycle refusal is still wrong on its own and is fixed by the
producer change above.
