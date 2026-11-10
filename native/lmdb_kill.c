/* lmdb-kill DIR: the proof that a dead process never leaves an LMDB env locked or its readers held
 * (docs/failure-points.md#lmdb-locks). Each case works on a store of its own under DIR.
 * 1. this process holds a store open; a child SIGKILLs itself inside a write txn; then a new process
 *    and this one each write: both commit and the killed write is gone (with LMDB's POSIX-semaphore
 *    locks, macOS's default, both block forever on the dead child's writer lock);
 * 2. a process died holding the reader table lock (its lock word left set, its byte lock given back
 *    by the kernel, as a SIGKILL leaves them): the next reader repairs the table and reads;
 * 3. fork while the writer lock is held (root forks agents with its store open): the child holds
 *    nothing, opens the store itself and commits after the parent; then the parent is SIGKILLed
 *    inside a write txn, and the child and a third process both commit;
 * 4. readers of killed processes, more of them than the reader table has slots, while this process
 *    holds the store: every open and every read txn succeeds (never MDB_READERS_FULL);
 * 5. a killed process's read txn does not keep freed pages from reuse: 40 commits rewriting one
 *    48 KB value grow the file by at most a few pages, not by the 3 pages a commit it pins;
 * 6. a process of a store kept with MDB_SAFENOSYNC SIGKILLed with 3 commits made since its last
 *    sync: the next opener (exclusive, the same OS boot) reads all of them: a kill loses nothing.
 * CASES, default 123456, names the cases to run. Prints LMDB-KILL OK and exits 0 only when every case
 * run holds. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>

#ifndef MDB_SAFENOSYNC
#define MDB_SAFENOSYNC 0	/* a LMDB without it */
#endif

static MDB_env *open_env(const char *p, unsigned maxreaders) {
  MDB_env *e; int r = mdb_env_create(&e);
  if (!r) r = mdb_env_set_mapsize(e, 1 << 26);
  if (!r && maxreaders) r = mdb_env_set_maxreaders(e, maxreaders);
  if (!r) r = mdb_env_open(e, p, MDB_NOSUBDIR | MDB_NOTLS | MDB_NOSYNC, 0664);
  if (r) { printf("env_open %s: %s\nLMDB-KILL FAILED\n", p, mdb_strerror(r)); exit(1); }
  return e;
}

/* put key k = v (n bytes); die: SIGKILL this process inside the txn instead of committing */
static int putv(MDB_env *e, const char *k, const void *v, size_t n, int die) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V = {n, (void *)v};
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (r) return r;
  if (!r) r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (r) { mdb_txn_abort(t); return r; }
  if (die) { printf("  %d: inside a write txn, SIGKILL\n", getpid()); kill(getpid(), SIGKILL); }
  return mdb_txn_commit(t);
}
static int put(MDB_env *e, const char *k, int die) { return putv(e, k, "v", 1, die); }

static int has(MDB_env *e, const char *k) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V;
  if (mdb_txn_begin(e, NULL, MDB_RDONLY, &t)) return 0;
  int r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_get(t, d, &K, &V);
  mdb_txn_abort(t); return r == 0;
}

/* fork: open p, put k, exit 0 when it committed */
static int child_put(const char *p, const char *k, const char *who) {
  pid_t c = fork();
  if (!c) { int r = put(open_env(p, 0), k, 0); printf("  %s: %s\n", who, r ? mdb_strerror(r) : "committed"); _exit(r ? 1 : 0); }
  int st; waitpid(c, &st, 0);
  return WIFEXITED(st) && !WEXITSTATUS(st);
}

static void path(char *out, size_t n, const char *dir, const char *name) { snprintf(out, n, "%s/%s", dir, name); }

static int killed_writer(const char *dir) {
  char p[512]; path(p, sizeof p, dir, "writer");
  printf("1. a writer SIGKILLed inside a txn while this process holds the store\n");
  MDB_env *e = open_env(p, 0);
  int r0 = put(e, "before", 0), st;
  pid_t k = fork();
  if (!k) { put(open_env(p, 0), "killed", 1); _exit(0); }
  waitpid(k, &st, 0);
  printf("  child: signaled %d, signal %d\n", WIFSIGNALED(st), WTERMSIG(st));
  int w = child_put(p, "after", "a new process writes");
  int r1 = put(e, "holder", 0);
  printf("  the holder writes: %s\n", r1 ? mdb_strerror(r1) : "committed");
  int ok = !r0 && WIFSIGNALED(st) && w && !r1 && has(e, "after") && has(e, "holder") && !has(e, "killed");
  mdb_env_close(e);
  return ok;
}

