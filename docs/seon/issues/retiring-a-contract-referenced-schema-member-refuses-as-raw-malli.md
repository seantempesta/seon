---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, schema, publication, retirement]
---

# Retiring a contract-referenced schema member refuses as raw Malli

Plan 1.3e refuses an attribute retirement while a program row names the
attribute in `:seon.fn/writes`, naming the writers. A named Malli member that
is not an attribute but is referenced by a function contract has no such
refusal.

Probe (lane publication-lock-deletion, 2026-09-22, disposable
`tmp/publication-lock-lane/incremental-probe.clj`): on a scratch store
published at `87c4228f7`, `seon.source.edn` was replaced by its `a614fb898`
version (removing `:seon.source/publish-result` and
`:seon.source/publication-error`) while `seon.cluster.source/publish!` still
declares `:seon.source/publish-result` as its output, then
`(seon.cluster/refresh-source! "tmp/root" ["resources/seon/schemas/seon.source.edn"] nil dir)`
ran incrementally (5,636 ms). It refused with
`{:type :malli.core/invalid-schema, :data {:schema :seon.source/publish-result, :form :seon.source/publish-result}}`.
Nothing was published: restoring the resource returned the unchanged commit
`6ab2eac7-442e-513b-9b55-dc5ef9292161` with `:seon.source/built? false` (153 ms).

The refusal is correct in effect but names only the member, not the
surviving contract owner (`seon.cluster.source/publish!`), and is not the
typed `:seon.program/deletion-refused-error` 1.3e gives an attribute.

Wanted: removing a named schema member while any function contract or
schema references it refuses at the declaration write with the typed
retirement refusal naming each referrer; converting the referrers in the
same publication is accepted.
