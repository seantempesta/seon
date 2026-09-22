---
type: reference
status: runtime replaced; platform admission and installed MCP connection remain unresolved
created: 2026-09-21
tags: [agent-platform, b1b, integration]
---

# B1b integration on the development root

Implementation: `171388062`; reviewed shared instructions: `6120f28f3`, citation
closure `9ff9e610a`. The eight canonical destructive drills remain in lane-b1b.md.
This note records the root integration separately; it is not a platform green.

## Positive evidence

- `bin/seon reset --force` replaced old default PID25658 and indexed an empty store.
  PID55696 reported ready-ms112209, missing-layers[], one root agent, no problems,
  URL http://127.0.0.1:7994, source6ab1cc53-3e63-5cf8-932a-e86f63f070cb.
- That process disappeared after the command tool session completed. A second
  ordinary-session launch with nohup also disappeared; neither is a surviving
  development host proof. Starting the same `bin/seon start` via Python Popen with
  start_new_session=True produced a child surviving launcher exit and later independent
  commands. No operator source or alternative supervisor was added. The observation
  implicates the command-session boundary; the exact termination signal was not captured.
- Retained host: PID56288, start2026-09-22T00:33:08.873Z, parent1, prepl57524,
  web7994. Warm boot ready-ms5468, missing-layers[], one root, no problems.
- Fresh `seon.dev.mcp/execute-clj-eval` through the checked-in bridge: JVM(+1 1)=2,
  2ms; explicit boot/connection -> db/pull returned cluster name "default",5ms;
  SCI(+1 1) returned shown text "2", outcome ok,8ms evaluation/20ms envelope.
- Independent HTTP GET / returned200.
- `bin/test-check default --test seon.id-test/data-shape-and-explicit-length-determine-identity`
  exited0 under its unchanged5000ms request allowance:8pass/0fail/0error,
  recorded run b995d318d9df, basis536870943/completion536870945. The earlier scratch
  timeout is not recast as a pass or attributed without evidence.
- `bin/seon init --dev default` exited0 and returned source commit
  6ab1cce7-675e-5689-a085-286bbbcdcdb1. Hook publication is resumed after this
  successful explicit publication/adoption; automatic post-edit test checks remain off.

## Explicit limits

`bin/test --platform` exited1 during admission, before executing its selected92
platform members. The guard names six tests reaching the destructive fixture:

- seon.cluster.source-lineage-test/existing-clusters-remain-on-their-chosen-source-commit
- seon.cluster.source-lineage-test/stale-incremental-upsert-preserves-the-newer-publication
- seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate
- seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster
- seon.cluster.source-test/incremental-upsert-derives-scalar-safety-from-the-installed-schema
- seon.cluster.source-test/incremental-upsert-records-source-identity-on-the-expected-commit

The lane's earlier ten-name guard probe included long tests; this invocation's
eligible platform scope differs. No policy was weakened, tests relabelled or results
claimed green. The three source-test files are unchanged from launch995155f1e, but
that alone does not prove identical prior graph selection. The existing
[admission issue](../../../seon/issues/platform-flow-census-reaches-root-cleanup-through-scheduler.md)
owns reconciliation of the fixture and tier policy. B4 already owns that boundary.

The app's active mcp__seon__eval_clj connection still returns transport/error:null
for(+1 1) while a fresh bridge against the same host succeeds. Its connection needs
reloading; source bridge verification is not installed-connection verification.
No supported reload tool is exposed to this task; previous app-control access was
explicitly denied. No app process was killed to force a reconnection.

The temporary permission to leave boot/REPL/source broken ends with this runtime
replacement. The failed platform checkpoint and app-side connection are still reported
separately; later cuts must not treat them as established green evidence.
