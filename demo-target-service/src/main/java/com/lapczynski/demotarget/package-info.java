/**
 * A deliberately fault-injectable payment API: the service the Commander investigates.
 *
 * <p>Every injected fault is bounded and auto-expiring. No unbounded memory exhaustion, fork bombs
 * or uninterruptible loops are implemented here, by policy.
 */
package com.lapczynski.demotarget;
