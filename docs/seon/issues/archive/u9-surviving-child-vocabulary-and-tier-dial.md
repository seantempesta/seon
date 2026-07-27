---
type: issue
status: superseded
severity: cleanup
tags: [issue, agent, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Remove the remaining child vocabulary and tier dial

## Problem

The D-list cut leaves independent cleanup seams outside its owned rows:
`src/seon/agent/turn.cljs` still describes prompt failures as execution-child
failures, `src/seon/host/invoke.clj` retains child/pod error copy, and
`:seon.config.execution/host-tier?` remains in configuration despite system
teaching having one unconditional cluster JVM contract.
`src/seon/agent/ctx/driver.cljs` also retains response-local map keys in the
deleted `seon.execution.runtime` namespace.

## Acceptance

- Prompt docstrings and error values name the cluster JVM and its
  `:interrupt-fn`.
- Host-session busy/refusal errors contain no child/pod topology claims.
- Context-driver response data uses an owning live namespace.
- The dead host-tier configuration fact and its tests are deleted once all
  non-context consumers are audited.
- Diffusion's isolated self-host compiler remains explicitly excluded.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
