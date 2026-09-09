---
type: issue
status: open
severity: blocker
tags: [issue, test, wave/contract-gate]
---

# Platform runner exceeds the configured worker checkouts

Observed 2026-09-08 in adoption lane snapshot `run.vPJVrV`, based on
`152f11a68` plus that lane's paths. `SEON_TEST_WORKERS=3 bin/test --paths
… --platform` prepares three worker checkouts (`bin/test:534` in the
snapshot), but `seon.test.runner/worker-count` at HEAD derives half the
visible processors without consulting that setting. It launched `pool-4`,
whose stderr reports `Could not locate seon/test/runner__init.class,
seon/test/runner.clj or seon/test/runner.cljc on classpath.` Exit 1 occurred
before readiness; this is not a platform assertion result.

The shared runner files already had another lane's edits, so the adoption
lane did not modify them. Its retry uses test-process-only
`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=6` together with
`SEON_TEST_WORKERS=3`, making both existing derivations select three.

Acceptance: one configured worker count governs preparation and JVM launch;
the platform gate runs with the requested count on a machine with more
processors. Evidence is also recorded in
[the adoption landing note](../../prds/context-generation/research/adoption-rows-landing-2026-09-08.md).

Components reproduced this at snapshot `run.fv5GcQ` on 2026-09-08:
`pool-4` exited 1 before readiness; its stderr could not locate
`seon/test/runner`. The coordinator failed after 45 seconds. This was
`SEON_TEST_WORKERS=3 bin/test --paths <components paths> --platform`.
The retry uses the documented six-visible-processors setting only in test
processes, preserving the protected runner files.

Page-feed reproduced the same class in `run.WGAuvB` on 2026-09-08: one
prepared worker and six visible CPUs caused `pool-2` to exit 1 without
`seon/test/runner` on its classpath. Its retry uses one worker and two visible
CPUs; no runner file was edited.
