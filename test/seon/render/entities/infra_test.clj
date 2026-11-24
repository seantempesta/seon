(ns seon.render.entities.infra-test
  "Proof tests for the multi-footprint pure renderers in `seon.render.entities.infra`."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.render.entities.infra :as infra]))

(def ^:private samples
  {:seon.code.file/entity {:seon.code.file/path "src/a.clj" :seon.code.file/text "l1
l2
l3" :seon.code.file/exec? true}
   :seon.git/entity {:seon.git/repo :main :seon.git/branch "main" :seon.git/commit "e0e9e4750892e118f2dac303823140b6bba34cc1"}
   :seon.route/entity {:seon.route/name :seon.route/root-debug :seon.route/pattern "/root/debug"
                       :seon.route/method :get :seon.route/handler 'seon.web/set-view-mode! :seon.route/doc "Root agent Context & Debug view."}})

(deftest three-entities-have-two-distinct-footprints-test
  (testing "each entity has a compact chip and a richer card with distinct min widths"
    (doseq [[k [chip-fn card-fn]]
            {:seon.code.file/entity [#'infra/render-file-chip #'infra/render-file-card]
             :seon.git/entity [#'infra/render-git-chip #'infra/render-git-card]
             :seon.route/entity [#'infra/render-route-chip #'infra/render-route-card]}]
      (let [m {:seon.code.file/entity (:seon.code.file/entity samples)
               :seon.git/entity (:seon.git/entity samples)
               :seon.route/entity (:seon.route/entity samples)}
            hc (:seon.ui/hiccup (chip-fn m))
            hk (:seon.ui/hiccup (card-fn m))
            w #(-> % second :data-min-width)]
        (is (vector? hc) (str k " chip returns hiccup"))
        (is (vector? hk) (str k " card returns hiccup"))
        (is (< (long (w hc)) (long (w hk))) (str k " chip is narrower than card"))
        (is (string? (seon.render.text/hiccup->text hc)))
        (is (string? (seon.render.text/hiccup->text hk)))))))
