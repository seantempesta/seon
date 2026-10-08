---
title: "FK-SCI: One SCI fork commit (injectable future-call; alias refuses a retarget as Clojure does)"
type: reference
status: ready
created: 2026-10-08
---

# FK-SCI: One SCI fork commit

Ledger row FK-SCI (group C). Owner answers AU-Q19 option 1 (injectable `future-call`) and the 2026-10-08 ruling
"Clojure's own rule" for aliases (coordinator's measurement, below). Consumers: UL-24 (delete Seon's `pmap` copy),
RP-8 (alias retarget). Model: Sonnet. Host-bound (SCI is the evaluator): throwaway check for the bump.

## Where the fork lives (verified at Seon `1fcdbf47a`)

- `deps.edn:62`: `org.babashka/sci {:local/root "reference-code/sci"}`, a **local root** submodule.
- `reference-code/sci` at `ce0aa53b`, remote `fork` = `git@github.com:seantempesta/sci.git`. Commit, push to `fork`,
  bump the gitlink.

## Change 1: the future addon takes an injectable `future-call`

`sci.addons.future/install` (`reference-code/sci/src/sci/addons/future.clj:42-51`) installs
`'future-call (copy-core-var future-call)` and `'pmap (new-var 'pmap pmap)`; its `pmap` (`:16-36`) runs each
element through the host macro `future**`, i.e. the host `clojure.core/future-call`, so it bypasses whatever
`future-call` the context was given. Seon therefore copies `pmap` (`src/seon/sci/kernel.clj:693-719`) over its own
`seon.sci.kernel/future-call` (`:669-691`, which carries the arm and counts owned work).

- `install` gains a 2-arity `[opts {:keys [future-call]}]` (a host fn of one thunk; the 1-arity is
  `(install opts {})`, unchanged behavior). With `future-call` it installs `'future-call` as
  `(new-var 'future-call future-call)`, else the copied core Var as today. A second argument keeps the addon's option
  out of the context opts `sci/init` reads.
- `pmap` becomes `(defn- pmap-with [future-call] (fn pmap …))`, the current body with `future**` replaced by
  `(future-call (vars/binding-conveyor-fn (fn [] (f %))))`; `install` installs `(new-var 'pmap (pmap-with injected))`.
- The `future` macro (`future*`, `:8-11`) already expands to SCI's `future-call`, so it follows the injection.
- Fork test (`test/sci/` new `future_addon_test.clj`, JVM): `(install opts {:future-call counting})` with an injected `future-call` that counts calls
  into an atom; `(sci/eval-string "(doall (pmap inc [1 2 3]))" ctx-opts)` answers `(2 3 4)` and the count is 3;
  `(deref (future 1))` answers 1 and the count is 4; without the option, behavior and the existing addon tests are
  unchanged.

## Change 2: `alias` and `require … :as` refuse a retarget, as Clojure does

Measured (coordinator, 2026-10-08; reproduced here with plain `clojure` 1.12 in a scratch directory):

- JVM Clojure: `(alias 'm 'clojure.string)` then `(alias 'm 'clojure.set)` throws `IllegalStateException`
  "Alias m already exists in namespace u, aliasing clojure.string"; `(require '[clojure.set :as m])` after
  `(require '[clojure.string :as m])` throws the same; re-aliasing to the **same** namespace is allowed (returns nil).
  Retargeting needs `(ns-unalias *ns* 'm)` first.
- SCI (agent context): the second alias silently overwrites; `(ns-aliases *ns*)` then shows `clojure.set`.

Change, at both writers of `[:namespaces current-ns :aliases alias]`:

- `sci.impl.namespaces/sci-alias` (`src/sci/impl/namespaces.cljc:478-486`): inside the `swap!`, when the current
  namespace already has `alias-sym` mapped to a different namespace symbol, throw (on the JVM
  `IllegalStateException.`, in CLJS `js/Error.`) with Clojure's message
  `(str "Alias " alias-sym " already exists in namespace " current-ns ", aliasing " existing)`; equal target: no-op
  as today.
