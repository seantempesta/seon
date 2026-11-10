(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)            what draws what in this agent's live walk, from its root: {:render :args :id :tokens :reads? :children}
    (my.render/diff p0 p1)      what moved between two points of your branch, as edits: the page at p0 taken to the page at p1"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.render :as render] [seon.repl :as repl] [seon.ctx.wire :as wire] [seon.ctx] [seon.agent :as agent] [datahike.api :as d]))

(defn tree
  "What draws what in this agent's live walk (the one its model's page and its person's page are drawn from, brought up to
  date now), from its root, as data: each component {:render :args :id :tokens :reads? :children}, :tokens its own output's by the
  one sizer (`seon.render/tree`). The lists drawn so far: the model's, and every other once a person's page has asked."
  {:malli/schema [:=> [:cat] [:maybe :seon.render/tree]]}
  [] (render/tree (repl/page-walk)))

(defn diff
  "What moved of your page between point p0 and point p1 of your branch ({:branch :commit :t}; p0 nil: nothing before it, so each part whole, [[id] :+ form]), as edits [[path op value] ...], each path starting with the part's id: the page drawn at each point with today's renders and the edits `seon.render/page-edits` makes of the two. Pure in the points: the same points and renders give the same edits, which the model reads for each turn between the points of its two requests (:seon.model-call/basis). The same edits your turn's page message prints (seon.ctx.wire/page-edits-between). See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat [:maybe [:map [:branch :any] [:commit :uuid] [:t :int]]] [:map [:branch :any] [:commit :uuid] [:t :int]]] [:or [:vector :any] :map]]}
  [p0 p1] (wire/page-edits-between (agent/db) (agent/branch-index) (:db/id @seon.ctx/ctx) p0 p1))
