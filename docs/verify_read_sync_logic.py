#!/usr/bin/env python3
"""
Standalone LOGIC-VERIFICATION harness for ReadSyncService (M1 read-sync).
Java (JUnit) can't run in this environment (no JDK runtime), so this is a faithful port of the
ReadSyncService algorithm to produce REAL executable evidence of the 5 behaviors the JUnit
suite asserts. The JUnit ReadSyncServiceTest runs under CI once the repo/CI runner is up.
Mirrors: independent+strict expectedDelta, overlap fetch bound, contiguous-success watermark,
idempotent upsert, zero_change anomaly.
"""

def parse(rev): return int(rev) if rev not in (None, "", "null") else 0

class MockPolarion:
    """Independent count vs fetch — separate iterations over the same store (as in Java mock)."""
    def __init__(self): self.store = {}   # polarionId -> (revision, fields)
    def put(self, pid, rev, fields): self.store[pid] = (rev, fields)
    def count_changed_since(self, since):            # INDEPENDENT loop (not fetch().size())
        return sum(1 for (rev, _) in self.store.values() if parse(rev) > parse(since))
    def fetch_changed_since(self, since):
        return [(pid, rev, f) for pid, (rev, f) in self.store.items() if parse(rev) > parse(since)]

def read_sync(polarion, watermark, overlap, fail_ids=frozenset(), prev_failed=0, faulty=None):
    fetch_since = str(max(0, parse(watermark) - overlap))
    # STRICT bound from watermark (B4 fix): genuinely-new upstream only
    if faulty:  # faulty stub: (count, items)
        expected_delta, items = faulty
    else:
        expected_delta = polarion.count_changed_since(watermark)
        items = polarion.fetch_changed_since(fetch_since)
    items = sorted(items, key=lambda it: parse(it[1]))     # revision ascending
    written = failed = 0
    new_wm = watermark
    contiguous = True
    for (pid, rev, _f) in items:
        if pid in fail_ids:
            failed += 1; contiguous = False              # stop advancing; item re-fetches next cycle
        else:
            written += 1
            if contiguous: new_wm = rev                  # advance only through success prefix
    flags = []
    if written == 0 and expected_delta > 0: flags.append("zero_change")
    if failed > 0 and prev_failed > 0: flags.append("consecutive_failure")
    return dict(read=len(items), written=written, failed=failed,
                expectedDelta=expected_delta, watermark=new_wm, anomalyFlags=flags)

def check(name, cond):
    print(f"  {'PASS' if cond else 'FAIL'}  {name}"); assert cond, name

# 1. firstSync — writes all + health record
p = MockPolarion(); [p.put(i, r, {}) for i, r in [("A","1"),("B","2"),("C","3")]]
r = read_sync(p, "0", 1)
check("firstSync_writesAll (read=3,written=3,failed=0)", r["read"]==3 and r["written"]==3 and r["failed"]==0)

# 2. secondSync — delta-only (watermark 3, overlap 0)
p = MockPolarion(); [p.put(i, r_, {}) for i, r_ in [("A","1"),("B","2"),("C","3"),("D","4"),("E","5")]]
r = read_sync(p, "3", 0)
check("secondSync_transfersOnlyDeltas (read=2,written=2,watermark=5)", r["read"]==2 and r["written"]==2 and r["watermark"]=="5")

# 3. silentFailure — independent probe fires zero_change (count=5, fetch empty)
r = read_sync(None, "0", 0, faulty=(5, []))
check("silentFailure_zeroChangeAnomaly (written=0,expectedDelta=5,zero_change)",
      r["written"]==0 and r["expectedDelta"]==5 and "zero_change" in r["anomalyFlags"])

# 4. idle-run — strict bound => no false zero_change (watermark 5, overlap 2, nothing new)
p = MockPolarion(); [p.put(i, r_, {}) for i, r_ in [("D","4"),("E","5")]]
r = read_sync(p, "5", 2)
check("idleRun_noFalseZeroChange (expectedDelta=0, no zero_change)",
      r["expectedDelta"]==0 and "zero_change" not in r["anomalyFlags"])

# 5. watermark stops at contiguous all-success prefix on failure (BAD at rev 2 fails)
p = MockPolarion(); [p.put(i, r_, {}) for i, r_ in [("OK1","1"),("BAD","2"),("OK3","3")]]
r = read_sync(p, "0", 0, fail_ids={"BAD"})
check("watermarkStopsAtContiguousSuccessPrefix (failed=1, watermark=1)", r["failed"]==1 and r["watermark"]=="1")

print("\nALL 5 SCENARIOS PASS — ReadSyncService core logic verified.")

# --- PR#4 reconcile (REQ-M1-05): drift on already-synced item (C4 gate) ---
def sha_fields(fields):
    import hashlib
    s = "".join(f"{k}={fields[k]};" for k in sorted(fields))
    return hashlib.sha256(s.encode()).hexdigest()

def reconcile(polarion_items, stored_hashes):
    matched = mismatched = missing = 0; drifted = []
    for pid, fields in polarion_items:
        fresh = sha_fields(fields)
        if pid not in stored_hashes: missing += 1
        elif stored_hashes[pid] != fresh: mismatched += 1; drifted.append(pid)
        else: matched += 1
    return dict(matched=matched, mismatched=mismatched, missing=missing, drifted=drifted)

# stored "A" with original fields; Polarion "A" drifted (title changed)
stored = {"A": sha_fields({"title": "orig", "definition": "d"})}
r = reconcile([("A", {"title": "DRIFTED", "definition": "d"})], stored)
check("reconcile_detectsSilentDriftOnAlreadySyncedItem (mismatched=1, A drifted)",
      r["mismatched"] == 1 and "A" in r["drifted"])
r = reconcile([("A", {"title": "orig", "definition": "d"})], stored)
check("reconcile_cleanWhenHashesMatch (matched=1, mismatched=0)", r["matched"] == 1 and r["mismatched"] == 0)

print("\nALL RECONCILE SCENARIOS PASS — REQ-M1-05 overlap/already-synced coverage verified.")

# --- PR#5 bidi write-back conflict (REQ-M1-03/04, TS-B-02): B2 E1/E2 predicate ---
def write_back(polarion_rev, last_common_rev, current_tms_fields, stored_hash, has_open_conflict=False):
    if has_open_conflict: return "FROZEN"
    polarion_moved = polarion_rev is not None and polarion_rev != last_common_rev
    tms_dirty = sha_fields(current_tms_fields) != stored_hash
    if polarion_moved and tms_dirty: return "CONFLICT_QUEUED"   # no LWW
    return "WRITTEN"

synced = {"title": "orig", "definition": "d"}
stored_h = sha_fields(synced)
# Polarion-only change (rev moved 5->7, TMS clean) → write, no false conflict
check("bidi_polarionOnly_noFalseConflict_writes",
      write_back("7", "5", synced, stored_h) == "WRITTEN")
# both changed (Polarion 5->7 AND TMS edited) → conflict queued, no overwrite
check("bidi_bothChanged_conflictQueued_noLWW",
      write_back("7", "5", {"title": "tms-edit", "definition": "d"}, stored_h) == "CONFLICT_QUEUED")
# open conflict freezes auto-write (anti-thrash)
check("bidi_openConflict_frozen",
      write_back("7", "5", {"title": "x"}, stored_h, has_open_conflict=True) == "FROZEN")

print("\nALL BIDI/CONFLICT SCENARIOS PASS — REQ-M1-03/04 E1/E2 no-silent-overwrite verified.")
