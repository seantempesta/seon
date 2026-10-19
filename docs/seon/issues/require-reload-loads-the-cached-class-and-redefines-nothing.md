---
type: issue
status: fixed-pending-default-reload
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

Scope (probed 2026-10-19, lane class-cache, JVM-mode reads on default pid 11460, nothing reloaded):

- Default runs cached classes: `seon/cluster__init.class` resolves from
  `target/dev-program-classes/3d38c658…` with mtime 4102444800000 against the source's 1792409481000, and
  `seon.cluster__init` is initialized.
- Development adoption on default is not affected in practice: its source directory is the sealed archive
  `data/source/a92ba5b9…` (`seon.operator/seal-archive!`), adoption reloads only namespaces whose published bytes
  changed and `seon.cluster/verify-development-sources!` refuses changed bytes, so an archive JVM has nothing to
  reload. A JVM on a mutable checkout gets no class cache (`seon.operator/program-classes` is nil for a source that is
  no commit archive).
- Default IS affected where an absent namespace is loaded with `require :reload` after a `remove-ns`
  (`seon.sci.eval/host-namespace!`, `seon.test`'s test-Var loader): with the class cached, the initialized `__init`
  class runs nothing and the namespace stays absent. Reproduced with a two-namespace fixture: classes first on the
  classpath, `(remove-ns 'xa.b) (require 'xa.b :reload)` leaves `(find-ns 'xa.b)` nil; from source it is present.
- `seon.cluster.program-classes-test/a-namespace-loaded-from-its-classes-keeps-protocol-and-record-identity-across-reload`
  asserts this defect as the intended behavior ("the reload loads the same classes again"): the record class is
  identical before and after because the namespace was never re-evaluated.

Cause is Clojure's own model, not only the stamp: `clojure.lang.RT/load` prefers `<ns>__init.class` whenever it is
newer than the source, and `loadClassForName` on an initialized class runs nothing, so no AOT-loaded namespace is
reloadable by `require :reload` (Clojure's own jar namespaces behave the same). With honest compile-time mtimes the
class is still newer than the archive's commit-time sources, so removing the 2100 stamp alone fixes nothing; it only
turns later commits sharing the key into misses. Hiding the classes from the post-boot loader (a URLClassLoader bound
as `Compiler/LOADER` only while boot loads) makes reload read the source but breaks it the other way: the reloaded
source cannot resolve a record or protocol class the hidden loader defined (`ClassNotFoundException xa.a.R`).

Measured price of the cache (default's archive and classpath, loading its 124 cached namespaces in a throwaway JVM,
two alternating runs under load): from classes 6,238 and 4,735 ms, from source 7,122 and 6,755 ms, so it saves about
1-2 s per launch.

Fix (root, owner-delegated, 2026-10-19: option 1 of three; the others kept the cache and reloaded source text at
each reload owner, or hid the classes from the post-boot loader): the program class cache is deleted. A launch puts
no compiled class of ours on the classpath (`seon.operator/start-classpath` prepends only the dependency cache's hit)
and boots without `*compile-files*` (`seon.operator/launch-form`), so every `require :reload` reads source, as
Clojure intends for code that is reloaded. The dependency class cache stays. Regression:
`seon.cluster.program-classes-test/a-launched-namespace-reloads-from-its-source`. Default picks it up at its next
restart; until then default keeps loading `target/dev-program-classes/3d38c658…`.
