---
type: prd
status: active
tags: [prd, architecture, agent]
---

# Deletions and wiring PRD

## Owner rulings (2026-07-20)

Delete `dev/storage-shootout.js` and the `reference-code/integrant`
submodule. For the two orphan namespaces: wire up what is useful, quality
permitting.

## Quality assessment of the orphans

**`src/seon/agent/ctx/usage.cljs` (70 lines) — useful, retain and wire it in.**
Registered schemas, errors-as-values (nil on absent/garbage EDN), documented
per-provider semantics (DeepSeek `prompt_tokens` includes cached; Anthropic
`input_tokens` excludes, cache fields add) — but the normalization is NOT
yet correct for the DEFAULT provider: Muse (`muse-spark-1.1`, the
openai-compat Meta gateway) reports cache hits under
`prompt_tokens_details.cached_tokens`, not DeepSeek's
`prompt_cache_hit_tokens` (verified live,
`docs/prds/agent-ctx/research/meta-model-api-muse-spark-2026-07-10.md:176-179`),
and `extract` (usage.cljs:52) reads only `(:prompt_cache_hit_tokens m)` —
every warm-cache Muse turn would render a plausible-looking 0% cache rate,
exactly the failure the wiring must forbid. Fixes REQUIRED in the same
wiring pass, before any consumer lands:

- widen the `:openai-compat` branch to `::cached (or
  (:prompt_cache_hit_tokens m) (get-in m [:prompt_tokens_details
  :cached_tokens]) ...)` — safe ordering, since the 2026-07-10 live
  verification shows DeepSeek returns BOTH fields in agreement and Meta
  returns only the nested one;
- when NEITHER cache field is present, make `::cached` an optional key and
  OMIT it (absent = no key, per the repo schema rule) so the UI renders
  "no cache data" rather than a plausible 0% — this correctly covers the
  client-estimated `:stream`-abort usage maps (`{:prompt_tokens
  :completion_tokens :total_tokens}` from `estimated-usage`), which
  genuinely carry no cache information;
- replace the banned `[:maybe ::usage-edn]` / `[:maybe ::usage]` fn
  schemas (model absence as absent key / omitted projection);
- counts must be non-negative integers; malformed or unknown provider
  shapes must omit the normalized projection and surface a debug
  diagnostic rather than silently becoming plausible all-zero usage;
- add `test/seon/agent/ctx/usage_test.cljs` with one fixture per
  live-verified shape: DeepSeek (both fields, must agree), Meta gateway
  (nested only), Anthropic (cache_read/cache_creation), estimated (no
  cache fields → `::cached` omitted), and garbage/unknown (→ nil +
  diagnostic).

Wiring ruling: a derived line in the existing debug turn projection, with the
agent-page transcript showing compact actual total/cached/output values.
The one render fn in the owning ctx block pulls
`:seon.agent.turn/usage-estimated?` ALONGSIDE `:seon.agent.turn/llm-usage`
and branches — the persisted usage on a `:stream` turn is a DeepSeek-shaped
CLIENT-SIDE estimate (`estimated-usage`,
`src/seon/ai/openai_compat.cljs:418-431`, flagged by
`seon.agent.turn` at turn.cljs:90-95, persisted at 902-909), and `extract`
cannot distinguish it from provider truth:

- flag absent → render the compact actual total/cached/output line;
- flag true → omit the actuals line entirely (the existing estimate
  display already covers the turn; no fake actual-vs-estimate comparison)
  or, if the owner wants the number visible, render it with an explicit
  "est. (stream abort)" marker and never in the actual-vs-estimate
  framing;
- `llm-usage` absent → omit.

`extract` itself stays pure over the EDN string — the discriminator is a
sibling datom, not part of the usage map. "Provider ground truth /
provider-reported actuals" applies ONLY when `usage-estimated?` is absent.
No new block family unless the owner wants usage as its own block.

**`src/seon/ui/components.cljc` (278 lines) — delete, do not wire.**
Readable but built for the removed "future adapter" era: it references the
superseded `docs/prds/namespace-ui/design-system.md`, duplicates styling
the live `seon.ui.*`/render fns evolved independently (its `card`,
`page-header`, `status-styles` have zero consumers while live equivalents
exist), and its `type-colors` keys ("LAUNCH"/"MESSAGE"/...) belong to a log
view that no longer exists. Wiring it now would mean refactoring healthy
live UI onto an unproven parallel component layer — the exact second-
renderer smell. Git preserves it if a design-system pass ever wants the
palette tables.

## Other deletions in scope

| Item | Verdict | Evidence |
|---|---|---|
| `dev/storage-shootout.js` | delete (ruled) | scratch benchmark |
| `reference-code/integrant` submodule | delete (ruled); also drop its `.gitmodules` entry | zero consumers after Integrant era removal |
| `test/seon/agent/ctx/canvas_test.cljs` direct `datahike.api` use (B9) | rewrite through `seon.db` / test fixtures | boundary violation |
| `seon/dev/docstring.clj:193` duplicated predicates | extract into a NEW pure leaf `.cljc` — `src/seon/agent/ctx/ns_name.cljc` (`seon.agent.ctx.ns-name`), only require `clojure.string`; see below | documented deliberate dup; no "owning `.cljc`" exists today |
| "tile"/"verbs" test fixture strings | rename (rides with stage 2) | vocabulary PRD |

