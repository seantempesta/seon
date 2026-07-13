---
type: component
status: active
tags: [component, agent]
---

# Capability gates — host-owned env grants for agent tools

Every agent capability with host-side blast radius (shell, web fetch,
filesystem, embeddings) is gated by a **host-owned env var, default-deny in
code**. Nothing inside the pod can grant a capability — the gates are read
from the process env (most of them live, per call), so the HOST (the launcher
that starts the pod) is the sole granting authority. Denied calls return a
guiding `ok?`-false envelope (errors-as-values), never a throw.

**Code defaults are conservative: unset = DENY.** Our own deployments grant
via the supervisor env seam (below); a downstream consumer that embeds seon
without `bin/seon` keeps the deny posture until their launcher opts in.

## The gate table

| Var | Read at (live?) | Values | Default (unset) | Gates |
|---|---|---|---|---|
| `SEON_SHELL` | `seon.agent.shell.internal/granted?` — live, every call | any non-blank value but `"0"` grants | **DENY** | `seon.agent.shell/run` + `py-run` (all shell execution) |
| `SEON_WEB` | `seon.agent.web.internal/granted?` — live, every call | any value but `"0"` grants | **DENY** | `seon.agent.web/fetch` availability (the master on/off gate; reachability is the config policy below) |
| `SEON_FS_ROOT` | `seon.agent.fs.internal/env-bootstrap` — ns load | path-list (`:`-split, like `$PATH`) | **DENY** (no allowed roots) | fs read/write/search + the shell `cwd` gate (shell delegates to fs) |
| `SEON_FS_READ_ONLY` | `seon.agent.fs.internal` — ns load | `"1"` = read-only | writable | `write-file` on the granted roots |
| `SEON_FS_LOCK` | `seon.agent.fs.internal/locked?` — live | any value but `"0"` locks | unlocked | makes `(seon.agent.fs/configure! …)` a no-op error |
| `SEON_EMBED` | `seon.embed/embed-feature-enabled?` (wire-server JVM) + pod reads (`turn`, `render.system`, `diffusion.retrieval`) | **PRESENCE** — ANY value (even `""`/`"0"`) = ON | **OFF** | the whole embedding-retrieval feature (index, backfill, per-turn semantic recall) |

Related feature/kill switches (same env seam, not capability grants):

| Var | Values | Default | Controls |
|---|---|---|---|
| `SEON_INSTRUMENT` | `0`/`false`/`off`/`no` disables | ON | runtime Malli instrumentation (kill-switch only) |
| `SEON_SOUL` | `false`/`0`/`off`/`no` disables; `SEON_SOUL_FILE` overrides path | ON when SOUL.md exists | the SOUL.md identity context block |
| `SEON_CANVAS_SCI` | `"0"` disables | ON | layer-1 SCI bounding of agent canvas fns |
| `SEON_RENDER_STRICT` | `1`/`true`/`on`/`yes` enables | OFF | fail-loud render dial |

## Web-access policy — reachability is CONFIG, not env

`SEON_WEB` (above) is only the master on/off gate. WHICH targets a granted
`seon.agent.web/fetch` can reach is a host-owned CONFIG policy — the cluster
manifest's `:seon.config/web` key, read via `seon.config/web-policy`. This
UNIFIES the two former web restrictions (the private-range SSRF guard + the
domain allowlist) into one `:seon.agent.web/policy` mode:

| Mode | Reaches |
|---|---|
| `:open` | everything — public AND private/loopback |
| `:public-only` | public only — blocks loopback/RFC-1918/link-local/ULA on every redirect hop (the SSRF-safe posture) |
| `:allowlist` | only hosts matching `:seon.agent.web/allowed-domains` (exact host or subdomain; an IP literal matches itself). A private host is reachable IFF it is explicitly listed — private membership rides the list, it is not special-cased. An empty list reaches nowhere. |

- **Code/schema default (no config):** `:public-only` — a downstream inheritor
  is never SSRF-open by accident.
- **The shipped clusters:** `config/system.edn` and `config/acme.edn` both set
  `:seon.agent.web/policy :open` — zero friction, and the web_fetch bench's
  loopback fixtures work with no special grant.
