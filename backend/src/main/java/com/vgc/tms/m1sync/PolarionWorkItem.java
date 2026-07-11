package com.vgc.tms.m1sync;

import java.util.Map;

/**
 * Polarion work item DTO (IF-1 contract v1.2). Anti-corruption boundary type — the TMS domain
 * never depends on Polarion's native model directly (mapped by {@link AntiCorruptionMapper}).
 *
 * @param polarionId work-item id (idempotent upsert key — REQ-M1-06)
 * @param type       polarion type: testcase | requirement | defect (mapped via typeMapping)
 * @param revision   Polarion revision (monotonic; used for watermark + conflict detection)
 * @param fields     work-item field values (title/definition/...); mapped per field-direction rules
 */
public record PolarionWorkItem(String polarionId, String type, String revision, Map<String, Object> fields) {
}
