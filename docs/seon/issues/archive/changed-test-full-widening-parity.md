---
type: issue
status: resolved
severity: friction
tags: [issue, agent, flow]
---

# Make full changed-test widening match the canonical pod gate

## Problem

A changed-test widening selected the complete manifest root list but did not
run the complete Shadow test artifact under the canonical Node test process
environment. Its result could disagree with a fresh green `bin/test-cljs` run.

## Evidence

On 2026-07-14, `deps.edn` plus `src/seon/db.cljs` widened to the pod gate but
passed 120 explicit `--test=` selectors. That omitted four compiled synthetic
probe namespaces (`seon.test.*-probes`) which an unfiltered Shadow invocation
runs, producing 1292 tests instead of the canonical 1305. The direct artifact
process also lacked the `config/test.edn` default established by
`bin/test-cljs`; boot therefore seeded no context blocks and 14 lifecycle,
context, and autocomplete assertions failed. The immediately preceding fresh
full runner passed 124 namespaces, 1305 tests, and 6175 assertions.

The working-tree repair marks broad selections as full, emits no runtime
selectors for them, and supplies the canonical test defaults to direct Node
execution. Focused operator coverage passes 17 tests/41 assertions. A direct
immutable-artifact rerun of the three previously failing namespaces passes 56
tests/318 assertions under `config/test.edn`; complete count parity remains the
acceptance proof.

## Owner

`seon.dev.changed-test` execution of the existing immutable Shadow test
artifact and the test-process defaults owned by `bin/test-cljs`.

## Acceptance

A broad or unknown pod input runs the immutable artifact without runtime test
selectors, so Shadow includes every compiled test namespace in its normal
order. Direct artifact execution defaults to `config/test.edn` and strict
rendering while preserving explicit caller overrides. Its namespace, test,
assertion, and failure counts match the canonical complete pod gate.

## Resolution

Broad and unknown selections now carry `:seon.dev.changed-test/full?`, execute
the immutable artifact without selectors, and inherit the canonical test
manifest and strict-render defaults while retaining explicit environment
overrides. The final mixed `deps.edn`/`src/seon/db.cljs` proof passed all three
boundaries: operator 84 tests/539 assertions, writer 50/308, and the full pod
gate 1,305/6,175 with zero failures and errors.
