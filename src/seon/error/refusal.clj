(ns seon.error.refusal
  "Pure diagnostic construction and cause-chain reading for error boundaries.")

(def ^:private machinery-namespace-prefixes
  ;; DERIVED FROM WHAT THESE FRAMES ARE, exactly as
  ;; `seon.instrument/caller-frame` derives its own: the host, the
  ;; language, the contract library, core.async's dispatch, the fault
  ;; machinery and the operator's prepl wire are what CAUGHT the failure. None of them is a place to go
  ;; and edit, and naming one routes the fault to the steward of the
  ;; checker instead of the steward of the code that broke.
  ["clojure." "java." "jdk." "sun." "malli." "seon.error" "seon.instrument"
   "seon.operator."])

(def ^:private first-party-namespace-prefixes
  ;; The program's own namespaces: the ones an agent can open and edit.
  ["seon." "my."])

(defn frame-function
  "The function a stack frame's class belongs to, as `ns/name`, or nil.

  Demunged Clojure frames read `ns/fn`, `ns/fn--1234` for a compiled
  arity and `ns/outer/fn` for a closure, so the function is the first two
  segments with the compiler's suffix dropped. A class that demunges to no
  `/` is a host class and is not a function at all; a frame in the
  language, host, contract or fault machinery is where a failure was
  caught, not where it happened, and names nothing."
  {:malli/schema [:=> [:cat :symbol] [:or :nil :qualified-symbol]]}
  [class-name]
  (let [demunged (clojure.lang.Compiler/demunge (str class-name))
        separator (.indexOf demunged "/")]
    (when (pos? separator)
      (let [frame-ns (subs demunged 0 separator)
            simple (subs demunged (inc separator))
            nested (.indexOf simple "/")
            simple (if (neg? nested) simple (subs simple 0 nested))
            suffix (.indexOf simple "--")
            simple (if (neg? suffix) simple (subs simple 0 suffix))]
        (when (and (seq simple)
                   (not (some #(.startsWith ^String frame-ns ^String %)
                              machinery-namespace-prefixes)))
          (symbol frame-ns simple))))))

(defn- stack-frames
  "`throwable`'s own complete stack frames as `[class method file line]`;
  a frame the JVM reports without a file is not complete and is omitted."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:vector :seon.error/frame]]}
  [^Throwable throwable]
  (into []
        (keep (fn [^StackTraceElement frame]
                (when-let [file (.getFileName frame)]
                  [(symbol (.getClassName frame)) (symbol (.getMethodName frame))
                   file (long (.getLineNumber frame))])))
        (.getStackTrace throwable)))

(defn first-party-frame?
  "Whether a frame belongs to a first-party function (`seon.*`/`my.*`,
  outside the fault machinery)."
  {:malli/schema [:=> [:cat :seon.error/frame] :boolean]}
  [[class-name]]
  (boolean
   (when-let [function (frame-function class-name)]
     (some #(.startsWith ^String (namespace function) ^String %)
           first-party-namespace-prefixes))))

(defn root-frame
  "The first complete stack frame of the ROOT cause, where the failure
  happened. The outermost throwable's frame is where it was wrapped: a
  wrapper's frame names the catch site, never the code that broke."
  {:malli/schema [:=> [:cat [:or :nil :seon.error/throwable]]
                  [:or :nil :seon.error/frame]]}
  [throwable]
  (when throwable
    (first (stack-frames (last (take-while some? (iterate ex-cause throwable)))))))

(defn chain
  "The whole cause chain, outermost to root, as `Throwable->map` reads it
  (`clojure/core_print.clj:473`): per link its class, message and
  `ex-data`, plus that link's own first-party frames. A link without a
  message, data or first-party frame omits the member."
  {:malli/schema [:=> [:cat :seon.error/throwable] :seon.error/chain]}
  [^Throwable throwable]
  (mapv (fn [{link-class :type :keys [message data]} link]
          (let [frames (filterv first-party-frame? (stack-frames link))]
            (cond-> {:seon.error/throwable-class (str link-class)}
              (seq message) (assoc :seon.error/message message)
              data (assoc :seon.error/data data)
              (seq frames) (assoc :seon.error/frames frames))))
        (:via (Throwable->map throwable))
        (take-while some? (iterate ex-cause throwable))))

(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable.
  From the Throwable derive the outermost exception class, the whole cause
  `:seon.error/chain`, the ROOT cause's first complete stack frame, and —
  when the observation states none — the root cause's message."
  {:malli/schema
   [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message {:optional true} :seon.error/message]
                [:seon.error/throwable {:optional true} :seon.error/throwable]]]
    :seon.error/base]}
  [{:seon.error/keys [throwable message] :as observation}]
  (if throwable
    (let [links (chain throwable)
          frame (root-frame throwable)
          root-message (:seon.error/message (peek links))]
      (cond-> (assoc (dissoc observation :seon.error/throwable)
                     :seon.error/exception-class (symbol (.getName (class throwable)))
                     :seon.error/chain links)
        frame (assoc :seon.error/frame frame)
        (and (nil? message) root-message) (assoc :seon.error/message root-message)))
    observation))

(defn refusal
  "Deepest structural error in `ex-data`, retaining its exception message;
  else deepest non-empty data, or nil.

  This error-handling reader preserves arbitrary producers' errors under the
  bare base contract. Non-error exception data remains an ordinary map;
  reading exception data does not declare another producer's error union."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]]
    [:or :nil :map :seon.error/base]]}
  [throwable]
  (loop [candidate throwable
         deepest nil
         classified nil]
    (if (nil? candidate)
      (or classified deepest)
      (let [data (ex-data candidate)]
        (recur (ex-cause candidate)
               (if (seq data) data deepest)
               (if (and (:seon.error/at data)
                        (:seon.error/layer data)
                        (:seon.error/operation data))
                 (assoc data :seon.error/message
                        (or (:seon.error/message data)
                            (not-empty (ex-message candidate))
                            (str (:seon.error/operation data))))
                 classified))))))
