/**
 * Google ADK integration: agent topology, tools, callbacks, plugins and model profiles.
 *
 * <p>All ADK types are confined to this module. The RxJava {@code Flowable} that ADK's Runner
 * returns is bridged to Spring's SSE here rather than leaking into the API layer.
 */
package com.lapczynski.commander.adk;
