---
type: issue
status: open
severity: defect
created: 2026-09-26
tags: [issue, namespace, repl, write-back, reader]
---

# A `require` at the REPL records the `require` form as its namespace's source

Reproduced 2026-09-26 (lane merge-ns-rows-opus, MCP branch `merge-ns-rows-opus`, default pid 36805):
in namespace `my.plan-test`, `(require '[clojure.walk])` replaced the namespace row. Its
`:seon.ns/requires` gained `clojure.walk` (correct), and its `:seon.ns/source` became
`"(require '[clojure.walk])"` — no longer the namespace's `ns` form. An `ns` form evaluated at the
REPL records itself (correct), and the file indexer records the file's `ns` form (`seon.fn/namespace-row`,
`src/seon/fn.clj:227`).

Consequence: the row's source and facts disagree. Write-back splices a namespace row's recorded
source over its `ns` form (`seon.cluster.source/proven-file`), so a merged `require` would write a
file with no `ns` form; the equivalence proof refuses it (`:not-equivalent`), but the agent's
ordinary way to add a require cannot reach the file.

Wanted: a namespace row's `:seon.ns/source` is always an `ns` form declaring exactly its facts. The
namespace schema already names a form renderer (`:seon.render/form seon.render.ns/namespace-form`,
`resources/seon/schemas/seon.ns.edn`); the producer of a `require`-changed row could record that
form, keeping the docstring and clause order of the row it replaces. Until then: add requires at
the REPL by evaluating the whole `ns` form.
