/* lmdb-sem DIR: the proof that each env has a SysV semaphore set of its own
 * (docs/failure-points.md#lmdb-robust-locks). The robust build locks with a set found by
 * ftok(lock.mdb), which keeps 16 bits of the inode, and the last close of an env removes its set.
 * 1. two envs whose lock files share a key: one is held open, the other opened and closed, and
 *    the held one opens again and writes (the old build answered EINVAL: the close removed the
 *    shared set);
 * 2. an env whose recorded set is gone while a process holds it (what a process that waited on
 *    the lock file while the last user closed finds): a new process opens it and writes;
 * 3. N processes open and close one env at once, LOOPS times each: no open fails;
 * 4. one process opens envs until one fails: the failure is MDB_SEM_UNDO_FULL, never EINVAL.
 * Prints LMDB-SEM OK and exits 0 only when every case holds. Case 1 makes about 65,536 files to
 * find a key (the inode counter's low 16 bits come round), each removed at once. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <sys/wait.h>

static int open_env(const char *dir, MDB_env **e) {
  int r = mdb_env_create(e);
  if (!r) r = mdb_env_set_mapsize(*e, 1 << 26);
  if (!r) r = mdb_env_open(*e, dir, MDB_NOTLS, 0664);
  return r;
}

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

static int collision(const char *base) {
  char a[512], b[512], la[600], lb[600], c[600]; int n;
  sub(a, sizeof a, base, "a"); sub(b, sizeof b, base, "b");
  snprintf(la, sizeof la, "%s/lock.mdb", a); snprintf(lb, sizeof lb, "%s/lock.mdb", b);
  close(open(la, O_CREAT | O_RDWR, 0664));
  key_t k = ftok(la, 'M');
  for (n = 0; n < 1 << 20; n++) {
    snprintf(c, sizeof c, "%s/c%d", base, n);
    close(open(c, O_CREAT | O_RDWR, 0664));
    if (ftok(c, 'M') == k) { rename(c, lb); break; }
    unlink(c);
  }
  printf("1. two lock files with ftok key %#x (after %d files)\n", k, n);
  if (ftok(lb, 'M') != k) { printf("  no file found with the key\n"); return 0; }
  MDB_env *held; int r = open_env(b, &held);
  if (!r) r = put(held, "held");
  printf("  holder of b: %s\n", r ? mdb_strerror(r) : "opened and committed");
  int ok = !r && child_open_put(a, "a", "another process opens a, writes and closes")
              && child_open_put(b, "b", "a new process opens b and writes");
  r = put(held, "held-after");
  printf("  holder of b writes again: %s\n", r ? mdb_strerror(r) : "committed");
  mdb_env_close(held);
  return ok && !r;
}

static int removed(const char *base) {
  char d[512], l[600];
  sub(d, sizeof d, base, "removed");
  snprintf(l, sizeof l, "%s/lock.mdb", d);
  printf("2. an env whose recorded set is gone while a process holds it\n");
  MDB_env *held; int r = open_env(d, &held);
  if (r) { printf("  holder: %s\n", mdb_strerror(r)); return 0; }
  int s = semget(ftok(l, 'M'), 0, 0);
  printf("  holder's set %d removed: %s\n", s, s >= 0 && !semctl(s, 0, IPC_RMID) ? "yes" : "no");
  int ok = s >= 0 && child_open_put(d, "joined", "a new process opens it and writes");
  mdb_env_close(held);
  return ok;
}

static int race(const char *base, int n, int loops) {
  char d[512]; int bar[2], i, bad = 0, fds[64][2];
  sub(d, sizeof d, base, "race");
  pipe(bar);
  for (i = 0; i < n; i++) {
    pipe(fds[i]);
    if (!fork()) {
      char c; int f = 0, j; close(bar[1]); read(bar[0], &c, 1);
      for (j = 0; j < loops; j++) { MDB_env *e; if (open_env(d, &e)) f++; mdb_env_close(e); }
      write(fds[i][1], &f, sizeof f); _exit(0);
    }
    close(fds[i][1]);
  }
  close(bar[0]); close(bar[1]);
  for (i = 0; i < n; i++) { int f = 0; read(fds[i][0], &f, sizeof f); bad += f; close(fds[i][0]); }
  while (wait(NULL) > 0) ;
  printf("3. %d processes open and close one env %d times each: %d of %d opens failed\n", n, loops, bad, n * loops);
  return bad == 0;
}

static int limit(const char *base) {
  enum { MAX = 40 }; MDB_env *e[MAX]; char d[600]; int i, r = 0;
  char dir[512]; sub(dir, sizeof dir, base, "many");
  for (i = 0; i < MAX && !r; i++) {
    snprintf(d, sizeof d, "%s/%d", dir, i); mkdir(d, 0755);
    r = open_env(d, &e[i]);
    if (!r) r = put(e[i], "k");
  }
  printf("4. one process opens envs: %d open, then %s\n", r ? i - 1 : i, r ? mdb_strerror(r) : "no limit reached");
  int n = i; while (n--) mdb_env_close(e[n]);
  return !r || r == MDB_SEM_UNDO_FULL;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  if (argc != 2) { printf("usage: lmdb-sem DIR\n"); return 2; }
  mkdir(argv[1], 0755);
  int c1 = collision(argv[1]), c2 = removed(argv[1]), c3 = race(argv[1], 16, 200), c4 = limit(argv[1]);
  int ok = c1 && c2 && c3 && c4;
  printf(ok ? "LMDB-SEM OK\n" : "LMDB-SEM FAILED\n");
  return ok ? 0 : 1;
}