### Docstring-predicate extraction (exact target)

Converting `src/seon/agent/ctx/namespaces.cljs` itself to `.cljc` is NOT an
option: its requires (`seon.db`, `seon.config`, `seon.agent.home`,
`seon.error.instrument` — namespaces.cljs:9-18) would drag pod-only code
into the babashka hook load (`bin/seon-hook:236-240` loads
`seon.dev.docstring` under bb via a local-root dep), throwing on the first
post-edit hook run and silently disabling the docstring/markdown lint
inside its catch. A same-name `.cljs`+`.cljc` pair is a build hazard, not a
dodge. Plan:

1. Create `src/seon/agent/ctx/ns_name.cljc` (`seon.agent.ctx.ns-name`)
   whose only require is `clojure.string`, holding `hidden-ns-name?`,
   `test-ns-name?`, AND `included-ns?` (which calls both —
   namespaces.cljs:118-127 — move it too rather than leaving a cross-file
   half). The `{:malli/schema ...}` attr-maps are inert metadata under bb
   and CLJ; no malli require.
2. `namespaces.cljs` requires the new ns and deletes its local copies.
3. `docstring.clj` requires it and deletes private `hidden-ns?`/`test-ns?`
   (docstring.clj:191-208) plus the "reimplemented here" comment.
4. Update the prose cross-references naming
   `seon.agent.ctx.namespaces/hidden-ns-name?` at
   `src/seon/schema/internal.cljc:7`, `src/seon/agent/ctx.cljs:290`, and
   `src/my/plan/internal.cljs:1803` in the same commit.

Acceptance for this row: `bb -e "(babashka.deps/add-deps {:deps
{'seon/hook-source {:local/root \".\"}}}) (require 'seon.dev.docstring)"`
succeeds (the hook path still loads); one real post-edit hook run on a
`.clj` file emits docstring feedback (no `DOCSTRING_LINT_ERROR` in the hook
log); `bin/test-cljs` stays green for the namespaces block tests.

## Acceptance

Three suites green; require-graph re-scan shows no orphan regressions;
usage line proven on three cases — renders actuals for one real
non-stream turn; omits/est-marks for one `:stream` turn with
`usage-estimated?` true; omits when `llm-usage` is absent — with a unit
test for the estimated branch of the render fn; submodule removal leaves
`git submodule status` clean and `bin/seon up` unaffected.

## Owner rulings 2026-07-20 (second round)

1. What `ctx.usage` actually does (owner asked): it parses the persisted
   per-turn `:seon.agent.turn/llm-usage` provider response EDN and
   normalizes it to a `{total, cached, output, provider-shape}` TOKEN
   triple (provider ground truth — DeepSeek `prompt_tokens` includes
   cached, Anthropic `input_tokens` excludes + cache fields add). It is
   tokens, not characters — it complements `seon.ai.tokens/estimate`
   (estimates) with provider-reported actuals, EXCEPT on `:stream` turns,
   where the persisted numbers are client-side estimates flagged by
   `:seon.agent.turn/usage-estimated?` (see the wiring ruling above) and
   must never be framed as actuals. Owner direction: likely the
   debug turn projection plus compact agent-page usage. This is settled: do
   not delete the namespace merely because it was temporarily orphaned.
2. Archive the namespace-ui PRD folder (ruled).

## file-block — RULED 2026-07-20: KEEP as the general mechanism

`seon.agent.ctx/file-block{,-ai,-html}` had zero usage after the
identity-file-block seeding deletion (`c35677fa`); false DEPRECATED
markers were fixed in `f5c145ed`. Owner ruling: KEEP — it is the GENERAL
mechanism for users to load any files into named, prioritized context
blocks via the config manifest (SOUL.md/AGENTS.md are just two such
declarations).

Proof the manifest path already works end to end (no decode fix needed):

- `resolve-agent-context` preserves a block map carrying
  `:seon.agent.ctx/file-path` + the two render symbols verbatim (loose
  `[:vector :map]` leaf; `file-path` is a registered attribute, so
  seed-copy transacts it and the wildcard `{:seon.agent/ctx [*]}` prompt
  pull hands it to the slot fns in the execution child).
- Behavioral test:
  `seon.ctx-test/manifest-file-block-renders-fresh-and-omits-when-absent`
  — decode preserved, present → renders priority-ordered, edited →
  fresh re-read, absent → omitted (no fallback).
- Live: `bin/seon config apply` of a manifest declaring a `:notes`
  file block (priority 30) seeded a fresh default-cluster agent whose
  `ctx-preview` showed `┌─ notes ─` between `:namespaces` (20) and
  `:canvas` (35); an edit landed on the next render; deleting the file
  removed the section. Cluster restored to `config/system.edn` after.
- A commented-out example of the general shape lives in the CONTEXT
  TREE section of `config/system.edn`. Issue note:
  `docs/seon/issues/archive/file-block-mechanism-unused-keep-or-delete.md`.
