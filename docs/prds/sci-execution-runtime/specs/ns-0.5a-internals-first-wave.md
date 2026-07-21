---
type: prd
status: active
tags: [prd, architecture]
---

# NS-0.5a — internals first wave (the dependency-free slice)

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. As you work, answer two
questions and report them in your summary: (a) now that you've read the
source, is there a better seam than the one this spec names? (b) what do
the existing owners call each thing — use their exact terms, never a
new synonym. **Stopping early to report a concern or a better seam is
FREE** — the session resumes with full context, and seam corrections are
exactly what we want. If anything in this spec contradicts what you find
in the source, stop and report rather than improvising.

## Goal

Execute the three items of the accepted NS-0.5 review that are
dependency-free right now (design authority — read its §1:
`docs/prds/sci-execution-runtime/research/ns05-ns5-design-review-2026-07-21.md`).
The standing law: only a namespace's parent may require its `.internal`.
These three close real violations without exposing genuine internals.

1. **Rename `seon.agent.internal` → `seon.agent.authorization`**
   (whole file — it owns one management-authorization rule, its pull
   selector, and authorization error values; review cites
   `src/seon/agent/internal.cljs:8-55`). Consumers to rewire:
   `src/seon/agent.cljs:865-937` and
   `src/seon/agent/lifecycle.cljs:13,176-181`, plus its test file(s)
   (rg for them). `git mv`, update requires/aliases/literal keys; the
   `::`-registered keys follow the ns.
2. **Delete the false `db.id → db.internal` edge**: `src/seon/db/id.cljc:22`
   requires the internal only for `assert-invocation-shape!` at
   `:1358`, but validation immediately preceding (`:1287-1293`) already
   guarantees the shape. Consolidate the allocator's validation locally
   (keep an equivalent honest assertion in `db.id` if the shape check
   adds real protection — read both sites and decide; report your
   choice) and remove the require.
3. **Extract the shared storage-normalization seam**: `edn-encoded-attr?`
   and `encode-edn-slot-values` (`src/seon/db/internal.cljs:235-264`)
   are consumed by BOTH `seon.db` (`src/seon/db.cljs:825`) and
   `seon.client` (`src/seon/client.cljs:1065-1087`) — a real shared
   seam wearing an internal label. Move them to a narrow new owner
   **`seon.db.storage`** (`.cljs`; promote to `.cljc` only if a JVM
   consumer already exists — check, don't speculate). `db.internal`
   requires the new owner (or its callers rewire — follow what reads
   most honestly); `client` requires the new owner and drops
   `db.internal` (`src/seon/client.cljs:72`). Do NOT move anything
   else out of `db.internal` — the review explicitly keeps the rest
   internal.

NOT in this unit (later waves, do not touch): `seon.schema.form`
extraction, `seon.eval.internal` → receipt promotion, anything touching
`seon.repl.internal`, `my.plan.internal`, or `repl.autocomplete` (owned
by a separate lane's checkout).

## Owned paths (touch nothing else)

- `src/seon/agent/internal.cljs` → `src/seon/agent/authorization.cljs`
- `src/seon/agent.cljs`, `src/seon/agent/lifecycle.cljs` — require/
  alias/key lines only
- `src/seon/db/id.cljc`, `src/seon/db/internal.cljs`,
  `src/seon/db.cljs`, `src/seon/client.cljs` (require lines + the one
  call-site swap), new `src/seon/db/storage.cljs`
- test files that require the renamed/moved names (enumerate in your
  summary)

Protected: everything else. A read-only research lane is probing the
live cluster — you may run `bin/test-cljs` but do NOT run
`bin/seon up/down/restart/reset` (the cluster must stay up) and do not
commit.

## Gates (run them; report honest results)

- Focused agent/db/client selectors while iterating; full
  `bin/test-cljs` once at the end.
- rg proof: zero `seon.agent.internal` tokens anywhere in `src/ test/`;
  `seon.db.internal` required ONLY by `seon.db` (its parent) — show
  the remaining consumer list.
- Live-proof stays with the orchestrator; your gate is tests + rg.
