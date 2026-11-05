(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)   what draws what in this agent's live walk, from its root: {:fn :args :id :tokens :reads? :children}"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.render :as render] [seon.repl :as repl]))

(defn tree
  "What draws what in this agent's live walk (the one its model's page and its person's page are drawn from, brought up to
  date now), from its root, as data: each component {:fn :args :id :tokens :reads? :children}, :tokens its own output's by the
  one sizer (`seon.render/tree`). The lists drawn so far: the model's, and every other once a person's page has asked."
  {:malli/schema [:=> [:cat] [:maybe :seon.render/tree]]}
  [] (render/tree (repl/page-walk)))
