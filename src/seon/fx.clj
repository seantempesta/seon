(ns seon.fx
  "Effects (lane FX1, notes/option3-FX1-effects.md). Every outside effect kind has ONE
  owner proc in root behind a bounded channel, and `lanes` performer procs:

    agent --socket--> stand-in --:fx/<kind>--> owner --:work--> lane (performs)
    agent <--socket-- stand-in <--api :back--- owner <--:done-- lane

  The owner admits (concurrency = its lanes; priority and budget are request attributes,
  rate and budget counters its live state), queues by priority, and replies {:dest :r}
  on the api's :back, the route the writer's answers already take. A lane performs one
  request with the kind's perform Var (the interop edge: it returns its declared error as
  data) and is the entrant boundary for a defect in it. Nothing here writes a branch: the
  asker records the outcome in its own recording transaction."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.lib :as lib] [seon.step :as step] [seon.config :as cfg] [seon.model :as model]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [clojure.java.shell :as sh] [clojure.string :as str] [jolt.ffi :as ffi]))

(defn port "The stand-in's out-port to kind's owner." {:malli/schema [:=> [:cat :keyword] :keyword]}
  [kind] (keyword "fx" (name kind)))

(defn- error
  "An effect's declared failure, as the one error map (its variant registered in
  seon.problems.edn), raised by seon.fx/call."
  {:malli/schema [:=> [:cat :qualified-keyword :string :map] :seon.error/base]}
  [type message data]
  (contract/error type 'seon.fx/call message data))

;; ---- performers: the interop edges, one Var per kind ------------------------------------

(defn echo "The stand-in effect: answers the request as its value." {:malli/schema [:=> [:cat :map] :map]}
  [req] {:value (:req req)})

(defn write-file
  "Write-back: the text to path, staged beside it and renamed over it (rename(2)), so a
  reader sees the old or the new file. Answers {:value {:bytes n :path path}}, n the path's
  size on disk after the rename, or :seon.fx/io-error {:path :tmp :errno :message} (errno
  absent when the stage itself failed) with the staged file removed."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [path text]} :req}]
  (let [tmp (str path ".fx-" (lib/c-getpid))
        staged (try (spit tmp text) nil
                    (catch Throwable e
                      (.delete (java.io.File. tmp))
                      (error :seon.fx/io-error (str "staging " tmp ": " (or (ex-message e) (str (type e)))) {:path path :tmp tmp})))]
    (or staged
        (let [[r errno] (lib/c-rename tmp path)]
          (if (zero? r)
            {:value {:bytes (.length (java.io.File. ^String path)) :path path}}
            (do (.delete (java.io.File. tmp))
                (error :seon.fx/io-error (str "rename " tmp " -> " path ": " (ffi/errno-message errno)) {:path path :tmp tmp :errno errno})))))))

;; Seon's own source: the program read from the tree a generation was built from, and
;; main's merged forms committed for the next one (seon.source). Never the working tree:
;; reading is read-only, and a commit is git's plumbing on a ref, so no checkout moves.

(defn read-source
  "The text of every .clj file under root's dirs (relative paths, e.g. src/seon/fx.clj),
  sorted by path. Answers {:value {path text}} or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root dirs]} :req}]
  (try
    (let [base (.getCanonicalPath (java.io.File. ^String root))]
      {:value (into (sorted-map)
                    (for [d dirs
                          f (file-seq (java.io.File. (str base "/" d)))
                          :let [p (.getCanonicalPath ^java.io.File f)]
                          :when (and (.isFile ^java.io.File f) (.endsWith p ".clj"))]
                      [(subs p (inc (count base))) (slurp f)]))})
    (catch Throwable e (error :seon.fx/io-error (or (ex-message e) (str (type e))) {:path root}))))

(defn- git!
  "git in dir with args (an env prefix first when given), its trimmed output; a failure
  throws its stderr (read-source and git-commit answer it as :seon.fx/io-error)."
  {:malli/schema [:=> [:cat [:maybe :string] :string [:sequential :string] [:maybe :string]] :string]}
  [index dir args in]
  (let [cmd (concat (when index ["env" (str "GIT_INDEX_FILE=" index)]) ["git" "-C" dir] args)
        r (if in (apply sh/sh (concat cmd [:in in])) (apply sh/sh cmd))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (first args) ": " (str/trim (str (:err r) (:out r)))) {})))
    (str/trim (:out r))))

