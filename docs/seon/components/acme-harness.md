---
type: component
status: active
tags: [component, agent]
---

# Acme third-party harness — an isolated downstream-consumer cluster

"Acme" is the codename for a third-party consumer that builds a product on
Seon. NEVER use a real product name anywhere in the repo — see the naming
rule in [[../../../CLAUDE.md]].

`bin/acme` boots a **fully isolated** second cluster (its own pod +
wire-server) alongside the live seon dev cluster, so you can reproduce and
fix consumer-facing bugs against a real third-party shape **without ever
touching the live deployment**. The downstream's own code lives in `acme/`
(a separate `deps.edn` project) and is compiled into the acme pod bundle via
`SEON_EXTRA_SRC` (Path B — [[extra-src]]).

## TL;DR — boot it

```bash
# optional: export GEMINI_API_KEY=...   (embeddings KNN)
# optional: export SEON_AI_PROVIDER=deepseek DEEPSEEK_API_KEY=...  (live drives)
bin/acme up                 # build + start wire-server + pod + status (one shot)
bin/acme status
bin/acme tail pod
bin/acme down               # stop the whole cluster (pod + wire-server)
```

`bin/acme up` is the operator one-liner: it runs `build` (compile
`:acme-client` → `out-acme/client/main.js`), then `start wire-server` (which
BLOCKS until the writer's req socket is ACCEPTING — the pod boot is gated on
it), then `start pod`, then `status`. The blocking wire-server start is why
`up` is race-free; starting `wire-server` and `pod` back-to-back by hand in
one shell line CAN race (the pod's 5-attempt ping fails in ~10s if the socket
isn't up yet — it then exits, see logs/acme/pod.log).

The granular commands still work if you want them:

```bash
bin/acme build              # one-off compile :acme-client -> out-acme/client/main.js
bin/acme start wire-server  # JVM writer FIRST (sole writer; pod boot is gated on it)
bin/acme start pod          # Node pod on http://127.0.0.1:7980
bin/acme restart pod        # rebuild loop: bin/acme build && bin/acme restart pod
```

Everything `bin/seon` does, `bin/acme` does for the acme cluster (it just
exports the isolated env block, then `exec bin/seon "$@"`) — `start`,
`stop <name>`, `status`, `tail`, `restart`, `cluster reset`. `bin/acme cluster
reset acme` now bounces ACME's own pod + wire-server: the supervisor gates the
process restart on `store_dir == $SEON_CLUSTER_DIR/store` (the cluster THIS
invocation manages), not the literal name `default`. It wipes
`data/clusters/acme/store` and race-safely restarts acme (wait_ready's the
wire-server before the pod), and can never touch the live default cluster. PLUS
the three cluster-level convenience commands `bin/acme` adds on top:

| command | what it does |
|---|---|
| `bin/acme up` | `build` + `start wire-server` + `start pod` + `status` |
| `bin/acme down` | stop the whole cluster (pod first, then wire-server) |
| `bin/acme stop` (no arg) | stop the whole cluster (`bin/seon stop` alone errors "needs a process name"); `bin/acme stop pod` still stops just one |

## Isolation — zero overlap with the live cluster

| | live default | **acme** |
|---|---|---|
| pod HTTP | 7890 | **7980** |
| wire-server socket REPL | 7891 | **7981** |
| store | `data/clusters/default` | **`data/clusters/acme`** |
| req/pub sockets | `tmp/seon-cluster-default-*.sock` | **`tmp/acme-cluster-*.sock`** |
| supervisor pid/lock | `tmp/proc` | **`tmp/proc-acme`** |
| pod port file | `tmp/seon-port` | **`tmp/seon-port-acme`** |
| logs | `logs/` | **`logs/acme/`** |
| pod bundle | `out/client/main.js` | **`out-acme/client/main.js`** |

Proven isolated: the acme pod connects to `data/clusters/acme` (not the live
store), and a default-store query shows zero acme rows. The live cluster's
PIDs are unchanged across an acme boot.

**Safety rule (hard):** the live default cluster is a working deployment.
NEVER `bin/seon start/stop/restart` it or write to its store. Use **only**
`bin/acme` for the acme cluster.

## How it works (the seams, all existing — no fork)

- `bin/acme` exports the isolated env + the downstream wiring, then delegates
  to `bin/seon`. The env knobs (all read by `bin/seon`): `SEON_PORT`,
  `SEON_WRITER_REPL_PORT`, `SEON_CLUSTER_DIR`, `SEON_REQ_SOCK`,
  `SEON_PUB_SOCK`, `SEON_PROC_DIR`, `SEON_LOG_DIR`, `SEON_PORT_FILE`,
  `SEON_CLIENT_OUT`, `SEON_EXTRA_SRC`, `SEON_EXTRA_PRELOAD`,
  `SEON_BRAND_NAME/TAGLINE/CSS`, `SEON_EMBED`. All default to today's values
  when unset, so seon's own usage is byte-identical.
- **Config = minimal overrides (owner directive 2026-07-02).**
  `SEON_CONFIG=config/acme.edn`, and that file is now `#merge [#include
  "system.edn" {…}]` — the default cluster's manifest verbatim plus ONE
  delta: the agent toolbelt gains `acme.helpers`/`acme.notes` (the
  `SEON_EXTRA_SRC` namespaces, which must be in `:seon.eval/home-requires`
  or their cards never render). The old demo divergences (max skill corpus,
  custom block tree + transcript decay, toolkit trims) are GONE: per-cluster
  configurability is proven and documented, and while acme is the live
  testing/dev harness those divergences cost fidelity (the `my.kb` trim
  blocker was exactly such a case). This holds until acme stops being the
  live-testing harness; system.edn edits flow to acme automatically via the
  `#include`.
- `SEON_EXTRA_SRC=acme/` is injected as a `:local/root` dep so `acme/src`
  joins the build classpath; `acme.*` compiles INTO the pod bundle.
- The acme pod runs its OWN bundle (`out-acme/client/main.js`) from the
  `:acme-client` shadow build, whose `:preloads` include `acme.pod` — so the
  one-off `bin/acme build` is a COMPILE, not a second watch (two shadow watch
  servers would collide on nREPL :7889).
- `acme.pod` (the `SEON_EXTRA_PRELOAD` entry ns) runs the registration
  `(reset! seon.client/!extra-core-vars …)` that makes the downstream's own
  source boot-index. **This is the crux of the indexing path.**

## The `acme/` overlay — what each file is for

| file | role |
|---|---|
| `acme/deps.edn` | the consumer's own project (`{:paths ["src"]}` + their deps) |
| `acme/src/acme/pod.cljs` | entry ns; requires the surface + runs the `(reset! …)` |
| `acme/src/acme/widget.cljs` | specced product fn + live-tiles (`dash`, plus `broken-tile` to exercise the error-response override) |
| `acme/src/acme/helpers.cljs` | a specced fn AND an UNSPECCED helper (`format-count`) the tile calls |
| `acme/src/acme/notes.cljs` | an ENTIRELY-UNSPECCED ns — proves a downstream ns owning ZERO specced fns still gets a `:seon.ns` row + shows in context (the full-surface indexing case) |
| `acme/src/acme/brand.cljs` | a second indexed ns (product copy) |
| `acme/src/acme/overrides.cljs` | function overrides via `set!` (no seon-src edit) |
| `acme/branding/acme.css` | custom CSS via `SEON_BRAND_CSS` |

## What it exercises

- **Full-surface source indexing + context** — `acme.*` namespaces boot-index
  into the acme store and render into agent context. The indexing is now the
  FULL surface: **every** `acme.*` ns gets a `:seon.ns` row (including the
  entirely-unspecced `acme.notes`) and **every** public fn gets a `:seon.fn`
  row — specced (`acme.widget/set-location!`, `acme.brand/tagline`) AND
  unspecced (`acme.helpers/format-count`, `acme.notes/slugify`,
  `acme.notes/note-line`, `acme.notes/word-count`). This is the client.cljs
  fix: the indexer derives the downstream ns set from the SEON_EXTRA_SRC file
  set, not from the specced-only `!extra-core-vars` (the old behavior gave an
  all-unspecced ns no row at all — silently invisible). Verified on a clean
  store: 6 acme nses + 8 acme fns indexed; a real DeepSeek-driven turn's
  `<namespace name="acme.notes">` block carries all three unspecced fns.
  Reproduce the *silent-failure* bug by commenting out the `(reset! …)` in
  `acme/src/acme/pod.cljs` and rebuilding: zero acme rows index and a loud
  boot WARN fires.
- **Live tile via SCI** — `acme.widget/dash` is a specced tile that calls the
  unspecced `acme.helpers/format-count` through a required-ns alias. It
  renders under the SCI-bounded path (proven: "N installed schemas", no
  "could not run under SCI bounding" warn).
- **Function overrides** — `acme.overrides` `set!`s
  `seon.render.live-tile/error-response` (and is the place to override
  `seon.ctx/core-default-ctx` to inject sections) — the universal
  extend-without-fork mechanism.
- **CSS / branding** — `acme/branding/acme.css` + `SEON_BRAND_NAME/TAGLINE`.

## Testing a seon fix in the acme env (the fix→verify loop)

The acme pod runs `out-acme/client/main.js`, which is NOT watched. So a change
to seon's own `src/seon/*.cljs` reaches the LIVE pod (via the running
cljs-watch) but does NOT reach the acme pod automatically. To verify a seon
fix in acme:

