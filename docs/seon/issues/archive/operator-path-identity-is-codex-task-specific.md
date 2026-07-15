---
type: issue
status: resolved
severity: friction
tags: [issue, orchestrator, flow]
---

# Normalize operator PATH identity across Codex tasks

## Problem

Two Codex tasks in the same checkout derive different managed-process
environment digests solely from their task-local `PATH`. A cluster launched by
one task is healthy and reports ready there, while `bin/seon status` or
`bin/acme status` run by another task reports every identical live process as
not ready. This makes shared-tree coordination mistake an observer identity
difference for runtime drift.

## Evidence

After default and ACME were rebuilt sequentially from source commit
`9a60761f`, both task-local operator checks reported all six processes ready,
both page pairs returned 200, and all four gzip SSE feeds decoded. Their
manifest-v4 dependency vectors are byte-equal and both select normalized
writer digest
`3cbacfc0852807f0726c2b82ff7d2b673f68343c3affaaf126aa621453e45ceb`.

From a second Codex task, the same process records remain alive and their logs
show complete builds, ready writers, started runtimes, and open feeds. Status
nevertheless reports all six `:seon.dev.process/ready? false`. The second task
derives default watcher/writer environment digest `d0ab839b...`, while the
records contain `a735e2ce...`; its pod digest is `ca6501ac...`, while the record
contains `2deb923b...`.

`seon.dev.config/child-environment` imports the complete host environment.
`seon.dev.process/managed-environment` correctly ignores
`CODEX_THREAD_ID`, but treats the complete `PATH` string as permanent process
identity. Codex tasks receive different `.codex/tmp/arg0/<task-specific>`
entries and path ordering. Direct process inspection confirms that both
watchers inherited the launching task's path rather than the observing task's
path. `same-process-spec?` therefore rejects an otherwise identical process
before its successful readiness probe can count.

## Owner

`seon.dev.config` and `seon.dev.process` jointly own the one managed child
environment and its stable identity projection. Keep exact executable
selection and real capability-bearing environment values; do not add a
Codex-specific status exception or weaken artifact/process containment.

## Acceptance

- Two tasks with different Codex wrapper directories and harmless `PATH`
  ordering observe the same launched target as ready.
- A change that selects a different Java, Node, Clojure, Babashka, or Shadow
  executable still changes process identity and reconciles the affected
  process.
- Changes to managed `SEON_*` and provider configuration retain their current
  exact identity behavior.
- Focused process/config tests cover task-local wrapper insertion, harmless
  path reordering, and actual selected-executable drift.
- Default and ACME remain simultaneously ready when status is queried from a
  task other than the one that launched them.

## Resolution

Commit `74530d90` replaces raw `PATH` bytes with the canonical executable
selected for each managed process's own `argv[0]`. Record publication and
`same-process-spec?` use that one projection. The child keeps its original
`PATH`; missing, unresolved, and relative executable paths fail closed; and
managed `SEON_*`, provider, Java, Node, and temporary-directory configuration
remain exact identity.

The focused process gate passes 48 tests and 226 assertions. A sequential
default-then-ACME rebuild selected the same manifest-v4 dependency vector and
writer digest
`3cbacfc0852807f0726c2b82ff7d2b673f68343c3affaaf126aa621453e45ceb`.
From a second Codex task, ACME was immediately ready. Default's actual launch
projection equaled all three recorded digests; the observer differed only in
the intentionally managed `DEEPSEEK_API_KEY`. Injecting the launched key
privately made all three default checks ready without revealing secret
material. Canonical Clojure, absolute Java, and canonical Node paths agreed
across tasks.

Both tasks independently observed default and ACME `/` and `/data` returning
200. All four root/data feeds returned gzip `text/event-stream` responses and
decoded a `datastar-patch-elements` event. Provider drift therefore remains a
real restart signal while task-local wrapper paths no longer are one.
