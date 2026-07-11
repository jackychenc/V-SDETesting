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

    /** Write back to Polarion (REQ-M1-03). Returns the new revision. Idempotent on polarionId. */
    String write(String polarionProjectId, PolarionWorkItem item);
}
