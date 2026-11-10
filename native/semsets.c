/* semsets LIST [--remove]: this user's SysV semaphore sets of the robust LMDB build, counted by whose
 * they are, and with --remove the orphans removed (bin/semsets is its manual; docs/failure-points.md#lmdb-robust-locks).
 * LIST is a file of lock file paths, one a line (thousands: never argv). A set of the build has four
 * semaphores, the reader and writer locks and two values naming its lock file (mdb.c's mdb_sem_ident:
 * a hash of the file's inode and device). Every set of `ipcs -s` is one of:
 *   not ours   another user's, or not four semaphores (another layout, another program);
 *   unclaimed  four semaphores naming no lock file yet (an opener between its semget and its claim);
 *   matched    it names a lock file of LIST that exists;
 *   live       the last process to operate on its reader or writer lock is alive;
 *   orphan     none of these: its lock file is gone and so is its last user, so nothing opens it again.
 * Only an orphan is removed. Prints one line of counts; exits 0, or 1 when a removal failed. */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/ipc.h>
#include <sys/sem.h>
#include <sys/stat.h>

union semun_ { int val; struct semid_ds *buf; unsigned short *array; };

static int alive(int pid) { return pid > 0 && (kill(pid, 0) == 0 || errno == EPERM); }

static int cmp(const void *a, const void *b) {
  uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
  return x < y ? -1 : x > y;
}

int main(int argc, char **argv) {
  if (argc < 2 || argc > 3 || (argc == 3 && strcmp(argv[2], "--remove"))) {
    fprintf(stderr, "usage: semsets LIST [--remove]\n");
    return 2;
  }
  int rm = argc == 3;
  FILE *lf = fopen(argv[1], "r");
  if (!lf) { perror(argv[1]); return 2; }
  size_t cap = 1024, m = 0;
  uint32_t *ids = malloc(cap * sizeof *ids);
  char path[4096];
  while (fgets(path, sizeof path, lf)) {
    struct stat st;
    path[strcspn(path, "\n")] = 0;
    if (!*path || stat(path, &st)) continue;
    uint64_t h = ((uint64_t)st.st_ino ^ ((uint64_t)st.st_dev << 40)) * 0x9E3779B97F4A7C15ULL;
    if (m == cap) ids = realloc(ids, (cap *= 2) * sizeof *ids);
    ids[m++] = (uint32_t)(1 + (h >> 34) % 32767) << 16 | (uint32_t)(1 + (h >> 49) % 32767);
  }
  fclose(lf);
  qsort(ids, m, sizeof *ids, cmp);

  FILE *ip = popen("ipcs -s", "r");
  if (!ip) { perror("ipcs -s"); return 2; }
  char line[512];
  long total = 0, notours = 0, unclaimed = 0, matched = 0, live = 0, orphans = 0, removed = 0, failed = 0;
  uid_t me = getuid();
  while (fgets(line, sizeof line, ip)) {
    int s;
    if (sscanf(line, "s %d", &s) != 1) continue;
    struct semid_ds buf; union semun_ u; u.buf = &buf;
    total++;
    if (semctl(s, 0, IPC_STAT, u) < 0 || buf.sem_nsems != 4 || buf.sem_perm.uid != me) { notours++; continue; }
    int i0 = semctl(s, 2, GETVAL), i1 = semctl(s, 3, GETVAL);
    if (i0 <= 0 || i1 <= 0 || i0 > 32767 || i1 > 32767) { unclaimed++; continue; }
    uint32_t k = (uint32_t)i0 << 16 | (uint32_t)i1;
    if (bsearch(&k, ids, m, sizeof *ids, cmp)) { matched++; continue; }
    if (alive(semctl(s, 0, GETPID)) || alive(semctl(s, 1, GETPID))) { live++; continue; }
    orphans++;
    if (rm) { if (semctl(s, 0, IPC_RMID) == 0) removed++; else failed++; }
  }
  pclose(ip);
  printf("sets %ld: not ours or another layout %ld, unclaimed %ld, matched to a lock file %ld, kept for a live pid %ld, orphans %ld",
         total, notours, unclaimed, matched, live, orphans);
  if (rm) printf(", removed %ld, failed %ld", removed, failed);
  printf(" (lock files %zu)\n", m);
  return failed ? 1 : 0;
}
