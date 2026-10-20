(ns seon.wire
  "nREPL's wire on an agent's socket (lane F2, after lane R2's r2.wire; the owner chose
  nREPL's message model for agents): bencode, encoded and decoded by jolt.nrepl's own
  `bencode`/`bdecode` (private there, reached by var); bytes travel as latin1 strings, one
  char per byte. The socket is read through seon.sock's conn (recvmsg), so a descriptor
  sent with a message (a clone's socket) is kept for its handler.

  REPL ops speak nREPL's fields (`eval`, `code`, `value`, `out`, `err`, `ex`, `status`,
  `done`, sessions). Seon's platform ops are `seon/<op>` messages whose structured data
  rides one `seon/edn` field, as R2's `seon/record` does: a datom vector is data, not a
  string to print for a reader."
  (:require [seon.sock :as sock] [clojure.edn :as edn] [jolt.nrepl]))

(def ^:private bencode* @#'jolt.nrepl/bencode)
(def ^:private bdecode* @#'jolt.nrepl/bdecode)
(def text-> "A wire text field (latin1 bytes of UTF-8) as a String." @#'jolt.nrepl/wire->)

(defn encode
  "The bencoded bytes of m (string keys; keyword-keyed entries are in-process values a
  middleware passes along and never go on the wire)."
  ^bytes [m]
  (.getBytes ^String (bencode* (into {} (remove (comp keyword? key)) m)) "ISO-8859-1"))

(defn send!
  "Send message m on fd (with pass-fd, a descriptor goes with it). true, or :epipe."
  ([fd m] (sock/send-frame! fd (encode m)))
  ([fd m pass-fd] (sock/send-frame! fd (encode m) pass-fd)))

(defn reader "A reader of whole messages over a seon.sock conn." [conn] {:conn conn :buf (volatile! "")})

(defn- decode-one [s] (when (pos? (count s)) (when-let [r (bdecode* s 0)] [(first r) (subs s (second r))])))

(defn- take-one [{:keys [buf]}]
  (when-let [[m more] (decode-one @buf)] (vreset! buf more) m))

(defn- pull! [{:keys [conn buf]} blocking?]
  (let [r (sock/read-bytes! conn blocking?)]
    (if (bytes? r) (do (vswap! buf str (String. ^bytes r "ISO-8859-1")) :more) r)))

(defn next-msg!
  "Blocking: the next message (string keys), or :eof. EINTR retries."
  [rd]
  (loop []
    (or (take-one rd)
        (if (= :eof (pull! rd true)) :eof (recur)))))

(defn poll-msg!
  "Non-blocking: the next whole message, :none, or :eof (after the last whole message)."
  [rd]
  (loop []
    (or (take-one rd)
        (case (pull! rd false) :more (recur) :again :none :eof :eof))))

;; ---- Seon's platform ops: internal keyword maps <-> seon/<op> messages --------------

(defn seon->wire
  "An internal message {:op k ...} as the seon/<op> wire message."
  [m] {"op" (str "seon/" (name (:op m))) "seon/edn" (pr-str (dissoc m :op))})

(defn wire->seon
  "A seon/<op> wire message as the internal {:op k ...}, or nil for any other message."
  [m]
  (let [op (get m "op")]
    (when (and (string? op) (.startsWith ^String op "seon/"))
      (assoc (edn/read-string (text-> (get m "seon/edn" "{}"))) :op (keyword (subs op 5))))))
