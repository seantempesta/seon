---
type: research
status: active
tags: [research, testing, runtime]
---

# CLJC test-parity audit — where the tests stopped following the code (2026-07-23)

Read-only audit of every namespace promoted to portable `.cljc` (or newly
born portable) since 2026-07-20, against the tier(s) that actually execute
it under ruling 26, and against what the three test surfaces can actually
see. Method: git history mining (`--diff-filter=D/R/A --since=2026-07-20`),
the writer runner's real discovery source, and per-family behavior mapping.
All claims cite file:line or commit.

## §0 Executive verdict — ranked

The single most important finding is not a missing test; it is that the
**JVM test discovery predicate predates the CLJC conversion** and now
silently omits ~20 JVM-relevant test files — including U1's guard suite,
U2's portable driver core test, and U5's web/config tests. Fixing discovery
re-arms most of the "missing" coverage at zero test-writing cost.

1. **Repair writer-runner discovery + add the computed orphan gate**
   (S; prevents: silent permanent parity holes — the class, not one bug).
   `script/seon/dev/test_roots.clj:65-73` discovers only `test/seon/db/**`
   plus DIRECT `test/seon/*_writer_test.clj`. Invisible to the full writer
   gate today: `test/seon/host/guard_test.cljc`,
   `test/seon/host/guard_context_test.clj`,
   `test/seon/host_guard_policy_test.clj` (wrong suffix),
   `test/seon/agent/driver_core_test.cljc` (the U2 spine),
   `test/seon/agent/{fs,shell,web}_portable_test.cljc`,
   `test/seon/agent/{lifecycle,message}/portable_test.cljc`,
   `test/seon/agent/fs/match_test.cljc`, `test/seon/ai/portable_test.cljc`,
   `test/seon/config/resolve_web_render_test.clj` (U5),
   `test/seon/web/server_test.clj` (U5),
   `test/seon/render/value_writer_test.clj` (never discovered since its
   2026-07-21 birth, bc0a3b115), `test/seon/repl/parse_test.cljc`,
   `test/seon/repl/parse/repair_test.cljc`,
   `test/seon/runtime/lifecycle_test.cljc`, `test/seon/ui/html_test.cljc`,
   `test/my/blob_test.cljc`, `test/seon/execution_process_test.clj`,
   `test/seon/authority_density_test.clj`. `bin/seon test changed` uses the
   same roots (`script/seon/dev/changed_test.clj:237-238,282-284`), so the
   edit hook is equally blind. Every one of these was "proven" only via
   explicit focused selectors during its lane. Fix: widen
   `writer-test-files` (all `test/**` `*_test.clj` + `.cljc`, minus the
   operator's `test/seon/dev` root and any genuinely CLJS-coupled residue),
   THEN add the computed rule (§3.1) to `test/seon/dev/test_roots_test.clj`
   so no future file can be invisible to all surfaces. No fourth runner.
2. **Claimant-tier schema validation regression** (M; prevents: a JVM
   claimant persisting datoms that violate the registered Malli shapes,
   poisoning every later reader). `src/seon/agent/driver/host.clj:32-37`
   builds its database context with
   `:seon.db.leaf/schema-validation? (constantly false)` and a nil
   projection. Datahike's `:schema-flexibility :write`
   (`src/seon/db/backend.clj:125`) rejects UNREGISTERED attrs, but Malli
   refinements — `:seon.agent.run/claim-epoch [:int {:min 1}]`
   (`src/seon/agent/run.cljs:40`), the `:seon.agent.turn/phase` enum
   (`src/seon/agent/turn.cljs:54`) — are enforced only on the pod tier.
   The bound-committed-projection mechanism already exists
   (`src/seon/host/context.clj:236`, wiki recipe). Wire it into the
   claimant leaf and land ONE regression: a JVM transact of an
   out-of-enum turn phase / zero epoch fails as a flat error value.
   Proposed: `test/seon/db/claimant_validation_test.clj` (under the
   discovered root even before fix #1 lands).
3. **Wire-codec encode totality at the one frame choke point** (S/M;
   prevents: an unserializable value thrown mid-response killing a session
   instead of steering — this morning's drill seed).
   `src/seon/db/transport/uds.cljc:210-217` `encode` calls
   `transit/write` bare; a value without a handler throws raw.
   `protocol/ordinary-wire-value?` exists and is regressed on BOTH tiers
   (`test/seon/db/protocol_test.clj:430`, `.cljs:164`), but no test proves
   every server→client response path applies it before `write-frame!`, and
   `rg unserializable test/` returns nothing. UNCLEAR exactly which
   response paths validate (probe: trace `write-frame!` callers in
   `src/seon/db/server.clj` and `src/seon/host/*.clj` for a validator
   between result and encode). Designed-out form in §3.2; the one
   regression: a response embedding a handler-less JVM object yields a
   flat `:seon.error/kind` steering value and the session survives.
   Proposed: extend `test/seon/db/transport_uds_test.clj`.
4. **JVM claim-lifecycle CAS against a real writer** (M; prevents: lost or
   doubled turns — the program's central promise). Pure builders are
   tested in `test/seon/agent/driver_core_test.cljc` (orphaned from the
   JVM gate, fix #1) and the fence wire once in
   `test/seon/host_conformance_writer_test.clj:648-733`; the full
   acquire(nil→1)/reacquire(e→inc e)/steal-expired/pause-reject/
   beat-with-consumed-input arc against real CAS semantics exists only as
   the manual probe `test/seon/agent/driver_process_probe.clj` and the
   unproven U2 falsifiers (u2-summary "Not yet proved" 1-4). **U2's
   resumed lane already owns this** — the audit's role is to confirm the
   gap is real and that the retained artifact must be a discovered
   `_test`, not a probe. Do not open a second lane on it.
5. **Dual-tier receipt builder test** (S; prevents: receipt CAS/derive
   drift between the tier that writes receipts today and the tiers that
   write them after U6b/U9). `seon.eval.receipt` is `.cljc`
   (32ea3b3bf) and its schema just went portable (d7f768c21), but the pure
   builder/derive suite `test/seon/eval/receipt_test.cljs` is CLJS-only.
   JVM behavioral coverage exists via
   `host_conformance_writer_test.clj:549-733` (receipt-first, lost-fence,
   held-fence). Promote the pure subset (receipt-state derivation,
   start/terminal tx-data CAS shape, closed schemas) to
   `test/seon/eval/receipt_core_test.cljc` after fix #1 makes that
   directory discoverable.
6. **Attempt-receipt persistence pre-stage for U6b** (S; prevents: the
   JVM LLM leaf landing on untested attempt CAS). The deleted
   `agent_retry_test.cljs`/`turn_fallback_test.cljs` (901eee2d3, 615
   lines) covered fallback-chain advance, payment-never-retries, frozen
   attempt caps, bounded usage persistence. Survivors:
   `driver_core_test.cljc` `retry-and-fallback-policy-is-portable` +
   `durable-turn-cursor-and-attempt-builders` (pure), and
   `test/seon/ai/portable_test.cljc` (retry decisions/usage — dual,
   orphaned). The open→terminal attempt transition against a real
   database on the JVM does not exist and is exactly what U6b needs on
   day one. Fold into the U6b spec as its first falsifier rather than a
   separate lane.

Everything else found is either covered, dying at U9, or owned by a
planned unit (§1, §4).

## §1 Moved-piece ledger

Tier legend: **W** writer JVM, **C** claimant JVM (host), **R** web-render
JVM (end state), **P** Bun pod (leaf after U9). "Disc?" = visible to the
full writer gate today.

| Namespace(s) | Move (commit) | Executes on | Old tests' fate | JVM coverage today | Verdict |
|---|---|---|---|---|---|
| `seon.db` / `db.internal` / `db.leaf` | f6d843ee7 07-22 | W+C+R+P | wrappers deleted; new dual test | `test/seon/db/portable_test.cljc` dual, disc? YES; decode/omit-nil covered (`:269-292`) | OK |
| `seon.db.protocol` (.cljc, pre-window) | — | all | — | `protocol_test.clj` + `.cljs`, both run | OK, minus encode-totality (§0.3) |
| `seon.db.transport.uds` .clj→.cljc | b1a69b7f2 07-20 | W+C+P | kept | `transport_uds_test.clj` disc? YES | OK |
| `seon.error` .cljs→.cljc | dd335338b 07-21 | all | kept (.cljs) | `host_error_sci_writer_test.clj` disc? YES | OK |
| `seon.eval.receipt` (eval/internal.cljs→) | 32ea3b3bf 07-21 + d7f768c21 | C+P | `eval/receipt_test.cljs` survived CLJS-only | conformance receipt tests (indirect) | GAP (small) → §0.5 |
| `seon.agent.run.core`, `turn.core`, `driver`, `loop.core`, `runtime.recovery.core` (NEW) | 901eee2d3 07-23 | C+P (drivers) | `agent_retry_test.cljs`, `turn_fallback_test.cljs` DELETED (901eee2d3); `agent_loop/run/turn_test.cljs` survive pod-side (R28 status unknown) | `driver_core_test.cljc` dual, disc? NO; fence wire once in conformance `:648-733`; `host_guard_policy_test.clj` disc? NO | GAP → §0.1/§0.4; U2 owns falsifiers |
| `seon.host.guard` (NEW) | 8000f5327 07-23 | C (+ any sci tier) | n/a | `guard_test.cljc`, `guard_context_test.clj`, `host_guard_policy_test.clj` — ALL disc? NO; `guard_config_test.cljs` CLJS | GAP (discovery only — tests exist and passed focused) → §0.1 |
| `seon.agent.message`/`lifecycle` + cores/leaves | 51c9ff967 07-22 | C+P | `message_test.cljs` survives | `{message,lifecycle}/portable_test.cljc` dual, disc? NO | GAP (discovery) |
| `seon.agent.fs.core`/`shell.core` | 29825ccc9 07-22 | C+P | `fs_test.cljs`/`shell_test.cljs` survive | `fs_portable/shell_portable/fs/match` .cljc, disc? NO | GAP (discovery) |
| `seon.agent.web` + internal | 85780757d 07-22 | C+P | `web_test.cljs` survives | `web_portable_test.cljc` disc? NO | GAP (discovery) |
| `my.blob` + core/schema | b3d16c0d5 07-22 | C+P | `blob_test.cljs`→`.cljc` renamed same commit | disc? NO (`test/my` outside both globs) | GAP (discovery) |
| `seon.ai.core`/`anthropic.core`/`openai-compat.core` | ca1ad5f52 07-23 | C+P (leaves stay CLJS until U6b) | CLJS adapter tests survive | `ai/portable_test.cljc` disc? NO | GAP (discovery); U6b adds the JVM leaf tests |
| `seon.config.resolve` (NEW .cljc) | baeac2ee2 07-21 | W (`db/server.clj`) + C (`host/preflight.clj`) + operator + P | `config_test.cljs` survives | `resolve_web_render_test.clj` disc? NO; `dev/config_test.clj` operator YES; bridge round-trips in `db/datahike/schema_test.clj:411-552` disc? YES | GAP (discovery) — otherwise the best-covered spine row |
| `seon.schema` / `schema.form` / bridge | form: 32ea3b3bf | all (bridge W) | `schema_test.cljs` CLJS | `schema_projection_writer_test.clj`, `schema_concurrency_writer_test.clj`, `db/datahike/schema_test.clj` — all disc? YES | OK |
| `seon.repl.parse` (+repair) renames | 9bc009828/05a13dd45 07-22 | C (host requires it: `driver/host.clj:16`) + P | renamed in place | `parse_test.cljc`/`repair_test.cljc` dual, disc? NO | GAP (discovery) |
| render/ui promotion (my.ui, ctx.usage, render.chat/schema/surface/view-unit/handlers, ui.clojure/markdown) | 0181ad7b7 07-23 | R (after U7; today P, JVM load proven by U4) | CLJS tests survive | U4 byte-identity gate + JVM load proofs; `ui/html_test.cljc` dual disc? NO; `render/value_writer_test.clj` disc? NO (since birth) | interim OK — U7 owns the port; re-arm the two orphans via §0.1 |
| `my.canvas/kb/plan/skills`, `plan.generation` | 5ac8f0efa 07-20, 9bc009828 | C behind door (U7/U8) + P | CLJS tests survive | none JVM | DEFER to U7/U8 (their specs must name dual tests) |
| `seon.retry`, `seon.time*`, `content-hash`, `ai.provider`, `ai.tokens`, `code`, `instrument`, `launch` | 84ab7097a etc. | mixed | CLJS tests survive | indirect (embed_writer, host tests) | OK — low-risk pure helpers; fold into §3.1's computed gate, no bespoke tests |
| eval.cljs engine, execution*, per-agent children | not moved | P, DIES at U9 | ~5.7k LOC child/self-host tests still present | n/a | DIES-AT-U9-SKIP (§4) |

## §2 Persistence-spine deep-dive

1. **register! → install → pull → decode → validate on the JVM.**
   General machinery: PROVEN and discovered —
   `db/datahike/schema_test.clj` `e2e-derive-install-transact-test:411`,
   config native round-trips `:473-552`, alias resolution `:357-408`;
   projection build `schema_projection_writer_test.clj`. **The gap is the
   NEW spine attrs**: claim attrs (`run.cljs:26-83`), turn phase + attempt
   state (`turn.cljs:54-178`) are registered in `.cljs` only — deliberate
   per the schema-leak scar (conversion-wiki "Loading a portable
   capability must not publish child-only schemas on the JVM") — so no
   retained JVM test can transact them without seeding, and none does
   (grep: only `host_conformance` touches `claim-epoch`, via raw CAS on an
   already-seeded fixture). Combined with §0.2 (validation off at the
   claimant leaf), the tier that will own these writes end-state validates
   nothing beyond Datahike native types. Gap statement: NO JVM test proves
   claim/phase/attempt round-trip register→install→pull→decode→validate.
   Close via §0.2's regression (which requires seeding the rows, proving
   the whole path).
2. **Corpus replay on the JVM.** COVERED:
   `host_conformance_writer_test.clj:1194` calls
   `context/replay-defs!` directly; `host_graduate_writer_test.clj`
   proves restart reconstruction; both discovered. Receiptless-probe scar
   (wiki) respected — these go through turn-id'd paths.
3. **Receipts CAS on both tiers.** JVM live path covered
   (conformance `:549,:648,:701`); pure builders CLJS-only (§0.5). Note
   `d7f768c21` (today) made the receipt schema portable — the builder test
   should follow the schema across.
4. **Codec totality + attr drift (the two drill seeds).** §0.3 and §3.2/3.3.
5. **Prompt-artifact cursor resume** (wiki: "Cursor resume must consume
   the persisted artifact"). Mechanism landed
   (`src/seon/agent/turn.cljs:883-931` `split-persisted-prompt` +
   `prompt-blob`). Tier: pod today, claimant after U6b/U7. No retained
   test names `split-persisted-prompt`; the U12 drill is the intended
   proof. Verdict: acceptable — drill-owned; flag only if U2's drill
   report omits a resumed-attempt byte assertion.

## §3 Designed-out classes (owner directive: eliminate by construction)

1. **Test-file invisibility** — choke point:
   `seon.dev.test-roots`. Constraint: computed, not a list — enumerate
   every `test/**/*_test.{clj,cljc,cljs}`; assert each is claimed by ≥1
   surface (operator root ∪ writer discovery ∪ CLJS `ns-regexp "-test$"`
   over `.cljs`+`.cljc`); fail with the orphan set. One regression: a new
   deftest in `test/seon/dev/test_roots_test.clj` (operator surface —
   already discovered). This permanently kills the §0.1 class.
2. **Unserializable wire values** — choke point: `uds.cljc`
   `write-frame!`/`encode` (one encoder for both directions). Constraint:
   the frame writer refuses (flat steering error, R15) any payload failing
   `ordinary-wire-value?`/transit-writability instead of throwing raw.
   One regression: §0.3's session-survives test. Removes the need for
   per-response-path totality tests forever.
3. **Transacted-but-unregistered / registered-but-unvalidated attrs** —
   two constraints already half-exist: Datahike `:schema-flexibility
   :write` (installed-schema totality — structural, no test needed beyond
   the existing bridge suite) and bound-committed-projection validation
   (`host/context.clj:236`) extended to the claimant database leaf
   (§0.2). One regression each; no hand-maintained attr list anywhere.
4. **Vacuous config-policy validation** — the `every?`-over-empty scar is
   already fixed with the require-complete-row rule (wiki; U2). Its
   regression `host_guard_policy_test.clj` exists but is invisible (§0.1).
   The class is closed the moment discovery is fixed.

## §4 Anti-recommendations

- **No new tests for anything in the U9 deletion inventory**: eval.cljs
  self-host engine, execution child bands, per-agent children, child
  Shadow plumbing — including "regression-protecting" their current
  behavior. R28 explicitly forbids proving breakage.
- **Do not resurrect** `agent_retry_test.cljs`/`turn_fallback_test.cljs`
  as CLJS: their retained behavior families live in
  `driver_core_test.cljc` + `ai/portable_test.cljc`; the missing piece is
  U6b's JVM attempt CAS (§0.6), not the old pod-async harness.
- **No fourth runner / no separate "portable" gate**: the three surfaces
  stand; fix discovery inside `bin/test-writer`'s existing root.
- **No CLJS-suite runs to demonstrate known R28 breakage** of surviving
  pod tests (`agent_loop_test.cljs` etc.); their fate is decided by
  U9's inventory, not by red runs now.
- **No pre-U7 porting of render/ctx CLJS tests to the JVM**: the U4
  byte-identity gate is the class-level assertion; U7's spec owns the
  dual placement of render tests when the code actually moves.
- **No string-exactness assertions** in any recommended test — facts,
  transitions, envelopes, CAS outcomes only (standing rule).
- **Do not spawn a parallel lane for the U2 falsifiers** (§0.4): the
  resumed U2 lane owns them; a second owner on run/turn CAS would violate
  one-mechanism ownership during the live drill freeze.

## Evidence appendix (spot verifications)

- Writer discovery source: `script/seon/dev/test_roots.clj:65-73`
  (`files-below "test/seon/db"` + `direct-files "test/seon"
  #(str/ends-with? % "_writer_test.clj")`). Orphan set computed against
  the live tree 2026-07-23; the wiki's "discovers test/seon/**/_test.clj[c]"
  sentence is WRONG as written and should be corrected when the wiki is
  next appended (it is what let the orphans accumulate unnoticed).
- CLJS discovery: `shadow-cljs.edn:301` `:ns-regexp "-test$"` — all dual
  `.cljc` tests DO run on the CLJS side; the hole is one-sided (JVM).
- Claimant validation stub: `src/seon/agent/driver/host.clj:32-37`.
- Focused-only proof pattern: e.g. u6a ran
  `tmp/orchestrator/u6a-gate-portable-jvm.log` via explicit selector;
  U1's suite likewise (`u1-focused-guard-writer-6.log`). Green focused
  logs are real but non-recurring — that is the parity hole's signature.
