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
| M4 | SCI env rebuild re-parses source for aliases (requires not stored structurally) | render/sci.cljs:230 | eval | verify closure vs `6f96b024` (M3's fix) — confirm alias root-fix included or keep open |
| M5b | Router serves a cached route projection, no tx-listener self-heal | web/router.cljs, serve.cljs:905 | eval (queued behind solve-path) | QUEUED |
| M6 | `async-fn?` ctor-name detection + once-per-process instrument gate | instrument.cljc:400 | tooling | OPEN |
| M8 | `core-ns-set` replay-skip + `fn-less-compiled-roots #{"my.kb"}` name sets (provenance, not names, should decide) | client.cljs:1067,2441 | tooling | OPEN — hand-list class |
| M9 | `!solve-deps` / `!create-agent-fn` require-cycle injection atoms | serve.cljs:110, render/sci.cljs:493 | tooling | OPEN (small) |
| M10 | Home-ns aliases unresolvable in agent nses (#73 — "fully qualify" docs are the workaround) | eval/sci env | tooling | OPEN — related M4 |
| M22 | Server registry legacy dual-shape snapshot reader | server/registry.clj:470 | tooling | OPEN (trivial) |
| M23 | `seon.repl/parse-forms` compat re-export | repl.cljs:59 | tooling | OPEN (trivial delete) |
| C1 | `SEON_EMBED` read ×3 under 3 names | turn.cljs:160, diffusion/retrieval.cljs:592, render/system.cljs:140 | tooling | QUEUED — unification sweep |
| C2 | pr-str+clip helpers ×8 (chars, violating tokens rule) | 8 files | tooling | QUEUED — unification sweep |
| C3 | Eval envelope bare `:ok`/`:error` vs namespaced envelope (owner ruled: → `:seon.eval/*`) | eval.cljs, client.cljs, worker_eval.cljs | tooling | QUEUED — unification sweep |
| C4 | Private `env*` readers ×2 + inline lookup vs `seon.platform/env-val` | ai/{diffusiongemma,openai_compat,anthropic}.cljs | tooling | QUEUED — unification sweep |
| C9 | Worker bootstrap-cache helpers copied from seon.eval "by design" | worker_eval.cljs:31 | tooling | QUEUED — unification sweep (shared leaf ns) |
| C10 | Dead `:seon.agent.ctx/fn` attr + inert-comment residue | client.cljs:426, eval.cljs:1373 | tooling | QUEUED — unification sweep |
| C14 | `build-tee-entities` mints `:seon.fn`/`:seon.ns` rows for defs in TRANSIENT nses (cljs.user scratch leaks into the program graph; `transient-ns-syms` guards only the requires-tee) | eval.cljs:~2099 | tooling | OPEN — one-line gate but a design call first: do scratch defs deserve persistence? |
| C15 | Server db-name demux wart: ambient path tags events WITH the leading colon, registry-resolved path strips it (docstring claims colon-free) — the two server paths disagree for a registry-routed subscriber | seon.server.wire/-main, store/config-for | tooling | OPEN (small; pod path self-consistent today) |
| C16 | `bin/seon restart pod` right after `restart wire-server` races writer warmup → pod fail-loud-exits, needs a second start | bin/seon | tooling | OPEN (supervisor; small) |
| C17 | Two turn-capture paths: always-on blob capture (the observability.md target — `rendered-as-of` + prompt/reply blob refs) vs the gated `seon.debug` prompt.txt/response.txt file tree, kept ONLY because the gym driver consumes it (`debug/set-override! :on` + reads `<debug-dir>/…/prompt.txt`; driver was in-flight/do-not-touch when capture landed 2026-07-02) | src/seon/debug.cljs, src/seon/agent/turn.cljs, test/seon/gym/driver.cljs:1808 | tooling | OPEN — migrate the gym driver to read `:seon.agent.turn/prompt-blob`, then DELETE the seon.debug file tree (blob capture subsumes it) |
| C18 | Wire timeout/backoff VALUES are code constants (rpc 5000, replay 30000, ping 2000/500, transact-timeout-ms, 2s reconnect) — honest tunables belong at the config edge per owner triage; semantics already defined/fail-loud | store/wire.cljs:160,252, wire_node.cljs:113,336 | tooling → fold into eval lane's cluster build (same layer) | OPEN (small) |
| F1 | Datahike fork: 2 pre-existing planner-ON failures (silent-`#{}` class; pod always runs planner) | fork query engine | tooling | OPEN — [[datahike-planner-on-preexisting-failures]] |
| C8 | Two corpus linters: seon.warn (pod) vs seon.dev.compliance (JVM) | warn.cljs / dev/compliance.clj | tooling | DEFERRED by owner — merge into seon.warn when JVM track retires |
| M5 | Dual compile worlds (4 heuristics cluster) | eval.cljs:615,782 | tooling | LEGITIMATE NOW / FIX-ROOT long-horizon (audit §5 — no fifth heuristic without reading that section) |

## Resolved (closure sha required)

| id | item | resolution |
|----|------|------------|
| M2 | Seed-inside-agent-scope + origin-forge warn-only guard | `ad6b9955` — origin stamped at boundary (derive-don't-claim), seed runs outside agent scope, guard DELETED (forgery impossible > enforced) |
| M7 | `skip-syms` hardcoded exemption set | `59624a9e` — def deleted; computed `async-unwrappable?` predicate (shape, not names). Known delta: eval/mem-db lost input-only wrap (documented) |
| M11 | tx-feed poll pump + rpc timer | rpc-timer fix `d5335667` (peer); pub-socket push migration + transact commit-or-not semantics `a24b172f` — poll pump DELETED, since-t replay via one `replay-tx` op, timeout rejections carry `:seon.store.wire/committed?`; live-proven (gap replay across SIGSTOP+server restart; both timeout branches; 9 min settled, zero failures) |
| C5 | `warn-on-seed-origin-forge!` vestigial guard | = M2, `ad6b9955` |
| C6 | Skills corpus dual home (.claude symlinks → seon-skills) | `68d73395` split (two audiences by owner ruling) + `21be639e` agent-perspective rewrite |
| C7 | Dev hook cwd-relative litter | `8185459f` — repo-root resolution; 57 strays cleaned |
| M3 | SCI silent unbounded fallback | `6f96b024` — SCI cage bounds every my.* render fn, fail-loud, unbounded fallback GONE (eval lane) |
| C12 | `~/src/datahike` duplicate checkout | verified-no-unique-work, deleted; bundle at tmp/datahike-salvage/; fork workflow rules in memory |
| F0 | Fork planner collect-field silent `#{}` | fork `da257d38` + seon `41c1b9b2`, live-proven; skills caveats removed `556fa779` |
| C11 | `transient-ns-syms` restated ns defs | `dada1ff9` — set derived from the single defs; value-identical, live-verified |
| C13 | "Redundant" per-entry origin claims in eval.cljs | CLOSED AS DESIGNED (`dada1ff9` report) — live-FALSIFIED: the `:agent` claim is a load-bearing narrowing inside `run-turn!`'s `:system` context; removal flips every eval tx to `:system`. Do NOT retry |

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
