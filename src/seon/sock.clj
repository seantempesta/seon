(ns seon.sock
  "The transport between root, the fork server and agents (lane F2, from lane T1's
  t1.sock, notes/option3-T1-transport.md): one AF_UNIX SOCK_STREAM socketpair per peer,
  plain libc through jolt.ffi, no Rust helper and no shared memory.
  - Frames are [u32 len][body]; the body is jfasl (konserve.jolt.fasl, lane X1). Values cross once each way.
  - A conn is a buffered reader over one fd. It reads with recvmsg, so a descriptor sent
    with a frame (SCM_RIGHTS) is kept in the conn's queue for the handler of that frame.
  - Root reads every agent's conn with one kqueue. EOF on a conn is the death of every
    process holding its other end; the agent's end is FD_CLOFORK, so a test child does
    not hide its parent's death.
  - The socket's buffer is the bounded channel: a full buffer makes the sender wait."
  (:require [jolt.ffi :as ffi] [konserve.jolt.fasl :as codec]))

(ffi/load-library)

(ffi/defcfn c-socketpair "socketpair" [:int :int :int :pointer] :int)
(ffi/defcfn c-fcntl "fcntl" [:int :int :varargs :int] :int)
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true})
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-shutdown "shutdown" [:int :int] :int)
(ffi/defcfn c-setsockopt "setsockopt" [:int :int :int :pointer :int] :int)
(ffi/defcfn c-memmove "memmove" [:pointer :pointer :size_t] :pointer)
(ffi/defcfn c-kqueue "kqueue" [] :int)
(ffi/defcfn c-kevent "kevent" [:int :pointer :int :pointer :int :pointer] :int {:blocking true})
(ffi/defcfn c-kevent-nb "kevent" [:int :pointer :int :pointer :int :pointer] :int)
(ffi/defcfn c-sendmsg "sendmsg" [:int :pointer :int] :ssize_t {:blocking true})
(ffi/defcfn c-recvmsg "recvmsg" [:int :pointer :int] :ssize_t {:blocking true})
(ffi/defcfn c-recvmsg-nb "recvmsg" [:int :pointer :int] :ssize_t)
(ffi/defcfn c-poll "poll" [:pointer :int :int] :int {:blocking true})
(ffi/defcfn c-recv-nb "recv" [:int :pointer :size_t :int] :ssize_t)
(ffi/defcfn c-clock-ns "clock_gettime_nsec_np" [:int] :uint64)

