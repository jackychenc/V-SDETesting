package com.vgc.tms.m1sync;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory mock Polarion (pilot, OUT-02) — contract-identical to sandbox/live (IF-1 v1.2).
 * Uses the contiguous integer-revision model (B4#3: sandbox spike confirms real Polarion's model).
 * Active for the {@code mock} profile; the REST impl replaces it for sandbox/live.
 *
 * The count and fetch paths are INTENTIONALLY INDEPENDENT (B4#1): {@link #countChangedSince} iterates
 * the store separately from {@link #fetchChangedSince}, so a test/fault that suppresses the extract
 * cannot also zero the probe — that is exactly what makes TS-B-03 detectable.
 */
@Component
@Profile("mock")
public class MockPolarionClient implements PolarionClient {

    /** polarionId -> item (insertion order); revision is a contiguous integer as string. */
    private final Map<String, PolarionWorkItem> store = new LinkedHashMap<>();

    // --- test/seed helpers (used by fixtures; not part of IF-1) ---
    public void put(PolarionWorkItem item) { store.put(item.polarionId(), item); }
    public void clear() { store.clear(); }

    @Override
    public int countChangedSince(String polarionProjectId, String sinceRevision) {
        long since = parse(sinceRevision);
        int n = 0;
        for (PolarionWorkItem it : store.values()) {        // independent iteration — NOT fetch().size()
            if (parse(it.revision()) > since) n++;
        }
        return n;
    }

    @Override
    public List<PolarionWorkItem> fetchChangedSince(String polarionProjectId, String sinceRevision) {
        long since = parse(sinceRevision);
        List<PolarionWorkItem> out = new ArrayList<>();
        for (PolarionWorkItem it : store.values()) {
            if (parse(it.revision()) > since) out.add(it);
        }
        return out;
    }

    private static long parse(String rev) {
        if (rev == null || rev.isBlank()) return 0L;
        try { return Long.parseLong(rev); } catch (NumberFormatException e) { return 0L; }
    }
}
