---
type: research
status: complete
tags: [research, health]
---

# Hand-maintained and fragile constructs index (2026-07-20)

Audit lane sweep of active source (`src/`, `script/`, `bin/`, `config/`,
`test/`, `.clj-kondo/`, `shadow-cljs.edn`, `bb.edn`) for literal sets,
mirrors, fragile couplings, and unguarded manual registrations. Calibrated
against today's fixed precedents (markdown-linter tag sets, render marker
mirror, config default duplication, docstring-predicate reimplementation).

## The acceptance bar: the guarded literal

The model is the core route set. `src/seon/route.cljs:99-114` is a literal
7-route vector, and `test/seon/route_test.cljs:28-48`
(`seed-set-is-the-corrected-contract`) asserts **exact set equality** on
names, patterns, methods, and the closed set of same-origin POST doors, plus
`handlers-are-qualified-symbol-data`. Drift in either direction fails a test
that names the contract. A hand-maintained literal is acceptable exactly when:

1. it is the *authority* for the fact (not a copy of data owned elsewhere),
2. one exact-equality (or provoke-and-assert) test closes it, and
3. the test failure message says what contract changed.

A second in-repo model for defaults: `src/seon/agent/ctx/transcript.cljs:90-93`
derives every `default-*` constant from the registered schema
(`(schema-default ::turns-retained)`) — defaults live once, in the schema.

Everything below is measured against those two patterns.

## Class 1 — literal sets enumerating data owned elsewhere

