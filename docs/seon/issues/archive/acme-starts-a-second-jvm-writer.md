---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, database, pod]
---

# Run ACME through the existing JVM writer

## Evidence

On 2026-07-18, the former `bin/acme up` path launched an independent JVM
writer under `tmp/proc-acme` while the default writer was already ready.
Process planning used writer ownership to decide Shadow watcher ownership, so
it could not express an ACME-owned watcher and Bun pod using the default writer.

## Resolution

The launch descriptor now names the Shadow watcher owner independently from
the existing Datahike writer owner. ACME supplies its own process directory as
the watcher owner and the default process directory, request socket, and REPL
port file as the writer owner. Process planning therefore starts only ACME's
watcher and Bun pod and admits the default writer as an external dependency.
Ordinary sibling clusters inherit both owners and still start only a Bun pod.

Default and ACME reached ready concurrently with one actual JVM writer. ACME
restart and shutdown did not change default process IDs. A default writer
restart left ACME's Bun pod alive; its dependency followed the replacement
writer PID and its Datastar feed returned a complete patch. Complete maintained
proof passes 1,136 CLJS tests/5,053 assertions, 219 writer tests/1,821
assertions, and 272 operator tests/1,537 assertions.

## Acceptance

- Default and ACME are concurrently ready with exactly one JVM writer process.
- ACME owns its flavor-specific Shadow watcher and Bun pod.
- ACME's Bun pod uses the default writer's request socket and process evidence.
- ACME retains its isolated database, blobs, process state, logs, web port, and
  `acme-client`/`acme-execution` artifact identities.
- ACME restart/down does not stop the default writer or Bun pod.
- Default writer restart reconnects ACME without restarting its Bun pod.