```bash
# edit src/seon/...           (the fix)
bin/acme build                # rebuild out-acme with the fix
bin/acme restart pod          # acme pod picks it up
# verify via HTTP 7980 / the wire REPL 7981
```

## Inspecting / driving the acme pod

- **HTTP:** there is NO `/agents` route (removed in the db->routes reitit
  cutover; an unmatched path 302s to `/`). The real surface is the seeded
  core routes — `curl -s 127.0.0.1:7980/` (root dashboard),
  `…/agent/<id>` (agent page), `…/agent/<id>/feed` (SSE) — plus the
  operator datom browser `curl -s 127.0.0.1:7980/data` (verified live:
  HTTP 200). List the seeded routes from the wire REPL:
  `[?e :seon.route/pattern]` datoms (verified live 2026-07-02: `/`,
  `/agent/{id}`, `/agent/{id}/call`, `/agent/{id}/feed`).
- **Wire-server REPL:** `nc -U`-style on `127.0.0.1:7981` (the loopback socket
  REPL) for writer-side queries.
- **NOT MCP:** the `mcp__seon_cljs__*` tools only see the live `:client`
  build's runtime, not `:acme-client`. Drive the acme pod via HTTP + the wire
  REPL, or add a transient `:acme-client` watch on a non-7889 nREPL port for
  deep interactive debugging.
