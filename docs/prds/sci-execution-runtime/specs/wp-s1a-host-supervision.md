---
type: prd
status: active
tags: [prd, architecture, database]
---

# WP-S1a — the sci execution host becomes a supervised child

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

Authority — read BOTH first:
`docs/prds/sci-execution-runtime/research/wps-supervision-grounding-2026-07-22.md`
(the managed-process contract with the writer spec as template, the
host launch surface, risks/falsifiers) and its §"WP-S1a" cut. Scope is
S1a ONLY: source-checkout ownership. S1b (packaged artifact) waits on
the W9 sci coordinate; S2 (lazy respawn) is a follow-up unit.

## Goal

`bin/seon` owns the JVM sci execution host as a recorded child with the
same discipline as watcher/writer/pod:

- a managed-process spec (argv from source, per-cluster socket config,
  writer dependency, containment generation, logs);
- readiness = raw-UDS connectability of the host eval socket (+
  whatever ready evidence the host can honestly publish — follow the
  writer's dynamic-file idiom);
- ordering `watcher → writer → host → pod` and reverse stop;
- `status`/`logs`/`restart`/`down` integration;
- safe socket ownership (never unlink a live foreign listener — the
  grounding's risk 3 falsifier);
- the pod's host-coordinate publication for agents (the grounding's
  risk-4 finding: no production publisher exists — wire the existing
  `:seon.execution.host/eval-socket-path` acquisition to the
  supervised host's socket; read how tests publish it today and make
  the operator/pod do it for real).

## Falsifiers (from the grounding — bake in)

- Full cycle: `bin/seon up` → status shows host ready → one host-tier
  invocation succeeds through the supervised host → `bin/seon down`
  leaves no process and no eval socket.
- Foreign-listener socket safety.
- Reverse-order stop leaves the writer alive until the host is gone.

## Owned paths

`script/seon/dev/process.clj`, `script/seon/dev/config.clj`,
`script/seon/dev/cli.clj` (the graph/order/status surfaces),
`src/seon/host.clj` ONLY if readiness publication needs a seam it
lacks (read first; report), the pod-side coordinate publication site
the grounding identifies, operator tests (enumerate).

Protected: everything else. Live `bin/seon` cycles on the DEFAULT
cluster are GRANTED for the falsifiers (leave it up and ready).
No commits.

## Gates

`bin/seon test operator` full (baseline 296/1656) + `bin/test-writer`
full (369/2781) + the live cycle transcript.
