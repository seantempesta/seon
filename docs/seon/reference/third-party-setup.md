---
type: reference
status: active
tags: [reference, agent]
---

# Standing up Seon as a third party (no Seon source checkout)

This is the concrete operator guide for a downstream consumer ("Acme" is the
in-repo codename) who wants to run Seon as a product substrate WITHOUT forking
or even checking out Seon's `src/`. It ties together the four already-working
seams: the embedding-backed wire-server uberjar, the pod source OVERLAY via
`SEON_EXTRA_SRC`, function OVERRIDES via `set!`, and branding. The worked
example throughout is the in-repo `acme/` overlay, which is verified green —
see [[../components/acme-harness.md]] for booting it with `bin/acme`.

The shape: two long-running processes per cluster.

- **wire-server** — a JVM datahike WRITER (sole writer; embedding-backed). You
  ship this as a self-contained uberjar. It owns the durable store.
- **pod** — a Node process (Seon's CLJS runtime: agent loop, inspector UI). It
  forwards writes to the wire-server over a Unix socket and reads local lazy
  db values. Your own source is COMPILED INTO this pod bundle via the overlay.

---

## (a) Run the wire-server from the uberjar

The wire-server is the only piece you need from Seon's build. Produce the
self-contained jar once:

```bash
clojure -T:build writer-uber          # → target/seon-wire-server-standalone.jar
```

That jar carries datahike + the embedding stack; you run it with no Seon
checkout on the host. It needs **Java 22+** (the embedding KNN uses the
`jdk.incubator.vector` module) and the native-vector + heap flags:

```bash
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC -Xmx2g \
     -jar target/seon-wire-server-standalone.jar \
     --preflight
```

`--preflight` is a real gate: it validates the embedding environment and exits
with a DISTINCT non-zero code for the FIRST failing check (it does NOT start
the server), so a deploy script can branch on the code:

| exit | check | meaning |
|---|---|---|
| 0 | — | all checks pass |
| 10 | `:java-vector-absent` | Java < 22 / `jdk.incubator.vector` module missing |
| 11 | `:seon-embed-unset` | `SEON_EMBED` master switch not set |
| 12 | `:gemini-key-blank` | `GEMINI_API_KEY` unset/blank |
| 13 | `:embed-roundtrip` | `embed-text` did NOT return a 1536-length vector |
| 14 | `:knn-selftest` | install + one-row KNN top-1 was not the seeded row |

**#36 — key-unset still boots.** Exit 12 is a PREFLIGHT failure, not a boot
blocker: if you DROP `--preflight` and start the server with `GEMINI_API_KEY`
unset, the wire-server **boots and accepts writes normally** — it simply never
embeds any text (zero outbound Gemini calls; KNN returns no hits silently).
The Gemini client is lazy and key-optional. So embeddings are an opt-in
enhancement, not a hard dependency: run preflight in CI to catch a misconfig
loudly, but a key-less deployment is a valid (retrieval-disabled) mode.

To actually RUN the server, drop `--preflight` and pass the store + socket
args (these are exactly what `bin/seon`/`bin/acme` build for you):

```bash
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC -Xmx2g \
     -jar target/seon-wire-server-standalone.jar \
     --backend file --path data/clusters/acme/store \
     --req-sock tmp/acme-cluster-req.sock \
     --pub-sock tmp/acme-cluster-pub.sock \
     --repl-port 7981
```

The `--repl-port` opens a loopback socket REPL (`nc 127.0.0.1 7981`) for
writer-side queries — useful for verifying indexing without touching the pod.

---

## (b) Overlay YOUR source into the pod via `SEON_EXTRA_SRC` (Path B)

Your product source is a normal `deps.edn` project. Point `SEON_EXTRA_SRC` at
it and `bin/seon` injects it as a `:local/root` dep, so your `src/` joins the
pod build classpath and `your.*` namespaces compile INTO the pod bundle. The
ONE registration that makes your own source boot-index is the preload ns
running the `(reset! …)` below — omit it and your entire surface is SILENTLY
invisible to indexing → context → retrieval (no error). With it, the **full
surface** indexes: every ns gets a `:seon.ns` row and every public fn gets a
`:seon.fn` row, **specced AND unspecced** (this is the client.cljs full-surface
fix — an all-unspecced ns like `acme.notes` used to get no row at all).

The worked example is `acme/src/acme/pod.cljs`:

```clojure
(ns acme.pod
  ;; Requiring the WHOLE surface here pulls it into THIS ns's compile-time
  ;; require closure — which is what lets the `specced-fn-vars` macro
  ;; (expanded HERE) actually see your.* vars. A helper living in a seon ns
  ;; could NOT: the macro expands against ITS OWN caller's closure, and a
  ;; seon ns can never require your downstream code. The macro MUST expand
  ;; in YOUR entry ns.
  (:require [acme.brand]
            [acme.helpers]
            [acme.notes]
            [acme.overrides]
            [acme.widget]
            [clojure.string :as str]
            [seon.client :as client])
  (:require-macros [seon.indexing :refer [specced-fn-vars]]))

(reset! client/!extra-core-vars
        (filterv #(str/starts-with? (str (:ns (meta %))) "acme.")
                 (specced-fn-vars)))
```

Wire the two env vars (`bin/acme` exports exactly these):

```bash
export SEON_EXTRA_SRC=/path/to/acme        # your deps.edn project
export SEON_EXTRA_PRELOAD=acme.pod         # your entry ns (runs the reset!)
```

Caveats that bite operators:

- **The macro closure direction is load-bearing.** Do NOT try to enumerate
  your vars from a seon-side helper — `specced-fn-vars` expands at the call
  site's closure, which for any seon ns excludes your code. It must be in
  YOUR entry ns, with `(:require-macros [seon.indexing :refer
  [specced-fn-vars]])`.
- **Classpath is fixed at compile time.** Set `SEON_EXTRA_SRC` BEFORE the
  build, not after. The acme pod runs a one-off compiled bundle
  (`out-acme/client/main.js`); after editing your `src/`, rebuild
  (`bin/acme build`) and restart the pod.
- **Reproduce the silent-failure bug** to convince yourself the hook is live:
  comment out the `(reset! …)`, rebuild — zero of your rows index and a loud
  boot WARN fires.

See [[extra-src]] for Path A (store/`my.*` prefix) vs Path B
(`SEON_EXTRA_SRC`) and when to use each.

---

## (c) Override any seon fn via `set!` (extend without forking)

The universal extend-without-fork mechanism: `set!` the callee's global var
slot. An EXISTING compiled caller reads that slot at call time, so your
override flows through without editing seon's source. Side effects fire when
your preload requires the overriding ns. This works for ANY seon fn —
inject context sections (`seon.ctx/core-default-ctx`), reshape a tile's error
card (`seon.render.live-tile/error-response`), etc.

Worked example — `acme/src/acme/overrides.cljs` reshapes the broken-tile card
so the human sees a calm Acme-branded placeholder instead of seon's stock one:

```clojure
(ns acme.overrides
  (:require [seon.render.live-tile :as live-tile]))

(defonce ^:private orig-error-response live-tile/error-response)

(set! live-tile/error-response
      (fn acme-error-response [req]
        (assoc (orig-error-response req)            ; keep the :seon.render/ai twin
               :seon.render/hiccup
               [:div {:class "seon-tile"}
                [:div {:class "seon-tile-compact p-3 text-xs text-text-300 italic"}
                 "Acme is preparing this view…"]])))
```

Two patterns worth copying from it:

- **Capture the original first** (`defonce orig-…`) and delegate to it, so you
  augment rather than replace (here: keep the agent-facing `:seon.render/ai`
  failure twin, only swap the human-facing hiccup).
- **`defonce` the capture** so a hot reload of the overriding ns doesn't
  re-capture your OWN override as the "original".

Verified live: wire a deliberately-throwing tile (`acme.widget/broken-tile`)
onto an agent and render `/agent/<id>` — the page shows "Acme is preparing
this view…", never seon's stock "Updating this panel…" card.

---

## (d) Branding via env (`SEON_BRAND_*`)

Three env vars, no source change (`bin/acme` exports them):

```bash
export SEON_BRAND_NAME="Acme"
export SEON_BRAND_TAGLINE="Acme — the third-party harness"
export SEON_BRAND_CSS=/path/to/acme/branding/acme.css
```

`SEON_BRAND_CSS` is inlined into the inspector page head AFTER seon's
`output.css`, so its token overrides win. Seon's theme is CSS custom
properties (`--color-*`); remap them for a visibly-own deployment, and use
selector rules for finer control. From `acme/branding/acme.css`:

```css
:root, [data-theme="phosphor"] {
  --color-amber-500: #0ea5e9;   /* remap the amber accent to cyan */
  --color-signal:    #38bdf8;
}
.seon-tile-compact { border-left: 2px solid #38bdf8; }  /* selector-level */
```

Verified live: the grid page shows the brand name + tagline, and the inlined
CSS carries both the cyan token overrides and the tile border rule.

---

## Putting it together

The `acme/` overlay + `bin/acme` wrapper is the end-to-end worked example of
all four seams running at once, fully isolated from the live cluster
(distinct ports/sockets/store/supervisor namespace). To see it green from a
clean state:

```bash
bin/acme down                        # stop anything stale (ignore errors)
bin/acme cluster reset default       # wipe data/clusters/acme/store (acme env)
bin/acme up                          # build + wire-server + pod + status
curl -s 127.0.0.1:7980/agents        # 200 — your branded inspector
```

Then drive a turn (`SEON_AI_PROVIDER=deepseek DEEPSEEK_API_KEY=…`) and read
`/agent/<id>` to see your overlay namespaces in context, your live tiles
rendering, and your overrides and branding applied. Full acceptance-check
list + isolation table: [[../components/acme-harness.md]].

## Key files

- `build.clj` (`writer-uber` target), `src/seon/embed/preflight.clj` (exit
  codes), `src/seon/server/wire.clj` (wire-server `-main` / CLI args)
- `src/seon/client.cljs` (`!extra-core-vars`, full-surface indexing),
  `src/seon/indexing.clj` (`specced-fn-vars` macro)
- `bin/acme`, `bin/seon` (env-parametrized supervisor), `acme/**` (the worked
  overlay), [[extra-src]] (Path A vs Path B)
