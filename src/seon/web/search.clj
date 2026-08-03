(ns seon.web.search
  "Pure provider response projections for the protected web owner."
  (:require [clojure.string :as str]))

(defn- organic-row
  [row]
  (let [title (get row "title")
        link (get row "link")
        snippet (get row "snippet")
        position (get row "position")]
    (when (and (string? title)
               (not (str/blank? title))
               (string? link)
               (not (str/blank? link))
               (integer? position)
               (pos? position))
      (cond-> {:my.web.result/title title
               :my.web.result/link link
               :my.web.result/position position}
        (and (string? snippet) (not (str/blank? snippet)))
        (assoc :my.web.result/snippet snippet)))))

(defn- organic-results
  [document max-results]
  (let [credits (get document "credits")
        rows (into [] (keep organic-row) (get document "organic"))
        results (vec (take max-results rows))]
    (when (and (integer? credits) (not (neg? credits)))
      {:my.web/results results
       :my.web/result-count (long (count rows))
       :my.web/returned (long (count results))
       :my.web/credits (long credits)})))
