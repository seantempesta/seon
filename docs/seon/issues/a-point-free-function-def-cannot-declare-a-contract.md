---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, program-graph, contracts, sci]
---

# A point-free function def cannot declare a contract

## Problem

Since P2-A (`seon.fn/function-definition?`), `(def f (comp inc inc))` is an
indexed row with `:seon.fn/arglists "()"`: clj-kondo reports no arglists for a
computed initializer. At SCI entry the form now matches its row and refuses as
`:seon.sci.eval/declaration-absent-error` (`:seon.fn/spec`), like
`(def nested (fn [x] x))`. Declaring `{:malli/schema [:=> [:cat :int] :int]}` on
the def cannot satisfy it: `seon.program/contract-facts`
(`src/seon/program.cljc:1085-1133`) derives source arities from the source and
arglists, finds none, and throws "Source and Malli arity sets do not match".
The same throw would refuse file indexing through `seon.fn/add-contract-facts`
if a file declared one (none does today: 0 of 403 computed defs in src/test
carry `:malli/schema`, measured 2026-09-25).

## Owner

`seon.program/contract-facts`. Candidate rule: a declaration with no source
arities takes its arities from the declared contract. Not designed.
