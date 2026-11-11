(ns my.render
  "The agent's view of what draws its page. Referred as my.render/..., loaded in every agent.
    (my.render/tree)            what draws what in this agent's live walk, from its root: {:render :args :id :tokens :reads? :children}
    (my.render/ctx p)           your record (@ctx) at a point of your branch, whole, as data
    (my.render/page p)          your page parts at a point of your branch, whole
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
  "What moved of your page between point p0 and point p1 of your branch ({:commit :t}, as a page call prints it; :branch optional; p0 nil: nothing before it, so each part whole, [[id] :+ form]), as edits [[path op value] ...], each path starting with the part's id: the page drawn at each point with today's renders and the edits `seon.render/page-edits` makes of the two. Pure in the points: the same points and renders give the same edits, which the model reads for each turn between the points of its two requests (:seon.model-call/basis). The same edits your turn's page message prints (seon.ctx.wire/page-edits-between). See docs/render.md#a-change-is-edits-by-name."
  {:seon.model-call/reply "My page changed since my last turn, by my work or by main's. These are the edits, so I see what moved without reading the page again."
   :seon.eval/intent "Read what changed on my page since my last turn."
   :malli/schema [:=> [:cat [:maybe [:map [:branch {:optional true} :any] [:commit :uuid] [:t :int]]] [:map [:branch {:optional true} :any] [:commit :uuid] [:t :int]]] [:or [:vector :any] :map]]}
  [p0 p1] (wire/page-edits-between (agent/db) (agent/branch-index) (:db/id @seon.ctx/ctx) p0 p1))

(defn ctx
  "Your record at point p of your branch ({:commit :t}, :branch optional): exactly what @ctx held there, as data, whole: your calls, evals
  and messages included, as your own pattern pulls them (the same value, never a filtered copy). Your first turn shows it whole as the result of this call, before your
  page (seon.ctx.wire/page-diff-msgs). See docs/ctx.md#what-the-model-reads-and-what-the-page-renders."
  {:seon.model-call/reply "My `ctx` atom holds the pull of my own entity from the database: my task, my turns, my evals and the messages sent to me. It is plain data, so I read it with Clojure, and a `swap!` on it writes my change to the database, checked against its schema, with no transaction to write. I read it whole once, here."
   :seon.eval/intent "Read @ctx: the pull of my entity, as data."
   :malli/schema [:=> [:cat [:map [:commit :uuid] [:t :int]]] :map]}
  [p] (wire/ctx-at (agent/db) (:db/id @seon.ctx/ctx) p))

(defn page
  "Your page parts at point p of your branch ({:branch :commit :t}, :branch optional): the page drawn at p with today's renders, as hiccup without its presentation, the parts the model reads. Pure in the point: the same point and renders give the same bytes, which your first turn's page shows as the result of this call (seon.ctx.wire/page-diff-msgs). See docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:seon.model-call/reply "My page is that same data drawn by render functions: my task and its plan, what it cites, and how my proofs stand. The person working with me sees these parts as html. I read it whole once; after this, each turn shows only what changed on it, as edits by name."
   :seon.eval/intent "Read my page, whole, as hiccup."
   :malli/schema [:=> [:cat [:map [:commit :uuid] [:t :int]]] :any]}
  [p] (wire/page-parts-at (agent/db) (:db/id @seon.ctx/ctx) p))
