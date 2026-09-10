(ns render-pass-probe-2026-09-09
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.operator :as operator]
            [seon.repl :as repl]))

(load-file "docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj")

(defn capture!
  "Save exact stored opening and context bytes without running a provider."
  [cluster-name label]
  (let [database @(operator/connection cluster-name)
        rows (evaluation/of-agent database "juniper")
        _ (assert (seq rows) (pr-str rows))
        _ (assert (not-any? :seon.cluster.eval/error rows))
        attempts (db/q '[:find [?attempt ...] :where [?attempt :seon.ai.attempt/id _]] database)
        _ (assert (vector? attempts) (pr-str attempts))
        first-turn (get-in (first rows) [:seon.cluster.eval/run :db/id])
        opening (filterv #(= first-turn (get-in % [:seon.cluster.eval/run :db/id])) rows)
        text #(str/join "\n" (map (comp repl/text repl/entity-emission) %))
        opening-text (text opening)
        context-text ((resolve 'juniper-fixture-2026-09-06/prompt) cluster-name)
        prefix (str "docs/prds/context-generation/research/render-pass-" label "-2026-09-09")
        result {:seon.render.pass/cluster cluster-name
                :seon.render.pass/basis (db/basis-t database)
                :seon.render.pass/opening-evaluations (count opening)
                :seon.render.pass/context-evaluations (count rows)
                :seon.render.pass/opening-bytes (alength (.getBytes opening-text "UTF-8"))
                :seon.render.pass/context-bytes (alength (.getBytes context-text "UTF-8"))
                :seon.render.pass/provider-attempts
                (count attempts)}]
    (spit (str prefix "-opening.txt") opening-text)
    (spit (str prefix "-context.txt") context-text)
    (spit (str prefix ".edn") (pr-str result))
    result))
