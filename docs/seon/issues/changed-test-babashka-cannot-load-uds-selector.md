---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Let Babashka load the selector-based UDS transport

## Problem

`bin/seon test changed --path src/seon/db/writer.clj` fails before selecting or
running tests because Babashka cannot resolve `java.nio.channels.SelectionKey`
while loading `seon.db.transport.uds`. The focused JVM writer doors themselves
remain green.

## Evidence

The direct changed-test command failed at the transport namespace import with:

```text
Unable to resolve classname: java.nio.channels.SelectionKey
```

The earlier Babashka UDS compatibility repair dynamically resolved an
asynchronous-close class, but the later selector-based transport added direct
SCI-visible channel class imports. This is an operator loading incompatibility,
not evidence of a writer protocol or UDS runtime failure.

## Owner

`seon.db.transport.uds` remains the one JVM/Babashka transport owner. The fix
must retain its selector-based JVM behavior while making the same namespace
loadable by the Babashka operator; it must not create another transport.

## Acceptance

- Babashka requires `seon.db.transport.uds` successfully.
- `bin/seon test changed --path src/seon/db/writer.clj` reaches the existing
  changed-test selection and runner.
- Focused JVM transport and writer contracts remain green.
