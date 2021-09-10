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

/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.indexmanagement.indexstatemanagement.resthandler

import org.apache.logging.log4j.LogManager
import org.opensearch.client.node.NodeClient
import org.opensearch.common.Strings
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.ISM_BASE_URI
import org.opensearch.indexmanagement.indexstatemanagement.transport.action.executemanagedindex.ExecuteManagedIndexAction
import org.opensearch.indexmanagement.indexstatemanagement.transport.action.executemanagedindex.ExecuteManagedIndexRequest
import org.opensearch.rest.BaseRestHandler
import org.opensearch.rest.BaseRestHandler.RestChannelConsumer
import org.opensearch.rest.RestHandler.Route
import org.opensearch.rest.RestRequest
import org.opensearch.rest.RestRequest.Method.POST
import org.opensearch.rest.action.RestToXContentListener

private val log = LogManager.getLogger(RestExplainAction::class.java)

class RestExecuteManagedIndexAction : BaseRestHandler() {

    companion object {
        const val EXPLAIN_BASE_URI = "$ISM_BASE_URI/execute"
    }

    override fun routes(): List<Route> {
        return listOf(
            Route(POST, EXPLAIN_BASE_URI),
            Route(POST, "$EXPLAIN_BASE_URI/{index}")
        )
    }

    override fun getName(): String {
        return "ism_execute_managed_index_action"
    }

    override fun prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer {
        log.info("${request.method()} ${request.path()}")

        val indices: Array<String>? = Strings.splitStringByCommaToArray(request.param("index"))
        if (indices == null || indices.isEmpty()) {
            throw IllegalArgumentException("Missing indices")
        }
        if (indices.size > 1) {
            throw IllegalArgumentException("Only one index is supported")
        }

        val executeRequest = ExecuteManagedIndexRequest(indices.first())

        return RestChannelConsumer { channel ->
            client.execute(ExecuteManagedIndexAction.INSTANCE, executeRequest, RestToXContentListener(channel))
        }
    }
}
