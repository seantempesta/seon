---
type: issue
status: resolved
severity: blocker
tags: [issue, toolkit, runtime]
---

# `my.web` is the one capability namespace agent code cannot resolve

## Problem

`my.web/fetch` and `my.web/search` are in the cluster's program graph —
public, contracted, with declared `:seon.effect/capability` owners and a
`:seon.ns` row — and they are the ONLY agent-facing capability functions the
SCI context cannot resolve. Agent code calling them gets sci's analyzer
error before anything reaches the effect execution boundary:

```text
Unable to resolve symbol: my.web/fetch
```

The web capability is therefore unreachable from agent code, which
contradicts ruling #20 ("an agent may call ANY function in its cluster's
program graph").

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator
root. Driven as one agent form through a real run
(`…/probes/tool-exercise/ctx-resolvability.edn`):

```clojure
(mapv (fn [s] [s (some? (resolve s))])
      '[my.fs/read my.fs/write my.shell/run my.web/fetch my.web/search
        my.background/poll my.background/await my.edit/form my.run/wait])
```

Result — everything true except the two `my.web` entries:

```text
[[my.fs/read true] [my.fs/write true] [my.shell/run true]
 [my.web/fetch false] [my.web/search false]
 [my.background/poll true] [my.background/await true]
 [my.edit/form true] [my.run/wait true]]
```

Three facts rule out the obvious explanations:

- the rows exist and match `my.fs/read`'s shape exactly — `:seon.fn/sym`,
  `:seon.fn/private? false`, a `:seon.fn/spec`, a `:seon.fn/source`, and
  `:seon.effect/capability 'seon.web.jvm/fetch`;
- the `:seon.ns` row for `my.web` exists (checked alongside `my.fs`,
  `my.shell`, `my.edit` — all four present);
- the namespace loads fine on the host: `(requiring-resolve 'my.web/fetch)`
  returns a Var in the same JVM.

So the graph, the declarations, and the host namespace are all fine; only
the context install skips it. `my.background`, `my.run`, and `my.message`
are statically copied into the base ctx in `src/seon/sci/eval.clj:100-230`
while `my.fs`, `my.shell`, and `my.edit` arrive by another route that
`my.web` misses.

## Expected

Every public contracted `my.*` namespace in the cluster's program graph
resolves in agent code, by the SAME mechanism — not by a static list one
namespace can fall off. A namespace present in the graph and absent from the
context is the kind of thing a query should answer, so the install should be
derived from the graph rather than enumerated.

## Acceptance

An agent form that calls `my.web/fetch` against a local HTTP server reaches
the effect execution boundary and produces an effect receipt. A regression asserts that the set
of public contracted `my.*` functions in the program graph and the set
resolvable in a fresh cluster ctx are equal — so a future namespace cannot
fall off silently.

## Note for the fix lane

`docs/prds/sci-execution-runtime/research/probes/tool-exercise/exercises.clj`
already contains `start-server!`, a local HTTP server with `/small`,
`/slow` (45 s, past the default 30 s eval limit) and `/huge` (a 4 MB body),
so the my.web exercises that this defect blocked — deadline crossing and
body-ceiling behaviour — can be run the moment it is fixed.

## Resolution (tool-repairs lane, 2026-08-08, `2db8a4be4` + `4eb8c6ab4`)

Cause confirmed by measurement, not inference: `my.fs`, `my.shell`, and
`my.edit` are in `all-ns` only because resolving the core predicates they
register loads them as a side effect. `my.web` registers none, so before
acquire `(find-ns 'my.web)` is false while its row is in the graph — and the
install intersected core-provenanced rows with `all-ns` and silently skipped
the difference.

Membership is now the graph's rather than the accident's, bounded by what the
PROCESS CAN SERVE: a core-provenanced row this JVM's classpath can locate is
loaded and bound; one it cannot serve is not part of this process's callable
surface; one it CAN serve that still fails to load is a loud refusal naming
the namespace and the cause. (The first landing bound the whole graph and
refused every cluster boot, because the graph is indexed from `test/` too —
see
[the-evaluation-context-requires-test-namespaces-that-boot-cannot-load](archive/the-evaluation-context-requires-test-namespaces-that-boot-cannot-load.md).)

Live proof, cluster `x` booted by `bin/seon start` in isolated operator root
`tmp/repairs-check`. Resolution in the started cluster's ctx:

```text
[[my.fs/read true] [my.fs/write true] [my.shell/run true] [my.web/fetch true]
 [my.web/search true] [my.background/poll true] [my.edit/form true]
 [my.run/wait true]]
```

And one real `my.web/fetch` against this note's own fixture server, driven as
a system run through the full path — settled effect receipt, 148 ms:

```clojure
#:my.web{:url "http://127.0.0.1:17331/small", :status 200, :redirects [],
         :body #:my.web.body{:bytes 36, :digest "b9fbb50a…a849", …}}
```

Recurring regressions in `test/seon/sci/eval_test.clj`:
`every-public-capability-function-in-the-graph-resolves-in-the-ctx` (set
equality between the graph's declared capability functions and the ctx's
resolvable ones) and
`the-context-binds-only-the-graph-this-process-can-serve`.

One defect found while proving it and filed separately: the fetched body came
back as a vector of 36 integers rather than text —
[my-web-fetch-returns-plain-html-as-a-vector-of-integers](my-web-fetch-returns-plain-html-as-a-vector-of-integers.md).
The two open `my.web` questions this note blocked (deadline crossing, body
ceiling) are now runnable and remain unrun.