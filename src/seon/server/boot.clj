(ns seon.server.boot
  "Assemble and launch the one immutable JVM writer runtime."
  (:require ;; Register the :proximum secondary-index type with datahike's
            ;; `datahike.index.secondary` multimethods BEFORE any cluster conn
            ;; opens. seon.embed/install! bakes a :proximum index into a
            ;; cluster store's schema; without this require, restoring that
            ;; store on a fresh wire-server boot throws "Unknown secondary
            ;; index type: :proximum" before any REPL/session can require it.
            ;; Boot is the glue ns that owns the writer's full load path, so
            ;; the require lives here (wire.clj stays secondary-index-free).
            [datahike.index.secondary.proximum]
            [seon.embed :as embed]
            [seon.server.broadcast :as broadcast]
            [seon.server.wire :as wire])
  (:gen-class))

(defn writer-runtime
  "Assemble the immutable dependencies for one writer process."
  {:malli/schema [:=> [:cat] :seon.server.wire/runtime]}
  []
  (let [embeddables (embed/default-embeddables)]
    {:seon.server.wire/database-initializer
     (fn [conn _db-name]
       (embed/initialize-database! embeddables conn))
     :seon.server.wire/transaction-transform
     (partial embed/augment-tx-with-embeddings embeddables)
     :seon.server.wire/knn-search embed/knn-search
     :seon.server.wire/transaction-publisher broadcast/broadcast!}))

(defn -main
  "Boot the writer with its explicitly composed runtime.

   `--preflight`: instead of starting the server, run the embedding-feature
   self-check (`seon.embed.preflight/run-preflight!`) and System/exit with its code (0 =
   all green; distinct non-zero per failure mode). This is the loud gate a
   third party scripts against — see the preflight ns."
  [& args]
  (if (some #{"--preflight"} args)
    (let [code ((requiring-resolve 'seon.embed.preflight/run-preflight!))]
      (flush)
      (System/exit code))
    (wire/start! (writer-runtime) args)))
