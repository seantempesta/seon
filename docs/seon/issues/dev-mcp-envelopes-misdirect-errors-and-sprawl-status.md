---
type: issue
status: open
severity: blocker
tags: [issue, tooling, mcp, repl, observability]
---

# Repair development MCP error locations and status scope

## Problem

The development MCP face has three related diagnosis defects:

- JVM evaluation exceptions report the `seon.cluster/mcp-io-prepl` serving
  frame instead of the underlying exception's throw-site frame.
- A nil dereference reaches Clojure's `Future` overload and exposes the host
  NPE sentence about `fut`, without classifying the actual nil-deref mistake
  as a flat error value.
- `runtime_status` treats its selected cluster as metadata only, probes and
  renders every discovered root row, repeats runtime data for duplicate cluster
  observations, includes root-wide sessions, and embeds complete problem facts.

These are rendered faces for tool consumers. They must carry the smallest
structured data that identifies the real failure and selected cluster, not
serving machinery or root-wide diagnostic payloads.

## Before evidence — 2026-08-04

All live probes used the isolated operator root `tmp/mcp-envelope-0804` and its
scratch clusters `mcp-envelope-0804` and `mcp-unrelated-0804`; no default
cluster was read or mutated.

Evaluating this through JVM mode:

```clojure
(throw (ex-info "mcp-frame-probe" {:probe/site :throw-form}))
```

returned:

```clojure
{:seon.dev.mcp/exception-class "clojure.lang.ExceptionInfo"
 :seon.dev.mcp/exception-message "mcp-frame-probe"
 :seon.dev.mcp/frame
 ["seon.cluster$mcp_io_prepl" "invokeStatic" "cluster.clj" 336]}
```

The root-cause `Throwable->map` entry already carries its own `:at`; current
`exception-summary` discards that location and instead searches the root trace
for the first namespace present in the database program graph.

The raw sentence
`Cannot invoke "java.util.concurrent.Future.get()" because "fut" is null`
is emitted by `clojure.core/deref-future` when its argument is nil. The same
wording appears in the resolved historical database-ID allocator issue, but no
current evidence connects this probe to that retired owner. The curation probe
reached it while reading a Konserve commit record; synchronous `k/get` itself
returns a value directly. The nil was supplied to `deref`, not returned as a
Future by Konserve.

Selecting only `mcp-envelope-0804` from the isolated two-cluster root returned:

```clojure
{:bytes 3712
 :selected "mcp-envelope-0804"
 :clusters ["mcp-envelope-0804" "mcp-unrelated-0804"]}
```

The research probe with a large unrelated problem fact produced roughly
19,000 tokens because each row embeds `readiness`, including complete
`:seon.problems/problems` rows.

## Owner

- `src/seon/cluster.clj`: cluster-side MCP exception and runtime observation
  projections.
- `script/seon/dev/mcp.clj`: root discovery selection, deduplication, session
  scope, and tool result assembly.
- `test/seon/cluster/mcp_test.clj` and
  `test/seon/dev/mcp_bridge_test.clj`: one recurring regression per class.

`src/seon/sci/eval.clj` and `src/seon/render/transcript.clj` are explicitly out
of scope.

## Acceptance

- Two exceptions with one common serving trace and distinct underlying
  `:via/:at` locations report their distinct root-cause frames.
- A nil dereference returns a flat `:seon.error` value naming the nil-deref
  mistake; the `Future.get` host sentence is absent.
- Selected status contains its cluster exactly once, probes it once, excludes
  unrelated cluster/session data, and represents problems only as derived
  family counts.
- Focused tests and the changed-test selector pass.
- A restarted MCP client observes the new envelopes; already-running clients
  are known to retain the old bridge definition and are not valid proof.
