---
type: issue
status: open
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

## Owner

`script/seon/dev/program_artifact.clj` owns build-time page-plan publication.
Manifest resolution must remain in the existing Babashka configuration reader
boundary; the hook may consume its ordinary data result but must not add Aero
IO to the JVM writer classpath or duplicate manifest semantics.

## Acceptance

- The page-plan hook resolves the selected manifest through the bounded
  Babashka reader.
- Reader failure and timeout are loud build failures.
- The row hook carries its one prepared value to the page-plan hook; the page
  hook never repeats derivation.
- A stable `bin/seon up` publishes a valid v12 artifact with `page-plan.edn`.
- Focused artifact tests and the live build checkpoint pass.
