---
type: issue
status: open
severity: blocker
tags: [issue, deletion, build, quarry]
---

# Delete the writer build that still packages the old host server

## Problem

The root build and dependency manifests still expose a canonical standalone
writer artifact whose entry namespaces exist only in `src-old/`. This is a
live build-time reader into the quarry, and an active reference page still
instructs third parties to build the artifact even though Seon's current
runtime is one fresh JVM process.

The old code is not merely present as archaeology: the build basis places it
on the classpath, AOT derives from it, and the uberjar names it as the main
runtime closure.

## Evidence

- `deps.edn:102-164` labels `:writer`, `:host`, and `:writer-test` old-system
  aliases, but `:writer` uses `:replace-paths ["src" "src-old"]` and
  `:writer-test` adds `test-old` to executable classpaths.
- `build.clj:21-24` makes `[:writer :host]` the writer artifact basis.
  `writer-aot!` loads `seon.db.server` (`:104-128`), AppCDS requires
  `seon.db.server`, `seon.host`, and `seon.web.server` (`:176-188`), and
  `writer-uber!` publishes `seon.DatabaseServerMain` (`:190-221`). Those
  namespaces and the Java main's server target survive only in the old
  system closure.
- `resources/seon/dev/writer-aot-namespaces.edn:244` still seals
  `seon.db.server` into the measured AOT roster.
- `docs/seon/reference/third-party-setup.md:18-38` calls the two-process pod
  topology the settled target and tells readers to run
  `clojure -T:build writer-uber`.
- Fresh operator references to `seon.db.server`, `seon.host`, and the old jar
  at `script/seon/fresh_operator.clj:451-471` are only legacy-process
  detection so `status`/`down` can report old processes. That defensive
  reader does not require the namespaces and is not a reason to preserve the
  build.

## Owner

The root artifact/build boundary. The one fresh `seon.cluster` JVM is the
runtime owner; `src-old/` and `test-old/` remain non-executing quarry.

## Acceptance

- Delete the old writer/host/test dependency aliases, writer AOT roster, Java
  old-server main, and writer uber/AppCDS tasks, or replace a genuinely needed
  distribution with one artifact built exclusively from fresh `src/` and the
  current cluster entrypoint.
- No root build or active instruction places `src-old/` or `test-old/` on an
  executable classpath or requires `seon.db.server`, `seon.host`, or
  `seon.web.server`.
- Third-party packaging guidance is observed against the fresh artifact and
  operator; the old two-process topology is not described as a target.
- Legacy process detection remains data-only until no deployed old process
  needs cleanup, then is deleted in its own outer-reader cut.