(defn git-commit
  "Commit files ({relative path text}) on branch of the repository at dir, with message,
  through git's plumbing: blobs, a tree from the branch's own with the files replaced, a
  commit whose parent is the branch head (a branch not there yet starts at dir's HEAD, the
  commit the running generation's tree checks out), and the ref moved only from that head. No
  working tree or checkout is touched, so main and every lane's files stay as they are.
  Answers {:value {:sha :files n}} ({:sha nil :files 0} when there is nothing to write),
  or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir branch files message]} :req}]
  (if (empty? files)
    {:value {:sha nil :files 0}}
    (try
      (let [ref (str "refs/heads/" branch)
            have? (zero? (:exit (sh/sh "git" "-C" dir "rev-parse" "--verify" "--quiet" ref)))
            parent (git! nil dir ["rev-parse" "--verify" (if have? ref "HEAD")] nil)
            gp (git! nil dir ["rev-parse" "--git-path" (str "seon-index-" (lib/c-getpid))] nil)
            index (if (str/starts-with? gp "/") gp (str dir "/" gp))]
        (try
          (git! index dir ["read-tree" parent] nil)
          (doseq [[path text] (sort files)]
            (git! index dir ["update-index" "--add" "--cacheinfo" (str "100644," (git! nil dir ["hash-object" "-w" "--stdin"] text) "," path)] nil))
          (let [sha (git! nil dir ["commit-tree" (git! index dir ["write-tree"] nil) "-p" parent "-m" message] nil)]
            ;; only from the head read above; a new branch only if none appeared meanwhile
            (git! nil dir ["update-ref" ref sha (if have? parent "0000000000000000000000000000000000000000")] nil)
            {:value {:sha sha :files (count files)}})
          (finally (.delete (java.io.File. ^String index)))))
      (catch Throwable e (error :seon.fx/io-error (or (ex-message e) (str (type e))) {:path dir})))))

;; ---- the owner -----------------------------------------------------------------------

(defn refill
  "The owner's token bucket at now-ms (lane R3): tokens grow at :per-ms up to :cap since :at.
  A bucket is the provider's rate as data: tokens per second, set from the dial and halved by
  a rate-limit outcome."
  {:malli/schema [:=> [:cat :seon.fx/bucket :int] :seon.fx/bucket]}
  [{:keys [tokens cap per-ms at] :as b} now-ms]
  (assoc b :tokens (min cap (+ tokens (* per-ms (max 0 (- now-ms at))))) :at (max at now-ms)))

(defn- admit
  "What the owner does with a queued request at now-ms: :run, :wait (no free lane, or its
  bucket holds fewer tokens than the request's estimate :est: a :tick or a lane's :done
  dispatches again), or the refusal (the task spent its :budget; spend is this proc's live
  counter, keyed by :budget-key)."
  {:malli/schema [:=> [:cat :map :map :int] [:or [:enum :run :wait] :map]]}
  [st {:keys [budget budget-key est]} now-ms]
  (cond
    (and budget (>= (get-in st [:spent budget-key] 0) budget))
    (error :seon.fx/budget-spent "the request's budget is spent" {:budget budget :spent (get-in st [:spent budget-key])})
    (>= (:busy st) (:lanes st)) :wait
    (and (:bucket st) (< (:tokens (refill (:bucket st) now-ms)) (or est 0))) :wait
    :else :run))

