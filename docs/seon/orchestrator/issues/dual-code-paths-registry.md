---
type: issue
status: active
tags: [issue, agent, index]
---

# Dual code paths & complexity-debt registry — LIVE, both lanes

**The one tracked list** (owner directive 2026-07-02: "don't lose track of
dual code paths — track everything and fix it"). Rules:

- Every dual-path / hand-list / silent-fallback / dual-home / warn-only
  finding gets a row HERE at discovery — from any audit, unit report, or
  drive. No finding lives only in chat or a dated research file.
- A row closes ONLY with the fixing commit sha. LEGITIMATE verdicts carry a
  rationale pointer and stay listed (they're re-audited, not forgotten).
- Depth lives in the source audits — this file is status, not analysis:
  [[../../prds/agent-ctx/research/magic-systems-audit-2026-07-02]] (M-rows) ·
  the tooling complexity sweep (C-rows, 2026-07-02 session) · unit reports.

## Open — FIX-ROOT (ranked by blast radius)

| id | item | where | lane | status |
|----|------|-------|------|--------|
| M1 | `*conn*` + `schema/*schemas` + shared compile-state ambient roots (scratch-world `set!` swap) | serve.cljs, client.cljs | eval (cluster-everywhere build) | RE-JUDGED by one-pod-per-cluster (coordination MAJORs): the root-swap scratch machinery is DELETED in the cluster build; one root per pod = correct by construction. Remaining ambient rows re-judge after it lands |
| M4 | SCI env rebuild re-parses source for aliases (requires not stored structurally) | render/sci.cljs:320 (`ns-requires`) | eval | VERIFIED PARTIAL 2026-07-03 (entity-ref unit): the SYMPTOM class is closed — fresh default-cluster boot + `/agent/root/debug` with live plan data = zero SCI-bounding warnings, plan-block bounded (`6f96b024` stored real `my.*.internal` source + deleted the fallback; `c5d6f985` unions `:seon.ns/requires` edges). REMAINING: `:as` aliases/`:refer`s still come from RE-PARSING `:seon.ns/source` text — store them structurally to close |
| M5b | Router serves a cached route projection, no tx-listener self-heal | web/router.cljs, serve.cljs:905 | eval (queued behind solve-path) | QUEUED |
| M6 | `async-fn?` ctor-name detection + once-per-process instrument gate | instrument.cljc:400 | tooling | OPEN |
| M8 | `core-ns-set` replay-skip + `fn-less-compiled-roots #{"my.kb"}` name sets (provenance, not names, should decide) | client.cljs:1067,2441 | tooling | OPEN — hand-list class |
| M9 | `!mint-agent-fn` (né `!solve-deps`) / `!create-agent-fn` require-cycle injection atoms | serve.cljs:110, render/sci.cljs:493 | tooling | OPEN (small; cluster build shrank !solve-deps 3 closures → 1 mint closure) |
| M10 | Home-ns aliases unresolvable in agent nses (#73 — "fully qualify" docs are the workaround) | eval/sci env | tooling | OPEN — related M4 |
| M22 | Server registry legacy dual-shape snapshot reader | server/registry.clj:470 | tooling | OPEN (trivial) |
| M23 | `seon.repl/parse-forms` compat re-export | repl.cljs:59 | tooling | OPEN (trivial delete) |
| C19 | `seon.eval/clip-result-body` — one more inline clip, char-denominated marker ("… +N chars clipped") not on C2's helper | eval.cljs:~2580 | tooling | OPEN — C2 sibling, found during the sweep |
| C20 | `seon.repl` parse-entry envelope is bare-keyed (`:kind`/`:ok?`/`:source`/`:error`) | repl internals; consumer eval.cljs read-entry branch | tooling | OPEN — C3 sibling, owned by seon.repl |
| C21 | Bare-keyed internal opts maps around the eval path: `record-eval!` opts (`:eval-id`/`:result`/`:ns`/…), `seon.eval/eval` opts (`:ns`/`:analyze-deps?`/`:timeout-ms`), client `load-error->log` `{:error :stack}` | eval.cljs, client.cljs | tooling | OPEN — C3 residue (the result envelope itself is fixed) |
| C22 | JVM-lane `truncate-value` copy (C2 fixed the pod lane; paused track keeps a chars copy) | db.clj:107 | tooling | OPEN — fold onto seon.ai.tokens (.cljc, already reachable) when the JVM track resumes |
| C14 | `build-tee-entities` mints `:seon.fn`/`:seon.ns` rows for defs in TRANSIENT nses (cljs.user scratch leaks into the program graph; `transient-ns-syms` guards only the requires-tee) | eval.cljs:~2099 | tooling | OPEN — one-line gate but a design call first: do scratch defs deserve persistence? |
| C15 | Server db-name demux wart: ambient path tags events WITH the leading colon, registry-resolved path strips it (docstring claims colon-free) — the two server paths disagree for a registry-routed subscriber | seon.server.wire/-main, store/config-for | tooling | FIXED `8a035be9`+`7ac63a0c` (cluster build): `-main` opens the ambient conn THROUGH the registry (one open path, colon-free label everywhere), db-name = CLUSTER NAME on both sides (`seon.store.wire/cluster-name`, `bin/seon --db-name`) — live-verified: feed logs `db acme`/`db probe1`, no socket artifact |
| C16 | `bin/seon restart pod` right after `restart wire-server` races writer warmup → pod fail-loud-exits, needs a second start | bin/seon | tooling | FIXED `7ac63a0c` (cluster build): `cluster create` ready-gates the wire-server (real socket check) before spawning `pod-<name>`; `start all`/`cluster reset` already gated. Bare `restart pod` after `restart wire-server` remains caller-sequenced (use `start all`) |
| C18 | Wire timeout/backoff VALUES are code constants (rpc 5000, replay 30000, ping 2000/500, transact-timeout-ms, 2s reconnect) — honest tunables belong at the config edge per owner triage; semantics already defined/fail-loud | store/wire.cljs:160,252, wire_node.cljs:113,336 | tooling → fold into eval lane's cluster build (same layer) | FIXED (sha pending) — triaged as STRUCTURAL constants, not config tunables (values justified by mechanism — op payload size, boot budgets, event-loop-alive semantics; nobody tunes them per cluster): ONE "wire timing" block in `seon.store.internal.wire-node` (rpc-tick/default-rpc/replay/ping ×3/ensure-db/transact/feed-reconnect), every former literal in wire.cljs + wire_node.cljs now references it (incl. the previously-uncited ensure-db 15000 and the "reconnecting in 2s"/"~10s" prose duplicates); grep-proven zero inline `:timeout-ms <n>`/`setTimeout … <n>` in either file |
| C23 | Injectable-key semantic collisions: ANY request schema declaring an OPTIONAL injectable key (`:seon.agent/id`/`:seon.db/db`/`:seon.render/at`) with a non-"me"/non-current meaning gets the caller's context resolved in silently. `start!`/`delegate!` fixed (`9892f407` — child-id slot removed); no audit yet of the remaining request schemas | seon.instrument/injectables ∩ every `register!`d request map | tooling | OPEN — one grep-audit unit: for each optional declared injectable key, confirm it MEANS "me/now/current db" |
| C24 | `changed-defs` body-redef rescue covers only a single top-level `(defn …)` source (the strict-persistence gate). A body-only redef inside a multi-form batch entry or via `(def f (fn …))` is still digest-invisible (stale `:seon.fn/source` for SCI). Root option: make `analyzer-info/var-digest` body-sensitive without meta-churn false-positives | eval.cljs `changed-defs` / analyzer_info.cljs `var-digest` | tooling | OPEN (residue of the `c5d6f985` fix; the per-form parser makes single-defn the common case) |
| C25 | Twins auto-run fn is invoked TWICE per render (once per view — `render-fn-block-ai` + `render-fn-block-html` each call `run-render-fn`). Pure over the frozen db so correct; ~2× SCI cost per fn per render. Memoize per (sym, basis-t) render if drives show it hot — measure first | agent/ctx/render_fns.cljs `run-render-fn` | tooling | OPEN (perf note, not correctness) |
| C26 | Agents keep misreading `db/query` FIND-TUPLE results as entity maps (`(filter :attr tuples)` → silent ()); drive-observed: the subs-tile agent burned a 27-turn run on it. A context/teaching gap (the compact card shows the spec but not the tuple shape), possibly a render affordance (`:find (pull …) .` collection teaching) | agent-facing db/query teaching (system-text / datahike skill / compact card) | eval lane (context content) | OPEN — drive evidence in the auto-run unit report 2026-07-02 |
| F1 | Datahike fork: 2 pre-existing planner-ON failures (silent-`#{}` class; pod always runs planner) | fork query engine | tooling | OPEN — [[datahike-planner-on-preexisting-failures]] |
| C8 | Two corpus linters: seon.warn (pod) vs seon.dev.compliance (JVM) | warn.cljs / dev/compliance.clj | tooling | DEFERRED by owner — merge into seon.warn when JVM track retires |
| M5 | Dual compile worlds (4 heuristics cluster) | eval.cljs:615,782 | tooling | LEGITIMATE NOW / FIX-ROOT long-horizon (audit §5 — no fifth heuristic without reading that section) |

## Resolved (closure sha required)

| id | item | resolution |
|----|------|------------|
| C17 | Two turn-capture paths: always-on blob capture vs the gated `seon.debug` prompt.txt file tree (kept only for the gym driver) | `8a035be9` (WIP checkpoint: seon.debug + debug_test DELETED; driver + turn_capture_test migrated to `:seon.agent.turn/prompt-blob` reads) + `cf6607e2` (last residue: dead `:seon.agent.turn/prompt-file` attr retired from turn.cljs + client.cljs boot install). Live-proven on the running pod: gym `run-scenario!` prompt predicate passes via the blob read; the content-addressed blob file carries the marker; `logs/turns` gone, zero references |
| M2 | Seed-inside-agent-scope + origin-forge warn-only guard | `ad6b9955` — origin stamped at boundary (derive-don't-claim), seed runs outside agent scope, guard DELETED (forgery impossible > enforced) |
| M7 | `skip-syms` hardcoded exemption set | `59624a9e` — def deleted; computed `async-unwrappable?` predicate (shape, not names). Known delta: eval/mem-db lost input-only wrap (documented) |
| M11 | tx-feed poll pump + rpc timer | rpc-timer fix `d5335667` (peer); pub-socket push migration + transact commit-or-not semantics `a24b172f` — poll pump DELETED, since-t replay via one `replay-tx` op, timeout rejections carry `:seon.store.wire/committed?`; live-proven (gap replay across SIGSTOP+server restart; both timeout branches; 9 min settled, zero failures) |
| C5 | `warn-on-seed-origin-forge!` vestigial guard | = M2, `ad6b9955` |
| C6 | Skills corpus dual home (.claude symlinks → seon-skills) | `68d73395` split (two audiences by owner ruling) + `21be639e` agent-perspective rewrite |
| C7 | Dev hook cwd-relative litter | `8185459f` — repo-root resolution; 57 strays cleaned |
| M3 | SCI silent unbounded fallback | `6f96b024` — SCI cage bounds every my.* render fn, fail-loud, unbounded fallback GONE (eval lane) |
| C12 | `~/src/datahike` duplicate checkout | verified-no-unique-work, deleted; bundle at tmp/datahike-salvage/; fork workflow rules in memory |
| F0 | Fork planner collect-field silent `#{}` | fork `da257d38` + seon `41c1b9b2`, live-proven; skills caveats removed `556fa779` |
| H1 | Dormant replica-peer harness pinning dead polling ops | `03e1ce3e`+`a74e3e88` — Stage A/B harness (replica-peer/probe nses, probe/ drivers, 2 deps.edn aliases, 2 shadow builds) DELETED and the subscribe-tx/next-tx-event/unsubscribe-tx handle-ops + bounded-queue machinery it was the last consumer of removed; pub-socket push + `replay-tx` is the ONE feed; findings preserved in datahike-native-replica-2026-06-09.md; recoverable at `2ef14d1276` |
| C11 | `transient-ns-syms` restated ns defs | `dada1ff9` — set derived from the single defs; value-identical, live-verified |
| C13 | "Redundant" per-entry origin claims in eval.cljs | CLOSED AS DESIGNED (`dada1ff9` report) — live-FALSIFIED: the `:agent` claim is a load-bearing narrowing inside `run-turn!`'s `:system` context; removal flips every eval tx to `:system`. Do NOT retry |
| C1 | `SEON_EMBED` read ×3 under 3 names | `e6075961` — `seon.agent.turn/embed-retrieval-on?` is the one pod-side reader; retrieval's private copy deleted, render/system inline read routed through it; live-verified consistent |
| C2 | pr-str+clip helpers ×8 (chars, violating tokens rule) | `9a56d2bd` — ONE bounded-print in `seon.ai.tokens` (promoted .cljc): `clip-str` (string + TOKEN budget [+ marker fn]) / `bounded-pr-str`; 7 copies deleted, `seon.agent.ctx/clip-or-full` keeps its char-cap + `full?` contract but delegates the cut and its loud markers now speak tokens. Residue rows: C19 (clip-result-body), C22 (JVM copy) |
| C4 | Private `env*` readers ×2 + inline lookup | `163d952b` — all three providers call `seon.platform/env-val`; live-verified key resolution |
| C9 | Worker bootstrap-cache helpers copied "by design" | `d71ab670` — shared LEAF `seon.eval.bootstrap-cache` (no db/schema/pod deps); both copies deleted; worker bundle unchanged (98 files) + wire-proven |
| C10 | Dead `:seon.agent.ctx/fn` attr mention + inert-removal comment residue | `dab5a3a1` — deleted outright (client.cljs, eval.cljs, transcript.cljs) |
| C3 | Eval envelope bare `:ok`/`:error` (owner ruled → namespaced) | envelope is now `{:seon.eval/ok? :seon.eval/value :seon.eval/ending-ns}` / `{:seon.eval/ok? false :seon/error <error-map>}` / `{:seon.eval/ok? false :seon.eval/pending-promise <p>}` across eval.cljs + client.cljs + worker_eval (internal; JSON wire keys unchanged — third-party boundary) + all test consumers; live-proven producer + full agent turn + worker pipe; suite 955/4403 green. `5a3af643` |

## Legitimate — listed, rationale'd, re-audited periodically

M12 auto-await (`maybe-await-value`) · M13 `!next-budget-ms` (caveat noted) ·
M14 repair layer · M15 `result/<id>` stash (three-tier rule) · M16
`agent-authored-sym?` prefix routing (owner-settled) · M17 kill-switches
(behavior forks — audit periodically) · M18 instrument degrade rows · M19
root-id shape exemption · M20 capability gates · M21 `race-timeout`
(compensated by M3's fix) · M24 fail-soft catch density (doctrine — but it is
the substrate that lets M3-class silent degradation hide; every new
`catch :default` must surface a `:seon/error`, never continue silently).
C-legit: SSRF hostname denylist (security constant) · spec-constant literals.
