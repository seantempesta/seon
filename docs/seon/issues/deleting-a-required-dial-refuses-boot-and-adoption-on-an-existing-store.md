---
type: issue
status: fixed-pending-verification
created: 2026-09-26
severity: platform
owner: src/seon/config.clj (apply-compiled!), src/seon/db.clj (validate-write-report)
tags: [issue, schema, adoption, config, boot]
---

# Deleting a required dial refuses boot and adoption on an existing store

## Evidence

HEAD 7c40396b3 removed `:seon.config/on-core-error` from `seon.config.edn` and
`config/default.edn`. `bin/seon start --head` then failed on the live store. The
first write on `:cluster-default` was refused: "Transaction report validation
rejected." The refusal it carried (reproduced read-only with `d/with` on the live
branch value) said:

    seon.db/transact! refused transaction data at [34508 :seon.config/on-core-error]:
    expected the required key :seon.config/on-core-error ... Entity: #:seon.config{:cluster "default"}.

## Causes (two, both at their owners)

1. **Config applied before adoption.** Boot compiles its rows from the
   checkout's declarations, but it writes them to a branch that has not yet
   adopted those declarations. That branch still requires the dial. The exact
   reconcile planned `[:db.fn/retractAttribute [:seon.config/cluster "default"]
   :seon.config/on-core-error]`. `accrete-row-attributes!` already covered the
   added-dial side of this; nothing covered the retired side.
2. **The writer judged the report by the declarations it replaces.**
   `write-report-validator` closed over the pre-write projection. Adoption
   (`seon.cluster/adopt-rows!`) retracts a retired attribute with its data and
   rewrites the entity schema that required it, in one transaction. The
   validator still refused that adoption, because the old entity schema
   required the key. Reproduced with `d/with`: the same 47-datom adoption is
   refused under the old validator and admitted when validated against the
   projection the final database carries.

## Fix

- `seon.config/hold-retired-attributes`: config keeps the current value of any
  attribute the checkout no longer declares. Adoption retires that attribute.
- `seon.db/validate-write-report`: the final report is validated against the
  final database's own declarations (`carried-projection` of `:db-after`). A
  value with no declaration rows falls back to the construction projection.

Regressions: `seon.config-test/a-dial-the-checkout-no-longer-declares-is-left-for-adoption`,
`seon.schema-in-place-test/a-required-key-retires-with-its-entity-schema-in-one-transaction`.

## Remaining

`seon.cluster/accrete-schema-population!` writes declarations and schema rows
in two transactions. A handed projection that retires a required key would
still be refused there. `adopt-rows!` uses one transaction and is not affected.
