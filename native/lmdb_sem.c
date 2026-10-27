/* lmdb-sem DIR: the proof that each env has a SysV semaphore set of its own, and that an env costs
 * its process no semaphore undo entry for its life (docs/failure-points.md#lmdb-robust-locks).
 * The robust build locks with a set keyed by a hash of the lock file's inode and device, holding
 * that identity; the last close of an env (the one that gets the lock file exclusively) removes it.
 * 1. a set of another lock file at an env's key, its writer lock held: the env is opened, written
 *    and closed by another process; the foreign set is still there, its identity and its held lock
 *    untouched (two envs whose keys meet never share a set), and a new process opens the env;
 * 2. an env whose recorded set is gone while a process holds it (what a process that waited on
 *    the lock file while the last user closed finds): a new process opens it and writes, and after
 *    the last close no set names the env;
 * 3. 16 processes open one env at once 200 times each, adding one to a count under the writer
 *    lock: no open fails, no addition is lost, and no set names the env after;
 * 4. one process opens and writes 50 envs at once: none fails (the fork before this one held an
 *    undo entry for each env's life, and kern.sysv.semume, 10, stopped it at 5);
 * 5. a process that opened an env and was killed inside a write txn leaves its set, its writer
 *    lock given back by the kernel; the next process opens the env, writes and closes it, and no
 *    set names the env after.
 * Prints LMDB-SEM OK and exits 0 only when every case holds. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <sys/wait.h>
#include <signal.h>

static int open_env(const char *dir, MDB_env **e) {
  int r = mdb_env_create(e);
  if (!r) r = mdb_env_set_mapsize(*e, 1 << 26);
  if (!r) r = mdb_env_open(*e, dir, MDB_NOTLS | MDB_NOSYNC, 0664);
  return r;
}

/* The lock file's identity and the key its set is looked for at: mdb.c's mdb_sem_ident */
static void ident(const char *l, int id[2], key_t *key) {
  struct stat st; stat(l, &st);
  unsigned long long h = ((unsigned long long)st.st_ino ^ ((unsigned long long)st.st_dev << 40)) * 0x9E3779B97F4A7C15ULL;
  id[0] = 1 + (int)((h >> 34) % 32767); id[1] = 1 + (int)((h >> 49) % 32767);
  *key = (key_t)((h & 0x7fffffff) | 1);
}

/* The set naming lock file L, from its key on (64 keys is far past any collision here), or -1 */
static int find_set(const char *l) {
  int id[2], i; key_t k; ident(l, id, &k);
  for (i = 0; i < 64; i++, k = (key_t)(((k + 2) & 0x7fffffff) | 1)) {
    int s = semget(k, 0, 0);
    if (s >= 0 && semctl(s, 2, GETVAL) == id[0] && semctl(s, 3, GETVAL) == id[1]) return s;
  }
  return -1;
}

/* How many sets name lock file L, from its key on: one while its env is open, none after its
 * last close */
static int sets_naming(const char *l) {
  int id[2], i, n = 0; key_t k; ident(l, id, &k);
  for (i = 0; i < 64; i++, k = (key_t)(((k + 2) & 0x7fffffff) | 1)) {
    int s = semget(k, 0, 0);
    if (s >= 0 && semctl(s, 2, GETVAL) == id[0] && semctl(s, 3, GETVAL) == id[1]) n++;
  }
  return n;
}

union semun_ { int val; struct semid_ds *buf; unsigned short *array; };

static int put(MDB_env *e, const char *k) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V = {1, "v"};
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (r) return r;
  r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (!r) r = mdb_txn_commit(t); else mdb_txn_abort(t);
  return r;
}

/* fork: open DIR, write KEY and close it; the child exits 0 when it committed */
static int child_open_put(const char *dir, const char *key, const char *who) {
  pid_t p = fork();
  if (!p) {
    MDB_env *e; int r = open_env(dir, &e);
    if (!r) r = put(e, key);
    printf("  %s: %s\n", who, r ? mdb_strerror(r) : "opened and committed");
    mdb_env_close(e);
    _exit(r ? 1 : 0);
  }
  int st; waitpid(p, &st, 0);
  return WIFEXITED(st) && !WEXITSTATUS(st);
}

static void sub(char *out, size_t n, const char *base, const char *name) { snprintf(out, n, "%s/%s", base, name); mkdir(out, 0755); }

static int foreign(const char *base) {
  char a[512], la[600]; int id[2]; key_t k; union semun_ u;
  sub(a, sizeof a, base, "a");
  snprintf(la, sizeof la, "%s/lock.mdb", a);
  close(open(la, O_CREAT | O_RDWR, 0664));	/* its inode, so its key, before the env's first open */
  ident(la, id, &k);
  printf("1. a set of another lock file at an env's key (%#x), its writer lock held\n", k);
  int f = semget(k, 4, 0664 | IPC_CREAT | IPC_EXCL);
  if (f < 0) { printf("  the key's set exists already: %s\n", strerror(errno)); return 0; }
  int other[2] = { id[0] % 32767 + 1, id[1] };
  u.val = 1; semctl(f, 0, SETVAL, u);
  u.val = 0; semctl(f, 1, SETVAL, u);	/* its writer lock held */
  u.val = other[0]; semctl(f, 2, SETVAL, u);
  u.val = other[1]; semctl(f, 3, SETVAL, u);
  int ok = child_open_put(a, "a", "another process opens the env, writes and closes");
  struct semid_ds buf; u.buf = &buf;
  int there = !semctl(f, 0, IPC_STAT, u),
      kept = there && semctl(f, 1, GETVAL) == 0 && semctl(f, 2, GETVAL) == other[0] && semctl(f, 3, GETVAL) == other[1];
  printf("  the foreign set: %s, its identity and held lock %s\n", there ? "there" : "gone", kept ? "untouched" : "changed");
  ok = ok && kept && child_open_put(a, "a2", "a new process opens the env and writes");
  semctl(f, 0, IPC_RMID);
  return ok;
}

