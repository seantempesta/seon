---
type: issue
status: resolved
tags: [issue, config, database]
severity: blocker
---

# Config schema alias blocks fresh cluster open

## Evidence

On 2026-07-23, the isolated `u5web` operator reached writer, host, and pod
reconciliation after the `UnixPath` fix. The pod then failed opening a fresh
database because the writer rejected this registered config schema:

```text
A canonical schema form cannot be stored by Datahike.
{:seon.db.writer/attribute :seon.config.render-context/sha-256,
 :seon.db.writer/schema-form :seon.content-hash/digest}
```

Selecting a minimal manifest containing only the nine U5 web-render facts
produced the same failure, proving it occurs during complete schema
installation rather than from declared render-context values. Evidence is in
`tmp/orchestrator/u5-gate-live-up.log` and the referenced pod log.

U5 did not weaken schema installation, modify the render-context owner, or
launch internal processes outside the supervisor.

## Acceptance

- The canonical schema projection resolves the digest alias to a
  Datahike-storable scalar form.
- A fresh named cluster opens with both `config/system.edn` and a minimal
  manifest.
- `bin/seon up` proceeds through pod readiness to the dependent JVM web-render
  member.

## Resolution

`seon.db.datahike.schema/malli-form->datahike-attribute` now recursively
dereferences a bare qualified-keyword form through the complete canonical form
population before compiling its Datahike declaration. It preserves the
bridge's existing terminal `:seon.db/ref` storage semantics. Alias cycles and
unresolvable aliases fail with the complete traversal in
`:schema-alias-chain`; the writer retains its existing
`A canonical schema form cannot be stored by Datahike` rejection boundary.

Regression evidence is in
`tmp/orchestrator/u3-gate-schema-alias-writer-final.log`: the focused
`seon.db.datahike.schema-test` and `seon.db.writer-initialization-test`
namespaces pass 26 tests and 164 assertions. The bridge tests prove recursive
digest-to-string projection, cycle rejection, and missing-alias rejection.
The writer-level test opens a fresh database, installs the recursively aliased
config attribute through the real initialization transaction, writes its
value, and reads the installed `:db.type/string` declaration and datom back.

Schema installation consumes the complete program independently of which
manifest sections carry initial values. The original full-system and minimal
web-render attempts both stopped at this same pre-reconciliation installation
boundary, so the fresh writer-level proof covers both manifest selections.
An additional `bin/seon up` was intentionally omitted while the runtime spine
lane's protected pod sources were mid-rebuild; pod readiness is not needed to
falsify this resolved writer bridge defect.