(defn- settle
  "The owner at a lane's outcome m: the lane is free; the task's spend grows by the actual
  cost; the bucket gets back (or pays) the difference from the estimate; a rate-limit outcome
  (the provider's 429, the named dragon of this edge) halves the bucket's rate, and each
  other outcome raises it by a twentieth back toward the dial (AIMD, TCP's congestion rule)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st {:keys [budget-key cost est outcome]}]
  (let [limited? (= :seon.model/rate-limited (:seon.error/type outcome))]
    (cond-> (-> st (update :busy dec) (update :done inc))
      budget-key (update-in [:spent budget-key] (fnil + 0) (or cost 1))
      (:bucket st) (update :bucket (fn [b] (-> b (update :tokens + (- (or est 0) (or cost 0)))
                                               (assoc :per-ms (if limited?
                                                                (max (/ (:rate-ms b) 64) (/ (:per-ms b) 2))
                                                                (min (:rate-ms b) (+ (:per-ms b) (/ (:rate-ms b) 20)))))))))))

(defn- reply!
  "The outcome to its asker. Through the api's :back (the writer's answer route), or with
  cfg/FX-DIRECT straight to the asker's stand-in by its id: [agent :in]."
  {:malli/schema [:=> [:cat :seon.host/volatile :map :map] :nil]}
  [o dest r]
  (if (and cfg/FX-DIRECT (:agent dest))
    (step/out! o [(:agent dest) :in] {:op :write :msg (assoc r :op (:op dest) :k (:k dest))})
    (step/out! o :out {:dest dest :r r})))

(defn- wake-later
  "The clock (lane R3's :tick): when the head request waits on the bucket alone (a lane is
  free), one :tick is due on the owner's :tick port once the bucket refills to its estimate.
  Answers st, marked :tick-due until that tick arrives."
  {:malli/schema [:=> [:cat :map :map :int] :map]}
  [st m now]
  (if (and (:bucket st) (:tick st) (not (:tick-due st)) (< (:busy st) (:lanes st)))
    (let [b (refill (:bucket st) now)
          ms (long (Math/ceil (/ (max 1 (- (or (:est m) 0) (:tokens b))) (:per-ms b))))]
      (a/take! (a/timeout ms) (fn [_] (a/put! (:tick st) {})))
      (assoc st :tick-due true))
    st))

(defn- dispatch
  "Take queued requests in priority order while lanes are free; a refused one is answered
  at once. Answers st."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (loop [st st]
    (if-let [[qk m] (first (:queue st))]
      (let [now (quot (lib/now-ns) 1000000)
            d (admit st m now)]
        (cond
          (= :wait d) (wake-later st m now)
          (= :run d) (do (step/out! o :to-lanes (assoc m :taken-ns (lib/now-ns)))
                         (recur (cond-> (-> st (update :queue dissoc qk) (update :busy inc))
                                  (:bucket st) (update :bucket #(update (refill % now) :tokens - (or (:est m) 0))))))
          :else (do (reply! o (:dest m) (assoc d :seon.fx/kind (:kind st)))
                    (recur (update st :queue dissoc qk)))))
      st)))

(defn- bound
  "The owner's queue holds at most cfg/FX-BUF requests; at the bound it reads only :done
  (Flow's input filter), so stand-ins park on :in: back-pressure to the asking agent."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] (assoc st ::flow/input-filter (if (>= (count (:queue st)) cfg/FX-BUF) #(not= :in %) identity)))

(defn owner-proc
  "The owner of one effect kind. In :in (the stand-ins' requests {:req :dest :prio
  :budget :budget-key :agent}, addressed by id; cfg/FX-BUF), :done (the lanes'
  outcomes). Out :to-lanes (its performers' shared channel), :out (the api's :back:
  {:dest :r})."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:kind "the effect kind" :lanes "performers, its concurrency"
                :rate "tokens per second the provider admits (nil: no bucket)"
                :tick "its clock's channel (external in-port, with :rate): a :tick when the bucket refilled"}
       :ins {:in "requests, addressed [:fx/<kind> :in] by any proc" :done "its lanes' outcomes"} :outs {}
       ;; admission and routing only: never blocks, never computes long
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:kind :lanes :busy :done]) (assoc :queued (count (:queue st)) :spent (:spent st))))})
  ([args] (assoc (select-keys args [:kind :lanes]) :busy 0 :done 0 :seq 0 :queue (sorted-map) :spent {}
                 :bucket (when-let [r (:rate args)]
                           {:tokens (double r) :cap (double r) :per-ms (/ r 1000.0) :rate-ms (/ r 1000.0) :at (quot (lib/now-ns) 1000000)})
                 :tick (:tick args)
                 ::flow/in-ports (if (:tick args) {:tick (:tick args)} {})
                 ::flow/out-ports {:to-lanes (:work args) :out (:back args)}))
  ([st _] st)
  ([st in m]
   (let [o (volatile! {})]
     (if (nil? m)
       [st nil]
       (case in
         :in (let [n (inc (:seq st))]
               [(bound (dispatch (-> st (assoc :seq n) (assoc-in [:queue [(- (:prio m 0)) n]] (assoc m :in-ns (lib/now-ns)))) o)) @o])
         :done (do (reply! o (:dest m) (:outcome m))
                   ;; root counts every failed effect across agents (seon.error, class :effect);
                   ;; the asker records its own outcome on its branch
                   (when (:seon.error/type (:outcome m))
                     (step/out! o :out {:op :problem :class :effect :error (dissoc (:outcome m) :seon.fx/us :seon.fx/queue-us)
                                        :agent (get-in m [:dest :agent])}))
                   [(bound (dispatch (settle st m) o)) @o])
         :tick [(bound (dispatch (dissoc st :tick-due) o)) @o])))))

