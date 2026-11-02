package com.itland.governance.domain.approval;

/**
 * Which implementation owns the lifecycle of an approval: the retained legacy code (quorum in Java, TTL sweep by
 * {@code ApprovalExpiryJob}) or the embedded Flowable process (ADR 0011). Rows opened before the cutover stay LEGACY
 * until they resolve; nothing is migrated silently.
 */
public enum ApprovalEngine { LEGACY, FLOWABLE }
