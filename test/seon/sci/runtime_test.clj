(ns seon.sci.runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [sci.ctx-store]
            [seon.id :as id]
            [seon.profile :as profile]
            [seon.sci.runtime :as runtime]))

;; A synthetic program of helpers and callers in one namespace, every name in
;; three versions: branches change overlapping helpers and callers, and a
;; composition picks one version per name, as a merge picks one digest.

(def ^:private helpers '[h0 h1 h2 h3])

(def ^:private callers
  "Each caller and the names it calls: c2 calls a caller, so a composition can
  change a caller under another caller. k0 and k1 stand for the numeric
  kernels a runtime keeps compiled while nothing they reach changed (k1 reaches
  h0 through k0), p0 for a schema predicate, and r0 calls h1 through `resolve`,
  which the call index cannot follow."
  '{c0 [h0 h1] c1 [h1 h2 h3] c2 [c0 h3] k0 [h0] k1 [k0 h3] p0 [h2] r0 [h1]})

(def ^:private program-names (into helpers (sort (keys callers))))

(defn- call-text
  "The text of `program-name`'s call of `callee` on `x`: r0 resolves its callee
  at run time."
  {:malli/schema [:=> [:cat :symbol :symbol] :string]}
  [program-name callee]
  (if (= 'r0 program-name)
    (str "((resolve 'rt.gen/" callee ") x)")
    (str "(" callee " x)")))

(defn- source
  "Version `v` of `program-name`'s definition."
  {:malli/schema [:=> [:cat :symbol nat-int?] :string]}
  [program-name v]
  (if-let [callees (get callers program-name)]
    (str "(defn " program-name " [x] (+ " (* 1000 (inc v)) " " (str/join " " (map #(call-text program-name %) callees)) "))")
    (str "(defn " program-name " [x] (+ x " (* 10 (inc v)) " " (subs (str program-name) 1) "))")))

(defn- row
  "`text`, one `defn` in namespace `namespace-name`, as a runtime row."
  {:malli/schema [:=> [:cat :symbol :string] [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]]}
  [namespace-name text]
  {:seon.fn/sym (symbol (str namespace-name) (second (str/split text #" ")))
   :seon.fn/source text
   :seon.program/definition-digest (id/digest 64 [text])})

(defn- version
  "Version `v` of program name `name` as a runtime row."
  {:malli/schema [:=> [:cat :symbol nat-int?] [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]]}
  [program-name v]
  (row 'rt.gen (source program-name v)))

(def ^:private declaration (str "(ns rt.gen) (declare " (str/join " " program-names) ")"))

(defn- fresh-space
  "A Var space holding `declaration`'s names."
  {:malli/schema [:=> [:cat :string] :seon.sci.eval/ctx]}
  [text]
  (let [ctx (sci/init {})]
    (sci/eval-string* ctx text)
    (runtime/space ctx)))

(def ^:private compiled
  "Version 0 of every program name as the JVM compiled it: each calls version
  0 of its callees, as a compiled Var calls the loaded roots, and r0's
  `resolve` finds the loaded h1."
  (eval (read-string (str "(letfn ["
                          (str/join " " (map #(str/replace-first (str/replace (source % 0) #"\(\(resolve 'rt.gen/(\w+)\) x\)" "($1 x)")
                                                                 "(defn " "(")
                                             program-names))
                          "] {" (str/join " " (map #(str "'" % " " %) program-names)) "})"))))

(defn- answers
  "What `ctx` answers for every program name at one input each, then for p0
  called as a schema calls its predicate: the loaded Var's compiled function
  unless `ctx` binds the Var otherwise (`seon.sci.kernel/held-apart-var`)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] [:vector :int]]}
  [ctx]
  (sci.ctx-store/with-ctx ctx
    (let [each (sci/eval-string* ctx (str "[" (str/join " " (map-indexed #(str "(rt.gen/" %2 " " %1 ")") program-names)) "]"))
          held @(sci/resolve ctx 'rt.gen/p0)
          predicate (if (identical? held (compiled 'p0)) (compiled 'p0) held)]
      (conj each (predicate 5)))))

(defn- from-scratch
  "What the composition `versions` (name -> version) answers evaluated from its
  own source alone, in a fresh SCI context with no other definitions."
  {:malli/schema [:=> [:cat [:map-of :symbol nat-int?]] [:vector :int]]}
  [versions]
  (let [ctx (sci/init {})]
    (sci/eval-string* ctx (str declaration " " (str/join " " (map (fn [[program-name v]] (source program-name v)) versions))))
    (sci.ctx-store/with-ctx ctx
      (conj (sci/eval-string* ctx (str "[" (str/join " " (map-indexed #(str "(rt.gen/" %2 " " %1 ")") program-names)) "]"))
            (sci/eval-string* ctx "(rt.gen/p0 5)")))))

(defn- main-rows
  "Version 0 of every program name."
  {:malli/schema [:=> [:cat] [:vector [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]]]}
  []
  (mapv #(version % 0) program-names))

(defn- versions-of
  "The composition `ctx` runs: each program name to the version its digest names."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] [:map-of :symbol nat-int?]]}
  [ctx]
  (let [by-digest (into {} (for [program-name program-names v (range 3)]
                             [(:seon.program/definition-digest (version program-name v)) v]))]
    (into {} (map (fn [[sym digest]] [(symbol (name sym)) (by-digest digest)])) (::runtime/names ctx))))

(def ^:private reach
  "The synthetic program's reach index: `resolve` is the run-time resolution."
  (runtime/reach-index (into (zipmap (map #(symbol "rt.gen" (str %)) program-names) (repeat #{}))
                             (map (fn [[caller callees]]
                                    [(symbol "rt.gen" (str caller))
                                     (cond-> (into #{} (map #(symbol "rt.gen" (str %))) callees)
                                       (= 'r0 caller) (-> (disj 'rt.gen/h1) (conj 'clojure.core/resolve)))]))
                             callers)
                       '#{clojure.core/resolve}))

(defn- with-compiled
  "`ctx` running compiled every name whose closed reach holds no name its
  program changed from main's (`seon.sci.runtime/compiled-names`), as a
  branch's runtime keeps the JVM's compiled Var; every other name runs its
  analysed definition."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map-of :symbol :string]] :seon.sci.eval/ctx]}
  [ctx main-digests]
  (let [changed (into #{} (keep (fn [[sym digest]] (when-not (= digest (get main-digests sym)) sym))) (::runtime/names ctx))
        kept (runtime/compiled-names reach (::runtime/closed reach) changed)]
    (sci.ctx-store/with-ctx ctx
      (doseq [sym (keys (::runtime/names ctx))
              :let [program-name (symbol (name sym))
                    sci-var (sci/resolve ctx sym)]]
        (cond (contains? kept sym) (sci/bind-root! ctx sci-var (compiled program-name))
              ;; a compiled root its parent kept, which this change reaches
              (identical? @sci-var (compiled program-name))
              (sci/bind-root! ctx sci-var (runtime/analysed ctx (version program-name 0))))))
    (assoc ctx ::kept kept)))

(deftest a-composed-runtime-answers-what-its-own-source-answers
  ;; Invariant 1: a runtime depends only on its name -> digest map. Each step
  ;; branches one earlier runtime and changes some names to some version; every
  ;; runtime must answer what its own source answers from scratch, while it
  ;; runs compiled each name the reach rule keeps compiled: kernels, a
  ;; predicate and the helpers under them change too.
  (let [main (runtime/bind (fresh-space declaration) (main-rows))
        main-digests (::runtime/names main)
        steps (gen/vector (gen/map (gen/elements program-names) (gen/choose 0 2) {:max-elements 7}) 1 6)
        result (tc/quick-check
                60
                (prop/for-all [changes steps]
                  (let [runtimes (reduce (fn [held change]
                                           (conj held (with-compiled
                                                        (runtime/bind (nth held (mod (count change) (count held)))
                                                                      (mapv (fn [[program-name v]] (version program-name v)) change))
                                                        main-digests)))
                                         [(with-compiled main main-digests)] changes)]
                    (every? #(= (from-scratch (versions-of %)) (answers %)) runtimes)))
                :seed 20261019)]
    (is (:pass? result) "every composition answers what its own source answers")
    (is (= #{'rt.gen/h0 'rt.gen/h1 'rt.gen/h2 'rt.gen/h3 'rt.gen/k0 'rt.gen/k1 'rt.gen/p0 'rt.gen/c0 'rt.gen/c1 'rt.gen/c2}
           (::kept (with-compiled main main-digests)))
        "main runs compiled every name but the one that resolves at run time")
    (is (= #{'rt.gen/h1 'rt.gen/h3}
           (::kept (with-compiled (runtime/bind main [(version 'h0 1) (version 'h2 1)]) main-digests)))
        "a change to h0 and h2 interprets the kernels and the predicate above them")))

(deftest composing-branches-analyses-each-distinct-digest-once
  ;; Invariant 4: A changes h1, B changes h2, C composes A's h1 with B's h2.
  (let [main (runtime/bind (fresh-space declaration) (main-rows))
        a (runtime/bind main [(version 'h1 1)])
        b (runtime/bind main [(version 'h2 1)])
        c (runtime/bind a [(version 'h2 1)])
        main-versions (zipmap program-names (repeat 0))]
    (is (= (count program-names) (count (::runtime/analysed main))) "main analyses each of its definitions")
    (is (= 1 (count (::runtime/analysed a))) "A analyses its one change")
    (is (= 1 (count (::runtime/analysed b))) "B analyses its one change")
    (is (= #{} (::runtime/analysed c)) "C's composition analyses nothing: both digests are cached")
    (is (= #{'rt.gen/h2} (::runtime/bound c)) "C binds only what differs from A")
    (is (= (from-scratch (assoc main-versions 'h1 1)) (answers a)))
    (is (= (from-scratch (assoc main-versions 'h2 1)) (answers b)))
    (is (= (from-scratch (assoc main-versions 'h1 1 'h2 1)) (answers c)))
    (is (= (from-scratch main-versions) (answers main)) "main keeps its own answers")))

(deftest creating-a-branch-and-continuing-an-env-cost-only-what-changed
  ;; Invariants 2 and 3: a branch is a fork and an empty difference; a request
  ;; with nothing changed pays nothing for the program; one change pays one
  ;; analysis and one binding.
  (let [main (runtime/bind (fresh-space declaration) (main-rows))
        branch (profile/measure #(runtime/bind main []))
        unchanged (profile/measure #(runtime/bind (:seon.profile/value branch) (main-rows)))
        one (profile/measure #(runtime/bind (:seon.profile/value unchanged) [(version 'h3 2)]))]
    (is (= #{} (::runtime/analysed (:seon.profile/value branch)) (::runtime/bound (:seon.profile/value branch))))
    (is (< (:seon.profile/alloc-bytes branch) (* 1024 1024)) "a branch allocates under 1 MB")
    (is (= #{} (::runtime/analysed (:seon.profile/value unchanged)) (::runtime/bound (:seon.profile/value unchanged)))
        "an unchanged continuation analyses and binds nothing")
    (is (< (:seon.profile/alloc-bytes unchanged) (* 1024 1024)) "an unchanged continuation allocates under 1 MB")
    (is (= 1 (count (::runtime/analysed (:seon.profile/value one)))) "one change is one analysis")
    (is (= #{'rt.gen/h3} (::runtime/bound (:seon.profile/value one))) "and one binding")
    (is (< (:seon.profile/alloc-bytes one) (* 4 1024 1024)) "one change allocates a few MB at most")))

(deftest a-hundred-branches-analyse-each-distinct-digest-once
  ;; Invariant 5 at scale: 100 branches each rewrite 100 of 1,000 functions,
  ;; half of each branch's rewrites shared with the other branches.
  (let [names (mapv #(symbol (str "f" %)) (range 1000))
        scaled (fn [program-name v] (row 'rt.scale (str "(defn " program-name " [x] (+ x " v "))")))
        main (runtime/bind (fresh-space (str "(ns rt.scale) (declare " (str/join " " names) ")"))
                           (mapv #(scaled % 0) names))
        rewrites (fn [b] (into (mapv #(scaled (nth names (mod (+ (* 7 b) %) 1000)) 1) (range 50))
                               (mapv #(scaled (nth names (mod (+ (* 13 b) 500 %) 1000)) (+ 2 b)) (range 50))))
        branches (mapv #(runtime/bind main (rewrites %)) (range 100))
        distinct-digests (into #{} (comp (mapcat rewrites) (map :seon.program/definition-digest)) (range 100))
        call (fn [ctx sym] (sci.ctx-store/with-ctx ctx (sci/eval-string* ctx (str "(" sym " 0)"))))]
    (is (= 1000 (count (::runtime/analysed main))))
    (is (= (count distinct-digests) (reduce + (map (comp count ::runtime/analysed) branches)))
        "every distinct digest is analysed once across all branches")
    (is (= [0 0 1 2] [(call main 'rt.scale/f0) (call main 'rt.scale/f500)
                      (call (first branches) 'rt.scale/f0) (call (first branches) 'rt.scale/f500)])
        "each runtime answers its own versions")))
