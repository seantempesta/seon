# Malli Schemas, Contracts & Generators in Seon

All stored Datahike attributes, runtime write checks, and `seed = 42` generative tests derive from one Malli registry (`resources/seon/schemas.edn` + `:seon.schema/entity`).

## Syntax & Predicate Rules

- Use **predicate symbols** (NOT keywords) for Clojure predicates: `number?`, `pos-int?`, `nat-int?`, `bytes?` (never `:number` or `:pos-int`).
- Built-in keywords: `:int`, `:double`, `:string`, `:boolean`, `:keyword`, `:qualified-keyword`, `:symbol`, `:qualified-symbol`, `:uuid`, `:map`, `:vector`, `:set`, `:enum`, `:or`, `:maybe`, `:tuple`, `:cat`, `:=>`, `:function`, `:any`.
- Never use bare `:map` for domain return types when concrete keys are known — define or reference a multi-segment schema key (e.g. `:seon.health/report`) so `:seon.code.var/input` and `:seon.code.var/output` link the program graph.

## Registering & Evolving Schemas

- Prefer simplifying or collapsing existing `:seon.schema/entity` specs before adding a new key. Check `(user/schema "keyword")` first.
- Register or update schemas via `seon.fork.helpers/register-schema!` (`(user/register-schema! :seon.foo/bar [:map ...] {:doc "..." :examples [...]})`) backed by `seon.schema/register-schema!`. Updating an existing key preserves `:seon.schema/doc`, `:seon.schema/examples`, and `:seon.schema/context-policy` when omitted.
- Datahike derivation tags on `:seon.schema/form` props: `:seon.db/attributes true` (on `:map` entity schemas), `:seon.db/identity true`, `:seon.db/index true`, `:seon.db/to [:seon.other/entity]`, `:seon.db/storable true`.

## Pure Function Contracts, Examples & `seed = 42` Generative Checks
- Every pure public `defn` (`:seon.code.var/entity`) carries `:malli/schema` + `:cost/ticks`, e.g. `{:malli/schema [:=> [:cat :int :string] [:map {:closed true} [:ok? :boolean] [:label {:optional true} :string]]] :cost/ticks 500000}`.
- Tighten loose `:map` returns with explicit keys and `{:closed true}` when the returned map keys are fixed; mark optional keys `[:k {:optional true} T]`.
- Multi-arity functions use `[:function [:=> [:cat :int] :int] [:=> [:cat :int :int] :int]]`. `seon.schema.stress/generatable-function-schema?` stresses single-arity `:=>` and concrete multi-arity `[:function ...]` schemas; `(user/stress-var 'ns/fn)` ALSO verifies `:examples` on non-generatable `:function`/`:any`/`:seon.host/*` schemas.
- Mark functions that perform I/O, mutate state, or call `^:seon.flow/edge` helpers with `^:seon.flow/edge` (or accept `:seon.host/db` / `:seon.host/conn`).
- Attach `:examples [[arg-vec expected] ...]` inside `:=>` properties (`[:=> {:examples [[[1 "a"] {:ok? true}]]} [:cat :int :string] [:map [:ok? :boolean]]]`; 0-arg: `[[] expected]`; **1-arg taking a vector/seq `[a b]`**: `[[[a b]] expected]` — outer vector is the arg list, inner vector is arg 1!) and verify via `(user/stress-var 'ns/fn)` or `(user/run-tests!)`.
- Validate custom Malli forms in the REPL against Seon's live registry via `(malli.core/validate form val {:registry (:registry (seon.schema/registry-for-db (user/db)))})`.
- Keep scratch `def` probes in `repl`/`user` (never inside production `seon.*` namespaces — defs inside `seon.*` persist into Datahike).
- Quote symbol values inside `:examples` (e.g. `'foo.bar/baz`, since `(defn ...)` evaluates its attr-map); for multi-arity `:function`, attach `:examples` on each `[:=> {:examples ...} ...]` arity.
- Creating a new test ns: `(in-ns 'seon.foo-test)` then `(ns seon.foo-test (:require [clojure.test :refer [deftest is testing]] [seon.foo :as foo]))`, define `(deftest ...)`, and prove via `(user/run-tests! 'seon.foo-test)`.
## Fixing Malli Errors

- `:malli.core/invalid-input` (`:input-contract`): your call passed the wrong shape — inspect the expected `:seon.schema/entity` form and examples.
- `:malli.core/invalid-output` (`:output-contract`): the function returned a value violating its own return schema — check `:got` and `:human` in `seon.schema/stress-var` output.
- `:malli.core/child-error` or `:malli.core/invalid-schema`: an unknown schema ref or invalid keyword predicate (like `:number`) inside a `:map` or `:=>` form — verify all referenced keys exist in `(user/schema ...)`.
