;; The HTML block set for the benchmark.
;;
;; HEAD commit 29794272b ("Replace seeded context with one fresh walk", same
;; day) DELETED the block seeding from `seon.cluster.agent/creation-tx`, so a
;; freshly booted cluster's agent page currently declares ZERO blocks and the
;; live page renders empty. The html render functions all still exist; only
;; the declarations are gone. This reinstates exactly the html half of the
;; deleted `seon.render.agent/blocks` vector (29794272b^) so the delivery
;; pipeline can be measured against a realistic page.
(require '[datahike.api :as d] '[seon.cluster.store :as store]
         '[seon.render.block :as block])
(def inst (get @@(ns-resolve 'seon.cluster 'running-instances) "bench"))
(def cconn (:seon.boot/cluster-connection inst))
(def html-blocks
  [{:seon.render.block/name :agent-header
    :seon.render.block/band :anchor
    :seon.render.block/priority 25
    :seon.render/html 'seon.render.agent/agent-header-html}
   {:seon.render.block/name :message-bar
    :seon.render.block/band :anchor
    :seon.render.block/priority 30
    :seon.render/html 'seon.render.web/message-bar-html}
   {:seon.render.block/name :transcript
    :seon.render.block/band :dynamic
    :seon.render.block/priority 40
    :seon.render/html 'seon.render.agent/transcript-html}
   {:seon.render.block/name :focus
    :seon.render.block/band :dynamic
    :seon.render.block/priority 50
    :seon.render/html 'seon.render.agent/focus-html}])
(println :TX (pr-str (select-keys (store/transact! cconn
   [{:seon.cluster.agent/id "root" :seon.cluster.agent/blocks html-blocks}])
   [:seon.error/kind])))
(def surfaces (block/surfaces @cconn {:seon.cluster.agent/id "root"
                                      :seon.render/kind :seon.render/html
                                      :seon.sci.admit/caps {}}))
(println :SURFACES (count surfaces) (mapv :seon.render/surface-id surfaces))
(println :ERRORS (mapv :seon.error/kind (keep :seon.error/value surfaces)))
