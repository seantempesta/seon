(ns seon.sci.runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [sci.ctx-store]
            [seon.id :as id]
            [seon.cache]
            [seon.profile :as profile]
            [seon.sci.eval :as eval]
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

(defn- answers
  "What `ctx` answers for every program name at one input each."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] [:vector :int]]}
  [ctx]
  (sci.ctx-store/with-ctx ctx
    (sci/eval-string* ctx (str "[" (str/join " " (map-indexed #(str "(rt.gen/" %2 " " %1 ")") program-names)) "]"))))

(defn- from-scratch
  "What the composition `versions` (name -> version) answers evaluated from its
  own source alone, in a fresh SCI context with no other definitions."
  {:malli/schema [:=> [:cat [:map-of :symbol nat-int?]] [:vector :int]]}
  [versions]
  (let [ctx (sci/init {})]
    (sci/eval-string* ctx (str declaration " " (str/join " " (map (fn [[program-name v]] (source program-name v)) versions))))
    (answers ctx)))

(defn- main-rows
  "Version 0 of every program name."
  {:malli/schema [:=> [:cat] [:vector [:map [:seon.fn/sym :seon.fn/sym] [:seon.fn/source :string] [:seon.program/definition-digest :seon.program/definition-digest]]]]}
  []
  (mapv #(version % 0) program-names))

;; The real program's shapes beside plain functions, each run through the
;; production binder (`seon.sci.eval/bind-analysed!`) on contexts shaped as an
;; acquired base: compiled roots on every Var, `::runtime-rows` and a reach
;; index. A branch installs its own definitions as an overlay or an agent's
;; write does, then binds; its answers are compared with its whole composed
;; source evaluated in a fresh SCI context, which knows nothing of the index.
;; - m0, a macro expanding to a call of h1 (its versions add a constant), and
;;   cm, which uses it: the JVM expanded m0's version 0 into compiled cm.
;; - v0, a value definition holding h0, and cv, which calls through it; va, a
;;   value holding the caller c0 itself, and cva through it; vo, an open value
;;   (it holds r0) holding h3 by object, and cvo through it.
;; - hb, host-bound: SCI cannot run it, so it runs compiled on every runtime,
;;   calling main's h2 (the one exception, F2); chb calls it. Names reaching hb
;;   are not compared.
;; - ch hands h3 to compiled library functions (`mapv`, `reduce`).
;; - cc derives a value through `seon.cache/lookup`, keyed by its input only,
;;   with h0: a branch changing h0 or cc must not read main's derivation, and
;;   main must not read the branch's.
;; - cn calls `al/hx`, the alias al naming rt.a (hx adds 7) on main; a branch
;;   may point al at rt.b (hx adds 9), a change of the namespace's resolution.

(def ^:private macro-name 'm0)

(def ^:private macro-callers '{cm [m0 h1]})

(def ^:private value-callers '{v0 [h0] va [c0] vo [h3 r0]})

(def ^:private more-callers '{cv [v0] cva [va] ch [h3] hb [h2] chb [hb] cc [h0] cvo [vo] cn []})

(defn- macro-source
  "Version `v` of the macro m0."
  {:malli/schema [:=> [:cat nat-int?] :string]}
  [v]
  (str "(defmacro m0 [x] (list '+ " (* 100 v) " (list 'rt.gen/h1 x)))"))

(def ^:private shaped-names
  "Every name of the shaped program but the macro, in definition order."
  (into program-names '[v0 va cv cva cm ch hb chb cc vo cvo cn]))

(defn- shaped-source
  "Version `v` of `program-name` in the shaped program: the macro's, a value's
  (values never change), or the definition `source` and its shapes give."
  {:malli/schema [:=> [:cat :symbol nat-int?] :string]}
  [program-name v]
  (case program-name
    m0 (macro-source v)
    v0 "(def v0 {:f h0})"
    va "(def va c0)"
    vo "(def vo {:f h3 :r r0})"
    cvo (str "(defn cvo [x] (+ " (* 1000 (inc v)) " ((:f vo) x)))")
    cn (str "(defn cn [x] (+ " (* 1000 (inc v)) " (al/hx x)))")
    cv (str "(defn cv [x] (+ " (* 1000 (inc v)) " ((:f v0) x)))")
    cva (str "(defn cva [x] (+ " (* 1000 (inc v)) " (va x)))")
    cm (str "(defn cm [x] (+ " (* 1000 (inc v)) " (m0 x)))")
    ch (str "(defn ch [x] (+ " (* 1000 (inc v)) " (reduce + (mapv h3 [x (inc x)]))))")
    hb (str "(defn hb [x] (+ " (* 1000 (inc v)) " (h2 x)))")
    chb (str "(defn chb [x] (+ " (* 1000 (inc v)) " (hb x)))")
    cc (str "(defn cc [x] (seon.cache/lookup :rt.gen/cc [x] (constantly 1) (fn [] (+ " (* 1000 (inc v)) " (h0 x)))))")
    (source program-name v)))

(defn- shaped-row
  "`program-name`'s version `v` as a runtime row: a value's carries its calls."
  {:malli/schema [:=> [:cat :symbol nat-int?] :map]}
  [program-name v]
  (let [text (shaped-source program-name v)
        sym (symbol "rt.gen" (str program-name))]
    (cond-> {:seon.fn/sym sym :seon.fn/source text :seon.program/definition-digest (id/digest 64 [text])
             :seon.sci.runtime/macro-digests (if (contains? macro-callers program-name)
                                               [[(symbol "rt.gen" "m0") (id/digest 64 [(macro-source 0)])]]
                                               [])}
      (contains? value-callers program-name)
      (assoc :seon.sci.runtime/kind :value
             :seon.sci.runtime/calls (into #{} (map #(symbol "rt.gen" (str %))) (value-callers program-name)))
      (not (contains? value-callers program-name)) (assoc :seon.sci.runtime/kind :function))))

(def ^:private shaped-calls
  "Every shaped name's calls as `:seon.fn/calls` holds them."
  (merge callers macro-callers value-callers more-callers '{m0 [h1]}))

(def ^:private shaped-compiled
  "Version 0 of every shaped name as the JVM compiled it: each body calls
  version 0 of its callees, the macro expanded at compile time, a value
  holding the compiled functions, and r0's `resolve` finding the loaded h1."
  (eval (read-string
         (str "(let ["
              (str/join " " (map #(str % " " (str/replace-first (str/replace (shaped-source % 0) #"\(\(resolve 'rt.gen/(\w+)\) x\)" "($1 x)")
                                                                 #"^\(defn \S+ " "(fn "))
                                 program-names))
              " v0 {:f h0} va c0"
              " cv (fn [x] (+ 1000 ((:f v0) x))) cva (fn [x] (+ 1000 (va x)))"
              " cm (fn [x] (+ 1000 (+ 0 (h1 x)))) ch (fn [x] (+ 1000 (reduce + (mapv h3 [x (inc x)]))))"
              " hb (fn [x] (+ 1000 (h2 x))) chb (fn [x] (+ 1000 (hb x)))"
              " cc (fn [x] (seon.cache/lookup :rt.gen/cc [x] (constantly 1) (fn [] (+ 1000 (h0 x)))))"
              " vo {:f h3 :r r0} cvo (fn [x] (+ 1000 ((:f vo) x))) cn (fn [x] (+ 1000 (+ x 7)))"
              "] {" (str/join " " (map #(str "'" % " " %) shaped-names)) "})"))))

(def ^:private shaped-index
  "The shaped program's reach index: `resolve` is the run-time resolution."
  (runtime/reach-index (into {} (map (fn [[caller callees]]
                                       [(symbol "rt.gen" (str caller))
                                        (if (= 'r0 caller)
                                          '#{clojure.core/resolve}
                                          (into #{} (map #(symbol "rt.gen" (str %))) callees))]))
                             (merge (zipmap (conj shaped-names macro-name) (repeat [])) shaped-calls))
                       '#{clojure.core/resolve}))

(def ^:private alias-namespaces
  "rt.a and rt.b, each defining hx, which rt.gen's alias al names."
  "(ns rt.a) (defn hx [x] (+ x 7)) (ns rt.b) (defn hx [x] (+ x 9))")

(defn- shaped-main
  "Main's shaped runtime: every Var rooted at its compiled version and the
  macro at version 0, al naming rt.a, with the base's snapshot of runtime rows
  (no row for the macro or the host-bound hb) and the reach index."
  {:malli/schema [:=> [:cat] :seon.sci.eval/ctx]}
  []
  ;; `seon.cache/lookup` is a Var, as the program space copies it, so a changed runtime binds its own
  (let [ctx (sci/fork (sci/init {:namespaces {'seon.cache {'lookup (sci/copy-var* #'seon.cache/lookup (sci/create-ns 'seon.cache))}}}))]
    (sci.ctx-store/with-ctx ctx
      (sci/eval-string* ctx (str alias-namespaces " (ns rt.gen) (alias 'al 'rt.a) (declare " (str/join " " shaped-names) ") "
                                 (macro-source 0)))
      (doseq [program-name shaped-names]
        (sci/bind-root! ctx (sci/resolve ctx (symbol "rt.gen" (str program-name))) (shaped-compiled program-name))))
    (assoc ctx :seon.sci.kernel/program-snapshot
           (atom {:seon.sci.eval/runtime-rows (mapv #(shaped-row % 0) (remove #{'hb} shaped-names))
                  :seon.sci.eval/reach-index shaped-index}))))

(defn- shaped-branch
  "A runtime forked from `parent` holding `change` (name -> version) as its own
  definitions, installed as an overlay or an agent's write installs them, and
  al naming `target` (`parent`'s when absent), then bound by the production
  binder, with rt.gen's resolution changed when al moved; its composition
  rides on it."
  {:malli/schema [:function
                  [:=> [:cat :seon.sci.eval/ctx [:map-of :symbol nat-int?]] :seon.sci.eval/ctx]
                  [:=> [:cat :seon.sci.eval/ctx [:map-of :symbol nat-int?] [:maybe :symbol]] :seon.sci.eval/ctx]]}
  ([parent change] (shaped-branch parent change nil))
  ([parent change target]
  (let [ctx (assoc (sci/fork parent) :seon.sci.kernel/program-snapshot (atom @(:seon.sci.kernel/program-snapshot parent)))
        held-target (::alias parent 'rt.a)
        target (or target held-target)
        ;; the overlay interprets the branch's own rows again in definition
        ;; order, so its own callers expand its macro and read its helpers
        own (merge (::versions parent) change)
        ordered (sort-by #(if (= macro-name %) -1 (.indexOf ^java.util.List shaped-names %)) (keys own))]
    (sci.ctx-store/with-ctx ctx
      (when (not= target held-target)
        (sci/eval-string* ctx (str "(in-ns 'rt.gen) (ns-unalias 'rt.gen 'al) (alias 'al '" target ")")))
      (doseq [program-name ordered]
        (sci/eval-string* ctx (str "(in-ns 'rt.gen) " (shaped-source program-name (own program-name))))))
    (#'eval/bind-analysed! ctx (into {} (map (fn [[program-name v]] [(symbol "rt.gen" (str program-name))
                                                                    (id/digest 64 [(shaped-source program-name v)])]))
                                     change)
                           (if (= target held-target) #{} #{'rt.gen}))
    (assoc ctx ::versions (merge (::versions parent) change) ::alias target))))

(defn- shaped-answers
  "What `ctx` answers for every shaped name at one input each, by name; p0
  also called as a schema calls its predicate."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :map]}
  [ctx]
  (sci.ctx-store/with-ctx ctx
    (assoc (zipmap shaped-names
                   (sci/eval-string* ctx (str "[" (str/join " " (map-indexed (fn [i n]
                                                                               (case n
                                                                                 v0 (str "((:f rt.gen/v0) " i ")")
                                                                                 va (str "(rt.gen/va " i ")")
                                                                                 vo (str "((:f rt.gen/vo) " i ")")
                                                                                 (str "(rt.gen/" n " " i ")")))
                                                                             shaped-names)) "]")))
           ::predicate (let [held @(sci/resolve ctx 'rt.gen/p0)]
                         ((if (identical? held (shaped-compiled 'p0)) (shaped-compiled 'p0) held) 5)))))

(defn- shaped-from-scratch
  "What the composition `versions` answers evaluated from its own source alone
  in a fresh SCI context: every name interpreted, hb included, and every
  derivation computed, never read from the cache."
  {:malli/schema [:function [:=> [:cat [:map-of :symbol nat-int?]] :map] [:=> [:cat [:map-of :symbol nat-int?] :symbol] :map]]}
  ([versions] (shaped-from-scratch versions 'rt.a))
  ([versions target]
  (let [ctx (sci/init {:namespaces {'seon.cache {'lookup (fn [_kind _key _weigh derive-fn] (derive-fn))}}})]
    (sci/eval-string* ctx (str alias-namespaces " (ns rt.gen) (alias 'al '" target ") (declare " (str/join " " shaped-names) ") "
                               (macro-source (get versions macro-name 0)) " "
                               (str/join " " (map #(shaped-source % (get versions % 0)) shaped-names))))
    (shaped-answers ctx))))

(def ^:private reaching-hb
  "The names whose answer passes through hb."
  #{'hb 'chb})

(deftest a-composed-runtime-answers-what-its-own-source-answers
  ;; Invariant 1: a runtime depends only on its composition. Each step
  ;; branches one earlier runtime and changes some names (the macro included)
  ;; to some version, through the production binder; every runtime answers
  ;; what its whole source answers from scratch, but where a host-bound name
  ;; stands between, and there the runtime names it. Each chain starts from a
  ;; cold analysis cache, so whichever runtime analyses first must not decide
  ;; another's answer.
  ;; A branch's own value definition is its own write, re-derived by the
  ;; overlay's closure, not by the binder: values never change here.
  (let [changing (vec (remove (into #{'hb} (keys value-callers)) (conj shaped-names macro-name)))
        steps (gen/vector (gen/tuple (gen/choose 0 5) (gen/map (gen/elements changing) (gen/choose 0 2) {:max-elements 6})
                                     (gen/elements [nil 'rt.a 'rt.b]))
                          1 5)
        result (tc/quick-check
                60
                (prop/for-all [chain steps backwards? gen/boolean]
                  (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed :rt.gen/cc})
                  (let [main (assoc (shaped-main) ::versions {})
                        runtimes (reduce (fn [held [pick change target]]
                                           (conj held (shaped-branch (nth held (mod pick (count held))) change target)))
                                         [main] chain)]
                    (every? (fn [ctx]
                              (let [versions (::versions ctx)
                                    expected (shaped-from-scratch versions (::alias ctx 'rt.a))
                                    answered (shaped-answers ctx)
                                    h2-changed? (not= 0 (get versions 'h2 0))]
                                (and (= (apply dissoc expected reaching-hb) (apply dissoc answered reaching-hb))
                                     (or h2-changed? (= expected answered)))))
                            ;; whichever runtime derives first must not decide
                            ;; another's cached answer
                            (if backwards? (rseq runtimes) runtimes))))
                :seed 20261019)]
    (is (:pass? result) "every composition answers what its own source answers")))

(deftest an-open-value-holds-the-functions-of-its-runtime
  ;; Runtime re-review R7: an open value is evaluated again whenever its runtime's delta changes, so a child
  ;; that changes h3 after its parent evaluated vo holds the child's h3, as its own source answers.
  (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed :rt.gen/cc})
  (let [parent (shaped-branch (assoc (shaped-main) ::versions {}) {'h0 1})
        child (shaped-branch parent {'h3 1})]
    (is (= (get (shaped-from-scratch (::versions parent)) 'cvo) (get (shaped-answers parent) 'cvo))
        "the parent's vo holds the h3 it runs")
    (is (= (get (shaped-from-scratch (::versions child)) 'cvo) (get (shaped-answers child) 'cvo))
        "the child's vo holds the child's h3")))

(deftest a-child-derivation-holds-its-own-change-of-its-parents-helper
  ;; Runtime re-review R6's two-step chain: a parent changes h0 to 1 and its child changes it to 2; cc derives
  ;; through `seon.cache` with h0, and each answers its own h0.
  (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed :rt.gen/cc})
  (let [parent (shaped-branch (assoc (shaped-main) ::versions {}) {'h0 1})
        child (shaped-branch parent {'h0 2})]
    (is (= (get (shaped-from-scratch (::versions parent)) 'cc) (get (shaped-answers parent) 'cc)) "the parent's h0")
    (is (= (get (shaped-from-scratch (::versions child)) 'cc) (get (shaped-answers child) 'cc)) "the child's h0")))

(deftest a-derivation-is-keyed-by-its-runtimes-whole-delta
  ;; Two runtimes that differ only by what an ancestor changed: x changes h0 then cc, y only cc. cc derives
  ;; with h0, so whichever derives first must not serve the other.
  (doseq [x-first? [true false]]
    (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed :rt.gen/cc})
    (let [main (assoc (shaped-main) ::versions {})
          x (shaped-branch (shaped-branch main {'h0 1}) {'cc 1})
          y (shaped-branch main {'cc 1})
          answer (fn [ctx] (get (shaped-answers ctx) 'cc))
          pair (if x-first? (let [a (answer x)] [a (answer y)]) (let [b (answer y)] [(answer x) b]))]
      (is (= [(get (shaped-from-scratch (::versions x)) 'cc) (get (shaped-from-scratch (::versions y)) 'cc)] pair)
          "each answers its own composition"))))

(deftest a-namespace-resolution-change-answers-its-own-namespace
  ;; A branch pointing rt.gen's alias al at rt.b re-analyses the names it resolves, and its child keeps it.
  (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed :rt.gen/cc})
  (let [main (assoc (shaped-main) ::versions {})
        moved (shaped-branch main {} 'rt.b)
        child (shaped-branch moved {'h1 1})]
    (is (= (shaped-from-scratch {} 'rt.a) (shaped-answers main)) "main resolves al to rt.a")
    (is (= (shaped-from-scratch {} 'rt.b) (shaped-answers moved)) "the branch resolves al to rt.b")
    (is (= (shaped-from-scratch {'h1 1} 'rt.b) (shaped-answers child)) "and so does its child")))

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

(defn- small-main
  "A loaded program of `sources` ([sym kind source]) in namespace rt.small: `calls` as `:seon.fn/calls` holds them
  (`resolve` the run-time resolution), listed callers first, each name evaluated from its source and then rooted at `roots`, its compiled
  value (a compiled macro's root answers what main's expansion answered)."
  {:malli/schema [:=> [:cat [:sequential [:tuple :qualified-symbol :keyword :string]] [:map-of :qualified-symbol [:set :symbol]]
                       [:map-of :qualified-symbol :any]]
                  :seon.sci.eval/ctx]}
  [sources calls roots]
  (let [ctx (sci/fork (sci/init {}))
        rows (mapv (fn [[sym kind source]]
                     {:seon.fn/sym sym :seon.fn/source source :seon.program/definition-digest (id/digest 64 [source])
                      ::runtime/kind kind ::runtime/calls (get calls sym #{})
                      ::runtime/macro-digests (vec (for [[caller-sym callees] calls :when (= caller-sym sym)
                                                         [macro-sym kind] (map (juxt first second) sources)
                                                         :when (and (= :macro kind) (contains? callees macro-sym))]
                                                     [macro-sym (id/digest 64 [(some #(when (= macro-sym (first %)) (last %)) sources)])]))})
                   sources)]
    (sci.ctx-store/with-ctx ctx
      (sci/eval-string* ctx (str "(ns rt.small) (declare " (str/join " " (map (comp name first) sources)) ")"))
      ;; callees first: `sources` lists each name before what it reads
      (doseq [[_ _ source] (reverse sources)]
        (sci/eval-string* ctx (str "(in-ns 'rt.small) " source)))
      (doseq [[sym v] roots] (sci/bind-root! ctx (sci/resolve ctx sym) v)))
    ;; acquired as the shared program context is: its open definitions analysed once, with no change
    (doto (assoc ctx :seon.sci.kernel/program-snapshot
                 (atom {:seon.sci.eval/runtime-rows rows :seon.sci.eval/changes {}
                        :seon.sci.eval/reach-index (runtime/reach-index calls '#{clojure.core/resolve})}))
      (#'eval/bind-analysed! {} #{}))))

(defn- small-branch
  "A runtime forked from `parent` holding `source` as `sym`'s own definition, bound by the production binder."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :string] :seon.sci.eval/ctx]}
  [parent sym source]
  (let [ctx (assoc (sci/fork parent) :seon.sci.kernel/program-snapshot (atom @(:seon.sci.kernel/program-snapshot parent)))]
    (sci.ctx-store/with-ctx ctx (sci/eval-string* ctx (str "(in-ns 'rt.small) " source)))
    (#'eval/bind-analysed! ctx {sym (id/digest 64 [source])} #{})
    ctx))

(defn- small-answer
  "What `ctx` answers for `text`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :string] :any]}
  [ctx text]
  (sci.ctx-store/with-ctx ctx (sci/eval-string* ctx text)))

(deftest a-value-is-produced-after-its-whole-reach
  ;; Runtime review 3 (Astra) blocker 4: `(def a-value (f))` with f -> g -> h; a branch changing h evaluates
  ;; a-value after every due function its evaluation runs, so it holds the branch's h.
  (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed})
  (let [main (small-main '[[rt.small/a-value :value "(def a-value (f))"] [rt.small/f :function "(defn f [] (g))"]
                           [rt.small/g :function "(defn g [] (h))"] [rt.small/h :function "(defn h [] 1)"]]
                         '{rt.small/a-value #{rt.small/f} rt.small/f #{rt.small/g} rt.small/g #{rt.small/h} rt.small/h #{}}
                         {'rt.small/a-value 1 'rt.small/f (fn [] 1) 'rt.small/g (fn [] 1) 'rt.small/h (fn [] 1)})
        query "[rt.small/a-value (rt.small/f) (rt.small/g)]"
        branch (small-branch main 'rt.small/h "(defn h [] 2)")
        child (small-branch branch 'rt.small/h "(defn h [] 3)")]
    (is (= [2 2 2] (small-answer branch query)) "the branch's value holds its h")
    (is (= [3 3 3] (small-answer child query)) "a continuation's value holds its own h")
    (is (= [1 1 1] (small-answer main query)) "main keeps its own")))

(deftest an-open-macro-expands-with-its-runtime
  ;; Runtime review 3 (Astra) blocker 5: a macro finding its helper by name (`resolve`, open) expands, on a branch
  ;; changing that helper, with the branch's helper, and its caller is analysed again.
  (seon.cache/evict-kinds! #{:seon.sci.runtime/analysed})
  (let [main (small-main '[[rt.small/m :macro "(defmacro m [] ((resolve 'rt.small/h)))"]
                           [rt.small/f :function "(defn f [] (m))"] [rt.small/h :function "(defn h [] 1)"]]
                         '{rt.small/f #{rt.small/m} rt.small/m #{clojure.core/resolve} rt.small/h #{}}
                         {'rt.small/m (fn [_form _env] 1) 'rt.small/f (fn [] 1) 'rt.small/h (fn [] 1)})
        branch (small-branch main 'rt.small/h "(defn h [] 2)")]
    (is (= [2 2 2] (small-answer branch "[(rt.small/h) (rt.small/m) (rt.small/f)]")) "the branch's macro reads its h")
    (is (= [1 1 1] (small-answer main "[(rt.small/h) (rt.small/m) (rt.small/f)]")) "main keeps its own")))
