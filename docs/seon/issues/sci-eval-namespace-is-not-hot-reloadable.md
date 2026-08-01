---
type: issue
status: open
severity: friction
tags: [issue, sci, eval]
---

# `seon.sci.eval` is not hot-reloadable — a reload breaks every eval

Observed live 2026-08-01: after `(require 'seon.sci.eval :reload)` in the
`default` cluster JVM, every evaluation through the door failed with
`class seon.sci.eval.EvaluationArm cannot be cast to class
seon.sci.eval.EvaluationArm (different DynamicClassLoaders)` until the
cluster JVM was restarted. Cause: the arm `deftype` gets a new class on
reload while the `defonce` base-ctx/guard closures retain the old class
(`src/seon/sci/eval.clj` ~lines 265-297). The sci-realism audit
(`research/sci-repl-realism-audit-2026-08-01.md`, W1) hit this as a
broken live cluster.

This violates the hot-reload contract the REPL workflow depends on
("re-evaluating a defn changes running behavior immediately") for the
one namespace most central to agent behavior. Either make the namespace
reload-safe (no identity-bearing `deftype`/`defonce` coupling across
reload — e.g. the arm as a plain map, or the guard rebuilt on reload),
or make the failure loud and immediate at reload time instead of at the
next evaluation.

Acceptance: `(require 'seon.sci.eval :reload)` in a live cluster JVM
followed by a door evaluation succeeds, or refuses loudly naming the
restart requirement; a regression covers the class.
