---
type: issue
status: open
severity: critical
created: 2026-09-25
tags: [issue, config, provider, paid-calls, tests]
---

# A sparse config apply drops the no-provider overlay and calls a paid provider

**What happened (2026-09-25, lane fix-turn-hang-opus):** a regression set the episode limit with `seon.config/apply!` and a
one-key manifest inside a test fixture. The apply replaced the fixture's whole manifest, so provider settings fell back to the
shipped DeepSeek endpoint; `DEEPSEEK_API_KEY` is set in the JVM, and the probe made 2 real paid requests. Root's earlier serial
run of `seon.cluster.turn-test/spending-the-last-episode-run-is-a-loud-core-fault` (tmp/orchestrator/verify-2055.out.d/017.txt)
very likely made paid calls too. Default itself stays `:seon.config.ai/no-provider true`.

**Law broken:** standing owner rule — no paid agent calls; default and development run no-provider (config layers: schema
default → ordered overlays, `config/development.edn` carries `no-provider true` → environment → cluster facts → per-agent).

**Owner fix required:** an apply must layer onto the overlays, never replace them; a partial manifest changes only its keys.
No test world may resolve a paid provider unless the test explicitly supplies one as a value. Regression: a sparse apply in a
fixture keeps `no-provider`, and a provider call from that world is refused before any network I/O.