static int removed(const char *base) {
  char d[512], l[600];
  sub(d, sizeof d, base, "removed");
  snprintf(l, sizeof l, "%s/lock.mdb", d);
  printf("2. an env whose recorded set is gone while a process holds it\n");
  MDB_env *held; int r = open_env(d, &held);
  if (r) { printf("  holder: %s\n", mdb_strerror(r)); return 0; }
  int s = find_set(l);
  printf("  holder's set %d removed: %s\n", s, s >= 0 && !semctl(s, 0, IPC_RMID) ? "yes" : "no");
  int ok = s >= 0 && child_open_put(d, "joined", "a new process opens it and writes");
  mdb_env_close(held);
  int n = sets_naming(l);
  printf("  sets naming the env after its last close: %d\n", n);
  return ok && n == 0;
}

/* add one to the count under key "n", in a write txn (under the writer lock) */
static int add(MDB_env *e) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {1, "n"}, V; unsigned n = 0;
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (r) return r;
  r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r && !mdb_get(t, d, &K, &V)) memcpy(&n, V.mv_data, sizeof n);
  n++; V.mv_size = sizeof n; V.mv_data = &n;
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (!r) r = mdb_txn_commit(t); else mdb_txn_abort(t);
  return r;
}

static unsigned count(MDB_env *e) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {1, "n"}, V; unsigned n = 0;
  if (mdb_txn_begin(e, NULL, MDB_RDONLY, &t)) return 0;
  if (!mdb_dbi_open(t, NULL, 0, &d) && !mdb_get(t, d, &K, &V)) memcpy(&n, V.mv_data, sizeof n);
  mdb_txn_abort(t);
  return n;
}

static int race(const char *base, int n, int loops) {
  char d[512]; int bar[2], i, bad = 0, fds[64][2];
  sub(d, sizeof d, base, "race");
  pipe(bar);
  for (i = 0; i < n; i++) {
    pipe(fds[i]);
    if (!fork()) {
      char c; int f = 0, j; close(bar[1]); read(bar[0], &c, 1);
      for (j = 0; j < loops; j++) { MDB_env *e; if (open_env(d, &e) || add(e)) f++; mdb_env_close(e); }
      write(fds[i][1], &f, sizeof f); _exit(0);
    }
    close(fds[i][1]);
  }
  close(bar[0]); close(bar[1]);
  for (i = 0; i < n; i++) { int f = 0; read(fds[i][0], &f, sizeof f); bad += f; close(fds[i][0]); }
  while (wait(NULL) > 0) ;
  MDB_env *e; unsigned got = 0;
  if (!open_env(d, &e)) { got = count(e); mdb_env_close(e); }
  char l[600]; snprintf(l, sizeof l, "%s/lock.mdb", d);
  int left = sets_naming(l);
  printf("3. %d processes open one env %d times each and add one: %d of %d failed, the count %u, sets naming it after %d\n", n, loops, bad, n * loops, got, left);
  return bad == 0 && got == (unsigned)(n * loops) && left == 0;
}

static int many(const char *base) {
  enum { MAX = 50 }; MDB_env *e[MAX]; char d[600]; int i, r = 0;
  char dir[512]; sub(dir, sizeof dir, base, "many");
  for (i = 0; i < MAX && !r; i++) {
    snprintf(d, sizeof d, "%s/%d", dir, i); mkdir(d, 0755);
    r = open_env(d, &e[i]);
    if (!r) r = put(e[i], "k");
  }
  int n = r ? i - 1 : i;
  printf("4. one process opens and writes %d envs at once: %d, %s\n", MAX, n, r ? mdb_strerror(r) : "none failed");
  while (n--) mdb_env_close(e[n]);
  return !r;
}

static int crashed(const char *base) {
  char d[512], l[600];
  sub(d, sizeof d, base, "crashed");
  snprintf(l, sizeof l, "%s/lock.mdb", d);
  printf("5. a process killed inside a write txn, then the next opens, writes and closes\n");
  pid_t p = fork();
  if (!p) {
    MDB_env *e; MDB_txn *t;
    if (open_env(d, &e) || mdb_txn_begin(e, NULL, 0, &t)) _exit(1);
    kill(getpid(), SIGKILL);
  }
  int st; waitpid(p, &st, 0);
  int left = find_set(l), wfree = left >= 0 && semctl(left, 1, GETVAL) == 1;
  printf("  killed: %s; its set %s, its writer lock %s\n", WIFSIGNALED(st) ? "yes" : "no",
         left >= 0 ? "left" : "gone", wfree ? "given back (SEM_UNDO)" : "not free");
  int ok = WIFSIGNALED(st) && left >= 0 && wfree && child_open_put(d, "after", "the next process opens, writes and closes");
  int n = sets_naming(l);
  printf("  sets naming the env after: %d\n", n);
  return ok && n == 0;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  if (argc != 2) { printf("usage: lmdb-sem DIR\n"); return 2; }
  mkdir(argv[1], 0755);
  int c1 = foreign(argv[1]), c2 = removed(argv[1]), c3 = race(argv[1], 16, 200), c4 = many(argv[1]), c5 = crashed(argv[1]);
  int ok = c1 && c2 && c3 && c4 && c5;
  printf(ok ? "LMDB-SEM OK\n" : "LMDB-SEM FAILED\n");
  return ok ? 0 : 1;
}
