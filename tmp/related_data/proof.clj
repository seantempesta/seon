;; Lane related-data-opus, 2026-09-28. Disposable REPL probes, JVM mode on
;; default. walk.clj here is src/seon/render/walk.clj of this lane's diff
;; under the namespace tmp.related-data.walk (its `::` keys spelled
;; :seon.render.walk/…), whose `related` threads the checkout's packaged
;; projection (this diff's schema declarations) into `graph`: default runs a
;; committed archive, so nothing here interns into a seon.* namespace.
;; Load: (load-string (slurp "tmp/related_data/walk.clj")) then this file.
(ns tmp.related-data.proof
  (:require [seon.ai.tokens :as tokens]
            [seon.cluster.boot]
            [seon.contracts-compile-test :as cct]
            [seon.config]
            [seon.db]
            [seon.operator.runtime]
            [seon.render]
            [seon.render.value]
            [tmp.related-data.walk :as walk]))

(def conn (seon.cluster.boot/connection "default"))
(def db0 (seon.db/db conn))
(def ctx (:seon.sci.eval/ctx (get @seon.operator.runtime/running-instances "default")))
(def checkout (cct/packaged-projection "/Users/sean/src/seon"))

(defn ms
  "Milliseconds `f` takes, and its value."
  [f] (let [t (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t) 1e6) v]))

(def profile (seon.render/request-profile {:seon.db/db db0 :seon.sci.eval/ctx ctx :seon.render/value {}}))

(def caps (seon.config/result-caps (seon.config/effective db0 "default")))

(def unit0 {:seon.db/db db0 :seon.sci.eval/ctx ctx :seon.render/profile profile
            :seon.sci.admit/caps caps :seon.sci.eval/time-limit-ms 5000
            :tmp.related-data.walk/checkout checkout})

(defn view
  "The root's declared render with its related data beside it, as the value
  printer's `declared` receives it, and for AI the printed text."
  [lookup output]
  (let [value (seon.db/pull db0 '[*] lookup)
        answer (seon.render/declared-projection unit0 value output)
        [t with] (ms #(walk/with-related unit0 value output answer))
        ;; The installed function, task and test renders predate this diff:
        ;; drop what the diff's renders no longer return (hand-listed
        ;; neighbours, a distance-0 test's host) so size is the diff's.
        installed-only [:seon.render.ns/calls :seon.render.ns/called-by :seon.render.ns/called-by-count
                        :seon.render/operations :seon.task/proofs :seon.task/subjects]
        shown (get-in with [:seon.print/node :seon.print/value])
        shown (if (map? shown)
                (-> (apply dissoc shown installed-only)
                    (update :seon.related/data
                            (fn [data] (if (map? data)
                                         (update-vals data (fn [g] (if (:seon.related/items g)
                                                                     (update g :seon.related/items (partial mapv #(if (map? %) (dissoc % :seon.render.test/host) %)))
                                                                     g)))
                                         data))))
                shown)
        text (when (= output :seon.render/ai)
               (:seon.render.value/text
                (seon.render.value/prepare {:seon.db/db db0 :seon.sci.eval/ctx ctx :seon.render/profile profile
                                            :seon.render/value shown
                                            :seon.render.value/options {:seon.render.value/structural? true}})))]
    {:ms t :tokens (some-> text tokens/estimate) :shown shown :text text}))

(comment
  (view [:seon.fn/sym 'seon.db/write-owned-values-error] :seon.render/ai)
  (view [:seon.fn/sym 'seon.test/run] :seon.render/ai)
  (view [:seon.task/id "4bb788513862"] :seon.render/ai))
