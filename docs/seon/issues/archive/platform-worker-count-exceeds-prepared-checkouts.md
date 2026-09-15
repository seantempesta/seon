---
type: issue
status: resolved
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
[the adoption landing note](../../../prds/context-generation/research/adoption-rows-landing-2026-09-08.md).

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

Hook-async reproduced this in `run.0eCRor`, based on `ef424c37c` plus its
operator paths: `SEON_TEST_WORKERS=2` prepared two checkouts, while the
coordinator reported nine workers. `pool-3` exited 1 before readiness with
the same missing-runner diagnostic. No platform assertions ran. The retry
sets both `SEON_TEST_WORKERS=2` and the existing JVM worker-count property
(`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Dseon.test.worker-count=2'`).
The protected launcher and runner files remain untouched.

Turn-cut reproduced the same boundary in `run.filFjF`: three prepared
checkouts, but `pool-4` launched and exited 1 with the missing-runner
classpath error. The retry sets `SEON_TEST_WORKERS=3` and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=6 -Dseon.test.worker-count=3'`.
No launcher or runner edits were included.

## Resolution (2026-09-15 triage)

surface: runner-gate

Fix `b1cb47f14` passes the prepared count into the coordinator. At triage HEAD `131fa2a56`, `bin/test:665–666` reads `SEON_TEST_WORKERS`, and `bin/test:733` passes that same count as `-J-Dseon.test.worker-count=$worker_count`. `src/seon/test/runner.clj:1711–1722` consumes the property before considering processor count. Verified with `git show HEAD:bin/test` and `git show HEAD:src/seon/test/runner.clj`; the stated preparation/launch mismatch is removed.
