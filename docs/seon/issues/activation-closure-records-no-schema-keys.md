---
type: issue
status: open
severity: friction
tags: [issue, boot, schema, runtime]
---

# The activation closure records zero schema keys and zero required attributes, so two of its five checks are vacuous

## Problem

`seon.cluster/require-activation!` refuses a boot that is missing any activation
fact. Two of the five fact categories it checks are always empty in the stored
closure, so those two checks can never fail — they compute
`(set/difference #{} database-schemas)`.

Discovered 2026-08-07 while repairing
[a-cohosted-second-cluster-cannot-boot](a-cohosted-second-cluster-cannot-boot.md);
the contract repair there makes the emptiness LEGAL (an absent
cardinality-many attribute is the empty set) but does not make it CORRECT.

## Evidence

A raw pull of the published closure entity on a freshly published store
(`bin/seon --root tmp/cohost-operator init`, cluster `a`):

```clojure
{:db/id 24319
 :seon.activation/source-digest      "494e87df…"
 :seon.activation/config-defaults    [PersistentVector 19]
 :seon.activation/config-required    [PersistentVector 62]
 :seon.activation/executable-symbols [PersistentVector 1000]
 :seon.activation/lookup-refs        [PersistentVector 6]}
```

`:seon.activation/schema-keys` and `:seon.activation/required-attributes` have
no datoms at all. Both are the two derived from the projection catalog:

```clojure
;; src/seon/cluster.clj:759-793, activation-requirements
shapes              (into [] (filter :seon.schema/entity?)
                          (:seon.schema.projection/catalog projection))
schema-keys         (into #{} (map :seon.schema/key) shapes)
required-attributes (into #{} (mapcat :seon.schema/required-attrs) shapes)
```

The other three come from `forms` and from a database query, and all three are
populated — so the failure is specific to `projection-from-database` over the
source scratch database at publication time returning a catalog with no
`:seon.schema/entity?` shapes (or an empty catalog).

Consumer: `closure-fact-missing` (`src/seon/cluster.clj:1002-1041`) then reports
nothing missing for either category at every boot.

## Owner

`seon.cluster/activation-requirements` plus
`seon.schema/projection-from-database` as it is called from the publication
scratch in `seon.cluster.source/activation-seal-tx`
(`src/seon/cluster/source.clj:185-228`).

Note `activation-seal-tx` already refuses an EMPTY closure
(`::activation-empty`) but only on the SUM of all six counts, which the 1,000
executable symbols carry — so a per-category emptiness passes.

## Acceptance criteria

- A published closure carries a non-empty `:seon.activation/schema-keys` and
  `:seon.activation/required-attributes`, matching what
  `activation-requirements` derives against a booted cluster's database.
- The seal refuses per-category emptiness, not only a zero total — a category
  that can legitimately be empty says so explicitly.
- One regression asserts a published closure's five categories against the
  same derivation, so a silently empty category is red rather than vacuous.
