---
type: issue
status: open
severity: correctness
created: 2026-09-25
tags: [issue, config, provider, paid-calls, tests]
---

# A sparse config apply drops the no-provider overlay and calls a paid provider

**What happened (2026-09-25, lane fix-turn-hang-opus):** a regression set the episode limit with `seon.config/apply!` and a
one-key manifest inside a test fixture. The apply replaced the fixture's whole manifest, so provider settings fell back to the
shipped DeepSeek endpoint; `DEEPSEEK_API_KEY` is set in the JVM, and the probe made 2 real paid requests. Root's earlier serial
run of `seon.cluster.turn-test/spending-the-last-episode-run-is-a-loud-core-fault` (tmp/orchestrator/verify-2055.out.d/017.txt)
very likely made paid calls too. Default itself stays `:seon.config.ai/no-provider true`.

**Owner 2026-09-25:** the calls are cheap and acceptable; fix because a test world silently resolving a provider is wrong, not as an emergency.

**Rule:** standing owner rule — no paid agent calls; default and development run no-provider (config layers: schema
default → ordered overlays, `config/development.edn` carries `no-provider true` → environment → cluster facts → per-agent).

**Owner fix required:** an apply must layer onto the overlays, never replace them; a partial manifest changes only its keys.
No test world may resolve a paid provider unless the test explicitly supplies one as a value. Regression: a sparse apply in a
fixture keeps `no-provider`, and a provider call from that world is refused before any network I/O.

## Status (2026-09-25, lane fix-no-provider-overlay-opus)

- **Cause, verified at the REPL.** `seon.config/apply!` compiled `defaults → request manifest → environment` and
  exact-reconciled. It never read what the cluster already held. The development overlay entered only through boot's
  own compile (`boot/start!`) and `init --dev`. A one-key compile on the live JVM resolved
  `https://api.deepseek.com/chat/completions` with `DEEPSEEK_API_KEY` and no `no-provider`.
- **Fixed at the owner.** The row now records `:seon.config/overlay-keys`, the dials that a layer above the shipped
  defaults decided. `apply!` holds those decisions underneath its own manifest, so an apply changes only its keys.
  Applying the shipped document is the one full reset. Regression:
  `seon.config-test/a-sparse-apply-changes-only-its-keys-and-keeps-no-provider`.
- **Still open, needs a ruling.** Test worlds still compile only the shipped defaults (option A, 3a7ffa69c), so a
  world that never names a provider resolves the shipped paid one. See the landing note
  `docs/prds/agent-platform/landing/lane-fix-no-provider-overlay-2026-09-25.md`.
- **Still open, defence in depth.** `docs/seon/issues/provider-completion-ignores-the-no-provider-request-attribute.md`.
