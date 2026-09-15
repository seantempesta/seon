---
type: research
status: complete
tags: [issue, runtime, docs]
---

# Slice B blocker triage — 2026-09-15

**Resolved: 11 / Superseded: 5 / Confirmed open: 2 / Unverifiable: 7. Total: 25.**

Both confirmed-open observations are friction. Four remaining notes retain blocker severity and require behavioral verification; none is represented as reproduced.

## Verification boundary

Read end to end: [AGENTS.md](../../../../AGENTS.md), [issue lifecycle](../../../seon/issues/README.md), the last five dated sections of [the context-generation working edge](../plan/unsettled.md) (2026-09-15 14:45Z through 17:25Z), and [the steward-platform entry](../../steward-platform/README.md). Also read the localized issue instructions and Clojure, REPL, provider, and testing skills.

Entry HEAD was `7e35df2131c71f476a85c6a38bfc8eb292cb36f5`. Source evidence used `git show HEAD:<path>`, `git grep ... HEAD -- <paths>`, and deleting/fixing commit diffs; no working-tree source changes were treated as findings. Concurrent commits advanced HEAD during the triage. The source pointers in the table identify the inspected implementation; historical measurements are explicitly distinguished in each note. Live default observations do not prove HEAD adoption convergence.

Closed notes moved into `docs/seon/issues/archive/` under the issue lifecycle. Existing relative research links in those moved notes were adjusted. No production or test source was edited. The owner retains index scheduling; this lane did not rewrite the shared index.

## Issue verdicts

