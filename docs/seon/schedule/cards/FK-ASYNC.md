---
title: "FK-ASYNC: A core.async fork whose flow takes a caller error channel"
type: reference
status: ready
created: 2026-10-08
---

# FK-ASYNC: A core.async fork whose flow takes a caller error channel

Ledger row FK-ASYNC (group E). Owner answer AU-Q8 option 2. Consumer: UL-17 (delete `seon.flow`'s `WritePort` reify
and its reach into Flow internals). Model: Sonnet for the fork, root for the remote and `deps.edn`. Host-bound:
throwaway check for the switch.

## Where it lives today (verified at Seon `1fcdbf47a`)

- `deps.edn:24`: `org.clojure/core.async {:mvn/version "1.10.874-alpha3"}`, a **Maven** dependency. There is no fork
  remote: `reference-code/core.async` is a read-only submodule at tag `v1.10.874-alpha3` (`dc35f3e`) with remote
  `origin` = `https://github.com/clojure/core.async.git`.
- **A local fork commit already exists and is unpushed**: branch `seon/flow-stop-errors` at `fb21fc0` (2026-09-24),
  "flow: deliver errors thrown while handling ::flow/stop". It makes `spi/start` return the proc's future, closes
  `error`/`report` only after every proc future completes (`flow/impl.clj` `stop`), and reports a throwing transition
  to `::flow/error` instead of dying, with a regression `test-stop-transition-error-delivered`. That is the second
  half of AU-Q8's gap (`impl.clj:179` closed the channel before an unwinding proc reported). This card builds on it.
- The first half: the graph's error channel is `(async/chan (async/sliding-buffer 100))` (`impl.clj:102`), which
  drops faults under load; Seon replaces it with `seon.flow/fault-port`, a reified `WritePort`
  (`src/seon/flow.clj:304-322`), reaching into `flow.impl.graph`, `flow.spi` and `core.async.impl.*`
  (`flow.clj:11-15`).

## Steps

1. **Root** creates the GitHub fork `seantempesta/core.async` (an external action: the lane does not create remotes)
   and adds it as remote `fork` in `reference-code/core.async`; the submodule's URL in `.gitmodules` changes to the
   fork.
2. **Fork change** (on `seon/flow-stop-errors`, one new commit): `clojure.core.async.flow/create-flow`'s config
   accepts `:error-chan`, a caller channel; `impl.clj:102` uses it when given (`(or (:error-chan config) (async/chan (async/sliding-buffer 100)))`),
   and `stop` closes it only after the procs exit (as `fb21fc0` already does), never earlier; a caller channel the
   graph did not make is still closed at stop, so a consumer's loop ends (document it in `create-flow`'s docstring
   beside `:mixed-exec`). `start`'s returned `:error-chan` is that channel. Nothing else changes.
3. **Fork test** (`src/test/clojure/clojure/core/async/flow_test.clj`): with `:error-chan (async/chan 1000)`, 500
   procs-worth of faults (a step that throws on every message, 500 messages) all arrive (count 500, not ≤ 100); a
   fault raised while handling `::flow/stop` arrives before the channel closes (the existing `fb21fc0` test, run
   with the caller channel too). The fork's suite: `clojure -X:test` or the repository's documented runner (read
   `reference-code/core.async/deps.edn`/`README.md`) green.
4. Push both commits to `fork seon/flow-stop-errors`.
5. **Seon**: `deps.edn` `org.clojure/core.async {:local/root "reference-code/core.async"}` (its `deps.edn` already
   lists `:paths ["src/main/clojure"]`; check it compiles without the AOT'd classes the Maven jar carries: the jar
   ships compiled `ioc` classes; a local root compiles on load, so time one boot on parent and change);
   `reference-code/core.async` gitlink bumped to the pushed head. `core.async.flow-monitor` (`deps.edn:154-155`)
   depends on core.async: confirm its resolution then takes the local root (`clojure -Stree`).

## Seon side (the switch commit)

- Files: `deps.edn`, `.gitmodules`, `reference-code/core.async`. `seon.flow` is unchanged here (UL-17 uses the
  option).
- Proof: the candidate boots; `seon.flow-test` and `seon.turn-test` green; `(-> (Class/forName "clojure.core.async.flow.impl.graph.Graph") .getProtectionDomain .getCodeSource .getLocation)`
  names the local root, not the Maven jar.
- `--test seon.flow-test --test seon.turn-test`
- Commit: `bin/lane commit <lane> -m "FK-ASYNC: core.async from our fork (flow stop delivers its errors; create-flow takes :error-chan)" --test seon.flow-test --test seon.turn-test deps.edn .gitmodules reference-code/core.async`
- Root restarts default in the batch that carries the other fork bumps.

## Cost

Boot: core.async compiled from source instead of AOT classes; measure boot on parent and change (expected under 2 s
more; if more, AOT-compile the fork at `bin/seon`'s dependency-class cache, which already caches classes per commit).

## Deleted

Nothing in this row; UL-17 deletes the reify and four internal requires.

## Dependencies

Root (fork creation, `deps.edn` is root's in the restart batch). The task
`seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check` blocks `bin/lane commit` for any
`deps.edn` change until it is fixed; until then root commits the switch in its restart batch, as it did DEPS-1.
UL-17, then UL-18, after this.
