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
   failure instead of a silent hang."
  (:require [cljs.test :refer [is]]))

(defn settle!
  "Terminal handler for a cljs.test async promise chain — the hang-proof
   replacement for the bare `(.then done)` tail. Thread-LAST so it reads
   as the final link: `(-> p (.then …) (settle! done))`.

   resolve → (done); reject / a throwing assertion callback → loud
   `(is false …)` naming the rejection, then (done). Returns the chained
   Promise (settled). `done` fires exactly once."
  [p done]
  (let [fired (volatile! false)
        fin   (fn [] (when-not @fired (vreset! fired true) (done)))]
    (-> p
        (.then (fn [_] (fin)))
        (.catch (fn [e]
                  (is false (str "async test chain REJECTED — " e))
                  (fin))))))
