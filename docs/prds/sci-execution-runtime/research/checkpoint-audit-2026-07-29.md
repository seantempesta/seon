---
type: research
status: active
tags: [research, audit]
---

# Frozen-checkpoint adversarial audit, 2026-07-29

## Verdict

The wave is not ready to leave the checkpoint. The principal happy-path
claims are independently true, but a refused terminal program transaction
leaves its receipt running and drives the agent into a durable-error hot loop.
That is one blocker. Two operator/config seams and one stored-derived renderer
fallback are real but lower-ranked.

| Rank | Count |
|---|---:|
| Blocker | 1 |
| Friction | 2 |
| Cleanup | 1 |

Audit start was source checkpoint `9f2fba0ae`; the final inspected HEAD was
`232e7981d`. The intervening commits were documentation-only falsifier
results. No lane report was accepted as evidence, no concurrent scratch
artifact was read, and this audit changed no source or test file.

## Dependency ledger and scope

The source and dependency boundaries read directly were:

- Clojure `1.12.5`, Malli `0.20.0`, and core.async
  `1.10.874-alpha3` from `deps.edn`;
- Datahike checkout `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`,
  especially its writer transaction-function path;
- SCI checkout `8fac6e88f32d53a5fd82ebe80640881e317b84fd`,
  especially the forked-context and interrupt behavior;
- core.async reference checkout
  `dc35f3e0d7bc2eef502e77982f48641f025c8051`;
- http-kit fork `238a85cc555a38892f2f9a7583c9cf5cec0fb201`,
  including `ServerAtta`, `HttpServer`, `AsyncChannel`, `WriteState`, and its
  Java tests; and
- first-party owners `seon.sci.reader`, `seon.sci.eval`,
  `seon.cluster.run`, `seon.cluster.loop`, `seon.fn`, `seon.config`,
  `seon.schema.edn`, `seon.render.value`, `seon.render.web`, and the fresh
  operator.

The diff sweep covered the named landing commits for CRLF spans, integer
coercion, config, compute submission, program rows and acquisition, schema
cycles, the value renderer, failed stop, the http-kit fork, and resource
relocation. The commits were inspected individually because unrelated
research and documentation commits interleave the wave.

## Ranked findings

### B1 — a refused terminal transaction leaves a running receipt and hot-loops

The ownership fence itself is correct. In a live scratch agent,

```clojure
(ns-unmap (quote seon.config) (quote defaults))
```

produced `:seon.cluster.run/program-delete-not-owned`. The function remained
in the database and the SCI context did not apply the deletion. There was no
partial program mutation across the refused transaction.

The receipt did not become terminal. The same agent immediately derived
ordinal zero again, refused `receipt-start-call` with `receipt-exists`,
committed another durable error, and self-woke. For roughly ten seconds, until
the audit disarmed it, the process emitted and committed repeated
`receipt-exists` failures.

The source explains the loop:

- `src/seon/cluster/run.cljc:543-588` refuses the program mutation inside the
  receipt-settle transaction, correctly preventing a partial commit;
- `src/seon/cluster/loop.cljc:913-937` installs a program row only after a
  successful transaction report, correctly preventing a context mutation;
- `src/seon/cluster/loop.cljc:265-298` records the refusal but does not
  terminalize the already-started receipt; and
- work derivation therefore selects the same running receipt again.

The namespace docstring at `src/seon/cluster/loop.cljc:18-23` says a rejected
terminal transaction is followed by a terminal error receipt. That is false
today. The issue is
[[refused-terminal-transaction-leaves-a-running-receipt-hot-loop]].

### F1 — the fresh operator calls start through stale instrumentation

A clean JVM zero-overlay start succeeded. Adding a cluster to the already-live
development JVM did not:

```bash
bin/seon start checkpoint-audit-stale-instrumentation
```

The live wrapper rejected `:seon.config/manifest` under the old
`:seon.boot/overrides` schema, although current `start!` source declares
`:seon.boot/start-request`.

