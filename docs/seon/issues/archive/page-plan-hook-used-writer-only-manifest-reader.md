---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, operator]
---

# Page-plan build hook used the writer-only manifest boundary incorrectly

## Evidence

The first stable R45-S3 build checkpoint on 2026-07-24 failed in
`seon.dev.program-artifact/publish-rows!`. The JVM Shadow hook called
`seon.config.resolve/read-manifest`, whose CLJ branch intentionally refuses
Aero manifest IO so the writer runtime cannot acquire that capability.

After that boundary was repaired, the next source-stable build exposed a
second flush defect: development mode derived rows and the page plan once in
`publish-rows!`, but returned the preceding Shadow build state. The following
page-plan hook therefore could not consume the exact prepared value.

The next source-stable launch proved that independently re-resolving
`config/system.edn` was itself the wrong boundary: a retained cluster selected
the operator-resolved manifest with SHA-256 `607f...`, while the watcher
resolved a different value with SHA-256 `9c48...`. After binding the hook to
the launch descriptor's exact manifest, initialization reached Datahike and
exposed that the schema bridge did not yet map Malli `:re` schemas to their
stored string type.

## Owner

`script/seon/dev/program_artifact.clj` owns build-time page-plan publication.
Manifest resolution must remain in the existing Babashka configuration reader
boundary; the hook may consume its ordinary data result but must not add Aero
IO to the JVM writer classpath or duplicate manifest semantics.

## Acceptance

- The page-plan hook consumes and digest-checks the operator-selected resolved
  manifest; it never independently resolves a configuration source.
- The row hook carries its one prepared value to the page-plan hook; the page
  hook never repeats derivation.
- Both schema bridges map Malli `:re` validation to Datahike string storage.
- A stable `bin/seon up` publishes a valid v12 artifact with `page-plan.edn`.
- Focused artifact tests and the live build checkpoint pass.

## Resolution

Resolved by the R45-S3 commits through `407533985`. The hook now consumes and
digest-checks the operator-selected resolved manifest, carries one prepared
row/page-plan value across Shadow stages, and maps Malli `:re` schemas to
Datahike string storage on both schema bridges.

The stable 64-row artifact published 98 exact pages with application digest
`e7ae62acdac63ff431707b443107cd168cc7f42b5076643381d5aaee099a183b`.
Fresh apply, exact-identity zero-write re-apply, interrupted prefix resume, and
the 256-row comparison all passed on `r45s3`; durable evidence is in
`tmp/orchestrator/r45s3-gate.log`.