- **Live agent drives:** export `SEON_AI_PROVIDER=deepseek` +
  `DEEPSEEK_API_KEY` (cheap, pre-authorized) to drive turns in the acme pod.

## Known warts (non-blocking)

- `bin/acme start wire-server && bin/acme start pod` on ONE shell line can
  race (the pod ping-fails in ~10s and exits if the writer socket isn't
  accepting yet). Use `bin/acme up` — it blocks on wire-server readiness
  between the two starts. After a bare `start pod` always poll
  `curl -fsS -o /dev/null 127.0.0.1:7980/` until it exits 0 — the same
  probe `bin/seon`'s `ready_check` uses (`-f` passes on 200 AND on the
  302 an unmatched path gets, so it won't false-fail; the start command
  returns before the pod is actually ready).
- Acme's 7980/7981 were chosen because 7990/7991 collided with another
  deployment on the dev machine; override with `SEON_PORT=… bin/acme …` if
  needed.

## Key files

- `bin/acme`, `bin/seon` (env-parametrized supervisor)
- `shadow-cljs.edn` (`:acme-client` build → `out-acme/`)
- `acme/**`, `docs/prds/agent-runtime/acme-thirdparty-harness-2026-06-22.md`
  (the root-cause + acceptance-check PRD), [[extra-src]]
