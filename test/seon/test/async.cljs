(ns seon.test.async
  "Shared terminal handler for `cljs.test` `(async done …)` promise chains.

   THE BUG it fixes (audit follow-up #44): the idiom

     (async done
       (-> (fresh-conn)
           (.then (fn [conn] … (is …) …))
           (.then done)))

   attaches a success handler but NO reject handler. When the chain
   REJECTS — `fresh-conn` fails, or (far more common) an assertion
   callback THROWS — the rejection has nowhere to go and `done` is
   never called. The single-process `:node-test` runner then HANGS on
   that one test, wedging the WHOLE suite (it never reaches the next
   `deftest`), and `bin/test-cljs` can only report an opaque stall.

   `settle!` is the hang-proof replacement for the bare `(.then done)`
   tail. Drop it in as the final `->` link:

     (-> (fresh-conn) (.then assertions) (settle! done))

   resolve → (done); reject / throwing assertion callback → loud
   `(is false …)` + (done). The rejection becomes a VISIBLE test
   failure instead of a silent hang.

   #41 — a NEVER-settling chain (an `^:async` body awaiting a Promise that
   never resolves, or a `(js/Promise. (fn [_ _]))` that is never settled)
   would still park the bare tail forever. `settle!` also races a
   wall-clock timeout: if neither resolve nor reject fires within
   `default-timeout-ms`, it emits a loud `(is false \"TIMED OUT …\")` and
   calls `done`, so the offending test fails LOUDLY + INDIVIDUALLY and the
   runner moves on instead of wedging the whole single-process suite.
   (No preemption — JS is single-threaded, so the parked body keeps running
   in the background, same caveat as every seon timeout — but the runner is
   freed.)"
  (:require [cljs.test :refer [is]]))

(def default-timeout-ms
  "Per-test wall-clock bound for `settle!`. Generous — every converted test
   settles in well under a second; this only catches a genuinely stuck
   (never-settling) chain. A test that legitimately needs longer passes an
   explicit `ms`."
  15000)

(defn settle!
  "Terminal handler for a cljs.test async promise chain — the hang-proof
   replacement for the bare `(.then done)` tail. Thread-first so it reads
   as the final link: `(-> p (.then …) (settle! done))` (or
   `(settle! done ms)` for a custom timeout).

   resolve → (done); reject / a throwing assertion callback → loud
   `(is false …)` naming the rejection, then (done); never settles within
   `ms` → loud `(is false \"TIMED OUT …\")`, then (done). `done` fires
   exactly once; the timer is cleared on settle so a fast test leaks no
   pending timer."
  ([p done] (settle! p done default-timeout-ms))
  ([p done ms]
   (let [fired (volatile! false)
         fin   (fn [] (when-not @fired (vreset! fired true) (done)))
         timer (js/setTimeout
                 (fn []
                   (when-not @fired
                     (is false (str "async test TIMED OUT after " ms
                                    "ms — its promise chain never settled "
                                    "(a never-resolving await, or a `done` "
                                    "that is never reached)."))
                     (fin)))
                 ms)]
     (-> p
         (.then (fn [_] (js/clearTimeout timer) (fin)))
         (.catch (fn [e]
                   (js/clearTimeout timer)
                   (is false (str "async test chain REJECTED — " e))
                   (fin)))))))