| # | Location | Literal | What breaks on drift | Computed rule / owner | Cost |
|---|---|---|---|---|---|
| 1.1 | `src/seon/client.cljs:1953` (+ docstring copy `src/seon/state.cljs:507-509`) | `:seon.db/managed-identity-attrs #{:seon.route/name :my.skills/name :seon.config/id}` | A new declaratively-seeded entity family (4th managed identity attr) silently escapes reconcile retraction — removed manifest rows persist | The repo law already names the owner: `:seon.entity/id-attr` enumerates identity attrs. Derive the set from the installed schema's identity attrs that appear in the `desired` rows (or from id-attr registrations scoped to config-managed schemas), so `desired` itself computes the set | small |
| 1.2 | `src/seon/eval.cljs:2583` `optional-fn-projection-attrs` | `#{:seon.fn/spec :seon.fn/schema-error :seon.fn/agent-facing?}` | A new optional `:seon.fn` attr added to the schema is not retracted on redefinition — stale facts revive on cold reconstruction (exactly the failure the fn's own docstring warns about) | Derive from the registered `:seon.fn` map schema's `{:optional true}` keys (the schema is already registered in the owning ns) | trivial |
| 1.3 | `src/seon/worker_eval.cljs:114-121` `error-warning-types` | 8 `cljs.analyzer` warning keywords | A renamed/removed analyzer warning key makes the entry dead and the compile error passes silently as ok? true | The enumeration is genuinely policy (which warnings are fatal), but membership is checkable: add a test asserting every element `∈ (keys cljs.analyzer/*cljs-warnings*)` — the guarded-literal pattern | trivial |
| 1.4 | `src/seon/derive.cljs:46-51` read-set | `#{:seon.agent/id :seon.agent/run …}` — "the immutable read-set" of `derive-state` | UI stops re-deriving when `derive-state` starts reading an attr not in the set (silent stale state) | Colocated-by-design and documented as the one dependency definition — acceptable authority, but unguarded. Add a behavioral test: transact each listed attr and assert derived-state change; transact a control attr and assert no change | small |
| 1.5 | `src/seon/reactive.cljs:191-192` `transaction-metadata-attributes` | `#{:db/txInstant :seon.db/user :seon.db/process}` | A new provenance attr (added to tx metadata) leaks into reactive match sets → spurious re-renders | Provenance attrs are owned by the `seon.db` tx-context schema; derive or reference the one provenance definition | trivial |
| 1.6 | `src/seon/error.cljs:207` | regex `^(seon\|clojure\|cljs\|sci\|goog)\.` ns-prefix classification | A new first-party root ns (e.g. `my.`) or vendored root misclassifies stack frames | `my.` is notably absent already — likely live drift. Compute first-party roots from the program graph's `:seon.ns` rows (loaded namespace roots) instead of a prefix list | small |
| 1.7 | `src/seon/diffusion/retrieval.cljs:183-189` `core-namespaces` | 15 qualifier strings treated as always-resolved | New std-lib alias in generated code gets false "unresolved" penalty | Derivable from the analyzer's loaded-namespace index; diffusion lane is semi-frozen, so a guard test is enough | small |
| 1.8 | `script/seon/dev/process.clj:120-129` env prefixes + keys | `["SEON_" "GOOGLE_" …]`, `#{"HOME" "PATH" …}` | New provider env var (`XAI_`, `MISTRAL_`…) silently stripped from children — the classic "works in shell, dies under operator" bug | Genuine policy allowlist (justified as authority) but has no test. Add an assertion test naming the contract; consider deriving provider prefixes from the one provider catalog in `seon.ai` | trivial (guard) |
| 1.9 | `src/seon/ai/anthropic.cljs:218-223` `known-message-keys` | Adapter's consumed top-level Message keys | New provider field would be double-carried | Justified third-party-boundary literal; the remainder-preserving design makes drift additive, not lossy. No action | — |
| 1.10 | `src/seon/ui/clojure.cljs:39-48` highlighter `def-forms`/`literals` | def-form names for syntax coloring | Cosmetic mis-highlight only | Justified: presentation-only, low blast radius | — |
| 1.11 | `script/seon/dev/issues.clj:6-9` severities/statuses/non-note-files | `#{"blocker" "friction" "cleanup"}` etc. | Issue lint rejects a new legal vocabulary word | It IS the authority for issue-note vocabulary; add the closure test naming it; fine as guarded literal | trivial |

Counter-example done right: `src/seon/error.cljs:218` `agent-fault-kinds` is
defined once and **referenced** by `src/seon/eval.cljs:2856` and
`src/seon/instrument.cljc:279` instead of being copied. That is the pattern
1.1–1.5 should converge to.

## Class 2 — mirrors (two places edited together)

| # | Locations | What is mirrored | What breaks | Single source of truth | Cost |
|---|---|---|---|---|---|
| 2.1 | `src/seon/db/datahike/schema.clj:40-58` (`leaf-type-map`, comment: "MIRRORS the CLJS bridge — keep in lockstep") ↔ `src/seon/db/internal.cljs:108` (`malli-type->datahike-type`) | The Malli→Datahike leaf type bridge, duplicated across JVM writer and CLJS pod | A type mapping added on one side only ⇒ pod-validated schema the writer rejects (or vice versa) — a cross-process schema wedge. **No parity test exists** (`test/seon/db/datahike/schema_test.clj` does not compare against the CLJS map) | Move the map to one `.cljc` both sides require; interim: a writer test that reads both and asserts equality | small (.cljc) |
| 2.2 | `script/seon/dev/config.clj:428`, `src/seon/launch.cljc:518`, `src/seon/web/serve.cljs:1665-1667` (+ prose copies serve.cljs:16,1654,1659,1694) | Default HTTP port `7890` as three independent fallbacks | Changing the default in one place forks behavior by entry path (operator vs bare pod vs serve) | The launch descriptor (`seon.launch`) is already the validated one owner; `serve.cljs` should take the port from the descriptor, `dev/config.clj` should call `launch` for the default instead of re-literal-ing `"7890"` | small |
| 2.3 | `src/seon/launch.cljc:~505` (`tmp/seon-cluster-<cluster>-db.sock`) ↔ `src/seon/db/transport/uds.cljs:27-29` (`tmp/seon-cluster-default-db.sock`) | Default UDS socket path pattern | Renaming the socket convention in launch leaves uds.cljs's fallback pointing at a dead path | Same fix as 2.2: uds default comes from the launch descriptor | small |
| 2.4 | `shadow-cljs.edn:81-96` (`:acme-client`), `:112-127` (`:bench-client`) | Comments declare "A BYTE-FOR-BYTE MIRROR of :client EXCEPT :output-to. Keep it in sync" — comment-enforced only | A compiler-option/preload change to `:client` silently forks acme/bench pod behavior from default | A test that reads `shadow-cljs.edn` and asserts `(= (dissoc client :output-to) (dissoc acme-client :output-to))` — turns the comment into the guarded-literal pattern. (Generation via bb is the deeper fix but a test closes the drift now) | trivial (test) |
| 2.5 | `src/seon/config.cljs:877` ↔ `src/seon/config.cljs:1288` | `#{:off :safe-syntax :symbols :aggressive}` repair-level enum, twice in one file; same for the default `:symbols` (also at both sites) | Enum extension updates one site; the accessor belt coerces a legal new level back to `:symbols` | One `def` (or read the enum from the registered schema per the transcript.cljs pattern) | trivial |
| 2.6 | `src/seon/config.cljs:846-890` resolver defaults ↔ per-knob accessor defaults later in the file (e.g. repair-level `:symbols` at 877 and 1288) | Each config knob's default value exists in the resolve map and again in its accessor's fallback | Divergent defaults by read path (resolved singleton vs stale-cache belt) | The `schema-default` pattern (`src/seon/agent/ctx/transcript.cljs:90-93`): defaults declared once in the registered schema, both resolver and accessor derive | unit |
| 2.7 | `src/seon/handlers/eval.cljs:63` ↔ `:108` | Identical regex `#":seon\.error/message\s+\"…\""` duplicated within the file | Fix one extraction, miss the other | One private fn — but see 3.2; the right fix deletes both | trivial |
| 2.8 | `script/seon/dev/mcp.clj:58` `default-build-id ":client"` | Shadow build id as a string constant in the MCP server | Renaming the `:client` build orphans the MCP default silently | `seon.dev.config`'s artifact-flavor table already owns build ids (mcp.clj:47 claims "zero mirrored logic" — this literal is the remainder); take the default from the flavor table | trivial |
| 2.9 | `shadow-cljs.edn:38-40` `:source-paths` "Kept in sync with deps.edn for documentation value only" | Path list restating deps.edn `:cljs` alias | Self-declared doc-only; low risk | Acceptable; optionally the 2.4 test can assert equality with deps.edn too | — |

## Class 3 — fragile couplings

| # | Location | Coupling | What breaks | Computed rule | Cost |
|---|---|---|---|---|---|
| 3.1 | `src/seon/warn.cljs:587-591` `bad-ref-marker` | `str/includes?` on Datahike's literal message "Lookup ref attribute should be marked as :db/unique" (+ regex on the attr at :630) | A datahike fork upgrade rewords the message ⇒ bad-ref rows silently fall through to the generic failed-evals check (wrong guidance, no error) | Provoke-and-assert guard: a test that performs the bad lookup-ref against a real connection and asserts the marker matches the produced message — pins the dependency string with evidence | trivial |
| 3.2 | `src/seon/warn.cljs:651-661` `fs-error-key-marker` + `fs-denial-marker` ("allowed-roots" substring), and `src/seon/handlers/eval.cljs:63,108` + `src/seon/agent/debug.cljs:534` regexes over the **pr-str'd** error | First-party structured error data is round-tripped through `pr-str` and re-parsed with substring/regex | Rewording a first-party denial message, or pr-str elision landing mid-key, breaks classification with no test failure — this is the same disease as today's fixed marker-token mirror | Errors are values: match on `:seon.error/kind` / a `:seon.agent.fs/denial` keyword in the stored `:seon.eval/error-data`, never on rendered prose. The data already exists (`error-data` is stored); the regexes re-derive what a key lookup gives directly | small |
| 3.3 | `src/seon/agent/testrun.cljs:124` | `re-matches #"^(FAILED\|ERROR)\s+(\S+)…"` over the first-party test runner's printed lines | Runner print-format change silently drops failure rows | The runner owns structured result data (`seon.test.runner` counters/refs); consume the data, not the rendering | small |
| 3.4 | `src/seon/error.cljs:181` | `(not (#{"ERROR" "Could not eval"} (str/trim msg)))` — meaningfulness test by message-string equality | New generic wrapper message reintroduces noise | Guard test naming the two known-useless strings (they come from cljs.js — third-party boundary, so a guarded literal is the honest form) | trivial |
| 3.5 | `script/seon/dev/process.clj:1460` `default-turn-timeout-ms 900000`; `src/seon/eval.cljs:126` `default-timeout-ms 10000`; `src/seon/db/transport/uds.cljc:168-174` byte/slot caps | Magic numbers with no recorded derivation | Not drift, but unfalsifiable tuning; contrast `src/seon/agent/web/internal.cljs:22-28` where each cap's docstring names its provenance ("openclaw's number") — that is the standard | One-line derivation docstring per constant (measured, borrowed, or arbitrary-and-why) | trivial |
| 3.6 | `script/seon/dev/release.clj:31-50` `babashka-assets` | bb `1.12.218` asset names + SHA-256 digests | Justified pinning — the digest IS the security contract; version bump is deliberate | Acceptable as-is; digests are the model of an intentional literal | — |
| 3.7 | `src/seon/repl/autocomplete.cljs:358-365` `export-format` "seon.autocomplete.export/v1", `split-policy` version strings | Versioned format identifiers | Justified: format versioning literals are the authority | — |

## Class 4 — drift-prone registrations (manual step, no completeness check)

| # | Registration | Manual step | Missing check | Computed rule | Cost |
|---|---|---|---|---|---|
| 4.1 | Render handlers: `src/seon/agent.cljs:220-273`, `src/seon/handlers/message.cljs:5-6`, `src/seon/test/runner.cljs:171-172`, `src/seon/render/canvas.cljs:337` | Every renderable entity schema names `:seon.render/ai`/`:seon.render/html` **symbols**, late-bound | A typo'd or moved handler symbol survives load and fails only at render time in a live turn. `test/seon/route_test.cljs` proves symbol-resolution testing for routes; no analogous test walks render registrations | One test: collect every registered schema property naming a render symbol, resolve each against the analyzer/program graph, assert all resolve — completeness computed from the registry itself | small |
| 4.2 | Config knobs (`src/seon/config.cljs` resolver, `config/system.edn` commentary) | New knob = schema + resolver entry + accessor + default (×2, see 2.6) | No test that every registered `:seon.config.*` schema key is consumed by the resolver | Schema-driven resolution (2.6) collapses the steps; interim completeness test: resolver output keys ≡ singleton schema keys | unit (with 2.6) |
| 4.3 | `script/seon/dev/test_roots.clj` | — | — | Already the model: discovers retained test roots from the filesystem, throws on missing `ns`/duplicates. No action; cited as the computed-discovery exemplar | — |
| 4.4 | `.clj-kondo/config.edn:12` `:exclude [await]` | New special forms must be hand-added | Acceptable: tiny, self-documenting, breaks loudly (lint error) | — | — |

## Ranked fix list

1. **2.1 CLJ/CLJS type-bridge mirror → one `.cljc`** (or interim parity test). Cross-process schema wedge risk, zero guard today. small.
2. **3.2 regex/substring over pr-str'd first-party error data → key lookups on `error-data`** (`warn.cljs` fs markers, `handlers/eval.cljs` ×2, `agent/debug.cljs`). Same disease class as today's fixed marker mirror. small.
3. **2.2 + 2.3 default port/socket fallbacks → launch descriptor is the one owner** (deletes the `"7890"` and socket-path triplication). small.
4. **2.4 shadow-cljs.edn byte-for-byte mirror builds → equality-modulo-`:output-to` test.** Turns a comment into a contract; protects every acme/bench proof run. trivial.
5. **1.1 `managed-identity-attrs` literal → derived from `:seon.entity/id-attr` registrations.** The repo law already names the owner. small.
6. **4.1 render-handler symbol-resolution completeness test.** Route test already proves the pattern. small.
7. **1.2 `optional-fn-projection-attrs` → derived from the `:seon.fn` schema's optional keys.** trivial.
8. **2.5/2.6 config default+enum duplication → `schema-default` pattern** (transcript.cljs is the in-repo model). unit; do 2.5's one-file dedup now (trivial).
9. **3.1 datahike bad-ref message marker → provoke-and-assert guard test** (pins the third-party string with evidence). trivial.
10. **1.3 analyzer `error-warning-types` membership guard vs `cljs.analyzer/*cljs-warnings*`** + **1.6 first-party ns-prefix regex → program-graph-derived roots** (note: `my.` is missing from the prefix list today — likely live misclassification). trivial + small.

Honorable mentions (do opportunistically): 3.3 testrun line-format regex → structured runner data; 1.8 env-allowlist guard test; 3.5 derivation docstrings for undocumented timeouts; 2.8 mcp default-build-id from the flavor table.

## Justified literals (no action, cited as calibration)

- `src/seon/route.cljs` core routes — guarded by exact-equality closure test (the model).
- `script/seon/dev/release.clj` pinned asset digests — the digest is the contract.
- `src/seon/ai/anthropic.cljs` adapter key set — third-party boundary, remainder-preserving.
- `src/seon/ui/clojure.cljs` highlighter token sets — cosmetic blast radius.
- `src/seon/repl/autocomplete.cljs` export-format version strings — format versioning.
- `script/seon/dev/test_roots.clj` — computed discovery, the anti-list exemplar.
- `src/seon/error.cljs` `agent-fault-kinds` — one def, referenced (not copied) by eval + instrument.