/* The reader table lock's "held" word (mtb_rlocked), at byte 20 of the lock file: after the magic,
 * the format, the last txnid and the reader count (mdb.c's MDB_txbody, MDB_USE_FCNTL). */
enum { RLOCKED_AT = 20 };

static int dead_reader_lock(const char *dir) {
  char p[512], l[600]; path(p, sizeof p, dir, "rlock"); snprintf(l, sizeof l, "%s-lock", p);
  printf("2. a process died holding the reader table lock\n");
  MDB_env *e = open_env(p, 0);
  int ok = !put(e, "k", 0);
  int fd = open(l, O_RDWR);
  unsigned char *m = fd < 0 ? MAP_FAILED : mmap(NULL, 64, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (m == MAP_FAILED) { printf("  map %s failed\n", l); return 0; }
  int was = *(int *)(m + RLOCKED_AT);
  *(int *)(m + RLOCKED_AT) = 1;	/* what a holder SIGKILLed between the lock and the unlock leaves */
  pid_t c = fork();
  if (!c) { int h = has(open_env(p, 0), "k"); printf("  the next reader: %s\n", h ? "read the store" : "failed"); _exit(h ? 0 : 1); }
  int st; waitpid(c, &st, 0);
  int now = *(int *)(m + RLOCKED_AT);
  printf("  the word before %d, after the repair %d\n", was, now);
  munmap(m, 64); close(fd);
  ok = ok && was == 0 && now == 0 && WIFEXITED(st) && !WEXITSTATUS(st) && has(e, "k");
  mdb_env_close(e);
  return ok;
}

static int fork_held(const char *dir) {
  char p[512]; path(p, sizeof p, dir, "fork");
  printf("3. fork while the writer lock is held\n");
  int cp[2], ct[2], tc[2]; char b;
  if (pipe(cp) || pipe(ct) || pipe(tc)) return 0;
  pid_t parent = fork();
  if (!parent) {	/* holds the writer lock across a fork */
    MDB_env *e = open_env(p, 0); MDB_txn *t; MDB_dbi d; MDB_val K = {6, "parent"}, V = {1, "v"};
    if (mdb_txn_begin(e, NULL, 0, &t) || mdb_dbi_open(t, NULL, 0, &d) || mdb_put(t, d, &K, &V, 0)) _exit(1);
    pid_t c = fork();
    if (!c) {	/* the parent's env is not the child's: it opens the store itself */
      int r = put(open_env(p, 0), "child", 0);	/* waits for the parent's commit */
      printf("  the child commits after the parent: %s\n", r ? mdb_strerror(r) : "committed");
      if (write(cp[1], "c", 1) != 1 || read(tc[0], &b, 1) != 1) _exit(1);
      r = r ? r : put(open_env(p, 0), "child2", 0);	/* after the parent died inside a write txn */
      printf("  the child writes after the parent's death: %s\n", r ? mdb_strerror(r) : "committed");
      b = r ? 'n' : 'y';
      _exit(write(ct[1], &b, 1) == 1 && !r ? 0 : 1);
    }
    close(cp[1]);
    usleep(100000);	/* the child waits on the writer lock now */
    int r = mdb_txn_commit(t);
    printf("  the parent commits: %s\n", r ? mdb_strerror(r) : "committed");
    if (r || read(cp[0], &b, 1) != 1) _exit(1);
    put(e, "dying", 1);
    _exit(1);
  }
  close(cp[0]); close(cp[1]); close(ct[1]); close(tc[0]);	/* a child that dies early ends this process's read */
  int st; waitpid(parent, &st, 0);
  printf("  the parent: signaled %d\n", WIFSIGNALED(st));
  int childok = write(tc[1], "g", 1) == 1 && read(ct[0], &b, 1) == 1 && b == 'y';
  int third = child_put(p, "third", "a third process writes");
  close(ct[0]); close(tc[1]);
  MDB_env *e = open_env(p, 0);
  int ok = childok && WIFSIGNALED(st) && third && has(e, "parent") && has(e, "child") && has(e, "child2") &&
           has(e, "third") && !has(e, "dying");
  mdb_env_close(e);
  return ok;
}

static int readers_full(const char *dir) {
  enum { SLOTS = 8, KILLED = 20 };
  char p[512]; path(p, sizeof p, dir, "readers");
  printf("4. %d readers of killed processes in turn, the reader table %d slots, this process holding the store\n", KILLED, SLOTS);
  MDB_env *e = open_env(p, SLOTS);
  int ok = !put(e, "k", 0), i, bad = 0;
  for (i = 0; i < KILLED; i++) {
    pid_t c = fork();
    if (!c) {
      MDB_env *ce = open_env(p, SLOTS); MDB_txn *t;
      int r = mdb_txn_begin(ce, NULL, MDB_RDONLY, &t);
      if (r) { printf("  reader %d: %s\n", i, mdb_strerror(r)); _exit(1); }
      kill(getpid(), SIGKILL);
    }
    int st; waitpid(c, &st, 0);
    if (!WIFSIGNALED(st)) bad++;
  }
  int h = has(e, "k");
  printf("  readers that failed to begin: %d; this process reads after: %s\n", bad, h ? "yes" : "no");
  mdb_env_close(e);
  return ok && !bad && h;
}

static size_t last_pgno(MDB_env *e) { MDB_envinfo i; mdb_env_info(e, &i); return i.me_last_pgno; }

static int dead_reader_pins(const char *dir) {
  enum { COMMITS = 40, VAL = 48 * 1024 };
  char p[512]; path(p, sizeof p, dir, "pins");
  static char v[VAL];
  printf("5. a killed process's read txn, then %d commits each rewriting one %d KB value\n", COMMITS, VAL / 1024);
  MDB_env *e = open_env(p, 0);
  int ok = !putv(e, "big", v, VAL, 0), i;
  for (i = 0; i < 4; i++) { v[0] = (char)i; ok = ok && !putv(e, "big", v, VAL, 0); }	/* settle the freelist */
  pid_t c = fork();
  if (!c) { MDB_env *ce = open_env(p, 0); MDB_txn *t; if (mdb_txn_begin(ce, NULL, MDB_RDONLY, &t)) _exit(1); kill(getpid(), SIGKILL); }
  int st; waitpid(c, &st, 0);
  size_t before = last_pgno(e);
  for (i = 0; i < COMMITS; i++) { v[0] = (char)(i + 9); ok = ok && !putv(e, "big", v, VAL, 0); }
  size_t grew = last_pgno(e) - before;
  printf("  the file grew %zu pages over %d commits\n", grew, COMMITS);
  mdb_env_close(e);
  return ok && WIFSIGNALED(st) && grew <= 12;
}

static int killed_after_sync(const char *dir) {
  char p[512]; path(p, sizeof p, dir, "steady");
  printf("6. a process of a store kept with MDB_SAFENOSYNC commits 3 times after its last sync and is SIGKILLed\n");
  pid_t c = fork();
  if (!c) {
    MDB_env *e; int r = mdb_env_create(&e);
    if (!r) r = mdb_env_set_mapsize(e, 1 << 26);
    if (!r) r = mdb_env_open(e, p, MDB_NOSUBDIR | MDB_NOTLS | MDB_NOSYNC | MDB_SAFENOSYNC, 0664);
    if (r || put(e, "synced", 0) || mdb_env_sync(e, 1) || put(e, "a", 0) || put(e, "b", 0)) _exit(1);
    put(e, "c", 0); kill(getpid(), SIGKILL);
  }
  int st; waitpid(c, &st, 0);
  MDB_env *e = open_env(p, 0);	/* the first opener after it, in the same boot */
  int ok = WIFSIGNALED(st) && has(e, "synced") && has(e, "a") && has(e, "b") && has(e, "c");
  printf("  the next opener reads every commit: %s\n", ok ? "yes" : "NO");
  mdb_env_close(e);
  return ok;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  signal(SIGPIPE, SIG_IGN);	/* a write to a dead child is an error, not this process's death */
  if (argc < 2 || argc > 3) { printf("usage: lmdb-kill DIR [CASES]\n"); return 2; }
  const char *only = argc == 3 ? argv[2] : "123456";	/* the cases to run, by number */
  mkdir(argv[1], 0755);
#define RUN(n, f) (strchr(only, n) ? f(argv[1]) : 1)
  int c1 = RUN('1', killed_writer), c2 = RUN('2', dead_reader_lock), c3 = RUN('3', fork_held),
      c4 = RUN('4', readers_full), c5 = RUN('5', dead_reader_pins), c6 = RUN('6', killed_after_sync);
  printf("cases: %d %d %d %d %d %d\n", c1, c2, c3, c4, c5, c6);
  int ok = c1 && c2 && c3 && c4 && c5 && c6;
  printf(ok ? "LMDB-KILL OK\n" : "LMDB-KILL FAILED\n");
  return ok ? 0 : 1;
}
