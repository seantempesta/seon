---
type: issue
status: open
severity: blocker
tags: [issue, schema, architecture, runtime]
---

# A schema-resource edit bricks value admission in every running cluster

## Problem

Declarations are read from the CLASSPATH at runtime; the predicates those
declarations name are registered at NAMESPACE LOAD. The two halves therefore
advance independently, and a running JVM can hold a declaration whose
predicate it has never registered. When that happens, value admission does
not degrade — it THROWS, from inside the projection of an ordinary return
value, and every subsequent evaluation in that cluster fails the same way.

The disk half is explicit in the fallback warning's own words
(`src/seon/schema.clj:728-734`): resolving the declaration population "reads
every schema resource on the classpath (152 reads, ~14 ms)". So the instant
any lane saves `resources/seon/schemas/<x>.edn`, every cluster running on
that checkout picks the new declaration up. The matching
`register-core-predicate!` call in `src/seon/<x>.clj` does NOT arrive,
because the namespace was loaded once at boot.

The throw path is
`seon.sci.admit/admit-value` → `project` → `project-node` →
`identity-only-node` (`src/seon/sci/admit.clj:141-143`) →
`seon.schema/identity-only-projection` (`schema.clj:2711`) →
`shape-projection` (`2631`) → `build-projection` (`1199`) →
`bound-forms` → `bind-predicates` (`schema.clj:159`), which refuses any
`[:fn <qualified-symbol>]` whose symbol has no registered callable.

This is the failure mode the shared-tree working model produces constantly:
several lanes editing schema resources while several clusters run.

## Evidence

Tool-exercise lane, 2026-08-07, isolated operator root
`tmp/tool-exercise-operator`, cluster `tools` booted 22:33.

1. 22:43 — a sibling lane saved `resources/seon/schemas/seon.flow.edn`
   (adding `:seon.flow/step-var`, whose `[:fn seon.flow/step-var?]` names a
   new predicate) and `src/seon/flow.clj` (adding the matching
   `register-core-predicate!` at line 60).
2. The running cluster picked up the declaration and not the registration.
   Probed directly in that JVM:

   ```clojure
   (pr-str {:has-step-var? (seon.schema/core-predicate-registered?
                            'seon.flow/step-var?)
            :flow-loaded? (some? (find-ns 'seon.flow))})
   ;; => "{:has-step-var? false, :flow-loaded? true}"
   ```

   `seon.flow` was loaded and `seon.flow/graph?` WAS registered — only the
   predicate added after boot was missing.
3. From that moment every `eval_clj` in the cluster failed, including calls
   whose value was a plain `String`, because the envelope map is projected
   through the same path.

Note the asymmetry that makes this severe: nothing the cluster's own
operator did caused it, and nothing in the cluster reports it. The cluster
looks alive in `bin/seon status` and in `runtime_status`, and only fails
when a value is projected.

## Second defect: the face is a 7 KB Java stack trace

The refusal is an uncaught `ExceptionInfo` and the MCP renders it verbatim
into the calling agent's context: `:via`, `:cause`, `:data`, and a `:trace`
of roughly 120 frames — about 7,000 characters per failed call, repeated on
every subsequent call. Two laws broken at once: nothing may throw into the
agent boundary (this should be a flat `:seon.error` value), and a
diagnostic must be readable. The one useful sentence,
`Predicate seon.flow/step-var? has no admitted callable in the corpus
projection`, is buried in `:cause` behind the frame list.

The frames also point at the WRONG owner: every one of them is inside
`seon.schema`/`seon.sci.admit`, so the face names the projection machinery
and never names the schema resource or the namespace whose registration is
missing.

## Expected

Declarations and their predicates acquire together at one basis, so a
declaration whose predicate is not available cannot enter a running
projection — the seon.env PRD already rules this direction ("the 22
load-time registration sentinels … replaced by acquisition at a basis",
[seon-env-prd-2026-08-07.md](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)).
This issue is the live reproduction of why that deletion is urgent rather
than tidy, and it should be listed as one of its acceptance cases.

Until then, and independently of it: an unresolvable predicate at admission
is a FLAT error value naming the schema key, the predicate symbol, and the
namespace expected to register it — never a thrown stack trace crossing
into agent context.

## Acceptance

- With a cluster running, adding a new `[:fn ns/pred?]` declaration to a
  schema resource on disk does not change that cluster's admission
  behaviour, and does not throw.
- An admission that genuinely cannot resolve a predicate returns a flat
  `:seon.error` value naming the schema key and the predicate symbol; a
  regression asserts the value's shape and asserts that nothing throws.
