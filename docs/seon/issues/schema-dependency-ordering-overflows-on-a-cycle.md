---
type: issue
status: open
severity: friction
tags: [issue, schema, publication, refusal]
---

# Schema dependency ordering overflows the stack on a cycle

## Problem

`dependency-first-schema-keys` (`src/seon/schema.clj:3681-3697`), which orders
`canonical-schema-rows` (`:3700`), adds a key to `seen` only *after* visiting its
dependencies. If two schema keys reference each other, `visit` recurses through the
pair until the JVM throws `StackOverflowError`. That is an `Error`, not a named
refusal; inside a transaction function, a Datahike writer `Error` stops the writer
(`reference-code/datahike/src/datahike/writer.cljc:139-171`, datahike skill).
A recursive schema reference therefore cannot be admitted, and the failure names
neither key.

## Evidence

- Source reading at HEAD 757964097: the `(contains? seen schema-key)` guard precedes
  the dependency reduce, but `(conj seen schema-key)` follows it.
- Fresh default (pid 39484): 1,951 schemas reference others, with 0 cycles and 0
  self-references (a read-only reachability probe over `:seon.schema/references`).
  The defect is latent today.

## Fix

Refuse a cycle by name before ordering. Mark a key in progress on entry, and refuse
with the cycle's key path when a key in progress is reached again. Add one class
regression with two mutually referencing schema forms. The references-as-refs design
(§3b) rules that recursive schema references are refused, not supported.

## Owner

`src/seon/schema.clj` owner (currently held by a running lane).
