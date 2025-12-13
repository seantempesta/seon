# Hyperlith - What We Should Adopt

**Reference:** `reference-code/hyperlith/` (git submodule)

**Author:** Anders Murphy - has done extensive research on this approach.

---

## The Core Insight

From the hyperlith README:

> "The compression is so good that in my experience it's more network efficient and more performant than fine grained updates with diffing (without any of the additional complexity)."
>
> "reduction in size by 90-100x! Sometimes more"

**Key realization:** Brotli streaming compression over SSE is SO efficient that sending the full HTML view on every change is actually BETTER than trying to be clever with partial updates or diffing.

---

## Hyperlith Philosophy

### 1. `view = f(state)`

Single render function per page. Push the full view, not deltas.

```clojure
;; This is all you need
(defview handler-home {:path "/"}
  [{:keys [db]}]
  (html [:main#morph ...render everything...]))
```

### 2. No Diffing Needed

> "Intuitively you would think the diffing approach would be more performant so you wouldn't even consider this approach."

But with:
- Brotli streaming compression (90-100x reduction)
- Idiomorph on client (handles DOM diffing)
- Throttled renders (max rate limiting)

...sending the full view is simpler AND more efficient.

### 3. CQRS Pattern

- **Actions** (POST) = modify database, return 204 (or 200 with signals)
- **Render functions** = re-render when database changes, push via SSE

Actions should NOT update the view directly - they modify state, then the render function handles the view.

### 4. Homogeneous Events

> "When your events are not homogeneous, you can't miss events, so you cannot throttle your events without losing data."

By treating all changes the same (just re-render), you CAN throttle without losing data. Simpler model.

---

## What We Should Change

### Drop: `starfederation.datastar.clojure` library

Hyperlith builds SSE events manually - it's simpler and gives full control:

```clojure
(defn patch-elements [event-id elements]
  (str "event: datastar-patch-elements"
    "\nid: " event-id
    "\ndata: elements " (str/replace elements "\n" "\ndata: elements ")
    "\n\n\n"))
```

### Add: Brotli Streaming Compression

This is THE key. Not gzip, not per-message compression - **streaming brotli over the connection lifetime**.

```clojure
;; deps.edn
com.aayushatharva.brotli4j/brotli4j {:mvn/version "1.18.0"}
com.aayushatharva.brotli4j/native-osx-aarch64 {:mvn/version "1.18.0"}  ; for M1/M2 Macs
```

### Adopt: Their SSE Pattern

```clojure
;; From hyperlith/impl/datastar.clj
(defn render-handler [path render-fn & opts]
  (router/add-route! [:post path]
    (fn handler [req]
      (let [<ch (a/tap refresh-mult (a/chan (a/dropping-buffer 1)))
            <cancel (a/chan)]
        (hk/as-channel req
          {:on-open
           (fn [ch]
             (thread
               (with-open [out (br/byte-array-out-stream)
                           br  (br/compress-out-stream out :window-size 18)]
                 (loop [last-hash nil]
                   (a/alt!!
                     [<cancel] (do (a/close! <ch))
                     [<ch] ([_]
                            (when-some [view (render-fn req)]
                              (let [view-str (html->str view)
                                    view-hash (Integer/toHexString (hash view-str))]
                                ;; Only send if changed
                                (when (not= last-hash view-hash)
                                  (->> (patch-elements view-hash view-str)
                                       (br/compress-stream out br)
                                       (send! ch)))
                                (recur view-hash)))))))))
           :on-close
           (fn [_ _]
             (a/>!! <cancel :cancel))})))))
```

Key elements:
1. `hk/as-channel` - http-kit async channel API
2. Streaming brotli with reusable output stream
3. Hash-based change detection (fast `Integer/toHexString(hash ...)`)
4. core.async tap/mult for broadcast

### Adopt: Their Shim Page Pattern

```clojure
[:body
 ;; Auto-POST on load AND reconnect when coming back online
 [:div {:data-init "@post('/')"
        :data-on:online__window "@post('/')"}]
 [:noscript "Your browser does not support JavaScript!"]
 [:main {:id "morph"}]]
```

Benefits:
- Only renders dynamic content for actual users (not bots)
- Shell can be pre-compressed with high quality
- Etag caching for the shell

### Adopt: Atom Watch for Auto-Refresh

```clojure
(add-watch db_ :refresh-on-change (fn [& _] (refresh-all!)))
```

No manual `trigger-refresh!` calls - state changes automatically trigger renders.

---

## Implementation Plan

### Option A: Port Hyperlith's Implementation

Copy/adapt these files from hyperlith:
- `impl/brotli.clj` - Brotli compression
- `impl/datastar.clj` - SSE handling (adapt to our needs)
- Update `web/sse.clj` to use this approach

### Option B: Use Hyperlith as a Library

Add hyperlith as a dependency and use their primitives:
```clojure
andersmurphy/hyperlith {:git/url "https://github.com/andersmurphy/hyperlith"
                        :git/sha "..."}
```

Then use:
- `hyperlith.core/defview`
- `hyperlith.core/defaction`
- `hyperlith.core/refresh-all!`

**Recommendation:** Option A - port the specific pieces we need. We want to understand the code and may need to adapt it.

---

## Files to Study

| File | What to Learn |
|------|---------------|
| `src/hyperlith/impl/brotli.clj` | Streaming brotli compression |
| `src/hyperlith/impl/datastar.clj` | SSE handling, patch-elements format |
| `src/hyperlith/core.clj` | defview/defaction macros, refresh-all! |
| `examples/chat_atom/src/app/main.clj` | Complete example of the pattern |

---

## Key Differences from Our Current Approach

| Aspect | Our Current | Hyperlith | Change To |
|--------|-------------|-----------|-----------|
| SSE Library | datastar-clj | Raw SSE | Raw SSE |
| Compression | Gzip (per message) | Brotli (streaming) | Brotli streaming |
| Change Detection | None (always send) | Hash-based | Hash-based |
| View Updates | Manual trigger | Atom watch | Atom watch |
| Shim Page | Basic | Auto-reconnect, Etag | Full pattern |
| Philosophy | Incremental | Full view = f(state) | Full view |

---

## Summary

The hyperlith approach is:
1. **Simpler** - No diffing, no partial updates, just render everything
2. **More Efficient** - Brotli streaming compression is that good
3. **More Robust** - No missed events, no state sync issues
4. **Battle-tested** - Author has deployed this in production

We should adopt it wholesale rather than trying to optimize our own approach.