(defmacro ^:private with-mem
  "Allocate n bytes for body, freed after it (jolt.ffi's caller-owned form)."
  [[p n] & body] `(let [~p (ffi/alloc ~n)] (try ~@body (finally (ffi/free ~p)))))

(def ^:private AF-UNIX 1)
(def ^:private SOCK-STREAM 1)
(def ^:private SOL-SOCKET 0xffff)
(def ^:private SCM-RIGHTS 1)
(def ^:private SO-SNDBUF 0x1001)
(def ^:private SO-RCVBUF 0x1002)
(def ^:private SO-NOSIGPIPE 0x1022)
(def ^:private F-GETFD 1)
(def ^:private F-SETFD 2)
(def ^:private FD-CLOFORK 2)
(def ^:private EVFILT-READ -1)
(def ^:private EV-ADD 1)
(def ^:private EV-DELETE 2)
(def ^:private EV-EOF 0x8000)
(def ^:private MSG-DONTWAIT 0x80)

(defn now-ns
  "A monotonic clock every process shares (CLOCK_UPTIME_RAW), for one-way latencies."
  [] (c-clock-ns 8))

(def BUF
  "Each socket's send and receive buffer, bytes (T1: 1 MB makes a 1 MB reply cost what
  the ring's did)."
  (Long/parseLong (or (System/getenv "SEON_SOCK_BUF") "1048576")))

(defn- set-int-opt! [fd opt v]
  (with-mem [b 4] (ffi/write b :int v) (c-setsockopt fd SOL-SOCKET opt b 4)))

(defn socketpair
  "[a b]: a connected AF_UNIX stream pair with BUF buffers and SO_NOSIGPIPE (a write to a
  dead peer answers EPIPE, not a signal)."
  []
  (with-mem [sv 8]
    (when-not (zero? (c-socketpair AF-UNIX SOCK-STREAM 0 sv)) (throw (ex-info "socketpair failed" {:errno (ffi/errno)})))
    (let [a (ffi/read sv :int 0) b (ffi/read sv :int 4)]
      (doseq [fd [a b]] (set-int-opt! fd SO-NOSIGPIPE 1) (set-int-opt! fd SO-SNDBUF BUF) (set-int-opt! fd SO-RCVBUF BUF))
      [a b])))

(defn clofork!
  "Close fd in every child this process forks from now on."
  [fd] (c-fcntl fd F-SETFD (bit-or FD-CLOFORK (c-fcntl fd F-GETFD 0))))

(defn close! [fd] (c-close fd))

(defn hang-up!
  "End both directions of fd: its reader sees EOF (a peer that never existed reads as dead)."
  [fd] (c-shutdown fd 2))

;; ---- a buffered conn ----------------------------------------------------------
;; st: long-array [buf cap lo hi]; fds: descriptors received with frames, in order

(def ^:private CTL
  "Control buffer bytes per recvmsg: room for many descriptors (several fork requests can
  arrive in one read; a short buffer would truncate them, and the kernel closes those)."
  1024)

(defn conn
  "A buffered reader over fd. Its buffer starts at 4 KB and grows to the largest frame
  (root holds one per agent)."
  [fd] {:fd fd :st (long-array [(ffi/address (ffi/alloc 4096)) 4096 0 0]) :fds (volatile! [])
        :msg (ffi/alloc 48) :iov (ffi/alloc 16) :ctl (ffi/alloc CTL)})

(defn- received-fds
  "Every descriptor in a recvmsg's control data (cmsghdr: len u32, level i32, type i32,
  then the fds; headers 4-byte aligned on darwin), in order."
  [ctl n]
  (loop [off 0 acc []]
    (if (> (+ off 12) n)
      acc
      (let [len (ffi/read (+ ctl off) :uint32)]
        (if (< len 12)
          acc
          (recur (+ off (bit-and (+ len 3) -4))
                 (if (and (= SOL-SOCKET (ffi/read (+ ctl off) :int 4)) (= SCM-RIGHTS (ffi/read (+ ctl off) :int 8)))
                   (into acc (for [i (range (quot (- len 12) 4))] (ffi/read (+ ctl off 12 (* 4 i)) :int)))
                   acc)))))))

(defn release-conn!
  "Free a conn's buffers (its owner is done reading; the fd is closed separately)."
  [{:keys [^longs st msg iov ctl]}]
  (ffi/free (aget st 0)) (ffi/free msg) (ffi/free iov) (ffi/free ctl))

(defn- ensure-room!
  "Make room for `need` bytes from lo: compact, then grow the buffer if needed."
  [{:keys [^longs st]} need]
  (let [buf (aget st 0) cap (aget st 1) lo (aget st 2) hi (aget st 3) live (- hi lo)]
    (when (pos? lo)
      (when (pos? live) (c-memmove buf (+ buf lo) live))
      (aset st 2 0) (aset st 3 live))
    (when (> need cap)
      (let [ncap (loop [c cap] (if (< c need) (recur (* 2 c)) c))
            nb (ffi/address (ffi/alloc ncap))]
        (when (pos? live) (c-memmove nb buf live))
        (ffi/free buf)
        (aset st 0 nb) (aset st 1 ncap)))))

(defn- take-frame
  "The next whole frame's bytes from the conn's buffer, or nil."
  [{:keys [^longs st]}]
  (let [lo (aget st 2) avail (- (aget st 3) lo)]
    (when (>= avail 4)
      (let [buf (aget st 0) n (ffi/read (+ buf lo) :uint32)]
        (when (>= avail (+ 4 n))
          (let [b (ffi/read-array (+ buf lo 4) n)]
            (aset st 2 (+ lo 4 n))
            b))))))

(defn- wanted [{:keys [^longs st]}]
  (let [lo (aget st 2) avail (- (aget st 3) lo)]
    (if (< avail 4) 4 (+ 4 (ffi/read (+ (aget st 0) lo) :uint32)))))

(defn- fill!
  "One recvmsg into the conn's buffer (a descriptor that came with it joins :fds).
  Answers bytes read, :eof, or :again (non-blocking with nothing there, or EINTR)."
  ([c blocking?] (fill! c blocking? (wanted c)))
  ([{:keys [fd ^longs st msg iov ctl fds] :as c} blocking? need]
    (when (> (+ (aget st 2) need) (aget st 1)) (ensure-room! c need))
    (when (= (aget st 3) (aget st 1)) (ensure-room! c (max need (inc (- (aget st 3) (aget st 2))))))
    (let [hi (aget st 3) room (- (aget st 1) hi)]
      (ffi/write iov :uint64 (+ (aget st 0) hi) 0) (ffi/write iov :uint64 room 8)
      (ffi/write msg :uint64 0 0) (ffi/write msg :uint32 0 8)
      (ffi/write msg :uint64 (ffi/address iov) 16) (ffi/write msg :int 1 24)
      (ffi/write msg :uint64 (ffi/address ctl) 32) (ffi/write msg :uint32 CTL 40) (ffi/write msg :int 0 44)
      (ffi/write ctl :uint32 0 0)
      (let [r (if blocking? (c-recvmsg fd msg 0) (c-recvmsg-nb fd msg MSG-DONTWAIT))]
        (when (pos? r)
          (let [n (ffi/read msg :uint32 40)]
            (when (>= n 12) (vswap! fds into (received-fds ctl n)))))
        (cond (pos? r) (do (aset st 3 (+ hi r)) r)
              (zero? r) :eof
              :else :again)))))

(defn read-bytes!
  "One read into c's buffer (blocking? or not), then every buffered byte, taken: a
  byte-array, :again (nothing there, or EINTR) or :eof. For a stream protocol of its own
  (bencode, seon.wire); descriptors that came along stay in c's :fds."
  [{:keys [^longs st] :as c} blocking?]
  (let [lo (aget st 2) hi (aget st 3)]
    (if (> hi lo)
      (let [b (ffi/read-array (+ (aget st 0) lo) (- hi lo))] (aset st 2 0) (aset st 3 0) b)
      ;; a stream of its own has no length header: just room for what arrives
      (let [r (fill! c blocking? 1)]
        (if (number? r)
          (let [lo (aget st 2) hi (aget st 3) b (ffi/read-array (+ (aget st 0) lo) (- hi lo))]
            (aset st 2 0) (aset st 3 0) b)
          r)))))

(defn recv!
  "Blocking: the next frame's value, or :eof. EINTR (a signal) retries."
  [c]
  (loop []
    (if-let [b (take-frame c)]
      (codec/decode b)
      (if (= :eof (fill! c true)) :eof (recur)))))

(defn- c-poll1
  "poll(2) one fd for input, up to ms: > 0 when readable (or at EOF)."
  [fd ms]
  (with-mem [p 8]
    (ffi/write p :int fd 0) (ffi/write p :int16 1 4) (ffi/write p :int16 0 6)
    (c-poll p 1 ms)))

(defn close-pending-fds!
  "Close every descriptor received on c and not yet taken (a forked child does this: they
  belong to its parent's later frames)."
  [{:keys [fds]}] (run! c-close @fds) (vreset! fds []))

(defn take-fd!
  "The oldest descriptor received with a frame on c (the one its handler is owed)."
  [{:keys [fds]}] (let [fd (first @fds)] (vswap! fds #(vec (rest %))) fd))

(defn next-frame!
  "Non-blocking: the next whole frame's value, :none when no whole frame is there yet,
  or :eof when the peer is gone (after its last whole frame)."
  [c]
  (loop []
    (if-let [b (take-frame c)]
      (codec/decode b)
      (case (fill! c false)
        :eof :eof
        :again :none
        (recur)))))

(defn recv-within!
  "Blocking up to ms: the next frame's value, :timeout, or :eof."
  [c ms]
  (let [v (next-frame! c)]
    (if (= :none v)
      (if (pos? (c-poll1 (:fd c) ms)) (recur c 0) :timeout)
      v)))

;; ---- writing ----------------------------------------------------------------

(defn- write-all! [fd p n]
  (loop [off 0]
    (if (= off n)
      true
      (let [r (c-write fd (+ p off) (- n off))]
        (if (neg? r)
          (if (= 4 (ffi/errno)) (recur off) :epipe) ; EINTR retries
          (recur (+ off r)))))))

(defn frame
  "v encoded once, as the bytes of one frame (to send to several peers)."
  ^bytes [v]
  (let [^bytes b (codec/encode v) n (alength b) f (byte-array (+ 4 n))]
    (aset f 0 (unchecked-byte n)) (aset f 1 (unchecked-byte (bit-shift-right n 8)))
    (aset f 2 (unchecked-byte (bit-shift-right n 16))) (aset f 3 (unchecked-byte (bit-shift-right n 24)))
    (System/arraycopy b 0 f 4 n)
    f))

(defn send-frame!
  "Write frame bytes f to fd; with pass-fd, the descriptor travels with them
  (SCM_RIGHTS). Answers true, or :epipe (the peer is gone)."
  ([fd ^bytes f] (send-frame! fd f nil))
  ([fd ^bytes f pass-fd]
   (let [n (alength f)]
    (with-mem [p (max 1 n)]
     (ffi/write-array p f 0 n)
     (if-not pass-fd
       (write-all! fd p n)
       (with-mem [m 80]
        (let [iov (+ m 48) ctl (+ m 64)]
         (ffi/write iov :uint64 (ffi/address p) 0) (ffi/write iov :uint64 n 8)
         (ffi/write ctl :uint32 16 0) (ffi/write ctl :int SOL-SOCKET 4) (ffi/write ctl :int SCM-RIGHTS 8) (ffi/write ctl :int pass-fd 12)
         (ffi/write m :uint64 0 0) (ffi/write m :uint32 0 8)
         (ffi/write m :uint64 (ffi/address iov) 16) (ffi/write m :int 1 24)
         (ffi/write m :uint64 (ffi/address ctl) 32) (ffi/write m :uint32 16 40) (ffi/write m :int 0 44)
         (let [r (c-sendmsg fd m 0)]
           (cond (neg? r) :epipe
                 (< r n) (write-all! fd (+ p r) (- n r))
                 :else true)))))))))

(defn send!
  "Encode v and write it as one frame to fd (with pass-fd, a descriptor goes with it)."
  ([fd v] (send-frame! fd (frame v) nil))
  ([fd v pass-fd] (send-frame! fd (frame v) pass-fd)))

(defn poke!
  "Write one byte to fd: wakes a kqueue loop watching its peer."
  [fd] (with-mem [p 8] (ffi/write p :uint8 1) (c-write fd p 1)))

(defn discard!
  "Read and drop what is in fd (non-blocking)."
  [fd] (with-mem [p 256] (loop [] (when (pos? (c-recv-nb fd p 256 MSG-DONTWAIT)) (recur)))))

;; ---- kqueue -----------------------------------------------------------------
;; struct kevent (arm64): ident u64 @0, filter i16 @8, flags u16 @10, fflags u32 @12, data i64 @16, udata u64 @24

(def ^:private KEV 32)

(defn kqueue [] (c-kqueue))

(defn kq-watch!
  "Watch fd for reading (add? true) or stop watching it."
  [kq fd add?]
  (with-mem [e KEV]
    (ffi/write e :uint64 fd 0) (ffi/write e :int16 EVFILT-READ 8) (ffi/write e :uint16 (if add? EV-ADD EV-DELETE) 10)
    (ffi/write e :uint32 0 12) (ffi/write e :int64 0 16) (ffi/write e :uint64 fd 24)
    (c-kevent-nb kq e 1 ffi/null 0 ffi/null)))

(defn kq-wait
  "Block for ready fds (at most max, or until timeout-ms): a vector of fds."
  [kq evs max timeout-ms]
  (with-mem [t 16]
   (let [_ (do (ffi/write t :int64 (quot timeout-ms 1000) 0) (ffi/write t :int64 (* 1000000 (mod timeout-ms 1000)) 8))
        n (c-kevent kq ffi/null 0 evs max t)]
    (loop [i 0 acc (transient [])]
      (if (< i n) (recur (inc i) (conj! acc (ffi/read (+ evs (* KEV i)) :uint64 0))) (persistent! acc))))))

(defn kq-buffer [max] (ffi/alloc (* KEV max)))
