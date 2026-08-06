---
type: issue
status: open
severity: blocker
tags: [issue, maintenance, schema, boot]
---

# Install maintenance result attributes on a fresh cluster

## Problem

A newly published and forcibly reforked default cluster reaches READY, then
its first scheduled maintenance settlement is refused because
`:seon.operator.log/path` is absent from Datahike's installed schema. The
global `:seon.operator.log/result` schema row is present and references that
declared attribute, but population did not install the attribute.

## Evidence

The shared-root proof published digest
`603ea178ada50f3e92c23bd6c9a29e776f9f614f14338bfc2c8ac1c5e3e6fbcd`,
forcibly reforked `default`, and reached `:seon.boot/ready-ms 3326`. The first
maintenance write then logged:

```text
Bad entity attribute :seon.operator.log/path ... not defined in current schema
SEON CORE FAULT (dev panic): The maintenance receipt transaction was refused.
```

A live database probe returned
`{:log-path-installed? false, :log-result-row? true}`.

## Expected owner

The schema-population installation projection must install every Datahike
attribute reached by an admitted operation-result entity schema before a
scheduled handler can settle its receipt. The repair belongs at that generic
projection, not in log rotation or receipt settlement.

## Acceptance

- A freshly published and reforked cluster has
  `:seon.operator.log/path` and every sibling maintenance result attribute in
  its installed Datahike schema.
- The declared global result-schema rows remain queryable.
- The first five scheduled maintenance settlements commit without a schema
  refusal or core fault.
