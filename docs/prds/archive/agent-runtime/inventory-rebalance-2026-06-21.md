---
type: prd
status: active
tags: [prd, agent]
---

# Inventory rebalance — show downstream code, drop seon schema noise

Surfaced 2026-06-21 by a downstream consumer (refer to them as `acme` only —
NEVER record their real name) integrating seon at `a241409`. One coherent
change to the agent's `<namespace>` inventory plus two adjacent asks.

## Problem (measured downstream)

A representative downstream turn is ~38K prompt tokens. The seon namespace/fn
inventory is ~17K of that (45%), and **~445 lines of it are raw
`seon.schema/register!` malli declarations** vs ~14 actual `defn` bodies (already
elided). Meanwhile the downstream's OWN product nses (`acme.*`) are **absent
entirely** — the model can't see them and guesses `seon.<x>` for downstream fns
(observed: called `seon.persona/set-location!`, which throws, instead of
`acme.persona/set-location!`). So nearly half the budget is seon type-noise and
the agent's actual working domain is invisible.

## The rebalance (same-or-fewer tokens, spent on the right code)

### A. Selection — remove the `seon.`/`my.` prefix allow-list (research §1 + §3 + §5)

`seon.ctx/included-ns?` currently gates the `<namespace>` tags on a
`:seon.ctx/included-prefixes` allow-list (default `["seon." "my."]`). This is an
opt-IN whitelist that wrongly excludes legitimately-indexed downstream code.
Replace with the structural-only rule: render EVERY indexed `:seon.ns` row EXCEPT
`*.internal` (`hidden-ns-name?`) and `*-test` (`test-ns-name?`). The library gate
stays where it structurally lives — the INDEX side (`seon.indexing/first-party-file?`
only indexes repo + `SEON_EXTRA_SRC` code; libraries never get a `:seon.ns` row).

Delete all the prefix/config machinery (no legacy): the `:seon.ctx/config-id` +
`:seon.ctx/included-prefixes` schema regs, `config-ref`, `default-included-prefixes`,
`included-prefixes`, `prefix-included?`, the `ensure-ctx-config!` lazy seed + its
atom + the `assemble-context` call, and the 2-arity of `included-ns?`. Update the
ns docstring + `namespaces-section` to the structural rule.

**Index-leak guard (load-bearing — the allow-list was the ONLY thing hiding it):**
`seon.eval/bind-result-var!` registers each eval's value under a reserved `result`
ns with `{:seon.eval/result-var? true}`; `analyzer-info/defs-since` filters
`:declared` but NOT `:seon.eval/result-var?`, so these synthetic defs get teed as
`:seon.fn` rows + a sourceless `{:seon.ns/name :result}` row (confirmed live).
**Primary fix:** add `(not (true? (:seon.eval/result-var? var-map)))` to
`defs-since`'s `:when`. Sweep the existing stray `:result` row (one-off retract or
`bin/seon cluster reset default`). Belt-and-suspenders: add `'result` to
`transient-ns-syms`.

Keep `*-test` excluded from the DEFAULT context (it's a structural convention like
`.internal`, applies to seon/`my.`/downstream alike) but KEEP indexing test nses
(they're first-class corpus — `index-tests`, `core-ns-set`, per-fn `:test` usage
examples all depend on them). Full tests reachable on demand via `render-namespace`.

Tests: rewrite `ctx_test/selection-rules` to the 1-arity + add a no-prefix
downstream case (`acme.widget`); delete `included-prefix-extensibility`; add
`renders-all-indexed-code-internal-excluded`; add an index-side pin that
`defs-since` skips a `:seon.eval/result-var?` def. Update
`teachings_test/ns-doc-examples` to the 1-arity. (Full edit map: research agent
output in this session's transcript / handoff.)

### B. Render depth — third-party in FULL, seon framework compact (user decision)

The write side already stores the real full file text for `my.*` + `SEON_EXTRA_SRC`
(`acme.*`) rows (`ns-row` via `full-source-ns?` OR `extra-core-ns-strs`); seon
framework rows store a `(ns x)` stub. `namespaces-section` currently re-elides that
stored full source back to compact for every non-current ns. Change the render
`cond` so a **non-stub row (`(not (ns-stub? ns-str src))`) renders its FULL stored
source**, not just the current ns. Net: seon framework (stubs) stays compact;
`my.*` + downstream `acme.*` product code renders whole. Purely DB-derived (the
non-stub check), no runtime atom, reactive-context-aligned.

### C. #38 — collapse `register!` dumps in the compact path

In `compact-ns-source`, stop emitting each schema's full `register!` source. Replace
with a **one-line attr summary** for the ns (the registered schema KEYS), and keep
the elided `defn`s (signature + first-line docstring + `:malli/schema` attr-map —
which still names the schema keys a fn uses). Full schema shapes remain queryable on
demand. This is COMPACT-PATH ONLY — the full third-party path (B) shows verbatim file
text including their `register!` calls. Verify the token delta (~10–15K/turn saving).

> B + C both rewrite `compact-ns-source` / the render branch → one agent, on top of A.

## Adjacent asks

### #37 — `bin/seon prep` must leave a WARM classpath (demo-risk)

`cmd_prep` is fingerprint-gated on git-deps only, but the Clojure CLI's cpcache
staleness check is mtime-based — a `deps.edn` edit that touches no git-dep (a maven
dep, `:paths`, an alias, a seon SHA bump touching only source) leaves the
git-fingerprint unchanged → `prep` reports no-op → cpcache is stale → a downstream
`clojure -M:writer` can die in the pre-`exec` `make-classpath2` window with an EMPTY
log. Fix: GUARANTEE a warm `:writer` + `:cljs` classpath on return — preferred (a)
run `clojure -P -M:writer && clojure -P -M:cljs` unconditionally (fast no-op when
warm, loud+synchronous refresh when stale), OR (b) add cpcache-vs-`deps.edn` mtime to
the staleness check. Bonus: `seon.server.wire/-main` prints `[writer] booting pid=…`
as its VERY FIRST statement so a future pre-`-main` death leaves a breadcrumb.

### #39 — let static sections join the cache prefix

The downstream's static KB section (~7K tokens, byte-identical every turn) renders
just below the ctx-cache boundary → re-bills every turn. ~68% of the prompt is
already a byte-stable prefix. Let a section declare low volatility (e.g.
`:seon.ctx/cacheable? true`) honored by the composer's ordering so a never-changing
section isn't stranded in the volatile tail. (Follow-up, after A/B/C.)

## Verification

Live DeepSeek turn (standing permission): after A, confirm `<namespace name="acme.…">`
appears, no `:result` tag, no `*.internal`, no `*-test` flood, lib row count still 0.
After B/C: downstream code renders FULL, seon framework compact with NO `register!`
dumps, total inventory tokens ≤ prior. End each unit with `bin/test-cljs`.
