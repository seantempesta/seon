---
type: issue
status: resolved
severity: friction
tags: [issue, render, context, doc, ugly-output, agent-facing]
---

# `doc` prints schema bodies and flattens arity alternatives — the primary teaching surface is ugly and misleading

## Disposition — 2026-09-15

Implementation: `a65985098`, with final wording in `1fd81b2be`; dependency selection `a24ad11b7`.

The documentation owner derives input/output positions from Malli function-schema data, preserving guard semantics and named contracts. The default JVM proof for `seon.db/pull` returns three ordered input alternatives and three `[:or :nil :map :seon.error/value]` outputs, without generator bodies. The canonical documentation tests pass. A distinct arglist-reading defect is recorded in [documentation-arglists-with-auto-keywords-are-not-edn](../documentation-arglists-with-auto-keywords-are-not-edn.md); it does not restore the flattened-contract mechanism.

Implementation commit and gate results: [refusal-grammar landing](../../../prds/context-generation/research/refusal-grammar-2026-09-15.md).


## Problem

`(doc f)` is the ONE surface a dropped-in agent uses to learn a
function, and its contract lines at HEAD are both ugly and wrong in
three ways (observed 2026-09-02 on cluster `ctxprobe`, SCI evaluation mode,
`(doc seon.db/pull)` and `(doc my.message/inbox)`):

1. **Schema bodies where the key suffices.** Every input ref prints its
   full Malli form after the key. `:seon.db/database-value` prints as
   `[:fn {:error/message "must be an immutable Datahike database value",
   :gen/gen seon.db/database-value-generator, :seon.schema/identity-only
   true, :seon.schema/identity-projection seon.db/database-value-identity}
   seon.db/database-value?]` — generator symbols and projection internals
   an agent can do nothing with — and it repeats once per arity (twice
   in `pull`, twice in `inbox`). An identity-only or `:fn` schema should
   print as its KEY (plus the `:error/message` at most).
2. **Arity alternatives are flattened into one `in:` set.** `seon.db/pull`
   arity 2 is `[database-or-selector options-or-eid]`; its contract is an
   `:or` of two positional shapes, but `role-contract-lines`
   (`src/seon/sci/eval.clj:1035`) prints the UNION of the arity's
   `:seon.fn.arity/input-refs` sorted by key — so `:seon.error/value`
   appears under `in:` for `pull` (it is an output alternative of a
   nested ref), and the agent cannot tell which arguments go together.
3. **The success output is missing.** `pull` arity 3 prints `out:
   :seon.error/value` only — the pulled entity's shape is not a
   registered ref so the derivation drops it; the agent reads "pull
   returns an error value".

## Owner

`seon.sci.eval/role-contract-lines` / `arity-contract-lines`
(`src/seon/sci/eval.clj:1035-1075`) — the contract lines derive from
`:seon.fn.arity/input-refs`/`output-refs` (a flat ref SET per arity)
instead of the arity's declared positional schema (`:seon.fn.arity/input`
AST / `:seon.fn.arity/arguments`, which the program graph already
stores — verified: the `seon.cluster.message/render-ai` arity row
carries `:seon.fn.arity/arguments` with per-argument `:seon.fn.argument/schema`).

## Acceptance

`(doc f)` prints, per arity, the arguments IN ORDER with each argument's
schema KEY (a `:fn`/identity-only schema prints its key only; an inline
form prints compactly), and the declared output alternatives; no
generator symbols, no repeated bodies; `(doc seon.db/pull)` names the
pulled entity as the success output. One regression over a fixture fn
with an `:or` positional contract. This is context-generation program
work (teaching = doc/dir renders, owner direction 2026-09-02); it lands
with the generator's teaching wave unless pulled forward.

## Re-observed 2026-09-16 00:55Z — "a value satisfying unknown error"

Batch 14 cold gate: `seon.sci.eval/evaluate-for-install refused return value
at [:seon.cluster.eval/ns]: expected the required key :seon.cluster.eval/ns
with a value satisfying unknown error, got a map missing :seon.cluster.eval/ns`.
The grammar's "expected <schema in words>" slot rendered the literal text
"unknown error" for a required-key miss on a map. Same refusal-grammar
class: the humaniser has no case for a missing required key whose value
schema is a registered keyword, so it falls to a placeholder. Belongs with
the refusal-grammar residuals.
