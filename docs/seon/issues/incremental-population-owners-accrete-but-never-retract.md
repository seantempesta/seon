---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [publication, config, schema, class/p1]
---

# An incremental population owner accretes; a removed row survives it

## Problem

An incremental publication now routes each changed input to the one owner
that installs its facts (`src/seon/cluster.clj:2060-2070` classifies, and
`populate-source!` runs exactly the named owners, `:1650-1690`). Both
non-Clojure owners are ACCRETIVE:

- the schema resource owner calls `accrete-schema-population!`, which
  installs declaration and schema-row changes and never retracts a
  declaration whose resource form disappeared;
- the config owner calls `transact-initialization!`
  (`src/seon/cluster.clj:1239`), an upsert loop over
  `config/default-population`. An initialization row DELETED from
  `config/default.edn` keeps its entity on `current-src`.

A complete publication builds a fresh scratch branch, so a removal
disappears there. The two decisions therefore diverge for removals only:
an addition or a value change converges identically.

## Why it matters

The publication decision must not change what the published facts MEAN.
Today a lane that deletes a provider descriptor row from the shipped
document sees it survive until the next complete publication, and nothing
reports the divergence.

## What the fix is not

`seon.reconcile/plan` cannot be pointed at `current-src` as it stands: its
`managed-eids` admits every entity first asserted by the managing process,
and on `current-src` that process asserted the schema rows, the program
rows and the process rows. A reconcile scoped by `boot-process-identity`
would plan `:db.fn/retractEntity` for the whole program graph. Exact
removal needs the PREVIOUS population's identities — the desired set of the
last publication — which no fact records today.

## The shape of the fix

Record the installed initialization identities (and the installed
declaration keys) as facts on `current-src` at publication, then let each
owner retract the difference. That is the same "derive at the authority"
move the incremental routing itself made: the owner that installs the
population is the one that knows what it installed last time.

Related: `complete-publication-takes-seventy-seconds`,
`issue-indexing-at-publication-costs-13-seconds`.
