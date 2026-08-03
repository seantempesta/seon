---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, render]
---

# Give MCP results the value system instead of a second truncation

## Problem

Owner direction, 2026-08-02: "I don't want to build parallel tools. I
want one really great set of tools we keep improving." MCP currently
solves the oversized-result problem with its own crude mechanism while
Seon already owns a principled one for the identical problem.

What MCP does today: a raw string chop —
`(str (subs value 0 limit) "\n… output truncated by MCP bridge")`
(`script/seon/dev/mcp.clj:86`), plus `:seon.dev.mcp/truncated?` flags
and per-field truncation (`:56,107,161-167,618`). The bytes past the
limit are simply gone: unrecoverable, unaddressable, and carrying no
identity.

What Seon already owns for the same problem, as one chain:

- `seon.sci.admit` — bounded projection with explicit `::print/elided`
  markers, depth/length/width caps, and `::capped?` evidence
  (`src/seon/sci/admit.clj:98-143,361-432`). Elision is a recorded
  fact, not a lost suffix.
- `seon.print` — the one closed print grammar; text and hiccup derive
  from one stored fact (ruling #26).
- `seon.render.value/node-id` — "Stable element id for one root
  selector and `get-in` path" (`src/seon/render/value.clj:19-33`),
  with `path-url` building `?path=…&offset=…` drill links (`:36-45`).
  Paged navigation by `get-in` path is the "drill" already in the
  vocabulary table.
  CORRECTION (2026-08-02, orchestrator's own overclaim, caught by the
  MCP PRD lane and re-verified): `node-id` hashes the ADDRESS
  `[agent-id root-address path]`, NOT the value, so it is a stable
  element identity for morph targeting and is NOT a retrievable
  content address. Retrieval comes from the blob tier below, whose
  digest IS the content. A design that returns only a node id hands
  back something nothing can look up; the two are complementary and
  an oversized result needs both.
- `seon.blob` — content-addressed `put!`/`get` with digest and size
  (`src/seon/blob.clj:19,32`), already the overflow tier for oversized
  eval results and reasoning under ruling #25's threshold split.
- `/data` is the existing paged navigation route
  (`src/seon/render/route.clj:22`).

So an oversized value already has: a bounded projection, honest elision
markers, a stable identity, a drill path, and a blob tier. MCP uses
none of it and loses the data instead.

## Acceptance

- MCP evaluation results project through the same admit/print chain the
  agent-facing path uses, with the same elision markers rather than a
  string chop.
- An oversized result yields a stable value reference (the existing
  `node-id` shape) plus enough of the value to be useful, and the
  remainder is RETRIEVABLE — by a follow-up drill call on a `get-in`
  path, and/or from the blob tier — never discarded.
- One threshold authority: the existing
  `:seon.config.eval.result/blob-threshold` family, not a second
  MCP-only token budget with its own constants.
- The status/inventory surface proposed in
  `docs/prds/mcp-surface/README.md` uses the same bounding, so there is
  one answer to "this response is too big" across every tool.
- No mechanism is duplicated: if the chain needs a non-cluster caller
  (MCP has no agent id and may target a degraded JVM), that is fixed in
  the owning namespace, never forked into the bridge.

Blocks the output-bounds row of `docs/prds/mcp-surface/README.md`,
which currently proposes keeping the MCP-only budget.

## 2026-08-02 implementation boundary

The prerequisite honest-health slice landed in `debe583d0` and passed the
focused MCP gate at 21 tests / 152 assertions. Removing the bridge chop is now
blocked at the protected io-prepl owner, not by uncertainty in the value
chain. Clojure `prepl` assigns the raw evaluated object to `*1` before its
output event, and `io-prepl` applies `valf` afterward
(`reference-code/clojure/src/clj/clojure/core/server.clj:239-253,270-281`). A
bridge-generated wrapper that projects the object before stringification would
therefore put the projection, not the raw result, in `*1` and break the
ratified stateful-session contract. The fix must install the shared projector
as the live server's `valf` in protected `src/seon/cluster.clj`; this MCP lane
stopped without editing that owner or removing the existing chop.

The browser half crosses a second unowned seam: `/data` is already present in
`src/seon/render/route.clj`, but digest/entity/schema selection is implemented
by private `data-response` in `src/seon/render/web.clj`. A route-table edit
cannot add blob retrieval. The shared artifact/reference contracts may also
need declarations in the unowned `resources/seon/schema.edn` authority.

## 2026-08-02 artifact fidelity falsifier

The semantic admitted value cannot be the artifact's stored source. Admission
first builds one closed `:seon.print/node`, derives
`:seon.sci.admit/value` from it through the lossy `semantic-value` projection,
and separately prints the node as `:seon.cluster.eval/result-edn`
(`src/seon/sci/admit.clj:385-419,463-468`). Reprinting the semantic value does
not reconstruct the tagged node.

A direct JVM probe used the shipped caps and an input exceeding both the
8,192-entry collection cap and the 262,144-character string cap. The admitted
result was capped; the existing tagged result was 687,341 characters, while
`pr-str` of the semantic value was 302,086 characters, and the strings were
unequal. Reading the tagged node and printing it again under explicit canonical
bindings reproduced all 687,341 characters exactly. The decisive result was:

```clojure
{:capped? true
 :original-count 687341
 :semantic-count 302086
 :semantic-equal? false
 :node-canonical-equal? true}
```

There is a second defect at the same choke point: `admit` currently inherits
ambient print bindings. Under `*print-length* = 2`, `*print-level* = 2`, and
`*print-meta* = true`, a small admitted map produced
`"#:seon.print{:face :seon.print/map, :entries [# #]}"`, which
`clojure.edn/read-string` cannot read. The owning print helper must bind at
least `*print-length* nil`, `*print-level* nil`, `*print-meta* false`,
`*print-readably* true`, `*print-dup* false`, and
`*print-namespace-maps* true`; both admission and artifact reads call that one
helper.

The one-source artifact therefore stores the structured print node, never the
semantic value beside its derived print string. Its exact proposed schema is:

```clojure
:seon.sci.admit/print-node :seon.print/node

:seon.render.value/artifact
[:map {:closed true}
 [:seon.sci.admit/print-node :seon.sci.admit/print-node]
 [:seon.sci.admit/capped? :seon.sci.admit/capped?]
 [:seon.sci.admit/record
  {:optional true}
  :seon.sci.admit/record]]
```

On read, the semantic drill value derives through the existing
`semantic-value` transformation and result EDN derives through the canonical
print helper. No second stored projection is justified.

## Resolution — 2026-08-03

The MCP bridge's output-token, character, event-count, and exception-frame
truncation paths are deleted. The cluster io-prepl now installs the shared
projector as its `valf`, after Clojure has assigned the raw result to `*1`.
Admission exposes one print node; value artifacts store that node plus cap and
optional diagnostic facts, while semantic drill data and result EDN derive
from it under canonical print bindings.

An isolated-root live proof evaluated `(vec (range 2000))`. The returned
window named digest
`482e503f48849b53e9241c98c5d151e3b29cdc6a303eca8b179e091af13ea2f5`,
size 102,984, and `retrievable? true`; `get_value` at offset 7 returned
`[7 8 9 10 11 12 13 14]` with that same source digest. `/data?value=…`
rendered `showing 8–15 of 2000`. A second eval in the same session read raw
`*1` and returned `[100 101 102]`. The storeless regression produces the same
digest with `retrievable? false` and the explicit remainder statement.

Recurring focused proof: `seon.cluster.mcp-test` (1 test / 6 assertions),
`seon.dev.mcp-bridge-test` (18 / 117), `seon.sci.admit-test` plus
`seon.render.value-test` (16 / 57), all green.
