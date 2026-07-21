---
type: research
status: active
tags: [research, agent, architecture]
---

# Probe: shared-var poisoning fix is pure stamping — no sci patch

Closes the design question for robustness-audit vector 2
([[audit-host-robustness-2026-07-21]]): an agent `defn`/`alter-var-root`
over a shared base or registry var mutates the one Var object every
context holds, poisoning the fleet.

## Mechanism (read, then executed)

- `sci/fork` copies only the env atom; Var objects stay shared
  (`reference-code/sci/src/sci/core.cljc:318`).
- `eval-def` calls `vars/bindRoot` on the pre-existing var
  (`src/sci/impl/evaluator.cljc:26-46`) — but `bindRoot` runs inside
  `with-writeable-var` (`src/sci/lang.cljc:97-103`,
  `src/sci/impl/vars.cljc:283-291`), which **refuses when the var's meta
  has `:sci/built-in true`** unless the current ctx is `:unrestricted`.
- `sci.core/alter-var-root` — the host-facing Clojure API — binds
  `:unrestricted true` explicitly (`core.cljc:249-257`). The in-eval
  `alter-var-root` copy does not. Privileged-host vs guarded-eval is a
  designed sci boundary.

## Executed proof (scratch copy of the pinned checkout, JVM)

Harness: scratchpad `cow-probe/probe.clj`, `:local/root` on a copy of
`reference-code/sci` HEAD. Base ctx with `my.shared/f`, var meta stamped
`:sci/built-in true`, two forks a/b:

1. baseline `(my.shared/f)` in a → `:original`
2. a evals `(in-ns 'my.shared) (defn f [] :evil)` → **refused**:
   "Built-in var #'my.shared/f is read-only."
3. b after attack → `:original`; 4. a after attack → `:original`
5. a evals `(alter-var-root #'my.shared/f …)` → **refused, same guard**
6. b unchanged
7. host-side `sci/alter-var-root` on the same var → ok (registry
   hot-swap path preserved)
8. b next call → `:v2` (upgrade propagates to all contexts)
9. a evals own-ns `(defn g [] 1) (defn g [] 2) (g)` → `2`
   (last-version-wins for agent-owned names intact)

## Resulting design (for the containment spec)

- `build-base!` and `register-wrappers!` stamp every base and registry
  var `:sci/built-in true` at creation/registration. One stamping walk;
  no second protection mechanism.
- The eval envelope classifies the "is read-only" throw into a steering
  `:seon/error` value: define your own function in your own namespace
  (rides the existing error-prose synthesis seam, B1 adapter item 1).
- Graduation/registry upgrades keep using `sci.core/alter-var-root`
  (already `:unrestricted`); nothing changes on the privileged path.
- Copy-on-write local overrides (agent shadows a shared name privately)
  remain a possible later enhancement via a small upstreamable
  `eval-def` patch; refusal-with-steering is the shipped behavior.

## Residual limits

- Protection covers root mutation through def/alter-var-root. It does
  not bound what an agent's own code does (vector 1/5 own that).
- Stamping must cover vars registered after boot (post-fork
  registrations go through the same `register-wrappers!`, so the stamp
  lands there — asserted by the spec's test).
