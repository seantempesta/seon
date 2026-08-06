---
type: research
status: complete
tags: [research, capability, architecture, agent]
---

# Envelope and symbol-resolution conformance audit (2026-07-20)

Two conformance questions for the source-cleanup PRD, proven from source, not
assumed: (A) does every capability-fn surface match the `seon.agent.fs`
template (gating, envelope, paging, error values), and (B) do the three
symbol-at-use-time mechanisms (render slots, route handlers, ctx block slots)
share one resolution path and one failure semantics?

Every file:line below was read in this audit on branch
`codex/runtime-reliability-refactor` (2026-07-20).

## A. The template contract (written down, falsifiable)

Read first: `src/seon/agent/fs.cljs` (832 lines) plus
`src/seon/agent/fs/internal.cljs:34-130`. The template the repo's
one-mechanism table names ("Capability fns | `seon.agent.fs` is the template
(gating, envelope, paging)") is, exactly:

1. **Schemas first, every key registered** — one `schema/register!` per
   scalar, then named `<verb>-request` / `<verb>-response` composites; shared
   shapes referenced, never inlined (fs.cljs:21-273).
2. **Map-in / map-out with fully namespaced keys** in the owning namespace
   (`:seon.agent.fs/*`); `:malli/schema` on every public fn.
3. **Gating** — allowlist grant inspected by `grants` and (where the agent may
   narrow it) mutated only by `configure!`; host lock via env
   (`SEON_FS_LOCK`); default-deny when unconfigured; every op runs the gate
   before I/O (`int/out-of-scope?` → `int/scope-denied`, `int/read-only?` →
   `int/denied`; fs.cljs:357-358, 390-392, 462-464).
4. **Success envelope** — `{:<ns>/ok? true :<ns>/path … + result keys}`.
5. **Error envelope, errors as values, never throws** — every failure branch
   and every `catch :default` returns a map. Two generations coexist INSIDE
   the template:
   - classic verbs (`read-file`/`write-file`/`edit-file`/`list-dir`/`stat`/
     `walk-dir`/`view`): `{::ok? false ::path ::error <bare string>}`
     (`int/->err`, `int/denied`, fs/internal.cljs:34-47);
   - anchored verbs (`replace!`/`insert!`): `{::ok? false ::path
     :seon.error/message <string> :seon.error/data <map>}` — the shared
     `:seon.error/*` shape (fs.cljs:257-273, 658-692). The anchored shape is
     the NEWER convention and matches the repo error owner (`seon.error`,
     one `:seon/error` family).
6. **Honest paging** — 1-based `::from-line`/`::max-lines` in,
   `::lines-returned`/`::total-lines` out, so a partial page never looks
   complete (fs.cljs:66-91, 604-650); cap-based walks report `::total-found`
   plus `::truncated?` and a guiding `::hint` (fs.cljs:161-192, 540-598).
7. **Load-bearing extras** — `::file-sha` fence for stale edits
   (fs.cljs:73, 675-683); key ORDER in `view` puts small load-bearing keys
   before big content (fs.cljs:635-649); token sizes via
   `seon.ai.tokens/estimate` wherever a size is surfaced.

Falsifier used per surface below: same `ok?` discriminator key style, same
error-value key(s), same gating pattern, same paging keys/honesty, map-in
map-out, never-throws.

## A. Conformance table — capability surfaces vs the template

Legend: **DRIFT** = real drift with a named conformance fix; **JUST** =
justified difference (reason given); **BUG** = live defect found in passing.

| Surface | ok? key | Error value shape | Gating | Paging | Verdict + file:line |
|---|---|---|---|---|---|
| `seon.agent.fs` classic verbs | `::ok?` | bare `::error` string (fs/internal.cljs:34-47) | template | template | **DRIFT (template self-drift)** — the template's own older half predates the `:seon.error/*` shape its anchored half uses. Fix: migrate `->err`/`denied`/`scope-denied` envelopes and the classic `*-response` schemas to `:seon.error/message`(+`:seon.error/data`), keeping `::ok?`/`::path` |
| `seon.agent.fs` anchored verbs | `::ok?` | `:seon.error/message`+`data` (fs.cljs:658-692) | template | n/a | conformant — this IS the target shape |
| `seon.agent.shell` (shell.cljs:44-115, internal.cljs:44-76) | `::ok?` | `:seon.error/message`+`data` (`in/fail`) | host env `SEON_SHELL`, read-only `grants`; cwd rides the fs allowlist (shell.cljs:263) | job output pages by char cursor `::since`/`::next-since` + honest `::tokens`/`::truncated?` (shell.cljs:451-481) | conformant. **JUST** ×2: host-owned grant (no `configure!`) is deliberate — nothing inside the pod may widen shell access; stream-cursor paging is the right idiom for an append-only stream (line paging would re-read) |
| `seon.agent.web` fetch/search (web.cljs:73-97, 130-147; internal `err`/`search-err`) | `::ok?` | `:seon.error/message`+`data` | `SEON_WEB` + host config policy (`:open`/`:public-only`/`:allowlist`), read-only `grants` | preview capped by `::max-preview-tokens` with honest `::total-tokens`; full doc pages via `my.blob/text` (delegation, not a second pager) | conformant. **JUST**: token-preview + blob delegation instead of line paging — the full text lives in the blob tier; one paging owner (`my.blob`) is reused, not forked |
| `seon.agent.search` grep/grep-graph (search.cljs; internal.cljs:48-63) | `::ok?` | bare `::error` string + `::raw-error` | rides fs allowlist (`in/gate-path`, default roots = fs grants) — **JUST**: one grant, reused | cap + `::truncated?` + `::hint` + honest `::match-count`/`::file-count` — template walk idiom | **DRIFT** (error shape only): `in/fail` returns `::error`/`::raw-error` bare strings. Fix: `:seon.error/message` + move raw detail into `:seon.error/data {::raw-error …}`; update `grep-response`/`grep-graph-response` schemas |
| `seon.agent.testrun` (testrun.cljs:55-76, 212-244) | `::ok?` | `:seon.error/message` (unrecognized/record failures) | none — **JUST**: pure parser + scoped persist, no host resource | n/a | conformant |
| `seon.agent.message` (message.cljs:74-101, 227-232, 384-470) | **none** — `message!` returns `{::id ::hops}` or `{:seon.error/message}`; `recent`/`recent-all` return a bare vector or `{:seon.error/message :seon.error/data}` | shared `:seon.error/*` ✓ | admission gate (`admission/available?`) — **JUST**: runtime admission, not a host grant | `::recent-limit` bound; no truncated?/honest-total | **DRIFT (half)**: the missing `ok?` discriminator is documented as deliberate ("concise domain result", message.cljs:226-232), and callers branch on `:seon.error/message` — acceptable, but it is a SECOND discriminator convention. Decision needed: either bless "presence of `:seon.error/message` = failure" as the discriminator for concise domain results (and say so in `toolkit.md`), or add `::ok?`. Recommend the former (smaller change, matches errors-as-values); `recent` should also report the honest total when it clips at `::recent-limit` |
| `my.blob` (blob.cljs:134-192, 714-882) | `::ok?` | **mixed**: bare `::error` string everywhere EXCEPT `text`'s binary refusal which uses `:seon.error/message`+`data` — both keys sit in ONE response schema (blob.cljs:165-177) | no fs-grant gate — **JUST**: writes only under the cluster archive (`<cluster>/blobs/`), its own tier, not agent-chosen paths | `text` mirrors fs line paging exactly (blob.cljs:123-126, 654-670 explicitly "the fs precedent") | **DRIFT** (error shape): one surface, two error keys is the clearest evidence of the split. Fix: converge `bad-hash`/`not-found`/`inspect-path`/put!/get envelopes to `:seon.error/message`(+`data`), drop the bare `::error` key from the response schemas |
| `my.kb` remember/recall (kb.cljs:47-85, 145-188, 345-424) | `remember`: none (success `{::id}` / failure = raw `:seon.db/transact-response` env); `recall`: `:seon.result/ok?` | recall failures: bare `::error` string built from `:seon.error/message` (kb.cljs:324-329) | none — **JUST**: db verbs; `seon.db` is the capability boundary | `::limit` + honest `::matched` pre-cap total — conformant honesty | **DRIFT** ×2: (1) `remember` leaks the raw transact envelope on failure instead of a domain fail value — fix: return `{:seon.result/ok? false :seon.error/message …}`; (2) `recall` re-wraps `:seon.error/message` into a bare `::error` string — keep the original key |
| `my.data` rows/sum-by/max-by/group-sum (data.cljs) | `:seon.result/ok?` (envelope producers) | none | none — **JUST** | n/a | **BUG**: `rows` does `(vec (await (db/query …)))` with NO error-envelope check (data.cljs:51-57) — a failed query returns `{:seon.error/message …}` and `vec` turns it into a vector of MapEntries reported as `ok? true`. Fix: branch on `:seon.error/message` before `vec`, return `{:seon.result/ok? false :seon.error/message …}` |
| `my.skills` load/unload/list (skills.cljs:32-57, 183-265) | `::ok?` | failure text under `::message` (NOT `::error`, NOT `:seon.error/message`); `list` passes raw db error maps through | none — ctx blocks | n/a | **DRIFT**: a third failure-text key. Fix: `::result` schema and all three verbs move failure text to `:seon.error/message`; keep `::message` for the SUCCESS guidance strings only (or rename) |
| `my.canvas` show!/clear!/save!/state/pinned (canvas.cljs:76-198) | none — passes `:seon.db/transact-response` through, or returns bare `{:seon.error/message :seon.error/kind :seon.error/data}` (show! at 102-111) | shared `:seon.error/*` ✓ | none — **JUST** | n/a | conformant under the "presence of `:seon.error/message` = failure" convention (same family as message!); pass-through of the transact envelope is the documented `seon.db` contract, not a fork |
| `my.ns` functions/full!/compact! (ns.cljs:31-47) | `:seon.result/ok?` | bare `::error` string (+ good `::hint`s) | none — **JUST** | n/a | **DRIFT** (error key only): same fix as recall — carry `:seon.error/message` instead of re-keying to `::error` |
| `my.plan` public verbs (plan.cljs:49-94, 788-1731) | `::ok?` | bare `::error` string (`internal/fail`); internal ops use `::direct-error` = `[:map [:seon.error/message]]` — both conventions in one file (plan.cljs:61, 87-94) | none — **JUST**: db verbs, agent-scoped by `::agent` ref | `list-open` windowed; `tree`/`document` bounded views | **DRIFT** (error key): `internal/fail`'s `::error` string vs the same file's `:seon.error/message` direct errors. Fix: one shape — `{::ok? false :seon.error/message …}` — in `write-response`/`plan-response`/`drop-response`/`status-response`/`list-response`/`reconcile-response` and `internal/fail` |

### A. Summary of the one real convergence

The gating and paging stories are healthy: every surface either implements the
template gate, rides it (search→fs, shell cwd→fs), or justifiably has none
(pure/db surfaces). Paging is honest everywhere it exists, in three named
idioms (line window, cap+truncated?+hint, stream cursor) plus web's
token-preview+blob delegation — all reuse, no forks.

The ONE real drift is the **failure-payload key**, currently four ways:

1. `:seon.error/message` (+`data`) — shell, web, fs-anchored, message, canvas,
   testrun, plan's direct errors. **The target.**
2. bare `<ns>/error` string — fs-classic, search, blob, plan `internal/fail`,
   my.ns, my.kb recall.
3. `::message` — my.skills failures.
4. leaked raw `:seon.db/transact-response` — my.kb remember failure branch.

And the **ok? discriminator**, three ways: per-ns `<ns>/ok?` (most), shared
`:seon.result/ok?` (my.kb recall, my.ns, my.data — with `seon.items`
envelopes), and none/`:seon.error/message`-presence (message!, canvas).

## B. Symbol-resolution semantics

### One resolution path — CONFIRMED (with one test-only exception)

`seon.eval/lookup-value` (eval.cljs:502-535) is the single late
symbol→runtime-value resolver: qualified-symbol check → bootstrap ns object →
munged member read; **never throws, nil on miss**; sees `eval-str`
redefinitions immediately. Every production consumer resolves through it
(rg over `src/**/*.cljs`):

| Caller | Site | What it resolves |
|---|---|---|
| render entity converters | render.cljs:352 (html), 621 (ai) | schema `:seon.render/html`/`ai` converter symbols |
| render block slots | render.cljs:746 (`view-renderer`) | ctx-block / node slot symbols (the ctx block contract at agent/ctx.cljs:61-74 names this exact path) |
| canvas | render/canvas.cljs (contract doc:12) → same render dispatch | `:seon.render.canvas/content` symbols |
| route handlers | web/router.cljs:169-181 (`route-handler`) | `:seon.route/handler` symbols at request time |
| execution call gate | execution.cljs:735, 776, 788, 941 | agent-selected function symbols in the child |
| warnings derivation | warn.cljs:993 | canvas symbols (derived `:canvas-unresolved` warning) |
| serve core lookups | web/serve.cljs:248, 1485 | `seon.client/apply-config!`, `quiesce-runtime!` (build-cycle-breaking internal indirection) |
| eval internals | eval.cljs:1035, 1059, 4091 | analyzer/bootstrap self-use |

The planned route-authority collapse
([[route-authority-collapse-2026-07-20]] §3) explicitly extends
`route-handler`'s existing `lookup-value` wrap to every migrated row —
"nothing new is built". So render slots, ctx block slots, and route handlers
already/by-design share the ONE path. No `requiring-resolve`-style second
mechanism exists in the client.

**Exception (test-only):** `src/seon/test/runner.cljs:335, 469, 725` calls
`js/goog.getObjectByName` directly instead of `lookup-value`
(runner.cljs:160's comment even names `lookup-value` as the intended
mechanism). Low stakes, but it is a second resolution idiom — converge when
touching the runner.

### Failure semantics — FOUR behaviors today, not one

What happens when the stored symbol resolves to nil:

| Surface | Unresolved-symbol behavior | Fault datom? | Derived warning? | Site |
|---|---|---|---|---|
| render entity converters (html + ai) | `(when f …)` → nil → the section **silently vanishes** | no | no | render.cljs:352-355, 621-624 |
| render block slots | legible self-healing ai line via `missing-render` ("fn X does not resolve — define it…"); html nil | no | no | render.cljs:721-747 |
| canvas content | guarded error card at render + **derived urgent `:canvas-unresolved` warning** re-queried from current facts | no (derived, not stored — correct) | yes | warn.cljs:975-1010 |
| route handlers | 500 response + console error line | not yet (design §3 adds ONE `seon.error/record!` fault per miss + warnings-render surfacing) | planned | router.cljs:169-181 |
| execution call gate | first tries `ensure-program!` (loads the fn's namespace from the db program), then a structured error VALUE per call | via normal error path | n/a | execution.cljs:770-795 |

(Only a *throwing* resolved renderer records a fault datom + banner today —
render.cljs:356-370, 625-640. An *unresolved* one records nothing.)

### Proposed single semantics (the convergence target)

Grounded in the repo's own best two exemplars — `missing-render` (legible,
self-healing, no stored state) and `:canvas-unresolved` (warning derived from
current facts, vanishes when the fact changes) — plus the route design's
already-settled §3 ruling:

1. **Resolution**: `seon.eval/lookup-value`, nil on miss, everywhere.
   Unchanged. Migrate `seon.test.runner`'s three `goog.getObjectByName` sites
   when next touched.
2. **At the consuming surface, nil is a value, surfaced legibly, never a
   silent drop**: render slots → the `missing-render` line (ai) / nil or an
   error card (html); routes → 500 (route exists, symbol missing = core
   misconfiguration; 404 stays the no-match path); execution → the structured
   per-call error value after one load attempt. All self-heal on the next
   render/request once the symbol defines.
3. **One derived-warning family**: generalize `:canvas-unresolved` into ONE
   unresolved-symbol warning derivation that queries every stored symbol slot
   — `:seon.render.canvas/content`, ctx-block `:seon.render/ai`/`html`
   symbols, entity-schema converter symbols, `:seon.route/handler` — checks
   `lookup-value` nil at derivation time, and omits itself when facts are
   clean. No stored warning, no acknowledgement flag (the reactive-context
   rule). The canvas-specific derivation becomes one case of it.
4. **Fault datom = forensic record at request-driven surfaces only**: per the
   route design, a dispatch-time miss records once via `seon.error/record!`
   (`:core` fault). Render-time misses do NOT write fault datoms per render —
   the derived warning is the visibility mechanism; a per-render datom would
   be a write-amplifying stored warning in disguise.
5. **Fix the one real gap**: render.cljs:352/621 must stop nil-vanishing —
   route the nil-f case through the same `missing-render` legible line
   (ai) / error-card (html) instead of `(when f …)`.

## Convergence plan (stage assignment)

Target stage: **source-cleanup Stage 5 — deletions and small unifications**
(roadmap.md §Stage 5), EXCEPT the route-handler items which ride **Stage 4**
with the route-authority collapse that owns them.

Ordered, each independently commit-able:

1. **(Stage 5) Error-payload convergence to `:seon.error/message`(+`data`)** —
   one mechanical sweep per owner, response schemas updated in the same
   commit: fs classic helpers (`fs/internal.cljs:34-47` + classic response
   schemas), search (`search/internal.cljs:48-56`, raw detail →
   `:seon.error/data`), blob (drop `::error` from response schemas), plan
   (`internal/fail` + the six response schemas), my.ns, my.kb recall.
   my.skills moves failure text off `::message`. Grep-proof: no
   `<ns>/error`-keyed failure remains in a capability response schema.
2. **(Stage 5) Discriminator ruling** — document in
   `docs/seon/architecture/toolkit.md`: per-ns `<ns>/ok?` for envelope verbs;
   "presence of `:seon.error/message` = failure" blessed for concise domain
   results (message!, canvas); `:seon.result/ok?` reserved for the shared
   `seon.items` envelope family. No third style.
3. **(Stage 5) Point fixes** — my.data/rows error-envelope check (**BUG**,
   data.cljs:51-57); my.kb remember failure wrapping; message/recent honest
   clip total.
4. **(Stage 5) render nil-f fix** — render.cljs:352/621 emit `missing-render`
   / error-card instead of silently vanishing.
5. **(Stage 5) One unresolved-symbol warning derivation** — generalize
   `:canvas-unresolved` (warn.cljs:975) across canvas + ctx blocks +
   entity converters (+ routes once stage 4 lands).
6. **(Stage 4, rides route collapse)** — route-handler fault datom on
   dispatch miss (design §3, already specified); route rows enter the
   generalized warning derivation.
7. **(opportunistic)** — `seon.test.runner` `goog.getObjectByName` →
   `lookup-value` (3 sites) when the runner is next edited.

Issue search: `docs/seon/issues/` has no existing note for the
`my.data/rows` bug or the render nil-vanish; both are recorded here and
should get issue notes when the fixing lane opens (this audit lane is
read-only on source).
