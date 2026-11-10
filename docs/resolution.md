# Resolution

A name resolves at run time, or a Var is defined outside its definition form, only where this page names the function and its reason; `bin/laws resolution` lists every such site in `src/`.

Every definition happens once, where it is defined: code names what it calls through its
namespace's requires, and behaviour varies through the environment or through data
([AGENTS.md §5](../AGENTS.md)). A name is resolved at run time only where
data names it (a declaration, a schema's render, a cost estimate) or where the compiler's
facts are read; a Var's root changes only when its contract is armed.

`intern`, `alter-var-root` and `with-redefs` are code smells, not banned (owner, 2026-10-21: "intern isn't banned it's a code smell"). A legitimate use builds vars programmatically in a namespace you own, where no definition form exists (a REPL harness, a test environment), and its docstring says why. A gap in another namespace (a library's, Jolt's stdlib) is filled in that namespace's owner instead, and a stub made by rebinding is replaced by a test environment that holds the stub's definition.

`bin/laws resolution` lists every function in `src/` that calls `requiring-resolve`,
`ns-resolve`, `resolve` on a quoted symbol, `intern`, `alter-var-root` or `with-redefs`
(strings and comments left out), and fails on one not named here; a name a list item gives
before its first colon whose site is gone leaves the page in the same commit. An agent's own program
is judged from the compiler's facts at merge (the vars of `seon.program/banned` and an inline definition, found by `seon.merge/mutations`), not by this check. `bin/laws resolution` reads `src/` without `seon.check` and `seon.e2e`, the check drivers, which redefine a Var on purpose in `bin/check hotreload`.

## A name with no var

Jolt compiles a qualified name that holds no var and fails only when its line runs (a var may be named before it exists), where Clojure refuses it at compile. So a misspelling reaches a branch, and on a path no test runs, main: `my.agent/no-var` called `d/empty-db`, which `datahike.api` lacks, so `(source x)` at root threw on every name, until a sweep of the compiler's facts found it. Two things now say it. An eval's answer warns (``:seon.repl/finding`` `:unknown-var`, from `seon.program/unknown-refs` over what the compiler recorded), while the agent can act, and `seon.main-test/every-name-a-form-uses-is-a-var` holds the program to it. A call with no arguments is not yet seen: `(ns/x)` compiles as a class's static, so its name isn't recorded (task `seon.program/a-call-with-no-arguments-to-no-var-is-told`).

## Sanctioned

The sites that resolve a name held in data to a var by design, each with its reason.

- `seon.contract/wrap!`: arming a contract is Malli's instrument model, the Var's root replaced by its checked wrapper: once at definition (`seon.contract/arm!`), and again over the raw root it kept when a schema the contract reaches is registered after it was armed (`seon.contract/rearm!`, docs/source.md#schemas-at-the-repl), the definition unchanged.
- `seon.system/step-fn`: `resources/seon/system.edn` names each proc's step-fn Var; the declaration is the set of these resolves.
- `seon.render/model`: a schema's declared model render (`:seon.render/model`) is a symbol in data, resolved when a request sends the part or the printer prints the value, as `render-of` is for a render.
- `seon.render/render-of`: a schema's declared render is a symbol in data, resolved when the value is shown.
- `seon.contract/function-var?`: a type's positional factory (`->T`) is known by its type `T` beside it in its namespace, looked up by name.
- `seon.render/declared-var`: the same, as its var: a layout drawn by the walk is a component, whose var's contract says what it reads.
- `seon.repl/undo-definition!`: a var an agent's definition just changed, put back as it was when recording that definition threw with it in place: the agent's own eval undone, in the process that ran it.
- `seon.cost/run`: a cost estimate names its function and its input as symbols in data.
- `seon.cost/bound`, `seon.cost/cases`: a generated estimate names its function (whose contract carries its bound) and its generator as symbols in data.
- `seon.proofs/mg-run`: a generative check's key names its function as a symbol in data ("mg/seon.merge/f"), resolved to its Var to run.
- `seon.agent/test-plan`, `seon.agent/judges?`, `seon.proofs/run-tests!`: a task's proofs and the tests a plan selects are symbols in data (reach digests' keys), resolved to their Vars to run, and to read whether each is a deftest of a namespace that merges (`judges?`, which `test-plan` and `my.agent/tests-reaching` share).
- `seon.program/account`, `seon.program/macro-uses`, `seon.ctx/callees`, `seon.source/head-var`, `seon.source/analyze-form`, `seon.repl/ns-form?`, `seon.program/definition-form?`: the compiler's facts, what a form's head or a method's multi names in its namespace; read, never defined.
- `seon.model/scripted`: the scripted provider's `:seon.config.ai/script` names its reply function as data (a test fixture in `test/`, loaded by name; one not found is `:seon.model/no-script`).
- `seon.agent/start-ns`: the task's `:seon.task/ns`, a symbol in its data, names the namespace an agent's REPL starts in, made (`create-ns`, clojure.core referred) when the program has none, as an ns form would make it.
- `seon.repl/defines-program?`: a form's head is resolved in its namespace (`ns-resolve`), or among the REPL's names, to know a definition of the program, which resolves as written: a lookup, no definition made.
- `seon.agent/repl-name`: a symbol nothing in its namespace resolves is looked up among the REPL's names (their namespaces' publics) and named qualified for Jolt's analyzer (`jolt.host/*resolve-unresolved*`, bound by `seon.repl/eval-string` for every form but a definition of the program): a lookup, nothing referred or defined in any namespace.
- `seon.repl/shadowed`: the clojure.repl helpers an agent's namespace holds a var of its own for are looked up by name (`ns-resolve`) to name that var in the move's `:helper-shadowed` finding: a lookup, no definition made.
- `seon.repl/unmapped`: a form's `ns-unmap` calls are known by resolving their head in the namespace the form ran in (`ns-resolve`), and the var gone by the namespace's interns (`ns-interns`): a lookup to record the removal Clojure made, no definition made.
- `seon.main/driver!`: the check driver (`seon.check`, in `test/`, outside the release binary) is loaded from the checkout's source when a check mode runs, so its `-main` is resolved by name then; the program never names it.
- `my.agent/asked`, `my.agent/no-var`, `my.agent/ns-named?`: a name an agent asks its navigation functions about (`callers`, `callees`, `source`) is read as its REPL reads it, in the agent's namespace (an alias resolved to the var's full name), and one that resolves to nothing is said so (`:my.agent/no-var`, the one answer for a missing name), never answered as no callers. A bare name is a namespace only when one of that name exists here or on the branch and no var of the agent's namespace resolves from it (`ns-named?`): `'seon.task` answers the namespaces requiring it, `'map` stays `clojure.core/map`.
- `my.agent/help`: reads its own namespace's docstring, the map of what is there, by the namespace's name.
- `seon.repl/eval-string`: each response's handle (``result/a3f9c1d2``, named by its answer), the agent REPL's own `*1`, defined by the REPL as it answers.

## Owed its owner's fix

The sites that fill a gap in another namespace, each owed a fix in that namespace's owner.

- `my.agent` (its last form): interns `source`, `source-fn`, `pst`, `doc` and `dir` into Jolt's `clojure.repl`, in every process (root, a test child and an agent alike), a namespace it does not own ([AGENTS.md §5](../AGENTS.md): a gap in another namespace is fixed in its owner). Jolt's `clojure.repl` carrying them deletes the intern.