- **Host-owned:** the agent READS its policy via `(seon.agent.web/grants)` but
  nothing in the pod can widen it (there is no runtime `configure!`).
- The retired `SEON_WEB_ALLOW_PRIVATE` / `SEON_WEB_DOMAINS` / `SEON_WEB_LOCK`
  env vars are GONE — config over env, env never shadows config.

## Where granted today

- **`bin/seon` (the default dev cluster + anything it supervises):** the env
  block after the `.env` source exports `SEON_SHELL="${SEON_SHELL:-1}"` and
  `SEON_WEB="${SEON_WEB:-1}"` — shell + web GRANTED by default for our
  dev/bench deployments. The pod command additionally sets
  `SEON_FS_ROOT=$SEON_ROOT SEON_FS_READ_ONLY=1` (durable read-only repo
  grant; shell `cwd` rides on it). `SEON_EMBED` is normalized (empty/`"0"` →
  unset, because the code gate is presence-based) and opted in via the
  gitignored `.env`.
- **`bin/acme` (the third-party harness):** exports the SAME grants in its
  own env block — deliberately through the public downstream seam (launcher
  env, zero `src/seon` edits), because acme doubles as the proof of
  downstream usage. `.env.acme` can override (e.g. `SEON_SHELL=0`).
- **Eval-suite bench clusters:** clone the acme pattern — their launcher
  exports the grants the same way (this satisfies the eval-suite design's
  precondition #1 on next pod start).
- **A fresh clone / downstream embed without our supervisors:** everything
  denied until the consumer's launcher sets the vars.

## How to override (per cluster, zero src edits)

- **Deny in our clusters:** `SEON_SHELL=0 bin/seon restart pod` (or put
  `SEON_SHELL=0` in the gitignored `.env` / `.env.acme`). Shell-set values
  win over the supervisor `${:-1}` defaults; `.env` values win too (sourced
  before the defaults are applied).
- **Shape web reachability:** this is CONFIG, not env — set
  `:seon.config/web {:seon.agent.web/policy :public-only}` (block internal/
  loopback) or `{:seon.agent.web/policy :allowlist :seon.agent.web/allowed-domains
  ["docs.example.com" "clojure.org"]}` in the cluster manifest
  (`config/system.edn` / `config/acme.edn`), then `bin/seon restart pod`. The
  SEON_WEB env var only gates whether web fetch is available at all. (See the
  web-access policy section below.)
- **Downstream consumer:** set the vars in YOUR launcher env before starting
  the pod. The gates are plain `process.env` reads — no seon config file or
  source edit is involved. `SEON_CONFIG` (the context manifest) is a separate
  seam and does NOT carry capability grants: gates are env-only by design
  (host-owned; the manifest is agent-context configuration the pod itself
  consumes).

Grants land on **pod start** for practical purposes: `SEON_SHELL`/`SEON_WEB`
are re-read live per call, but the pod inherits its env at spawn — so editing
`.env`/`bin/*` requires a pod restart (`bin/seon restart pod` /
`bin/acme restart pod`) to change the pod's environment. No rebuild needed.

## Live verification (one command, after the next pod restart)

```clojure
;; via the pod REPL / an agent eval — both must show granted:
(seon.agent.shell/grants)  ;; => {... :seon.agent.shell/granted? true ...}
(seon.agent.web/grants)    ;; => {... :seon.agent.web/enabled? true
                           ;;      :seon.agent.web/policy :open ...}
```

Or end-to-end: `(seon.agent.shell/run {:seon.agent.shell/command "pwd"
:seon.agent.shell/cwd "<repo root>"})` returns an `ok?`-true envelope instead
of the default-deny message.

## Design notes

- The gating pattern is the `seon.agent.fs` template (allowlist gating,
  errors-as-values envelope): every capability fn copies it exactly.
- `SEON_EMBED` is the odd one out — a PRESENCE gate (`some?`), not a
  value gate. The supervisors translate the operator convention (`0`/empty =
  off) into unset so both conventions agree. If it is ever changed to a value
  gate, that change belongs in `seon.embed` + the pod read sites together.
- The CLJS sandbox is NOT a security boundary; these gates catch LLM
  hallucinations. Real isolation is process boundaries + the wire capability
  surface.
