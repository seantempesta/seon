# Malli Schemas, Contracts & Generators in Seon

All stored Datahike attributes, runtime write checks, and `seed = 42` generative tests derive from one Malli registry (`resources/seon/schemas.edn` + `:seon.schema/entity`).

## Syntax & Predicate Rules

- Use **predicate symbols** (NOT keywords) for Clojure predicates: `number?`, `pos-int?`, `nat-int?`, `bytes?` (never `:number` or `:pos-int`).
- Built-in keywords: `:int`, `:double`, `:string`, `:boolean`, `:keyword`, `:qualified-keyword`, `:symbol`, `:qualified-symbol`, `:uuid`, `:map`, `:vector`, `:set`, `:enum`, `:or`, `:maybe`, `:tuple`, `:cat`, `:=>`, `:function`, `:any`.
- Never use bare `:map` for domain return types when concrete keys are known — define or reference a multi-segment schema key (e.g. `:seon.health/report`) so `:seon.code.var/input` and `:seon.code.var/output` link the program graph.

## Registering & Evolving Schemas

- Prefer simplifying or collapsing existing `:seon.schema/entity` specs before adding a new key. Check `(user/schemas "keyword")` first.
- Register or update schemas via `seon.fork.helpers/register-schema!` (`(user/register-schema! :seon.foo/bar [:map ...] {:doc "..." :examples [...]})`) backed by `seon.schema/register-schema!`. Updating an existing key preserves `:seon.schema/doc`, `:seon.schema/examples`, and `:seon.schema/context-policy` when omitted.
- Datahike derivation tags on `:seon.schema/form` props: `:seon.db/attributes true` (on `:map` entity schemas), `:seon.db/identity true`, `:seon.db/index true`, `:seon.db/to [:seon.other/entity]`, `:seon.db/storable true`.

## Pure Function Contracts & `seed = 42` Generative Checks

- Every pure public `defn` (`:seon.code.var/entity`) carries `:malli/schema` + `:cost/ticks`, e.g. `{:malli/schema [:=> [:cat :int :string] [:map [:ok? :boolean]]] :cost/ticks 500000}`.
- Multi-arity functions use `[:function [:=> [:cat :int] :int] [:=> [:cat :int :int] :int]]`. `seon.schema.index/generatable-function-schema?` automatically stresses both single-arity `:=>` and concrete multi-arity `[:function ...]` schemas.
- Mark functions that perform I/O, mutate state, or call `^:seon.flow/edge` helpers with `^:seon.flow/edge` (or accept `:seon.host/db` / `:seon.host/conn`).
- Attach `:examples [[[arg1 arg2] expected] ...]` on `:=>` props so `seon.schema/stress-var` verifies edge cases before 100 `seed = 42` generated samples.

## Fixing Malli Errors

- `:malli.core/invalid-input` (`:input-contract`): your call passed the wrong shape — inspect the expected `:seon.schema/entity` form and examples.
- `:malli.core/invalid-output` (`:output-contract`): the function returned a value violating its own return schema — check `:got` and `:human` in `seon.schema/stress-var` output.
- `:malli.core/child-error` or `:malli.core/invalid-schema`: an unknown schema ref or invalid keyword predicate (like `:number`) inside a `:map` or `:=>` form — verify all referenced keys exist in `(user/schemas ...)`.
