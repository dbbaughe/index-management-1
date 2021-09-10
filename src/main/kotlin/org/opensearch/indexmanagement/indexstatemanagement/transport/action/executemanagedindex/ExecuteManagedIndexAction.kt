/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.indexmanagement.indexstatemanagement.transport.action.executemanagedindex

import org.opensearch.action.ActionType

class ExecuteManagedIndexAction private constructor() : ActionType<ExecuteManagedIndexResponse>(NAME, ::ExecuteManagedIndexResponse) {
    companion object {
        val INSTANCE = ExecuteManagedIndexAction()
        // TODO: Cluster or index?
        const val NAME = "cluster:admin/opendistro/ism/managedindex/execute"
    }
}
