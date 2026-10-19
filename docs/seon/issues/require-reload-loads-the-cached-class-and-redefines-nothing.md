---
type: issue
status: open
severity: defect
tags: [issue, reload, operator, adoption, test]
---

# `require :reload` loads the cached class and redefines nothing on a JVM launched over cached classes

A first launch of a tree compiles into a staging directory that `seon.operator`'s `launch-form` promotes to
`target/dev-program-classes/<digest>` (`program-classes`: the src, script, test, deps and reference-code trees) with
every file's mtime set to 2100, "so they must win Clojure's class-newer-than-source test". The next launch of the same
tree puts that directory first on the classpath. `clojure.lang.RT/load` (clojure `3bc2b3e9`) then loads
`<ns>__init.class` instead of the source, and `loadClassForName` on an already-initialized class re-runs nothing: the
namespace is not re-evaluated and no Var is redefined.

Reproduced 2026-10-19 (lane runtime-rereview, [runtime-rereview](../../research/agent-platform/2026-10-19/runtime-rereview.md)
R8): one tree launched twice; first launch, `(require 'seon.profile :reload)` replaces `#'seon.profile/bucket`'s root;
second launch, the class resolves from the cached directory (mtime 4102444800000) and nothing is replaced.
`seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads` is red exactly on second launches (HEAD
with or without the runtime-f7b arm-gap patch), which explains its listing among 85299376b's accepted reds and the
arm-gap bisection.

Scope: development adoption reloads with `require :reload` (`seon.cluster/load-development-definitions!`), and a
default launched on a checked commit finds its classes cached, so an adoption there would keep the launch code of
every cached namespace while recording the new commit. Not probed on default.

Owner: the operator's class cache (`script/seon/operator.clj`) or the reload owner: a reload must read the source it
names.
