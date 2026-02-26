# Transit + ClojureScript Encoding Layer

## Problem Statement

Seon currently encodes Clojure namespaced keywords as Datastar-compatible signal paths using a lossy camelCase dot-notation scheme:

```
:seon.getting-started/exercise  ->  seon.gettingStarted.exercise
:seon.ctx/user-input            ->  seon.ctx.userInput
```

This is lossy and ambiguous:
- **Namespace vs name boundary lost**: `:foo.bar/baz` and `:foo/bar.baz` could collide after encoding
- **Hyphen vs camelCase boundary ambiguous**: `getUserInput` could decode to `get-user-input` or `getUser-input`
- **No support for rich types**: sets, keywords-as-values, dates, UUIDs all become strings
- **Round-trip not guaranteed**: decode(encode(x)) != x for edge cases
- **Every new data type requires custom encoding logic** in `seon.web.reactive.encoding`

The encoding layer (`seon.web.reactive.encoding`) is ~180 lines of bespoke transformation code that reimplements (badly) what Transit already does.

## Research Findings

### Transit Libraries (Stable, "Done" Software)

| Library | Version | Platform | Size |
|---------|---------|----------|------|
| `com.cognitect/transit-clj` | 1.0.333 | JVM (Clojure server) | N/A |
| `transit-js` | 0.8.874 | npm (Browser) | ~10KB gzipped |
| `com.cognitect/transit-cljs` | 0.8.280 | ClojureScript | wraps transit-js |

All implement Transit 0.8 spec and are wire-compatible. Transit is considered finished software with no API churn -- exactly the stability profile Seon values.

Transit preserves: keywords (qualified and unqualified), symbols, sets, maps with non-string keys, UUIDs, dates/instants, bigints, lists vs vectors. Key caching reduces payload size for repeated keys.

### Browser ClojureScript Options

| Tool | Engine | Bundle Size | Build Step | Persistent Data | Browser Eval |
|------|--------|-------------|------------|-----------------|--------------|
| **Scittle** | SCI (interpreter) | ~400KB | None | Yes | Yes |
| **Cherry** | Compiler (ES6) | ~350KB base | Node.js | Yes | Yes (Scittle2) |
| **Squint** | Compiler (dialect) | 10-20KB | Node.js | No (JS objects) | Limited |
| **shadow-cljs** | Full CLJS | ~1MB+ | JVM | Yes | Via REPL |
| **transit-js alone** | N/A | ~10KB | None | No | No |

**Scittle is the clear winner for Seon:**
- Zero build step -- include via `<script>` tag, write CLJS in `<script type="application/x-scittle">`
- Has a `scittle.transit.js` plugin that provides `cognitect.transit` namespace
- SCI engine means agents can send Clojure forms for browser evaluation
- Persistent data structures (keywords, sets, maps work natively)
- ~400KB is acceptable for a personal OS (not a public landing page)
- Scittle2 (Cherry-backed) is coming for better performance, same API

### Datastar SSE Integration

Datastar has three SSE event types (2025 naming):
1. **`datastar-patch-elements`** -- HTML fragments (morphed into DOM)
2. **`datastar-patch-signals`** -- JSON merged into signal store
3. **`datastar-execute-script`** -- executes JS in browser

Key insight: **Datastar's signal store is JSON-only. Transit data cannot live in signals directly.** But we do not need it to. The architecture is:

- **HTML fragments** (patch-elements): Server renders HTML, no encoding needed. This is our primary path and it stays unchanged.
- **Rich data** (for CLJS logic): Send transit-encoded strings via `datastar-execute-script` or a custom Scittle event handler. CLJS code in the browser decodes transit and operates on real Clojure data.
- **Form submissions** (client -> server): Scittle intercepts form data, encodes as transit, POSTs with `Content-Type: application/transit+json`.

This means **Datastar continues doing what it does well** (HTML morphing, simple signals for form state) and **transit handles the data channel** (rich Clojure data for logic, state sync, agent communication).

### Agent Code Execution in Browser

With Scittle (SCI), the server can send arbitrary Clojure forms for browser evaluation:

```clojure
;; Server sends via SSE:
event: datastar-execute-script
data: script document.querySelector('script[data-seon-eval]').dispatchEvent(new CustomEvent('seon-eval', {detail: '(js/console.log (+ 1 2))'}))
```

Or more elegantly, a dedicated Scittle listener:

```clojure
;; In browser Scittle:
(defn handle-eval [e]
  (let [code (.-detail e)]
    (sci/eval-string code)))
(.addEventListener js/document "seon-eval" handle-eval)
```

This enables: DOM inspection, live debugging, UI tweaks, test assertions -- all from server-side agents. Security is acceptable because Seon is a personal single-user system running on localhost.

## Recommended Architecture: Option D (Hybrid)

