---
type: issue
status: resolved
severity: friction
tags: [issue, capability, indexing, archive]
---

# Quoted private capability symbols were not indexable

## Problem

The capability design required a public function's metadata to name a private
handler with a qualified symbol. An unquoted private Var cannot appear as a
Clojure metadata value: compilation resolves it as a private Var access and
refuses the form. Quoting the name produces the required runtime symbol, but
the static index retained the source `(quote ...)` list and rejected it as an
invalid handler symbol.

The design also named `reference-code/babashka-fs`, while the exact selected
checkout is the nested submodule `reference-code/babashka/fs` at commit
`3fdcbcb8de6af0c880a0082700a295c55ffd2ecd`.

## Evidence

Loading `my.fs` with an unquoted marker failed with `var: seon.fs.jvm/read is
not public`. Using the runtime-correct quoted marker then made source
publication fail with capability rule `:invalid-handler-symbol`, because the
index observed `(quote seon.fs.jvm/read)` instead of the symbol.

## Owner

`src/seon/fn.clj` owns static capability metadata projection. The dated agent
tools design owns the dependency source location and declaration example.

## Resolution

Commit `d97cf9740` makes the index project a single quoted capability symbol to
the same qualified symbol Clojure stores in Var metadata, while retaining all
five malformed-capability refusals. The design now shows the compilable quoted
declaration and the actual nested dependency path.

## Proof

`seon.fn-test/quoted-private-handler-symbol-is-indexed-as-the-runtime-symbol`
passes. A raw JVM require reports
`{:seon.effect/capability seon.fs.jvm/read, :seon.workload :io}`, and complete
current-source publication admitted all four `my.fs` functions and their
private handlers.

## Acceptance

- A quoted qualified private handler name compiles and indexes as one symbol.
- Public, missing, un-schema'd, capability-marked, and unreachable handlers
  remain refused by the existing graph contract.
- The design points at the exact checked-out babashka.fs source.
