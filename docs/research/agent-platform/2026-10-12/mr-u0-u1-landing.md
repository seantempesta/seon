---
title: "mr-u0-u1: U0/U1 phase boundary contradiction"
type: research
status: stopped at assigned design boundary; no implementation committed
---

# mr-u0-u1: U0/U1 phase boundary contradiction

Stopped before schema or implementation edits, as assigned when the design contradicts the code. The existing path derives cluster coordinates, squash-merges them, and copies ended attempts onto main. U0/U1 delete that path before U2/U3 replace its consumers. No phase is proven or committed.

## Evidence

Authority: `docs/research/agent-platform/2026-10-12/hybrid-push.md#6-phases-about-fifteen-minutes-each-red-first-filed-as-tasks`, U0 deletes `:seon.squash/attempt`, `:seon.squash/copy`, `:seon.squash/conflict`; U1 deletes `seon.cluster.squash/copy!`, `seon.cluster.squash/squash-merge!`, and `seon.cluster.squash/conflict-task!`; U2/U3 follow U1.

The current `seon.cluster.agent/land-ends!` calls `seon.cluster.squash/copy!` with `:seon.squash/attempt` transaction metadata. `seon.cluster.agent/record-error-end!` also writes that metadata. `seon.cluster.agent/unlanded-ends` and `seon.cluster.agent/settle-kept-branches!` query it. These are surviving first-party consumers, not another lane's in-flight breakage. `seon.cluster.squash/copy!` and `seon.cluster.squash/squash-merge!` declare `:seon.squash/copy` output contracts, and `seon.cluster.squash/conflict-task!` declares `:seon.squash/conflict` input. Removing the schemas leaves unresolved admitted contracts; removing the functions leaves the end-copy caller unresolved. `test/seon/cluster/registry_test.clj` also calls `seon.cluster.squash/copy!`.

U0's required red proof is `seon.program-test/cluster-attributes-are-the-declared-cluster-entities`, in `test/seon/program_test.clj`, which the assignment does not include among owned files. Its current assertion excludes `:seon.message/to`; U0 intentionally reverses that behavior. This file needs explicit ownership in the revised slice.

The U1 task text permits message ownership by `:seon.message/from` **or** `:seon.message/to`; hybrid §3 and the assignment authorize only the sender (`:seon.message/from`). The task must be corrected to the named authority when implementation resumes.

## Options

1. **Recommended: revise the cuts to keep every intermediate commit loadable.** U0 adds the new schemas and words but retires the old squash schemas with their last consumers in U3. U1 adds the writer function and its proofs but retires `copy!` and its by-hand entrance with those same consumers. Include `test/seon/program_test.clj` in U0 ownership. Cost: one phase-plan/task correction and deferred deletions; guarantee: each phase boots with complete contracts. Sacrifice: U0/U1 do not perform the deletions currently demanded by their rows.
2. Combine U0–U3 into one coordinated platform slice, including the agent, wake, task, and integration-test owners. Cost: a larger assignment and integrated proof round; guarantee: schemas and callers convert together. Sacrifice: separate fifteen-minute U0/U1 landings.
3. Hold this lane until root supplies a revised loadable sequence. Cost: no code changes now; guarantee: default remains untouched. Sacrifice: U0/U1 remain unimplemented.

## Runtime observation and limits

`bin/seon status` reports source commit `015673f475eb78eeaf4b5a9231c187399a4e4bfe`. MCP `runtime_status` reports degraded health: `:seon.cache/discipline-warning` for `:seon.sci.eval/program-context` (recorded load 1517 ms) and `:seon.problems/file-without-namespace` for `script/seon/config_load_timing.clj`. These are observations, not new diagnosed causes.

First own-branch SCI form: `(seon.context/current-state)` as agent `mr-u0-u1`, cluster `default`: value `{:seon.context/turns {:my.agent/turns-left 100}}`, 22 ms / 9.1 MB allocation; request 399.8 ms, 9992 calls, one transaction. The block supplies no assigned task context.

A read-only host contract-compilation probe as that agent was refused: `{:seon.error/member :seon.agent/id :seon.error/offending "mr-u0-u1" :seon.error/expected "root" :seon.error/message "JVM mode is root's: run read-only host queries as agent \"root\"."}`; request 58.1 ms. No root impersonation followed. Thus live removal/compilation was not proven; unresolved references above are source evidence. No uncommitted source was loaded into default, and default was not stopped, reset, or restarted.

Dependency pins inspected: Datahike `42309184021bd24d0f9c0fd17f514c320ab037fd`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`. `malli.core/schema` resolves named contracts through its supplied registry. No schema adoption, red/green phase proof, lint, or candidate boot was run because implementation stopped at the required design boundary. Source/test line delta: zero. Only this landing note was claimed and written.
