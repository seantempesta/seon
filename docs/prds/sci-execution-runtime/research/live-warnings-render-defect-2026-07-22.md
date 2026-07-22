---
type: research
status: active
tags: [research, agent, database]
---

# Live warnings render defect, 2026-07-22

## Finding

The `[warnings] render failed: Warning acquisition failed. nil` line in turn
`va1uj4ncpg9u` is an unbounded-response defect in the warnings context
acquisition. It is not fallout from the NS renames or the
`seon.ns.source` extraction.

The drive ran while the default writer was intentionally reconstructed with a
64 KiB negotiated frame ceiling. The writer log records
`:seon.config.database.transport/maximum-frame-bytes 65536` at
`04:05:22` (`logs/operator/writer/0c83a7a9-2186-4f58-b4ce-cf7bdf88ade6.log:6`),
and the failing turn opened at `04:06:52`
(`logs/operator/pod/f392ae3c-88e5-4a63-9076-2f1f972ba857.log:20`). The normal
4 MiB writer did not return until `04:07:33`; its log records
`maximum-frame-bytes 4194304`
(`logs/operator/writer/65f05327-6d9d-4d6d-bffb-371ee3d4e0d6.log:2-6`).

At the failure site, `acquire-warnings` sends one `execute-many` request whose
first response contains the entire function corpus, entire installed schema,
entire schema provenance/form corpus, and a grouped count over every registered
attribute (`src/seon/agent/ctx/warnings.cljs:119-142`). `db/execute-many`
returns a top-level error value when the writer response is unsuccessful
(`src/seon/db.cljs:1142-1146`). The acquisition then reads only
`::db/results` from that error (`warnings.cljs:143`) and replaces the real
error with `{:seon.error/message "Warning acquisition failed."
:seon.error/data nil}` (`warnings.cljs:144-147`). The renderer faithfully
prints that discarded `nil` (`warnings.cljs:211-213`).

Under the negotiated ceiling, the writer encodes a response using that exact
ceiling; when the response is too large it replaces it with a correlated
`frame-too-large` failure (`src/seon/db/transport/uds.cljc:896-918`). The CLJS
client resolves that correlated failure to the pending request
(`src/seon/db/transport/uds.cljs:540-549`), after which `db/execute-many`
converts it to the top-level error described above.

## Live reproduction and size evidence

After the operator restored the normal 4 MiB writer, direct MCP evaluation of
`seon.agent.ctx.warnings/warnings-block` for `light-roses-smoke` returned the
clean result `""`. Repeating against `(seon.db/as-of database 536870946)` also
returned `""`. This is the expected negative control: the database facts at the
rendered basis are not the cause; only the negotiated transport ceiling changed.

I then executed the exact first acquisition batch from
`warnings.cljs:126-142` against that frozen database value. All nine members
succeeded under 4 MiB. Their individual `pr-str` sizes were:

```clojure
[970 1054 942 145391 61532 111824 298933 7372 874]

```

The aggregate is 628,892 characters before the enclosing response overhead.
Encoding that exact normalized result with the running client's Transit writer
produced 550,399 UTF-8 bytes, versus the live 65,536-byte limit.
The three largest unbounded members are the function rows
(`warnings.cljs:15-24`), schema forms (`warnings.cljs:33-37`), and attribute
counts (`warnings.cljs:39-43`). The same frozen acquisition fails under the
logged 64 KiB writer and succeeds after the logged 4 MiB reconstruction, while
the transport's oversized-response branch is the only ceiling-dependent path
between those two observations.

## History

This defect predates tonight. `git blame` attributes the unbounded acquisition,
its loss of the top-level error, and the `nil` fallback to commit `2dc9b44a7`
from 2026-07-16 (`warnings.cljs:15-44,119-147`). Tonight's W1.5b commit
`2ab1ce5e` made `maximum-frame-bytes` an honestly enforced negotiated limit and
therefore exposed the latent consumer bug. The q21 commit `d3c7c83a` fixed the
same defect class for boot-mandatory committed-program acquisition by paging
`:seon.schema/key` and `:seon.fn/sym`; it did not change the warnings owner.

## Minimal in-place fix

Fix `seon.agent.ctx.warnings/acquire-warnings` in place; do not raise the frame
limit and do not add another warning path.

1. Replace the unbounded corpus members with complete bounded paging over the
   same frozen database value. Follow the landed q21 mechanism:
   `db/index-page` over identity attributes followed by bounded
   `db/pull-many` batches (`src/seon/runtime/admission.cljs:235-267`), with a
   per-page result-weight below the 64 KiB minimum.
2. Preserve the current ordinary-data contract consumed by `seon.warn`; page or
   chunk the function rows, schema rows/provenance/forms, and grouped attribute
   counts rather than truncating them. Attribute counts can be queried in
   bounded chunks of the identity keys obtained from the schema pages.
3. Keep the small cutoff/current-namespace/runtime queries in bounded batches.
4. Preserve the real top-level read error in the fallback, e.g. use
   `first-result` when `::db/results` is absent. This does not fix acquisition,
   but prevents the misleading terminal `nil` on any future read failure.

The tempting one-line change to split the current `execute-many` call is not a
complete fix: individual corpus members already render at 111-299 KiB in EDN
and remain unbounded as the corpus grows.

## Safety and gates

This is safe to fix now if one lane exclusively owns:

- `src/seon/agent/ctx/warnings.cljs`
- `test/seon/agent/ctx/warnings_test.cljs`
- optionally `src/seon/warn.cljs` and `test/seon/warn_test.cljs` only if the
  ordinary-data contract must change (the preferred fix preserves it).

Focused proof:

- extend `warnings_test.cljs` with multi-page index/pull fixtures, assert every
  request carries the exact invocation database value, and assert the assembled
  `::warn/data` is identical to the current contract;
- add a top-level `execute-many` failure fixture and assert the rendered error
  retains `frame-too-large` data rather than `nil`;
- run
  `bin/test-cljs --test=seon.agent.ctx.warnings-test --test=seon.warn-test`;
- reconstruct an isolated/default checkpoint at the supported 64 KiB minimum
  and render the block or drive one fresh agent turn, proving no
  `[warnings] render failed` line. Coordinate that lifecycle checkpoint at the
  root; this audit performed no lifecycle operation.

The current 4 MiB direct render is useful regression evidence but is not the
graduation gate, because it merely masks the unbounded response again.
