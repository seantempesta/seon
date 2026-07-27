---
type: issue
status: superseded
severity: blocker
tags: [issue, sci, containment, runtime, host]
---

# Retained per-agent SCI contexts are never evicted, and each shares one guard holder

## Observed

`:seon.host/contexts` is an atom holding one forked SCI ctx per agent id, keyed
forever. Two independent implementations fill it and **nothing anywhere removes an
entry** — `rg 'dissoc.*contexts'` over `src/` returns nothing:

- `seon.agent.driver.host/ensure-context!` — `src/seon/agent/driver/host.clj:136-152`;
- the UDS session's startup arm — `src/seon/host.clj:138-160`.

Both do the same four steps (fork base → `restore-context-defs!` →
`install-registered-wrappers!` → `instrument/reconcile-current-context!`) and then
`swap! ... assoc agent-id created`. That is a duplicated mechanism as well as a leak.

Each retained ctx carries exactly **one** `guard/holder`:

```clojure
(defn fork-context [{::keys [ctx]}]
  (let [holder (guard/holder)]
    (assoc (sci/fork ctx) ::guard/holder holder
           :interrupt-fn (guard/interrupt-fn holder))))
```

`src/seon/host/context.clj:1423-1430`.

## Two distinct defects

**1. Unbounded retention.** Memory grows with agents-ever-started, never with
agents-currently-running. A fork is cheap to make (measured 2.1 µs / ~539 bytes for
the `sci/fork` itself), but a retained ctx holds the agent's whole replayed home
namespace, so the leak is the corpus, not the fork. `seon.host.instrument`'s
`:seon.host/contexts`-wide reconciliation walks this same never-shrinking map
(`src/seon/host/instrument.clj`), so one `defn` gets more expensive forever.

**2. One holder per ctx means concurrency shares containment state.**
`guard/reset!` (`src/seon/host/guard.cljc:57-76`) writes the step budget, the
enforce flag and the whole control cell into arrays owned by the holder, and
`guard/call!`'s `finally` clears the interrupt predicate
(`install-interrupted! holder nil`, `guard.cljc:242`). If two evaluations for the
**same agent** ever run concurrently, B's `reset!` refills A's budget and B's
`finally` clears A's deadline predicate: A becomes unbudgeted and uncancellable.

Not reachable today only by accident: `invoke/begin-invocation!` refuses a second
active invocation per session (`src/seon/host/invoke.clj:231-238`), and one open run
per agent keeps the driver path serial. Any per-agent concurrency — parallel steps, a
render concurrent with an eval — makes it reachable, with no error and no signal.

## Why this is the same bug as the rejected design

Owner ruling 2026-07-25 (ONE CORPUS, UNIVERSAL): there is one shared base ctx and a
**fork per eval** for in-flight isolation only, never a retained per-agent ctx. The
retention here is both the rejected model and the leak; a fork per eval fixes both,
and gives every evaluation its own holder by construction.

## Falsifiable failure

Start N agents, run one eval each, close every run: `(count @(:seon.host/contexts host))`
stays N. For the holder: run two evaluations for one agent id concurrently through
`driver-session` and observe the second's `guard/reset!` restoring the first's
`interpreter-step-counter`.

## Owner and acceptance

Owner: `src/seon/host/context.clj` (`fork-context`), with the two callers above
collapsed into one.

Acceptance: one implementation, a ctx per evaluation (or an evicted-on-close cache
with a measured rebuild cost), and a holder that cannot be observed by two
evaluations at once. One regression per class — a second concurrent evaluation for
one agent cannot alter the first's budget or interrupt cell.

## Related

- `docs/prds/sci-execution-runtime/research/redesign-ledger-2026-07-25.md` R-8a (the
  global fair RRWL that walks this map) and the ONE CORPUS ruling.
- `docs/prds/sci-execution-runtime/research/simplification-design-2026-07-25.md:382-388`
  reached the same conclusion about shared holders while rejecting a ctx cache.
- `docs/seon/issues/guard-safepoint-destructures-a-map-on-every-interpreter-step.md`
  — the other half of `fork-context`'s interrupt-fn.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
