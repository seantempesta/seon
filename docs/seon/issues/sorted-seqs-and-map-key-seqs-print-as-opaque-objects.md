---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, render, print-path, ugly-output]
---

# Sorted seqs and map key/value seqs print as opaque objects

The floor's permitted-operation table (`seon.render.value/classify`; design §3a) has no row for
`clojure.lang.ArraySeq`, `APersistentMap$KeySeq`/`ValSeq`, `PersistentArrayMap$Seq`, `PersistentHashMap$NodeSeq`
or `PersistentTreeMap$Seq`, so a whole value of one of these classes is `#object[clojure.lang.ArraySeq]`. These
are ordinary REPL results: `(sort xs)`, `(keys m)`, `(vals m)`, `(seq m)`, `(apply list …)` arguments.

## Evidence (default, 2026-09-26)

`(seon.render.value/prepare {:seon.render/value (sort [:b :a]) …})` → tree
`{:seon.print/face :seon.print/object, :seon.print/class "clojure.lang.ArraySeq"}`; MCP JVM `(sort (keys m))`
answers `#:seon.sci.admit{:opaque "clojure.lang.ArraySeq"}`.

## Proposal

`ArraySeq` is final with final fields `array` and `i`; `KeySeq`/`ValSeq` hold a final inner `seq` of an exact
persistent map's own seq. Each is a field-only row in the same style as `ChunkedCons`. The design currently lists
`KeySeq` as intentionally not a row, so this needs a design ruling and Astra review of the added rows.
