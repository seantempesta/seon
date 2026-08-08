---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, boot]
---

# The evaluation context requires `test/` namespaces boot cannot load

## Problem

`bin/seon start` refuses at the `config` layer:

```text
● tools boot: config
✗ The cluster instance failed above the REPL: First-party program namespace
  my.background-test could not be loaded for the evaluation context.
{:seon.fresh-operator/name "tools", :seon.boot/pid 64260,
 :seon.fresh-operator/phase "config",
 :seon.fresh-operator/error-kind :seon.boot/refused,
 :seon.error/kind :seon.boot/refused}
```

No cluster can be booted.

Two correct decisions collide:

- the program graph is indexed from BOTH source roots, `src/` and `test/`
  (`seon.fn/source-roots`), so `my.background-test` is an ordinary
  core-provenanced program row;
- `2db8a4be4` ("The evaluation context binds the program graph, not what
  happened to load") now eagerly `require`s every core-provenanced program
  namespace when building the context, and refuses loudly when one will not
  load.

The boot JVM runs under `-M:dev` (`script/seon/fresh_operator.clj:285`), and
`:dev` declares no `:extra-paths` (`deps.edn:109-113`), so `test/` is not on
its classpath. Every `test/` namespace in the graph is therefore
unloadable at boot, and the first one reached refuses the whole cluster.

Confirmed directly:

```text
$ clojure -M:dev -e "(require 'my.background-test)"
Could not locate my/background_test__init.class, my/background_test.clj
or my/background_test.cljc on classpath.
```

while `test/my/background_test.clj` exists and is perfectly valid — it is
simply not on the boot classpath.

The refusal itself is good and did its job; what is wrong is the premise
that every graph namespace is loadable in every process.

## How it got here

`2db8a4be4` is the fix for
[my-web-is-unreachable-from-agent-code](my-web-is-unreachable-from-agent-code.md),
and its reasoning is right: `my.fs`, `my.shell`, and `my.edit` resolved only
as a side effect of what happened to load, and `my.web` did not, which is
exactly the accident that issue reported. Binding the graph rather than the
accident is the correct direction. It just needs to bind the part of the
graph THIS process can load.

A test-runner JVM has `test/` on its classpath, which is very likely why
this passed where it was developed and fails under `bin/seon start`.

## Expected

The evaluation context binds the program rows the process can actually
serve. The cleanest expression is that the graph already knows which root a
row came from — a `test/`-rooted row is not part of an agent's callable
surface and should never be required into the cluster's context, which
makes the whole class unrepresentable rather than caught.

If test rows are genuinely wanted in the context, then `test/` belongs on
the boot classpath and that is the change; what cannot stand is a graph the
context promises to bind and a classpath that cannot back it.

## Acceptance

- `bin/seon start` boots to every layer with `test/` namespaces present in
  the program graph.
- `my.web/fetch` still resolves in agent code (the original fix's
  acceptance, unchanged).
- One regression builds an evaluation context from a graph containing a
  `test/`-rooted row and asserts the context is built without refusing —
  driven the way boot drives it, since a test-runner JVM cannot see this
  failure at all.

## Evidence

Tool-exercise lane, 2026-08-08 00:1x, isolated operator root
`tmp/tool-exercise-operator`, at `70bcd6bcc`. The same root booted cleanly
to every layer earlier in the evening, before `2db8a4be4` landed.

## Resolution (tool-repairs lane, 2026-08-08, `4eb8c6ab4`)

This note's diagnosis was exactly right, including that the refusal itself was
good and refused a wrong premise. Graph membership and PROCESS membership are
two different facts, and the classpath is the one that answers the second.
`seon.sci.eval/classpath-locatable?` asks the process itself — a computed fact,
not a path convention and not a maintained list, so it stays true when the
source roots, the aliases, or the packaging change. A row this process cannot
serve is nil; a row it CAN serve that still fails to load remains the loud
refusal naming the namespace and the cause.

The note's preferred expression (a `test/`-rooted row is excluded by its own
declared provenance) was not available: no per-row source-root fact is
recorded today, and `:seon.test` rows identify only namespaces that contain a
`deftest`, so `test/seon/test_support.clj` and its kind would still have been
required. Recording a source-root fact at index time is the stronger version
of this fix and is worth its own note if the classpath answer ever proves
insufficient.

Both halves proven, neither traded for the other:

- `bin/seon --root tmp/repairs-check init && bin/seon --root tmp/repairs-check
  start x` boots to every layer, `1/1 clusters alive`;
- in that started cluster's ctx, `my.web/fetch` and `my.web/search` both
  resolve alongside `my.fs`, `my.shell`, `my.edit`, `my.background`, and
  `my.run`, and one real `my.web/fetch` crossed the door with status 200 in
  148 ms.

Recurring regression: `the-context-binds-only-the-graph-this-process-can-serve`
in `test/seon/sci/eval_test.clj`, which asserts the RULE rather than the boot,
because a test-runner JVM has `test/` on its classpath and cannot see this
failure at all — exactly as this note required.
