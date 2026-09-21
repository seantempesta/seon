---
type: issue
status: open
severity: friction
tags: [issue, render, web, wave/live-drive-render]
---

# Replace repeated renderer-unavailable placeholders with evidence

## Problem

The Drive 1 agent page repeatedly paints a generic failure string with no
shape, renderer, path, or diagnostic. The page is both ugly and
non-diagnostic.

## Evidence

The HTML for `/agent/drive-one-agent` contains many blocks whose complete
visible content is verbatim:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

They appear between ordinary cluster, instruction, and toolkit namespace
blocks at paths including
`[:seon.render.walk/neighbours 0 :seon.render.walk/neighbours 0
:seon.render.walk/neighbours 0]` and after multiple toolkit namespaces.
`src/seon/render/web.clj` explicitly substitutes this string when a walked
unit carries a failure without output.

The live Attempt 4 page at `/agent/drive-one-agent-attempt-4` reproduced the
same defect after the paid run settled. Its 36,963-byte HTML contained the
same complete visible placeholder 15 times, including immediately after the
configuration, getting-started instruction, toolkit namespaces, historical
message/run, and current message/run blocks:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

### Fresh evidence — 2026-08-14

Still present, and it is not confined to agent or namespace pages — the ROOT
page carries the most. Counted in the browser at 1280x720 against two live
targets, the shared default having just booted onto HEAD:

| Page | `renderer unavailable` | Total walk units |
|---|---|---|
| `http://127.0.0.1:7994/` (default root, HEAD) | 69 | 138 |
| `http://127.0.0.1:7994/agent/root` | 69 | 138 |
| `http://127.0.0.1:55156/` (drive root) | 67 | 138 |
| `http://127.0.0.1:55156/agent/drive-one-agent-attempt-5` | 17 | 38 |

Half of all rendered blocks on a root page are now this placeholder, and it is
also the FIRST thing painted in the right-hand grid column on both roots. The
visible content is still exactly `renderer unavailable`, with no shape,
renderer, path, or diagnostic. Full walk:
[ui-verification-2026-08-14](../../prds/context-generation/research/ui-verification-2026-08-14.md).

### Fresh installer refusal — 2026-09-21

After the owner reset default (PID 12119), the browser at
`http://127.0.0.1:7994/` painted typed unknowns for
`seon.cluster.agent/render-identity-html` and `seon.render.value/render-html`.
Both named `seon.sci.eval/install-function-from-database!` as the refused
operation and `clojure.lang.ExceptionInfo` as the throwable. This is a
first-use acquisition failure, independent of retained render packages.

One read-only MCP JVM evaluation against default completed in 10 ms with
`:alive true`, no error, and `:windowed false`. It inspected the context at
`[:seon.turn.loop/cluster :seon.sci.eval/ctx]` in the running instance and
pulled both renderer rows from `(seon.db/db (seon.operator/connection
"default"))`. Both stored rows had `:seon.schema.admission/source :core`;
both `seon.sci.kernel/program-function` lookups returned nil. The context's
snapshot contained only `:functions`, `:namespaces`, and
`:seon.flow/commit-fault!`; its function and installed-function counts were
both zero, and acquisition refusals were empty.

`seon.render/invoke-selected` invokes `seon.sci.kernel/invoke` directly.
Its `ensure-function!` reached the raw installer on a lazy cluster context
before ordinary `seon.sci.eval/evaluate` had acquired the program. The raw
installer correctly refused the absent snapshot row despite its presence in
the supplied database.

The repair makes the lazy cluster context's installer call the existing
`seon.sci.eval/acquire!` first, then delegates installation to the existing
kernel owner. The generated base keeps the raw installer, avoiding recursive
acquisition while it builds the program. The canonical fixture regression
`seon.sci.lazy-acquisition-test/named-invocation-acquires-the-lazy-program-on-first-use`
asserts a successful first named invocation and reuse of the same program
snapshot and environment on a second invocation.

Verification remains pending: the bounded diagnosis lane ran no JVM, test,
operator, or adoption command. The owner must run the affected gate and
observe fresh browser output after adoption before claiming this live
subcase resolved. The older placeholder acceptance below remains open.

## Owner

`seon.render.web/unit-html` and the render failure value supplied by the walk.

## Acceptance

An ordinary agent page contains no bare `renderer unavailable`. Every failed
block renders a bounded typed diagnostic naming its path, selected renderer,
and failure, and a regression asserts those facts rather than the placeholder.