| Issue | Verdict | Surface | Evidence pointer |
|---|---|---|---|
| [default-component-probe-times-out-after-adoption.md](../../../seon/issues/default-component-probe-times-out-after-adoption.md) | CONFIRMED (friction) | other | MCP runtime status: unknown/timeout; JVM arithmetic: 2 in 2 ms; [triage detail](../../../seon/issues/default-component-probe-times-out-after-adoption.md#re-verified-at-head-2026-09-15) |
| [deletable-directories-have-no-claim-or-size-facts.md](../../../seon/issues/archive/deletable-directories-have-no-claim-or-size-facts.md) | RESOLVED | store-process | `src/seon/operator.clj:216,259,374`; maintenance result schema; [triage detail](../../../seon/issues/archive/deletable-directories-have-no-claim-or-size-facts.md#resolution-2026-09-15-triage) |
| [dependency-class-cache-prepare-races-concurrent-jvm-launches.md](../../../seon/issues/archive/dependency-class-cache-prepare-races-concurrent-jvm-launches.md) | RESOLVED | runner-gate | `bbeb6f651`, `9a8189cbc`, `f2e3bcb34`; `dev_cache.clj:368`; [triage detail](../../../seon/issues/archive/dependency-class-cache-prepare-races-concurrent-jvm-launches.md#resolution-2026-09-15-triage) |
| [dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md](../../../seon/issues/archive/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md) | SUPERSEDED | other | `07fd06a51`, `4fea58d50`, `98aadec8f`; residual → first note; [triage detail](../../../seon/issues/archive/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md#resolution-2026-09-15-triage) |
| [development-adoption-can-mix-host-and-sci-generations.md](../../../seon/issues/development-adoption-can-mix-host-and-sci-generations.md) | UNVERIFIABLE | adoption-publication | `src/seon/cluster.clj:1868,1965`; `src/seon/sci/eval.clj:957`; [triage detail](../../../seon/issues/development-adoption-can-mix-host-and-sci-generations.md#re-verified-at-head-2026-09-15) |
| [development-adoption-loads-test-namespace-off-classpath.md](../../../seon/issues/archive/development-adoption-loads-test-namespace-off-classpath.md) | RESOLVED | adoption-publication | `d756a09d4`; `src/seon/cluster.clj:1839`; [triage detail](../../../seon/issues/archive/development-adoption-loads-test-namespace-off-classpath.md#resolution-2026-09-15-triage) |
| [development-adoption-refuses-cohosted-clusters.md](../../../seon/issues/archive/development-adoption-refuses-cohosted-clusters.md) | SUPERSEDED | adoption-publication | `4bd2116a2`; residual → mixed-generation adoption; [triage detail](../../../seon/issues/archive/development-adoption-refuses-cohosted-clusters.md#resolution-2026-09-15-triage) |
| [development-adoption-retains-old-web-service-inputs.md](../../../seon/issues/development-adoption-retains-old-web-service-inputs.md) | UNVERIFIABLE | adoption-publication | `src/seon/render/web.clj:3423`; root HTTP 200 / 1.806230 s; [triage detail](../../../seon/issues/development-adoption-retains-old-web-service-inputs.md#re-verified-at-head-2026-09-15) |
| [dir-omits-the-agents-own-durable-functions.md](../../../seon/issues/archive/dir-omits-the-agents-own-durable-functions.md) | RESOLVED | context-generation | `5081a11fb`, `b28ccc1f8`; `src/seon/sci/eval.clj:1227`; [triage detail](../../../seon/issues/archive/dir-omits-the-agents-own-durable-functions.md#resolution-2026-09-15-triage) |
| [eval-samples-cost-42mb-of-store-each.md](../../../seon/issues/eval-samples-cost-42mb-of-store-each.md) | UNVERIFIABLE | store-process | Old sample workload changed; fresh 20-sample census required; [triage detail](../../../seon/issues/eval-samples-cost-42mb-of-store-each.md#re-verified-at-head-2026-09-15) |
| [failover-adds-an-uncaptured-system-context-fragment.md](../../../seon/issues/failover-adds-an-uncaptured-system-context-fragment.md) | UNVERIFIABLE | context-generation | `src/seon/turn.clj:4161,4204,4247`; gate not obtained; [triage detail](../../../seon/issues/failover-adds-an-uncaptured-system-context-fragment.md#re-verified-at-head-2026-09-15) |
| [fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves.md](../../../seon/issues/archive/fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves.md) | SUPERSEDED | store-process | `src/seon/error.clj:513`; `src/seon/cluster.clj:2451`; residual → GC cutoff; [triage detail](../../../seon/issues/archive/fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves.md#resolution-2026-09-15-triage) |
| [feed-first-frame-waits-behind-the-cluster-render-pass.md](../../../seon/issues/archive/feed-first-frame-waits-behind-the-cluster-render-pass.md) | SUPERSEDED | render-debug-page | `985a830b5`; `src/seon/render/web.clj:2116,2628,2685`; [triage detail](../../../seon/issues/archive/feed-first-frame-waits-behind-the-cluster-render-pass.md#resolution-2026-09-15-triage) |
| [foreign-write-fence-reads-only-the-dynamic-var.md](../../../seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md) | CONFIRMED (friction) | other | `src/seon/db.clj:220`; unbound guard returned nil in 3 ms; [triage detail](../../../seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md#re-verified-at-head-2026-09-15) |
| [function-install-case-count-is-read-from-an-absent-handle-key.md](../../../seon/issues/archive/function-install-case-count-is-read-from-an-absent-handle-key.md) | RESOLVED | turn-loop | `src/seon/turn.clj:3123`; case count now comes from effective config; [triage detail](../../../seon/issues/archive/function-install-case-count-is-read-from-an-absent-handle-key.md#resolution-2026-09-15-triage) |
| [generated-opening-live-pull-does-not-return-after-help.md](../../../seon/issues/archive/generated-opening-live-pull-does-not-return-after-help.md) | RESOLVED | context-generation | `6aca09cce`, `985a830b5`; ordinary loop no longer calls next-entry; [triage detail](../../../seon/issues/archive/generated-opening-live-pull-does-not-return-after-help.md#resolution-2026-09-15-triage) |
| [generated-turn-fork-omits-the-agent-scoped-environment.md](../../../seon/issues/archive/generated-turn-fork-omits-the-agent-scoped-environment.md) | RESOLVED | context-generation | `cbee18c5f`; `src/seon/sci/eval.clj:2148,2159`; [triage detail](../../../seon/issues/archive/generated-turn-fork-omits-the-agent-scoped-environment.md#resolution-2026-09-15-triage) |
| [generated-turn-omits-the-required-render-output.md](../../../seon/issues/archive/generated-turn-omits-the-required-render-output.md) | RESOLVED | context-generation | `6aca09cce`; `src/seon/turn.clj:1883,4631`; [triage detail](../../../seon/issues/archive/generated-turn-omits-the-required-render-output.md#resolution-2026-09-15-triage) |
| [indexed-core-function-can-be-falsely-marked-installed.md](../../../seon/issues/archive/indexed-core-function-can-be-falsely-marked-installed.md) | RESOLVED | adoption-publication | `src/seon/sci/eval.clj:798,1092,1575`; skipped core rows report 0; [triage detail](../../../seon/issues/archive/indexed-core-function-can-be-falsely-marked-installed.md#resolution-2026-09-15-triage) |
| [live-root-pull-of-189-members-takes-24-seconds.md](../../../seon/issues/archive/live-root-pull-of-189-members-takes-24-seconds.md) | SUPERSEDED | render-debug-page | `985a830b5`, `d6d399561`; residual → warm read-evidence replay; [triage detail](../../../seon/issues/archive/live-root-pull-of-189-members-takes-24-seconds.md#resolution-2026-09-15-triage) |
| [message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md](../../../seon/issues/message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md) | UNVERIFIABLE | turn-loop | `src/seon/turn.clj:3246`; two-agent composition needs gate; [triage detail](../../../seon/issues/message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger.md#re-verified-at-head-2026-09-15) |
| [namespace-removal-does-not-rebuild-contracted-only.md](../../../seon/issues/archive/namespace-removal-does-not-rebuild-contracted-only.md) | RESOLVED | other | `ff9507c1b`; private definition storage/restore removed; [triage detail](../../../seon/issues/archive/namespace-removal-does-not-rebuild-contracted-only.md#resolution-2026-09-15-triage) |
| [no-forms-replies-close-without-correction-or-rewake.md](../../../seon/issues/archive/no-forms-replies-close-without-correction-or-rewake.md) | RESOLVED | turn-loop | `c15d37d55`; `src/seon/turn.clj:2728,4077,4116`; [triage detail](../../../seon/issues/archive/no-forms-replies-close-without-correction-or-rewake.md#resolution-2026-09-15-triage) |
| [orderly-stop-completion-joins-have-no-bound.md](../../../seon/issues/orderly-stop-completion-joins-have-no-bound.md) | UNVERIFIABLE | store-process | `src/seon/cluster.clj:2832,2853`; `src/seon/flow.clj:1271,1275`; [triage detail](../../../seon/issues/orderly-stop-completion-joins-have-no-bound.md#re-verified-at-head-2026-09-15) |
| [ordinary-turns-do-not-use-the-additive-system-turn.md](../../../seon/issues/ordinary-turns-do-not-use-the-additive-system-turn.md) | UNVERIFIABLE | context-generation | `src/seon/turn.clj:3942,2074,2095`; writing path fixed, preview unverified; [triage detail](../../../seon/issues/ordinary-turns-do-not-use-the-additive-system-turn.md#re-verified-at-head-2026-09-15) |

## Ranked confirmed-open blockers

None. The two reproduced observations are an unavailable MCP health projection and the dynamic-custody guard returning nil when unbound. Neither probe demonstrated a blocked live agent or context generation, so both are friction. Ranking source hypotheses as confirmed blockers would overstate this triage.

## Blockers awaiting verification

This is a dated verification order, not a replacement for the issue index:

1. **Adoption generations** — `seon.cluster` / `src/seon/cluster.clj`: use one coherent reload/acquisition boundary while ordinary work is quiesced; verify with `seon.cluster.source-test` and `seon.custody-stability-test`. No race was induced.
2. **Uncaptured failover context** — `seon.turn` / `src/seon/turn.clj`: route the failure through stored history and capture before the backup request; verify in `seon.cluster.turn-test`. The source still adds the fragment; the behavioral gate was not obtained.
3. **Wrong-agent/duplicate completion delivery** — `seon.turn` and `seon.cluster.message`: derive delivery from the actual settled agent and triggering message; verify the existing two-agent composition in `seon.cluster.turn-test` using current APIs. No message was sent.
4. **Unbounded orderly-stop joins** — `seon.cluster` and `seon.flow`: carry the existing declared lifecycle bound to each exact completion wait; verify withheld publications in `seon.cluster.boot-test` and `seon.flow-test`. Default was never stopped.

Each corresponding issue says **UNVERIFIABLE-WITHOUT-GATE**. The service-input and private-read-preview notes carry the same label at friction severity. The sample-storage note instead needs a fresh measured provider workload; old bytes/sample are not a current result.

## Exact observations and gates

- `bin/seon status` at entry: default PID 23729 alive, PREPL 54412, HTTP 7994; filesystem usable 419.67 GiB. Descriptor health is not Flow health.
- `mcp__seon__runtime_status` with root `/Users/sean/src/seon`, cluster `default`: health `unknown`, Flow `unknown`, error `Read timed out`.
- Supported JVM session `triage-b`, same root/cluster: `(+ 1 1)` → `2`, 2 ms.
- Supported JVM probe `(binding [seon.db/*conn* nil] (#'seon.db/foreign-connection-error nil))` → nil, 3 ms. No write was performed.
- Before the no-JVM correction, `curl --max-time 15 -s -o /dev/null -w 'HTTP %{http_code}; bytes %{size_download}; seconds %{time_total}\n' http://127.0.0.1:7994/` → `HTTP 200; bytes 49291; seconds 1.806230`. No browser-paint claim.

The failover builder probe was pure: it never called `ai/complete`. Without a projection it returned `seon.schema/missing-projection`. The projection-supplied retry below timed out at 10,000 ms, so no messages value was observed:

```clojure
(let [database @(seon.operator/connection "default")
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (get (seon.ai/request-body
           {:seon.ai/endpoint "http://127.0.0.1:1"
            :seon.ai/model "triage" :seon.ai/max-tokens 1
            :seon.config.ai/no-auth true :seon.ai/timeout-ms 1000
            :seon.ai/prompt "captured" :seon.ai/system "later failure"})
          "messages"))))
```

Before the owner correction, one command launched:

```text
bin/test-fast --paths docs/seon/issues/function-install-case-count-is-read-from-an-absent-handle-key.md -- seon.cluster.turn-test
```

It created snapshot `tmp/test-runs/run.xC36jh` from HEAD `03976706cc350f105b0d66cd78b50197c3f7642e`, reported one namespace with 981 instrumented functions, and began test execution. No terminal result was received. After the correction the tool session no longer existed and PID 8622 was absent; no green result is claimed. No further JVM was launched and no platform gate was run, per the explicit correction. The 103 MiB owned snapshot was removed after process-table verification found no holder; recursive cleanup did not follow symlinks. No owned background shell remains.

An edit-hook Markdown check reported it could not derive dependency-pin evidence because `docs/seon/issues/plan-renderer-arity-change-blocks-development-publication.md` was absent. This is the observed shared-document boundary, not an attributed cause or a source failure. The lane did not repair another slice or run the shared index checker through a new JVM. Local Python checks passed: 25 table rows, one dated verdict and valid surface per issue, intact frontmatter, and all local Markdown file targets in all 26 documents resolve. `git diff --check` passed. These checks launch no JVM.

## Commits

- `c84880e72` — issues 1–5
- `ad5780df5` — issues 6–10
- `e3d98c91d` — issues 11–15
- `ccc9f8bb4` — issues 16–20
- `8276e6b25` — issues 21–25

The final documentation commit adds this report, reconciles the no-JVM verification boundary, corrects source coordinates, and repairs relative links after archiving.
