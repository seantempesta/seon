---
type: issue
status: open
severity: defect
tags: [issue, database, schema, program-graph]
---

# Writes admit program rows whose projection then refuses every read

Observed 2026-09-25 on disposable MCP branches (lane delete-duplicates, SCI
`seon.db/transact!` with branch custody). Each of these transactions landed,
and afterwards every `seon.db/q` on the branch refused while deriving the
projection:

- a function row whose `:seon.fn/spec` names an undeclared key
  (`[:=> [:cat :fixture.missing/key] :any]`): "Schema reference
  :fixture.missing/key is not declared in the candidate projection";
- two schema rows referencing each other (`[:or :string :b]`, `[:maybe :a]`):
  "canonical schema reference cycle ... Recursive canonical registrations are
  not supported" (`seon.schema/assert-acyclic-references!`);
- an error schema `[:and :seon.error/base [:map {...} [:seon.error/message :string]]]`:
  "Conflicting inherited entity member", and with only base members, "A named
  error schema requires a non-base domain member."

The projection owner states each refusal precisely, but at read time, after the
row is stored. The write should refuse with the same diagnostic
(unbreakable-graph rule: a severed connection refuses at the write), leaving the
branch readable.