- `sci.impl.load` (`src/sci/impl/load.cljc:110`, `(assoc-in the-current-ns [:aliases as] lib-name)`): the same check
  before the `assoc-in`; it covers `:as-alias` too (`:179-182` routes through the same `:as`), which in Clojure also
  goes through `alias`.
- `ns-unalias` is unchanged; after it the alias can be set to a new target.
- Fork tests (`test/sci/namespaces_test.cljc`): the two refusals with the exact message; same-target re-alias
  returns nil; `ns-unalias` then `alias` retargets; `(require '[clojure.set :as-alias m])` over an existing `m`
  refuses. Run SCI's JVM suite (`clojure -M:test` or the fork's script; read `reference-code/sci/deps.edn`) green.

## Seon side (the bump commit)

- Files: the gitlink `reference-code/sci`; `src/seon/sci/eval.clj:394-399`, where the threaded opts call `sci.future/install` and then overwrite
  `'future-call` and `'pmap` with `kernel/future-call`/`kernel/pmap`: becomes
  `(sci.future/install {:future-call kernel/future-call})` and the `'future-call` `assoc-in` goes (the `'pmap`
  `assoc-in` stays until UL-24); `test/seon/repl_differential_test.clj`.
- The kernel's `pmap` copy is **not** deleted here (UL-24 deletes it and its binding once this lands); the
  injection makes SCI's own `pmap` equivalent, which UL-24 proves.
- New rows in `seon.repl-differential-test/rows` (each alias name distinct, the rows share one agent namespace):
  - `["alias-retarget-refuses" "(alias 'fa1 'clojure.string) [(try (alias 'fa1 'clojure.set) :retargeted (catch Exception _ :refused)) (ns-name (get (ns-aliases *ns*) 'fa1))]" [:refused 'clojure.string]]`
  - `["require-as-retarget-refuses" "(require '[clojure.string :as fa2]) [(try (require '[clojure.set :as fa2]) :retargeted (catch Exception _ :refused)) (ns-name (get (ns-aliases *ns*) 'fa2))]" [:refused 'clojure.string]]`
  - `["alias-same-target" "(alias 'fa3 'clojure.string) (alias 'fa3 'clojure.string)"]` (nil, as plain SCI)
  - `["unalias-then-retarget" "(alias 'fa4 'clojure.string) (ns-unalias *ns* 'fa4) (alias 'fa4 'clojure.set) (ns-name (get (ns-aliases *ns*) 'fa4))"]`
    (`clojure.set`, as plain SCI)
  The expected values are plain JVM Clojure's (measured above); the first two state them because unforked SCI
  differs.
- RP-8 consequence (noted on its ledger row): an alias can no longer be retargeted silently inside a pass; the
  retarget RP-8 handles now happens only after an explicit `ns-unalias`, and RP-8's `:alias-retargeted` finding is
  still delivered for that explicit unalias plus re-alias.
- Proof: `seon.repl-differential-test` green with the four rows; `seon.sci.eval-test`, `seon.sci.kernel-test`
  green.
- `--test seon.repl-differential-test --test seon.sci.eval-test --test seon.sci.kernel-test`
- Commit: `bin/lane commit <lane> -m "FK-SCI: sci fork's future addon takes an injectable future-call; alias and require :as refuse a retarget as Clojure does" --test seon.repl-differential-test --test seon.sci.eval-test --test seon.sci.kernel-test reference-code/sci src/seon/sci/eval.clj test/seon/repl_differential_test.clj`

## Cost

None per call (one more Var lookup at install). UL-24 deletes 27 lines.

## Deleted

Nothing in this row.

## Dependencies

None for the fork. `src/seon/sci/eval.clj` is also PB-03's and UL-10's: the Seon commit is two lines; claim it
briefly or hand root the patch. UL-24 and RP-8 after this.