**Scittle for transit + utils + eval, Datastar for reactivity + SSE + HTML morphing.**

They complement each other:
- Datastar: declarative HTML reactivity, SSE connection management, DOM morphing
- Scittle: Clojure data fidelity, transit encode/decode, agent eval, rich client logic

The two communicate through DOM events and shared DOM state. Datastar owns the signal store (simple JSON values for form binding). Scittle owns a parallel Clojure atom for rich state.

## Implementation Phases

### Phase 1: Transit Encode/Decode (Minimum Viable)

**Server side:**
- Add `com.cognitect/transit-clj` dependency
- Create `seon.web.transit` namespace with `encode` / `decode` functions
- Wire into ring middleware: detect `application/transit+json` content-type, auto-decode request bodies
- Add transit encoding to SSE helper (new event type or data attribute)

**Browser side:**
- Download `scittle.js` + `scittle.transit.js` to `resources/public/js/`
- Add script tags to `base-page` in `seon.web.html`
- Create `resources/public/cljs/seon/transit.cljs` (Scittle source):
  ```clojure
  (ns seon.transit
    (:require [cognitect.transit :as t]))

  (def reader (t/reader :json))
  (def writer (t/writer :json))

  (defn decode [s] (t/read reader s))
  (defn encode [data] (t/write writer data))
  ```
- Verify round-trip: server encodes keyword map, browser decodes, logs to console

**Test:** `curl` the server for a transit response, verify keywords survive round-trip.

### Phase 2: Signal Integration (Datastar + Transit Coexisting)

- Replace `seon.web.reactive.encoding` with transit-based encoding for POST bodies
- Form submissions: Scittle intercepts `@post`, encodes signal data as transit before sending
- Server decodes transit POST bodies directly to qualified keywords (no more camelCase gymnastics)
- `data-signals` initialization: keep JSON for Datastar compatibility, but **also** embed a transit blob in a `data-seon-state` attribute for Scittle to hydrate
- Deprecate and eventually delete `seon.web.reactive.encoding` namespace

### Phase 3: Agent Browser Execution

- Add `seon-eval` custom event listener in Scittle bootstrap
- Server-side helper: `(browser-eval session-id '(js/document.title))` sends eval via SSE
- Results returned via POST back to server (transit-encoded)
- Agent tooling: "inspect DOM element", "check signal value", "assert text content"
- Rate limiting / size limits on eval payloads (defense in depth, even on localhost)

## Dependencies to Add

### Server (deps.edn)
```clojure
com.cognitect/transit-clj {:mvn/version "1.0.333"}
```

### Browser (download to resources/public/js/)
```
scittle.js          (~400KB, from npm scittle@0.8.31)
scittle.transit.js  (transit plugin for scittle)
```

No npm build step required. No shadow-cljs. No JVM dependency for browser code.

### Optional (Phase 3)
If we want nREPL-style browser REPL with editor integration, Cherry/Scittle2 will provide this. But SCI eval via custom events is sufficient for agent use cases.

## Trade-offs

### What We Gain
- **Perfect fidelity**: keywords, sets, dates survive round-trip without bespoke encoding
- **Delete ~180 lines** of fragile encoding/decoding code
- **Agent browser eval**: Clojure forms executed in browser, results returned as Clojure data
- **Future-proof**: transit is stable, scittle is actively maintained, both are Clojure ecosystem standards
- **No build step**: scittle loaded via script tag, CLJS written inline or in static files

### What Complexity We Add
- **~400KB bundle**: scittle.js is not small, but acceptable for personal-use app
- **Two script runtimes**: Datastar (ES module) + Scittle (SCI) both running in browser
- **Two state stores**: Datastar signals (JSON) + Scittle atom (Clojure data) need coordination
- **Learning curve**: developers need to understand which layer handles what

### What We Keep
- Datastar for SSE, HTML morphing, declarative reactivity (its strength)
- Server-rendered HTML as primary UI pattern (no SPA)
- Current URL structure (`/ns/:ns`, `/ns/:ns/:fn`)

## Migration Path

1. **Phase 1 is additive** -- add transit + scittle alongside existing encoding. Nothing breaks.
2. **Phase 2 replaces encoding** -- once transit POST decoding works, swap `decode-signals` to use transit. The `encode-keyword` / `decode-signals` functions in `seon.web.reactive.encoding` become dead code.
3. **Phase 3 is new capability** -- agent eval is purely additive.

The key constraint: `data-signals` attribute must remain JSON (Datastar requirement). Transit replaces the **wire format** for POST bodies and server-pushed data, not the DOM attribute format. Datastar signals stay as simple display values; transit carries the rich data channel.

## Status

- [x] Phase 0: Research (this document)
- [ ] Phase 1: Transit encode/decode
- [ ] Phase 2: Signal integration
- [ ] Phase 3: Agent browser execution
