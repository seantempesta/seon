---
type: research
status: complete
tags: [research, mcp, repl]
---

# MCP dogfood — first real agent-consumer pass

## Verdict

All three tools were bound and usable. SCI evaluation, JVM evaluation,
ambient database custody, shared SCI definitions, named JVM sessions, blob
retrieval, and path/offset drilling all worked. The new text face is a major
improvement over the previously archived 304 KB response, but the surrounding
MCP response is still much noisier and more misleading than the REPL value it
contains.

The requested past-end fix is implemented in the shared window owner and
live-proven. Before the fix, offset 9,000 into an admitted collection of length
8,193 returned bare `[]`. After hot-reloading the two edited namespaces, the
same `get_value` call returned the empty window beside the requested offset,
actual admitted length, and a derived beyond-end fact.

I read the active runbook, working edge, relevant current rulings and program
ledger, architecture targets, and all four selected skills before editing. The
current authority is [the SCI runtime runbook](docs/prds/sci-execution-runtime/AGENTS.md),
[the working edge](docs/prds/sci-execution-runtime/plan/unsettled.md),
[the ordered program](docs/prds/sci-execution-runtime/plan/README.md),
[runtime architecture](docs/seon/architecture/architecture.md),
[the toolkit target](docs/seon/architecture/toolkit.md), and
[the observability target](docs/seon/architecture/observability.md).

## Dependency ledger

- Clojure `1.12.5` is selected in [deps.edn](deps.edn); the vendored source
  at [reference-code/clojure](reference-code/clojure) was
  `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` during this pass. Its io-prepl
  semantics are the dependency boundary behind raw `*1` and returned events.
- The MCP bridge is [script/seon/dev/mcp.clj](script/seon/dev/mcp.clj). It
  constructs JVM and door remote forms, retains named io-prepl sessions, and
  calls the cluster-side owners.
- [src/seon/cluster.clj](src/seon/cluster.clj) owns `mcp-project`,
  `mcp-valf`, and the thin `mcp-get-value` adapter.
- [src/seon/render/data.clj](src/seon/render/data.clj) owns pure path
  selection and missing-path refusals.
- [src/seon/render/value.clj](src/seon/render/value.clj) owns the structural
  window used by routed value floors and MCP drilling. This is the truer owner
  of past-end facts.
- Recurring proofs are
  [test/seon/cluster/mcp_test.clj](test/seon/cluster/mcp_test.clj),
  [test/seon/render/value_test.clj](test/seon/render/value_test.clj), and
  [test/seon/render/data_test.clj](test/seon/render/data_test.clj).

## Tool-access verification

The toolset advertised exactly:

- `mcp__seon__eval_clj`, with `mode: "jvm" | "door"`;
- `mcp__seon__runtime_status`; and
- `mcp__seon__get_value`.

The first `runtime_status` call selected the live `default` cluster at PID
`44547`, prepl port `56021`, and web port `7994`. The trivial JVM and door
probes both returned `42`:

```text
JVM  :seon.dev.mcp/value 42
door :seon.dev.mcp/text "42"
```

Part 1 used 15 `eval_clj` calls, seven `get_value` calls, and two
`runtime_status` calls. Part 2 added one reload evaluation and two live proof
drills.

## Findings ranked by agent cost

### 1. Severe — the digest addresses the evaluation envelope, not the value

Door `(vec (range 50000))` returned this useful face and source digest:

```text
"[0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27\n  28 29 30 31 ...]"
:seon.blob/digest "adb10ba93c250287c21b6b2ff0f7196bd97efb1f0ac35cc2947bc0fdf1a85973"
:seon.blob/size 689083
```

The natural `get_value` call with default `path []` did not drill that vector.
It drilled the whole evaluation result and returned a huge nested value whose
critical beginning was:

```text
:seon.dev.mcp/value
{:seon.cluster.eval/ns ["seon.ns/name" "my.agents.mcp-dogfood"],
 :seon.cluster.eval/result-edn
 {:seon.sci.admit/truncated-string
  {:seon.sci.admit/truncated-string
   "#:seon.print{:face :seon.print/vector, :items [#:seon.print{:face :seon.print/number, :value 0} ...",
   :seon.sci.admit/elided true},
  :seon.sci.admit/elided true},
 ...}
:seon.blob/digest "3260649f3b2286972b6f2aff817911cbd5bef13f3dac9559ee4c43e49f71f20a"
:seon.blob/size 689315
```

Merely reading one artifact minted a second artifact larger than the first.
The useful, undocumented path was `[:seon.sci.admit/value]`. This is filed at
[Make an MCP digest address the evaluated value directly](docs/seon/issues/mcp-get-value-addresses-the-evaluation-envelope-not-the-result.md).