`script/seon/fresh_operator.clj:335-345` calls `start!` before
`instrument-form` can reapply instrumentation from the new instance's config.
This is the start-side recurrence of the stale-wrapper class previously seen
on operator stop. The issue is
[[fresh-operator-start-enters-stale-instrumentation-before-refresh]].

### F2 — default Babashka still cannot load the mixed operator graph

`resources/` is now present in `bb.edn`, and the fresh JVM resource loader is
green. The broader Babashka claim is not:

```bash
bin/seon --help
```

exits 1 because fresh `src/seon/schema.cljc` requires `datahike.api`, which is
absent from `bb.edn`'s dependency closure. The stack then continues through
quarry `seon.content-hash`, `seon.config.resolve`, and the old operator.

This is additional evidence on the existing
[[babashka-default-classpath-exposes-src-old]] issue, not a new root cause.
The script-only fresh `start` and `config` branches avoid this particular
dependency graph; old-facing help/status do not.

### C1 — the universal value renderer caches file-derived effective config

`src/seon/render/value.cljc:44-45` stores `(config/defaults)` in a delay.
`presentation-options` at lines 74-99 and `prepare` later in the namespace
merge that cached effective map into every generic structural render.

The normal database-backed block path supplies caps, so the focused renderer
and web suites do not show a wrong hard maximum. The process-local fallback is
still a second effective-config projection and is live for incomplete generic
units. Runtime consumers are supposed to read the cluster's database value,
not `config/default.edn` once per process. The issue is
[[value-renderer-caches-file-derived-effective-config]].

## Code-graph falsification

### Same-transaction publication

One agent executed six forms under the real per-agent flow:

1. a contracted `defn`;
2. `(def x 42)`;
3. a plain expression;
4. a `seon.schema/register!`;
5. a `deftest`; and
6. a thrown exception.

Queries over transaction ids showed:

| Form | Receipt/program result |
|---|---|
| contracted function | receipt result and `:seon.fn` source both at `536870931` |
| ordinary `def` | receipt only; no program row |
| plain expression | receipt value `43`; no program row |
| schema registration | receipt result and `:seon.schema` form both at `536870937` |
| `deftest` | receipt result and `:seon.test` source both at `536870939` |
| failed eval | terminal error receipt; no program row |

This is the intended one transaction at `receipt-settle-call`
(`src/seon/cluster/run.cljc:590-647`), not two writes observed close together.

### Selective admission

A second agent defined an uncontracted function and called it in the same run.
The definition returned an admitted Var receipt, the next form returned `2`,
and no `:seon.fn` row existed. That agrees with the explicit contract gate at
`src/seon/sci/eval.clj:320-346`: only a function carrying a valid complete
Malli schema becomes a durable row. Schema registration and `deftest` each
did become rows, as the transaction evidence above shows.

### Restart acquisition

In a separate scratch database, agent `restart-a` committed:

```clojure
(defn ^{:malli/schema [:=> [:cat :int] :int]} persisted [x] (inc x))
```

The audit stopped the cluster and JVM, reopened the same database in a new
JVM, and queried the function row before creating a second agent. Agent
`restart-b` then evaluated:

```clojure
(my.agents.restart-a/persisted 41)
```

Its ordinal-zero receipt contained `"42"`. Acquisition used program facts,
not receipt replay, matching `src/seon/sci/eval.clj:450-474`.

## Config falsification

### Fresh JVM and resource classpath

A fresh JVM started a zero-overlay scratch cluster successfully with
`:seon.boot/ready? true`, effective compute queue depth `10`, and populated
schema facts. A direct resource load enumerated the 34
`resources/seon/schema/*.edn` documents and registered 501 keys. The loader's
file and jar branches are one classpath mechanism at
`src/seon/schema/edn.clj:126-170`.

### One registration

In a fresh process, after loading the resource population, the audit performed
one registration:

```clojure
(schema/register!
 :seon.config.checkpoint-audit/scratch-dial
 [:int {:seon.db/index true :seon.config/default 17}])
```

