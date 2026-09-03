;; REPL probe 2026-09-03: a recursive, composable renderer over REAL data.
;; Stand-ins are named: `faces` plays the contract QUERY's answer (today zero
;; program rows declare :seon.render/ai as an output ref, so the query is
;; empty); `floor` plays seon.print/fit. Everything else is the real db.
(ns probe.render
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.operator :as operator]
            [seon.db :as db]))

(def conn (operator/connection "ctxprobe"))
(def db* (db/db conn))

;;; --- family of a value: identity attribute -> entity schema key (real facts)
(def identity-attributes
  (set (d/q '[:find [?a ...] :where [?s :seon.db/identity true] [?s :seon.schema/key ?a]] db*)))

(defn family
  "The value's schema family, derived: an explicit marker, else its identity attribute's entity schema."
  [v]
  (cond
    (and (map? v) (:probe/family v)) (:probe/family v)
    (map? v)
    (when-let [ida (some identity-attributes (keys v))]
      (d/q '[:find ?key . :in $ ?ida
             :where [?r :seon.schema/key ?ida] [?s :seon.schema/references ?r]
                    [?s :seon.db/attributes true] [?s :seon.schema/key ?key]]
           db* ida))
    (and (sequential? v) (seq v)) [:coll (family (first v))]
    :else nil))

;;; --- the registry stand-in for the selection query: {family -> {rank -> [sym fn]}}
;;; rank 2 = viewing agent's own ns, 3 = another agent's ns, 4 = the family's general face
(def faces (atom {}))
(defn declare-face! [family rank sym f] (swap! faces assoc-in [family rank] [sym f]))
(defn family-of-identity
  "DERIVED, never guessed: the entity schema whose required attributes include this identity attribute."
  [ida]
  (let [keys (d/q '[:find [?key ...] :in $ ?ida
                    :where [?r :seon.schema/key ?ida] [?s :seon.schema/references ?r]
                           [?s :seon.db/attributes true] [?s :seon.schema/key ?key]] db* ida)]
    (if (= 1 (count keys)) (first keys) (throw (ex-info "identity attribute does not name exactly one entity family" {:ida ida :keys keys})))))

(defn floor [v _ctx]
  ;; stand-in for seon.print/fit (bounded, honest, would emit elision values)
  (binding [*print-length* 6 *print-level* 3] (pr-str v)))

(defn select
  "Most specific render function for v: inline content/symbol, then the lowest rank registered, then the floor."
  [v]
  (cond
    (and (map? v) (string? (:seon.render/ai v))) ['inline (fn [v _] (:seon.render/ai v))]
    (and (map? v) (symbol? (:seon.render/ai v))) [(:seon.render/ai v) (resolve (:seon.render/ai v))]
    :else (or (some->> (get @faces (family v)) (sort-by key) first val)
              ['probe.render/floor floor])))

;;; --- Ring-style middleware around the SELECTED render function (cross-cutting, composable)
(defn wrap-error
  "A render function that throws becomes a flat error VALUE rendered by the floor — never a blank."
  [f sym]
  (fn [v ctx] (try (f v ctx)
                   (catch Throwable t (floor {:seon.error/kind :probe/render-failed :seon.render/producer sym :seon.error/message (ex-message t)} ctx)))))
(defn wrap-cost
  "Accumulate per-node cost into the threaded ctx (interceptor-style context map, no dynamic vars)."
  [f sym]
  (fn [v ctx] (let [t0 (System/nanoTime) out (f v ctx)]
                (when-let [a (::cost ctx)] (swap! a conj {:producer sym :ms (/ (- (System/nanoTime) t0) 1e6) :chars (count out)}))
                out)))
(defn wrap-provenance
  "The floor announces itself; explicit render functions are just functions (ruling 59b)."
  [f sym]
  (fn [v ctx] (let [out (f v ctx)]
                (if (and (= sym 'probe.render/floor) (::annotate-floor? ctx)) (str out "  ;; rendered-by " sym) out))))
(def middleware [wrap-error wrap-cost wrap-provenance])

(defn render
  "THE one recursive function: select, wrap, call; the selected function calls render on its parts."
  [v ctx]
  (let [[sym f] (select v)
        handler (reduce (fn [h w] (w h sym)) f middleware)]
    (handler v (assoc ctx ::rendered-by sym))))

(defn rendered-by [v] (first (select v)))

;;; --- level 0: the transcript layout (general face)
(declare-face! :probe/transcript 4 'probe.render/transcript-ai
  (fn [t ctx]
    (str (str/join "\n\n" (map #(render % ctx) (:entries t)))
         "\n\n;; basis t " (:basis t) " — " (count (:entries t)) " entries")))

;;; --- level 1: one entry (general face)
(declare-face! :probe/entry 4 'probe.render/entry-ai
  (fn [e ctx]
    (str (:ns e) "=> " (pr-str (:form e)) "\n"
         (render (:result e) ctx)
         "\n;; result/" (:handle e))))

;;; --- level 2: value faces (general, rank 4)
(declare-face! (family-of-identity :seon.ns/name) 4 'probe.render/namespace-ai
  (fn [ns ctx]
    (str "namespace " (:seon.ns/name ns)
         "\n  requires: " (str/join " " (map :seon.ns/name (:seon.ns/requires ns)))
         "\n  refers:   " (str/join " " (map :seon.ns.refer/local (:seon.ns/refers ns))))))

(declare-face! [:coll (family-of-identity :seon.fn/sym)] 4 'probe.render/functions-ai
  (fn [fns ctx]
    (str/join "\n" (for [f (sort-by :seon.fn/sym fns)]
                     (str (:seon.fn/sym f) "  — " (first (str/split-lines (or (:seon.fn/doc f) ""))))))))

;;; --- REAL data, REAL forms an agent could type
(def ns-form '(seon.db/pull '[:seon.ns/name {:seon.ns/requires [:seon.ns/name]} {:seon.ns/refers [:seon.ns.refer/local]}] [:seon.ns/name 'my.agents.root]))
(def inbox-form '(seon.db/q '[:find [(pull ?m [:seon.cluster.message/id :seon.cluster.message/at :seon.cluster.message/content]) ...] :where [?m :seon.cluster.message/to [:seon.cluster.agent/id "root"]]]))
(def fns-form '(seon.db/q '[:find [(pull ?f [:seon.fn/sym :seon.fn/doc]) ...] :where [?f :seon.fn/ns ?n] [?n :seon.ns/name seon.db]]))

(defn entry [n form result handle] {:probe/family :probe/entry :ns n :form form :result result :handle handle})

(def transcript
  {:probe/family :probe/transcript
   :basis (db/basis-t db*)
   :entries [(entry 'my.agents.root ns-form (db/pull db* '[:seon.ns/name {:seon.ns/requires [:seon.ns/name]} {:seon.ns/refers [:seon.ns.refer/local]}] [:seon.ns/name 'my.agents.root]) "a1")
             (entry 'my.agents.root inbox-form (vec (db/q '[:find [(pull ?m [:seon.cluster.message/id :seon.cluster.message/at :seon.cluster.message/content]) ...] :where [?m :seon.cluster.message/to [:seon.cluster.agent/id "root"]]] db*)) "b2")
             (entry 'my.agents.root fns-form (vec (take 4 (db/q '[:find [(pull ?f [:seon.fn/sym :seon.fn/doc]) ...] :where [?f :seon.fn/ns ?n] [?n :seon.ns/name seon.db]] db*))) "c3")]})

(defn run-probe! []
  (let [out (java.io.StringWriter.)
        t0 (System/nanoTime)
        cost (atom [])
        one (render transcript {::cost cost ::annotate-floor? true})
        t1 (System/nanoTime)]
    (.write out (str ";;;; PASS 1 — general faces only; messages have no face → the floor\n" one "\n"))
    ;; the viewing agent writes its own message-collection face (rank 2 beats a rank-4 face if one existed)
    (declare-face! [:coll (family-of-identity :seon.cluster.message/id)] 2 'my.agents.root/inbox-view
      (fn [ms ctx] (str/join "\n" (for [m (sort-by :seon.cluster.message/at ms)]
                                    (str (:seon.cluster.message/at m) "  " (subs (:seon.cluster.message/content m) 0 (min 60 (count (:seon.cluster.message/content m)))) "…")))))
    (let [two (render transcript {})]
      (.write out (str "\n;;;; PASS 2 — the agent defined inbox-view; ONLY the inbox entry's value changed\n" two "\n")))
    ;; another agent's entry layout (rank 3) — the whole transcript changes shape, no other function touched
    (declare-face! :probe/entry 3 'my.agents.planner/entry-ai
      (fn [e ctx] (str (:ns e) "=> " (pr-str (:form e)) "\n⟹ " (render (:result e) ctx) "\n⟸ result/" (:handle e))))
    (let [t2 (System/nanoTime) three (render transcript {}) t3 (System/nanoTime)]
      (.write out (str "\n;;;; PASS 3 — planner's entry face (rank 3) now shapes every entry; values untouched\n" three "\n"))
      (.write out (str "\n;;;; timing: pass1 " (/ (- t1 t0) 1e6) " ms, pass3 " (/ (- t3 t2) 1e6) " ms; identity attrs known: " (count identity-attributes) "\n;;;; per-node cost (pass 1, from the threaded ctx): " (pr-str (map #(update % :ms (fn [x] (double (/ (Math/round (* x 100)) 100)))) @cost)) "\n")))
    (spit "/Users/sean/src/seon/tmp/recursive-render-probe.out" (str out))
    :written))