### 2. High — every event exposes the synthesized remote wrapper

The result is small; the generated bridge form dominates every response. The
door smoke test returned this exact `form`:

```clojure
(do ((clojure.core/requiring-resolve (quote seon.cluster/project-next-prepl-value!))) (clojure.core/let [instances__393__auto__ (clojure.core/deref (clojure.core/deref (clojure.core/ns-resolve (quote seon.cluster) (clojure.core/symbol "running-instances")))) instance__394__auto__ (clojure.core/get instances__393__auto__ "default") cluster__395__auto__ (:seon.cluster.loop/cluster instance__394__auto__)] (clojure.core/if-not (clojure.core/and instance__394__auto__ (:seon.sci.eval/ctx instance__394__auto__) cluster__395__auto__) {:seon.error/kind :seon.dev.mcp/cluster-degraded, :seon.error/message "Cluster 'default' has a live JVM REPL, but its cluster layer is degraded; door evaluation is unavailable.", :seon.dev.mcp/cluster "default"} ((clojure.core/requiring-resolve (quote seon.sci.eval/evaluate)) {:seon.cluster.run.form/source "(+ 20 22)", :seon.cluster.run.form/ns [:seon.ns/name (quote user)], :seon.sci.eval/ctx (:seon.sci.eval/ctx instance__394__auto__), :seon.sci.admit/caps (:seon.sci.admit/caps cluster__395__auto__), :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster__395__auto__), :seon.config/on-core-error (:seon.config/on-core-error cluster__395__auto__)}))))
```

That is bridge diagnostic data, not the form I evaluated. It is filed at
[Stop returning the generated MCP wrapper as the evaluated form](docs/seon/issues/mcp-eval-responses-expose-the-generated-remote-form.md).

### 3. High — tiny faces are classified as oversized artifacts

Defining the small function printed:

```text
"#'my.agents.mcp-dogfood/dogfood-double"
```

The same response said:

```text
:seon.dev.mcp/windowed? true
:seon.blob/size 4165
:seon.dev.mcp/retrievable? true
```

The error face for `(nth [] 1)` was also only a few lines but became a
4,832-byte retrievable artifact. This is the same envelope-addressing defect
as finding 1: internal evaluation metadata, rather than the agent-visible
result, determines whether the value is oversized.

### 4. Medium — status returns unexplained elision markers

`runtime_status` was fast and correctly exposed both live sessions, but its
most operationally interesting fields contained bare markers:

```text
:seon.oversight/buffers ["seon.sci.admit/elided"]
:seon.dev.mcp/readiness {... :seon.sci.admit/elided true}
```

There is no retained count, total, or identity, so I cannot tell whether one
minor field or the decisive readiness evidence was removed. This is already
tracked at [Give the elision marker its count and identity](docs/seon/issues/elided-marker-carries-no-count-or-identity.md).

The same run also reproduced the existing operator disagreement: `bin/seon
status` reported the roster unreadable because the prepl was unreachable while
MCP evaluated successfully through that prepl and reported observed health.
That is already tracked at [Stop reporting an MCP-proven live prepl as unreachable](docs/seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md).

### 5. Medium — the error face is legible but not very actionable

The exact text face for `(nth [] 1)` was:

```text
#:seon.error{:kind :seon.sci.eval/evaluation-failed, :message "java.lang.IndexOutOfBoundsException",
  :data {:seon.sci.eval/throwable "java.lang.IndexOutOfBoundsException", :seon.sci.admit/record
    #:seon.eval{:fn-entries 0, :host-interop-count 0, :duration-ms 2, :allocated-bytes
      1354976, :outcome :error}}}
```

This is honest and compact, but the exception class appears twice and there is
no source position, operation (`nth`), requested index, or collection length.
The diagnostic record spends more space on allocation than on recovery data.

### 6. Low — deep elision is compact and stock-like, but pathless

The nested-value face was:

```text
{:root {:a {:b {:c #}}}, :sibling [[:x [:y [:z [0 1 2 3 4 5 6 7 8 9 10 11
            12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 ...]]]]]}
```

Judgment: the shape is preserved well, the line break is reasonable, and `#`
matches Clojure's print-level face. As a drillable agent surface it is still
lossy: neither `#` nor `...` says which path/digest resumes it. Finding 4's
existing elision issue owns that shared improvement.

## Things that worked cleanly

### Shared SCI context

Door mode defined `dogfood-double` in `my.agents.mcp-dogfood`, called it back
as `(dogfood-double 21)` to get `42`, then called it from `user` as
`(my.agents.mcp-dogfood/dogfood-double 11)` to get `22`. The definition entered
the shared cluster context immediately.

