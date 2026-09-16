;;; R5 probe — what clj-kondo emits for this source tree, and what we keep.
;;;
;;; Read-only. Every form was evaluated on cluster `default` through
;;; mcp__seon__eval_clj in `jvm` mode with explicit custody
;;; ((seon.operator/connection "default")). No cluster was stopped, reforked
;;; or restarted; no test JVM was launched.

(comment

  ;; P0 — what the program graph stores today (default, 2026-09-16).
  ;; => {:fns 4764 :tests 1779 :ns 437 :files 332
  ;;     :call-edges 63198 :keyword-edges 32836 :lint 1014}
  (let [db (datahike.api/db (seon.operator/connection "default"))
        c (fn [a] (count (seon.db/q '[:find ?e :in $ ?a :where [?e ?a]] db a)))]
    {:fns (c :seon.fn/sym) :tests (c :seon.test/sym) :ns (c :seon.ns/name)
     :files (c :seon.fn.file/path)
     :call-edges (count (seon.db/q '[:find ?e ?t :where [?e :seon.fn/calls ?t]] db))
     :keyword-edges (count (seon.db/q '[:find ?e ?k :where [?e :seon.fn/keywords ?k]] db))
     :lint (c :seon.lint/id)})

  ;; P1 — every facet clj-kondo can emit for the same 332 inputs, measured.
  ;; 4 015 ms total, cache off, lint skipped.
  ;; => {:var-usages 124557 :keywords 79625 :locals 33902 :local-usages 75642
  ;;     :java-class-usages 3362 :instance-invocations 2953 :protocol-impls 115
  ;;     :symbols 858 :namespace-usages 3073 :var-definitions 6675
  ;;     :namespace-definitions 340}
  ;; var-usages: 117 120 carry :arity, 38 439 are macro usages, 8 389 have no
  ;; :from-var, 37 are defmethod; 70 997 distinct caller→target, 72 059
  ;; distinct caller→target→arity.
  ;; keywords: 56 792 qualified, 1 039 :keys-destructuring, 35 725 distinct
  ;; holder×qualified-keyword. java: 247 distinct classes, 303 distinct
  ;; instance method names. 531 var-definitions carry a :lang arm.
  (let [files (->> (concat (file-seq (clojure.java.io/file "/Users/sean/src/seon/src"))
                           (file-seq (clojure.java.io/file "/Users/sean/src/seon/test")))
                   (filter #(.isFile %)) (map #(.getPath %))
                   (filter #(re-find #"\.clj[cs]?$" %)) sort vec)
        t0 (System/nanoTime)
        res (clj-kondo.core/run!
             {:lint files :lang :clj :repro true :cache false :skip-lint true
              :config {:analysis {:arglists true :var-usages true :keywords true
                                  :locals true :protocol-impls true :symbols true
                                  :java-class-usages true :instance-invocations true
                                  :context true
                                  :var-definitions {:shallow false :meta true}
                                  :namespace-definitions {:shallow false :meta true}}}})
        a (:analysis res)
        vu (:var-usages a)]
    {:files (count files) :ms (quot (- (System/nanoTime) t0) 1000000)
     :counts (into (sorted-map) (map (fn [[k v]] [k (count v)])) a)
     :var-usages {:with-arity (count (filter #(contains? % :arity) vu))
                  :defmethod (count (filter :defmethod vu))
                  :macro (count (filter :macro vu))
                  :no-from-var (count (remove :from-var vu))
                  :distinct-caller-target
                  (count (into #{} (map (juxt :from :from-var :to :name)) vu))
                  :distinct-with-arity
                  (count (into #{} (comp (filter #(contains? % :arity))
                                         (map (juxt :from :from-var :to :name :arity))) vu))}
     :java {:distinct-classes (count (into #{} (map :class) (:java-class-usages a)))
            :distinct-methods (count (into #{} (map :method-name) (:instance-invocations a)))}
     :keywords {:destructuring (count (filter :keys-destructuring (:keywords a)))
                :qualified (count (filter :ns (:keywords a)))
                :distinct-holder-kw
                (count (into #{} (comp (filter :ns) (map (juxt :from :from-var :ns :name)))
                             (:keywords a)))}
     :cljc (count (filter :lang (:var-definitions a)))})

  ;; P2 — :seon.fn/writes is derivable from facets we ALREADY request: the
  ;; qualified keywords lexically inside the span of each seon.db/transact!
  ;; var-usage. No new clj-kondo facet is needed; only the positions we throw
  ;; away at src/seon/fn.clj:290.
  ;; => {:transact-call-sites 1008 :sites-with-from-var 1008
  ;;     :writes-pairs 3033 :distinct-writers 524 :distinct-attributes 415
  ;;     :installed-pairs 2620 :installed-attributes 296}
  (let [files (->> (concat (file-seq (clojure.java.io/file "/Users/sean/src/seon/src"))
                           (file-seq (clojure.java.io/file "/Users/sean/src/seon/test")))
                   (filter #(.isFile %)) (map #(.getPath %))
                   (filter #(re-find #"\.clj[cs]?$" %)) sort vec)
        a (:analysis (clj-kondo.core/run!
                      {:lint files :lang :clj :repro true :cache false :skip-lint true
                       :config {:analysis {:var-usages true :keywords true}}}))
        kws-by-file (group-by :filename (filter :ns (:keywords a)))
        writer? #(and (= 'seon.db (:to %)) (#{'transact! 'transact} (:name %)))
        calls (filter writer? (:var-usages a))
        inside (fn [c k]
                 (let [r (:row k) cc (:col k)]
                   (and (:end-row c)
                        (or (> r (:row c)) (and (= r (:row c)) (>= cc (:col c))))
                        (or (< r (:end-row c)) (and (= r (:end-row c)) (<= cc (:end-col c)))))))
        pairs (into #{}
                    (mapcat (fn [c]
                              (keep (fn [k]
                                      (when (inside c k)
                                        [(symbol (str (:from c)) (str (:from-var c)))
                                         (keyword (str (:ns k)) (str (:name k)))]))
                                    (get kws-by-file (:filename c)))))
                    calls)
        installed (set (keys (:schema (datahike.api/db (seon.operator/connection "default")))))]
    {:transact-call-sites (count calls)
     :sites-with-from-var (count (filter :from-var calls))
     :writes-pairs (count pairs)
     :distinct-writers (count (into #{} (map first) pairs))
     :distinct-attributes (count (into #{} (map second) pairs))
     :installed-pairs (count (filter #(contains? installed (second %)) pairs))
     :installed-attributes (count (into #{} (comp (map second) (filter installed)) pairs))})

  ;; P3 — the datom budget the proposals must fit inside.
  ;; => {:total-datoms 441609 :fn-calls-datoms 63198 :keyword-datoms 32836}
  (let [db (datahike.api/db (seon.operator/connection "default"))]
    {:total-datoms (count (datahike.api/datoms db :eavt))
     :fn-calls-datoms (count (seon.db/q '[:find ?e ?t :where [?e :seon.fn/calls ?t]] db))
     :keyword-datoms (count (seon.db/q '[:find ?e ?k :where [?e :seon.fn/keywords ?k]] db))})

  ;; P4 — the installed seon.fn* attributes and their holders (elided by the
  ;; MCP profile at 32 of 78 keys; the rows cited in the note are exact).
  (let [db (datahike.api/db (seon.operator/connection "default"))]
    (into (sorted-map)
          (keep (fn [[k v]]
                  (when (and (keyword? k) (= "seon.fn" (namespace k)))
                    [k [(:db/valueType v) (:db/cardinality v)
                        (count (seon.db/q '[:find ?e :in $ ?a :where [?e ?a]] db k))]])))
          (:schema db)))
  )
