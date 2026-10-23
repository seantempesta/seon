# Resolution

A name resolves at run time, or a Var is defined outside its definition form, only where this page names the function and its reason; `bin/laws resolution` lists every such site in `src/`.

Every definition happens once, where it is defined: code names what it calls through its
namespace's requires, and behaviour varies through the environment or through data
([AGENTS.md §5](../AGENTS.md)). A name is resolved at run time only where
data names it (a declaration, a schema's render, a cost estimate) or where the compiler's
facts are read; a Var's root changes only when its contract is armed.

`bin/laws resolution` lists every function in `src/` that calls `requiring-resolve`,
`ns-resolve`, `resolve` on a quoted symbol, `intern`, `alter-var-root` or `with-redefs`
(strings and comments left out), and fails on one not named here; a name a list item gives
before its first colon whose site is gone leaves the page in the same commit. An agent's own program
is judged from the compiler's facts at merge (`seon.program/banned`), not by this check.

## Sanctioned

- `seon.contract/arm!`: arming a contract is Malli's instrument model, the Var's root replaced once by its checked wrapper at definition.
- `seon.system/step-fn`: `resources/seon/system.edn` names each proc's step-fn Var; the declaration is the set of these resolves.
- `seon.render/render-face`, `seon.render.proc/render-unit`: a schema's declared render (or a unit's) is a symbol in data, resolved when the value is shown.
- `seon.cost/run`: a cost estimate names its function and its input as symbols in data.
- `seon.agent/test-plan`, `seon.proofs/run-tests!`: a task's proofs and the tests a plan selects are symbols in data (reach digests' keys), resolved to their Vars to run.
- `seon.program/account`, `seon.program/macro-uses`, `seon.context/callees`, `seon.source/head-var`, `seon.source/analyze-form`, `seon.repl/ns-form?`: the compiler's facts, what a form's head or a method's multi names in its namespace; read, never defined.
- `seon.main/driver!`: the check driver (`seon.check`, in `test/`, outside the release binary: task `seon.main/drivers-leave-src`) is loaded from the checkout's source when a check mode runs, so its `-main` is resolved by name then; the program never names it.
- `seon.repl/eval-code`: each result's handle (``result/hN``), the agent REPL's own `*1`, defined by the REPL as it answers.

## Owed its owner's fix

- `seon.agent/make-ns!`: interns `source`, `source-fn` and `pst` into Jolt's `clojure.repl`, a namespace it does not own ([AGENTS.md §5](../AGENTS.md): a gap in another namespace is fixed in its owner). Task `seon.context/the-standard-repl-tools` (Jolt's `clojure.repl` carries them) deletes the intern.
