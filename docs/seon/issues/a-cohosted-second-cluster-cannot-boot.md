---
type: issue
status: open
severity: blocker
tags: [issue, runtime, boot, schema, concurrency]
---

# A second cluster in one JVM cannot boot: its contracts are compiled against the first cluster's projection

## Problem

Starting a second cluster into an ALREADY-RUNNING operator JVM fails at
`seon.cluster/require-activation!` with a Malli contract violation. The
identical cluster, from the identical published commit, boots cleanly through
every layer when it gets its own operator root — that is, its own JVM.

This is Defect II of the
[parallel isolation audit](../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md)
("derived state parked in process-wide slots") observed end to end at the boot
boundary rather than in a probe. It is a blocker for the
[test-infrastructure spec](../../prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md)'s
four-worker parallelism target, whose entire premise is many sovereign
environments in ONE JVM.

## Evidence

Observed 2026-08-07 by seon.env Phase 1 lane W1, at commit `ee00c6dd3`, on
isolated operator roots (never the shared default cluster).

Fails — second cluster, same JVM:

```
bin/seon --root tmp/w1-operator start w1     # -> web, healthy
bin/seon --root tmp/w1-operator init w1b     # publishes cleanly
bin/seon --root tmp/w1-operator start w1b
● w1b boot: repl / store / branch
✗ seon.cluster/require-activation! violated its contract (invalid-output):
  #:seon.activation{:schema-keys       [{:value nil, :message "missing required key"}]
                    :required-attributes [{:value nil, :message "missing required key"}]
                    :config-defaults   [{:value [:seon.config.ai/extra-body-edn …],
                                         :message "invalid type"}]
                    :config-required   [{…}]}
```

Succeeds — same cluster name, same commit, its own root and therefore its own
JVM:

```
bin/seon --root tmp/w1-operator-b init
bin/seon --root tmp/w1-operator-b start w1b
● w1b boot: namespaces / repl / store / branch / recovery / config
● w1b boot: program / work-launcher / agents / web
● w1b  http://127.0.0.1:7705  prepl=49749
```

## Mechanism (hypothesis, needs one probe to confirm)

The operator's start wrapper applies instrumentation under the FIRST running
instance's projection state: it selects `anchor` as the first running instance
with a cluster connection and calls `seon.instrument/apply!` inside
`schema/call-with-projection-state` for THAT cluster
(`script/seon/fresh_operator.clj`). The instrumented
`:seon.activation/closure` contract compiled for cluster A is then the one
cluster B's `require-activation!` output is validated against. The
`:config-defaults` "invalid type" against a vector of real config keys is the
signature of a contract compiled in a different projection generation, not of
a genuinely incomplete activation closure.

Confirm before fixing: start the second cluster with instrumentation
disabled, and separately compare the compiled `:seon.activation/closure`
validator identity across the two clusters' projection states.

## Owner

`seon.schema`'s compiled-shape caches and `seon.instrument/apply!`, with the
operator's start wrapper. The repair is the seon.env PRD's Phase 3 slice
"move the compiled caches onto the projection" — compiled state hangs off the
value it derives from, so two projections cannot exchange a validator.

## Acceptance criteria

- Two clusters start into one operator JVM, both reaching `web`, each holding
  its own projection and its own compiled validators.
- A test proves it as a class: instrumenting under cluster A's projection
  cannot change what cluster B's contracts validate.
- The failure face is readable: today this refusal prints a single unbroken
  ~9,000-character line repeating the same message four times around a full
  stack trace and the whole boot instance. A boot refusal needs a declared
  `:seon.render/ai` producer.