(defn lane-proc
  "One performer of an effect kind: takes a request from its owner's shared channel,
  performs it with the kind's Var, and gives the owner the outcome (with its timing).
  The entrant boundary for a defect in the perform Var: the defect is the outcome, as an
  error map, so its asker is always answered."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:perform "the kind's perform Var"} :ins {} :outs {}
       ;; the effect blocks in native IO (an HTTP read, a file write). Jolt runs :io procs
       ;; on fibers bound to their carrier thread for life, and a blocking FFI call pins the
       ;; carrier, stranding every other fiber placed on it (inv2: replies 4-15 s late).
       ;; :mixed gives each lane its own thread: the declared workload is true, and the
       ;; lane count already bounds the threads
       :workload :mixed
       :ping-map-fn (fn [st] (select-keys st [:n]))})
  ([args] {:perform (:perform args) :kind (:kind args) :ctx (:ctx args) :n 0
           :owner (:owner args) ::flow/in-ports {:work (:work args)}})
  ([st _] st)
  ([st _ m]
   (if (nil? m)
     [st nil]
     (let [t0 (lib/now-ns)
           r (try ((:perform st) (assoc m :ctx (:ctx st)))
                  (catch Throwable e (error :seon.fx/defect (or (ex-message e) (str (type e))) {})))
           t1 (lib/now-ns)
           outcome (assoc r :seon.fx/kind (:kind st) :seon.fx/queue-us (quot (- (:taken-ns m) (:in-ns m)) 1000)
                          :seon.fx/us (quot (- t1 t0) 1000))]
       [(update st :n inc) {[(:owner st) :done] [(cond-> {:dest (:dest m) :outcome outcome :budget-key (:budget-key m) :est (:est m 0)}
                                     (:cost r) (assoc :cost (:cost r)))]}]))))

(defn model
  "The model call (seon.model/complete) under root's provider (:ctx, which names the API
  key's environment variable; root alone reads it); a request's :model overrides the
  provider's model. Answers {:value completion :cost tokens} or the provider error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [req ctx]}]
  (let [p (cond-> (:provider ctx) (and (:model req) (= :openai-compat (:kind (:provider ctx)))) (assoc :model (:model req)))
        c (model/complete p (dissoc req :model))]
    (if (:seon.error/type c) c {:value c :cost (+ (:prompt-tokens c) (:completion-tokens c))})))

(defn kinds
  "Every effect kind root runs: its perform Var, its lanes (its concurrency) and the
  context only root holds (the model provider)."
  {:malli/schema [:=> [:cat :map] :map]}
  [provider]
  {:echo {:perform #'echo :lanes 1}
   :file {:perform #'write-file :lanes 1}
   :source {:perform #'read-source :lanes 1}
   :git {:perform #'git-commit :lanes 1}
   :model {:perform #'model :lanes cfg/MODEL-LANES :ctx {:provider provider} :rate (when (pos? cfg/MODEL-RATE) cfg/MODEL-RATE)}})

(defn procs
  "The owner and lane procs of every kind, for the root graph. Any proc reaches an owner
  by its id, [:fx/<kind> :in]; back is the api's :back channel. Answers {pid def}."
  {:malli/schema [:=> [:cat :map :seon.host/chan] :map]}
  [kinds back]
  (reduce-kv
    (fn [acc kind {:keys [perform lanes ctx rate]}]
      (let [work (a/chan lanes) owner (keyword "fx" (name kind))]
        (-> acc
            (assoc owner {:proc (flow/process #'owner-proc)
                          :chan-opts {:in {:buf-or-n cfg/FX-BUF} :done {:buf-or-n (* 2 lanes)}}
                          :args (cond-> {:kind kind :lanes lanes :work work :back back}
                                  rate (assoc :rate rate :tick (a/chan 1)))})
            (into (for [i (range lanes)]
                    [(keyword (str "fx." (name kind)) (str i))
                     {:proc (flow/process #'lane-proc) :args {:kind kind :perform perform :ctx ctx :work work :owner owner}}])))))
    {} kinds))
