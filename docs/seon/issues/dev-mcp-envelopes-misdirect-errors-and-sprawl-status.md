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

## Repair evidence — 2026-08-04

Commit `07fd06a51` repairs the non-overlapping bridge half: status now selects
and probes one cluster once, scopes sessions to that cluster, and reports the
deduplicated `cluster-health-flow` face.

Fresh bridge processes against the hot-reloaded scratch cluster returned:

```clojure
{:bytes 1772
 :selected "mcp-envelope-0804"
 :clusters ["mcp-envelope-0804"]
 :sessions []
 :problem-counts
 {:seon.problems/error-signatures 1
  :seon.problems/stale-vars 1}
 :full-problems? false}
```

The cluster-side B5 repair returns the real root location:

```clojure
{:seon.error/kind :seon.dev.mcp/jvm-exception
 :seon.error/message "mcp-frame-probe"
 :seon.dev.mcp/exception-class "clojure.lang.ExceptionInfo"
 :seon.dev.mcp/frame
 ["user$eval13323" "invokeStatic" "NO_SOURCE_FILE" 1]}
```

The B6 repair classifies `(deref nil)` without leaking the host sentence:

```clojure
{:seon.error/kind :seon.dev.mcp/nil-deref
 :seon.error/message "The evaluated form dereferenced nil."
 :seon.dev.mcp/exception-class "java.lang.NullPointerException"
 :seon.dev.mcp/frame
 ["clojure.core$deref_future" "invokeStatic" "core.clj" 2321]}
```

`bin/test seon.cluster.mcp-test seon.dev.mcp-bridge-test` passed with 28
tests and 175 assertions. The changed-test selector ran, but its operator
boundary failed in `seon.dev.fresh-operator-test` because `message-count`
changed across process restart, and its writer boundary failed because the
work launcher lacked the flow configuration facts; both boundaries then hit
the selector timeout. The retained evidence is in
`tmp/test-changed/changed-operator-1785877348331-31afe490-eb1b-4bd9-9160-a6791b539904.log`
and
`tmp/test-changed/changed-writer-1785877648412-2b1d80aa-27dc-4a53-a203-4cec45ef7e9c.log`.

The requested platform-incident probe published the current tree and cleanly
booted `incident-envelope-0804` through `agents` and `web` under
`tmp/dev-envelope-probe`. Commit `89fe1a287` subsequently landed the separate
`ensure-entity!` creation-result work, leaving only the MCP projection hunks in
`src/seon/cluster.clj`. The closing MCP commit owns those remaining hunks and
their regression, closes B5 and B6, and fully closes platform incident A0.

MCP clients must restart to load the repaired bridge; an already-running
client retains its original stdio server definition.

## Regression evidence — 2026-08-04

A fresh MCP client against the newly initialized isolated root
`tmp/repl-dogfood-edgefaces-0804` selected its only cluster, `edgefaces0804`,
but returned a contract violation instead of bounded runtime health:

```clojure
{:seon.error/kind "seon.instrument/contract-violated"
 :seon.error/message
 "seon.problems/problems violated its contract (invalid-output): #:seon.problems{:error-signatures [nil #:seon.error{:fact ...}]}"
 :seon.dev.mcp/exception-class "clojure.lang.ExceptionInfo"}
```

The bridge remained scoped to the selected cluster, but its one runtime value
was unusable. This reopens the issue: acceptance additionally requires a clean
isolated boot whose selected runtime health satisfies the
`seon.problems/problems` output contract when an error-signature entry contains
an absent side of a comparison.

## Envelope economy and elision repair — 2026-08-10

The 2026-08-08 independent verification found a separate response-economy
defect in the same bridge owner: every prepl event repeated the complete caller
form. Four diagnostic events plus the terminal return therefore echoed a
roughly 1,000-character form five times. The 2026-08-10 model-authoring
observer also found capped query lists ending in the internal
`:seon.sci.admit/elided` scalar, with no count, path, offset, or requery
decision.

`script/seon/dev/mcp.clj` now removes the prepl transport form from each event
and retains the exact caller source once as `:seon.dev.mcp/form` on the response.
The event vector keeps the prepl event grain and order. At the bridge's decoded
projection boundary, a capped sequential tail becomes the declared
`:seon.print/elision` value with its parent path, next offset, and an explicit
requery refusal; the internal scalar no longer reaches the JSON consumer as a
face.

Recurring proof:

- `seon.dev.mcp-bridge-test/evaluation-response-reports-the-exact-caller-source-once`
  exercises JVM and door modes on success and failure, proves one source echo,
  and proves every returned event is free of a form copy;
- `seon.dev.mcp-bridge-test/capped-list-tail-is-a-declared-elision-value`
  proves a capped list stays a list and its tail is the complete declared
  refusal-bearing elision shape; and
- `bin/test seon.ai-test seon.ai.tokens-test seon.cluster.prompt-test
  seon.dev.mcp-bridge-test seon.config-application-test` passed 81 tests / 510
  assertions / 0 failures / 0 errors; after extending the same attribution
  assertion through transport failures, `bin/test seon.dev.mcp-bridge-test`
  passed 21 tests / 145 assertions / 0 failures / 0 errors.

The issue remains open only for its independently reopened runtime-health
contract case above. A newly started MCP bridge process is still required for
live client proof; existing clients retain the old script definition.

## N5 disposition — converted 2026-08-12

Commit `4fea58d50` routes the cluster-side JVM exception and nil-deref faces
through `seon.error/diagnostic`, retaining the root frame as the owning
observation. The remaining open runtime-health output-contract case is not a
diagnostic-construction member of N5; it remains here because it still owns
this MCP surface.