With no other declaration edit, the next scratch boot installed the Datahike
attribute, committed the canonical schema row, and placed value `17` in the
zero-overlay effective config. An explicit override to `8` also followed the
same compiler in the standing test suite.

### Explicit absence

`:seon.config/manifest` validated
`:seon.config.error/escalate-to :seon.config/absent`. Compilation removed the
optional entry from the effective map and row; the marker itself did not
become a stored value. Required-entry absence remains a named refusal in the
full suite.

## http-kit fork falsification

The Seon renderer/web checkpoint passed:

```text
61 tests, 222 assertions, 0 failures, 0 errors
```

The real stalled-consumer regression was then run alone with an outer
`write-state` sampler. It passed two assertions and observed:

```clojure
{:samples 24
 :max-pending 239188
 :bound 524288}
```

The client never read from its socket while 20 complete 256 KiB morphs were
produced. The socket remained registered, and pending bytes stayed below the
two-morph backstop. `src/seon/render/web.clj:492-518` waits on the fork's
per-channel drain-or-close completion before submitting another event.

The fork's targeted Java tests also passed:

```text
OK (2 tests)
```

The broader upstream JUnit suite was not counted green: it ran 24 tests and
failed one unrelated `HttpClientDecoderTest` fixture with a null resource.
That failure does not touch `WriteState`, but it prevents claiming the entire
upstream suite passed.

A repository search over `src/`, `test/`, and `script/` found one production
caller of `http/write-state`, the web writer above, and no
`getDeclaredField`, `setAccessible`, `Reflector`, `toWrites`, or
`pendingWrites` access. The old reflection path has no surviving first-party
caller.

## Known-failure-mode sweep

- **Second mechanisms:** no second program reader, receipt-derived acquisition
  path, schema-registration executor, socket-queue accessor, or config
  compiler was found in the wave. Reader events feed both indexing and eval;
  current program facts feed restart.
- **Hand lists:** the config application ledger is test evidence with an
  equality check against the derived registered-dial set, not a runtime
  classifier. `seon.fn/source-roots` is an explicit build input to the
  ancestor digest, not a provenance/trust rule. Neither was promoted to an
  issue without a failing behavior.
- **Stored-derived state:** the value renderer's delayed effective config is
  the one real occurrence introduced by the wave; C1 owns it.
- **Symptom patches:** the fresh operator's post-start instrumentation refresh
  fails at the exact ordering boundary it claims to cover; F1 owns it.
- **Lying documentation:** the terminal-refusal and recursion-fence claims in
  `seon.cluster.loop` contradict the live refusal; B1 owns both prose and
  behavior.
- **Other named slices:** focused/full tests cover CRLF source spans, JDK
  integer coercion, schema-cycle refusal, compute submission, and failed-stop
  addressability. The diff sweep found no independent defect in those slices.

## Calibration — what is genuinely solid

The living program graph is materially simpler than receipt replay: one reader
produces declaration events, selective admission happens before the terminal
transaction, the transaction function validates and commits the declaration
with the receipt, and restart reads only current facts. Happy-path atomicity,
negative admission, cross-restart acquisition, and the ownership fence all
held under independent live probes.

The config compiler also has a strong center. Registration-derived composites,
zero-overlay completeness, explicit absence, schema population before config
apply, and a no-op converged reconcile all passed. The defects are at launcher
classpath/instrumentation edges, not a second config compiler.

The http-kit fork is narrow and source-grounded. It adds one atomic snapshot
and one drain-or-close future without changing `send!` semantics. The real
stalled socket stayed bounded, the fork tests passed, and Seon has exactly one
production caller.

The universal renderer's structural bounding and AI/HTML twin projections
passed their focused suite. Its issue is the config fallback, not its walk
totality or output grammar.

Finally, the complete independent gate at final source HEAD passed:

```text
Ran 531 tests containing 2183 assertions.
0 failures, 0 errors.
```

That green gate is useful calibration, but B1 demonstrates why it is not the
graduation gate by itself: no recurring test currently exercises a refused
terminal program transaction through the live agent flow and waits for
quiescence.
