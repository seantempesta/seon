---
type: issue
status: resolved
severity: friction
tags: [issue, test, schema, class/n2, wave/contract-gate]
---

# Make the public-contract census prove its subjects exist

## Problem

The standing public-function contract test reimplements source analysis and
asserts only that its missing-contract result is empty. An absent source tree,
reader failure that yields no forms, or discovery drift can therefore look
fully healthy.

## Evidence

`test/seon/public_contract_test.clj:39-71` contains a private Clojure reader,
namespace alias parser, and `defn` contract extractor parallel to the one
clj-kondo program-graph analysis. The test at lines 73-81 walks the literal
`src` directory and asserts only `(empty? missing)`; it never proves that any
files or public functions were observed.

A read-only census over the current tree found no public `defn` missing a
`:malli/schema`, so this is a false-green gate defect rather than evidence of
currently missing contracts.

## Owner

The canonical program-graph source analysis and its indexed public-function
facts.

## Acceptance

- The test queries the canonical analysis result rather than parsing source a
  second time.
- It asserts a nonzero, identity-bearing subject census before checking
  contract completeness.
- Missing source roots, unreadable files, and zero analyzed public functions
  are explicit failures with file-level evidence.
- One regression removes the subject input and proves the gate fails.

## Resolution

Resolved by commit `dac16b297`.

The contract census now consumes `seon.fn.analyzer/analyze`, the same canonical
static-analysis owner used to construct program facts, and derives identified
public `defn` subjects from its Var definitions. It refuses an empty census or
an unidentified subject before contract completeness is checked, retaining
the analyzed paths, files, and findings as evidence.

`public-contract-census-refuses-an-absent-source-root` is the retained
counterexample: an absent root must produce
`::no-public-function-subjects` with empty analyzed-file evidence. Source
verification confirmed the old private reader and alias parser are gone. The
focused 2026-08-13 run executed both the production census and absent-root
regression successfully.
