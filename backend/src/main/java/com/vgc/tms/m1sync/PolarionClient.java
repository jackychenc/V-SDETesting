package com.vgc.tms.m1sync;

import java.util.List;

/**
 * Polarion IF-1 read interface (REQ-M1-01/02), contract v1.2 (G3-frozen).
 * Pilot uses {@link MockPolarionClient} (OUT-02); sandbox/live impl is contract-identical.
 * Creds (token) come from the secret store only (C4 M1-T1); TLS to Polarion (M1-T2).
 */
public interface PolarionClient {

    /**
     * B4#1 — GENUINELY INDEPENDENT delta probe. Returns the count of items changed since the
     * given revision via a SEPARATE query path from {@link #fetchChangedSince}. This must NOT be
     * implemented as {@code fetchChangedSince(...).size()} — the anti-self-masking property
     * (TS-B-03) requires that a "success but writes nothing" extract cannot also suppress this
     * count (else expectedDelta reads 0 and the zero_change guard never fires).
     */
    int countChangedSince(String polarionProjectId, String sinceRevision);

    /** Fetch work items changed since the given revision (incremental extract, REQ-M1-02). */
    List<PolarionWorkItem> fetchChangedSince(String polarionProjectId, String sinceRevision);

    /** Current Polarion revision of a work item (for conflict detection, REQ-M1-04). null if absent. */
    String currentRevision(String polarionProjectId, String polarionId);

    /**
     * Revision-guarded (optimistic / If-Match) write-back (REQ-M1-03, contract v1.3 §E1).
     * Writes ONLY if Polarion's current revision still equals {@code expectedRevision} (the revision
     * read at the conflict check). If Polarion moved in the check→write window (concurrent edit),
     * the write is REJECTED and this returns null — the caller queues a ConflictItem, never forces.
     * Closes the TOCTOU silent-overwrite (LWW) vector (B4/C4 M1-T3). Returns the new revision on success.
     */
    String writeIfRevisionMatches(String polarionProjectId, PolarionWorkItem item, String expectedRevision);
}
