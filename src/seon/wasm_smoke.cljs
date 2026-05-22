(ns seon.wasm-smoke
  "Milestone-1 smoke entry for the WASM pod.

   STATUS 2026-05-20: PARTIAL. Bare cljs.core + core.async + konserve +
   timbre + environ each work under wasm-rquickjs/wasmtime CLI. Adding
   `[datahike.api :as d]` causes the wasmtime invoke to hang
   indefinitely at namespace-load time — root cause not yet localized.
   See docs/seon/pod/m1-findings-2026-05-20.md (write-up pending) and
   the orchestrator conversation 2026-05-20.

   This file currently inlines `datahike-smoke-test!` logic so the
   surface stays narrow once we unblock the hang. Don't add other
   requires here — keep the load graph minimal.

   The wrapper (out/smoke/wrapped.mjs) reads `globalThis.seonSmoke` and
   returns its pr-str'd EDN via the WIT `smoke` export."
  (:require
    [datahike.api :as d]))

(def ^:private smoke-schema
  [{:db/ident       :name
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string}
   {:db/ident       :rank
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(def ^:private smoke-seed
  [{:name "Alpha"    :rank 1}
   {:name "Seon"     :rank 2}
   {:name "Datahike" :rank 3}])

(def ^:private smoke-expected
  #{["Alpha" 1] ["Seon" 2] ["Datahike" 3]})

(defn ^:async smoke!
  "Returns a Promise resolving to a pr-str'd EDN string of the smoke result."
  []
  (let [cfg {:store              {:backend :memory
                                  :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))
          _    (await (d/transact! conn smoke-schema))
          tx   (await (d/transact! conn smoke-seed))
          rows (d/q '[:find ?name ?rank
                      :where
                      [?e :name ?name]
                      [?e :rank ?rank]]
                    @conn)
          result (if (= rows smoke-expected)
                   {:status :pass :datoms (count (:tx-data tx)) :rows rows}
                   {:status :fail :got rows :expected smoke-expected})]
      (pr-str result))))

(set! (.-seonSmoke js/globalThis) smoke!)

(defn -main [& _args]
  ;; Intentional no-op. The smoke is invoked by the WIT host via the
  ;; globalThis.seonSmoke handle, not on load.
  nil)