### Ambient database custody

All three database reads worked with no database value or connection passed:

```text
(seon.db/q ...)      => ["root"]
(seon.db/pull ...)   => #:seon.cluster.agent{:id "root", :namespace #:db{:id 11159}}
(seon.db/entity ...) => {:db/id 11160, :seon.cluster.agent/cluster #:db{:id 11158},
                         :seon.cluster.agent/id "root",
                         :seon.cluster.agent/namespace #:db{:id 11159}}
```

The ref-only `#:db{:id ...}` face is faithful Datahike data. A caller must know
to request a nested pull to learn the referenced namespace; that is database
semantics, not an MCP-only defect.

### Named JVM session

The named session `mcp-dogfood-2026-08-03` evaluated
`{:session/value 40}` and then `(assoc *1 :session/next 42)`, returning exactly:

```text
{:session/value 40, :session/next 42}
```

The second status call listed both `default` and
`mcp-dogfood-2026-08-03` sessions.

### Live JVM inspection

After correcting my first private-Var assumption, JVM mode exposed the live
cluster instance's ordinary keys, including `:seon.boot/cluster-connection`,
`:seon.cluster.loop/cluster`, `:seon.flow/graph`, `:seon.render.web/served`, and
`:seon.sci.eval/ctx`. The initial failed probe itself was useful: it returned a
structured compiler exception rather than losing the failure.

## Past-end fix

### Red falsifier

The first focused run was intentionally red:

```text
Ran 14 tests containing 52 assertions.
5 failures, 0 errors.
```

[test/seon/render/value_test.clj](test/seon/render/value_test.clj) proved the
shared window lacked `:seon.render.value/beyond-end?`.
[test/seon/cluster/mcp_test.clj](test/seon/cluster/mcp_test.clj) proved
`mcp-get-value` discarded the complete shared window envelope.

### Implementation

[src/seon/render/value.clj](src/seon/render/value.clj) now derives the counted
value's `:seon.render.value/total` once and returns
`:seon.render.value/beyond-end?` from the requested offset and that total.
[src/seon/cluster.clj](src/seon/cluster.clj) now returns that shared result
directly instead of selecting only `:seon.render.value/window`.

This changes the normal drill result from a bare collection to the same
structured page routed value floors already use: window, steps, requested
offset, shown count, admitted total, beyond-end fact, and more fact.

### Focused green gate

```text
bin/test seon.cluster.mcp-test seon.render.value-test
Ran 14 tests containing 52 assertions.
0 failures, 0 errors.
```

The automatic `current-src` publication hook was red on concurrent schema
work: `seon.schema/canonical-definition` violated its output contract because
one definition was not a parseable EDN-readable Malli form. This did not block
the source-classpath gate or the requested hot-reload proof. I did not edit the
schema lane's files or attempt to repair its session.

`bin/issues-index --check` was independently red on the pre-existing archived
[MCP door envelope issue](docs/seon/issues/archive/mcp-door-eval-returns-unbounded-value.md),
whose resolved frontmatter has no severity. The checker reported that every
valid note was indexed; I did not edit unrelated archive metadata.

### Live proof

JVM mode loaded the edited Vars with:

```clojure
(do (require 'seon.render.value :reload)
    (require 'seon.cluster :reload)
    :reloaded)
```

The exact pre-fix offset-9,000 call then returned:

```clojure
{:seon.render.value/window [],
 :seon.render.value/steps [],
 :seon.render.value/offset 9000,
 :seon.render.value/shown 0,
 :seon.render.value/total 8193,
 :seon.render.value/beyond-end? true,
 :seon.render.value/more? false}
```

An in-range offset 7 returned `[7 8 9 10 11 12 13 14]`, `total 8193`,
`beyond-end? false`, and `more? true`. This proves the loaded Vars in the
running default JVM; it does not claim that the sovereign default cluster's
database program graph was republished.

## Next improvements I want as an agent living in this REPL

1. Make the digest address the evaluated value at root. I should never need to
   discover `[:seon.sci.admit/value]`, and reading an artifact must not mint a
   second artifact.
2. Replace the generated-wrapper `form` with exact user source in normal
   responses. Keep bridge internals behind an explicit diagnostic view.
3. Give every elision marker retained/total counts plus a drill identity and
   path when the full value survives.
4. Make error faces prioritize recovery evidence: user form/source location,
   operation, and structured exception data before allocation diagnostics.
5. Base `windowed?` and blob placement on the addressed result artifact, not
   invisible evaluation-envelope bulk, so a one-line def or error stays small.
