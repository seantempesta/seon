# Resolution

A name resolves at run time, or a Var is defined outside its definition form, only where this page names the function and its reason; `bin/laws resolution` lists every such site in `src/`.

Every definition happens once, where it is defined: code names what it calls through its namespace's requires, and behaviour varies through the environment or through data. A name is resolved at run time only where data names it (a declaration, a schema's render, a cost estimate) or where the compiler's facts are read; a Var's root changes only when its contract is armed.

`intern`, `alter-var-root` and `with-redefs` are code smells, not banned. A legitimate use builds vars in a namespace you own where no definition form exists (a REPL harness, a test environment), and its docstring says why. A gap in another namespace is filled in that namespace's owner.

`bin/laws resolution` lists every function in `src/` (but the check drivers `seon.check` and `seon.e2e`) that calls `requiring-resolve`, `ns-resolve`, `resolve` on a quoted symbol, `intern`, `alter-var-root` or `with-redefs` outside strings and comments, and fails on one this page does not name in backticks; a site that is gone leaves the page in the same commit. An agent's own program is judged from the compiler's facts at merge (`seon.program/banned`, found by `seon.merge/mutations`), not by this check.

## A name with no var

Jolt compiles a qualified name that holds no var and fails only when its line runs, where Clojure refuses it at compile, so a misspelling can reach main on a path no test runs. An eval's answer warns (``:seon.repl/finding`` `:unknown-var`, from `seon.program/unknown-refs`), and `seon.main-test/every-name-a-form-uses-is-a-var` holds the program to it. A call with no arguments, `(ns/x)`, compiles as a class's static and is not yet seen (task `seon.program/a-call-with-no-arguments-to-no-var-is-told`).

## Sanctioned

The sites that resolve a name held in data to a var by design.

- `seon.contract/wrap!`: arming a contract replaces the Var's root with its checked wrapper, Malli's instrument model: at definition (`seon.contract/arm!`), and again when a schema it reaches is registered later (`seon.contract/rearm!`, docs/source.md#schemas-at-the-repl).
- `seon.graph/step-fn`: `resources/seon/system.edn` names each proc's step-fn Var.
- `seon.system/answer-down`: the same step-fn Vars, by the symbols the built system's declarations hold, for their describe's `:in-params`.
- `seon.render/model`, `seon.render/render-of`: a schema's declared render is a symbol in data, resolved when the value is sent or shown.
- `seon.contract/function-var?`: a type's positional factory (`->T`) is known by its type `T` beside it, looked up by name.
- `seon.render/declared-var`: the same, as its var: a component's var's contract says what it reads.
- `seon.ctx.wire/page-message`: a call Seon writes for the model (`my.render/ctx`, `my.render/page`, `my.render/diff`) names its function as a symbol ([the render is an eval](render.md#the-render-is-an-eval)); `my.render` requires `seon.ctx.wire`, so it is resolved by name.
- `seon.source/accounts`: a form the store holds whole is not analyzed again, so the vars it defines or declares are interned in its namespace as its analysis would have, for the forms after it ([main's program follows the tree](source.md#a-boot-catches-up-with-its-checkout)).
- `seon.repl/undo-definition!`: a var an agent's definition just changed is put back when recording that definition threw: the agent's own eval undone, in its own process.
- `seon.db/with-specs`: the entity index keeps each schema's compiled pattern (`seon.render/pattern`, made at its first use, [the pattern](ctx.md#the-pattern)); `seon.render` requires `seon.db`, so it is resolved by name.
- `seon.cost/run`, `seon.cost/bound`, `seon.cost/cases`: a cost estimate names its function, input and generator as symbols in data.
- `seon.proofs/mg-run`: a generative check's key names its function ("mg/seon.merge/f").
- `seon.agent/test-plan`, `seon.agent/judges?`, `seon.proofs/run-tests!`: a task's proofs and a plan's tests are symbols in data, resolved to run them and to read whether each is a deftest of a merging namespace (`judges?`, shared with `my.agent/tests-reaching`).
- `seon.program/account`, `seon.program/macro-uses`, `seon.ctx/callees`, `seon.source/head-var`, `seon.source/analyze-form`, `seon.repl/ns-form?`, `seon.program/definition-form?`: the compiler's facts, what a form's head names in its namespace; read, never defined.
- `seon.model/scripted`: `:seon.config.ai/script` names the reply function (a fixture in `test/`; missing is `:seon.model/no-script`).
- `seon.agent/start-ns`: the task's `:seon.task/ns` names the namespace an agent's REPL starts in, made with `create-ns` when the program has none.
- `seon.repl/defines-program?`: a form's head is resolved in its namespace, or among the REPL's names, to know a definition of the program: a lookup.
- `seon.agent/repl-name`: a symbol nothing in its namespace resolves is looked up among the REPL's names and named qualified for Jolt's analyzer (``jolt.host/*resolve-unresolved*``, bound by `seon.repl/eval-string`): a lookup.
- `seon.repl/shadowed`: clojure.repl helpers an agent's namespace shadows are looked up to name that var in the `:helper-shadowed` finding.
- `seon.repl/unmapped`: a form's `ns-unmap` calls are known by resolving their head, the var gone by `ns-interns`: a lookup to record the removal.
- `seon.main/driver!`: the check driver (`seon.check`, in `test/`, outside the release binary) is loaded from source when a check mode runs, its `-main` resolved by name.
- `my.agent/asked`, `my.agent/no-var`, `my.agent/ns-named?`: a name an agent asks `callers`, `callees` or `source` about is read as its REPL reads it, and one that resolves to nothing answers `:my.agent/no-var`, never "no callers". A bare name is a namespace only when one of that name exists and no var of the agent's namespace resolves from it: `'seon.task` is the namespace, `'map` stays `clojure.core/map`.
- `my.agent/help`: reads its own namespace's docstring by name.
- `seon.repl/eval-string`: each response's handle (``result/a3f9c1d2``), the agent REPL's own `*1`, defined as it answers.

## Owed its owner's fix

The sites that fill a gap in another namespace, each owed a fix in that namespace's owner.

- `my.agent` (its last form): interns `source`, `source-fn`, `pst`, `doc` and `dir` into Jolt's `clojure.repl` in every process. Jolt's `clojure.repl` carrying them deletes the intern.
