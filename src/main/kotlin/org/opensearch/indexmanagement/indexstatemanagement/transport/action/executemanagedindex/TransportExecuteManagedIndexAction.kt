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
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.indexmanagement.indexstatemanagement.transport.action.executemanagedindex

import org.apache.logging.log4j.LogManager
import org.opensearch.action.ActionListener
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.client.node.NodeClient
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.XContentHelper
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.authuser.User
import org.opensearch.indexmanagement.IndexManagementPlugin
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.indexstatemanagement.ManagedIndexRunner
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexConfig
import org.opensearch.indexmanagement.opensearchapi.parseWithType
import org.opensearch.indexmanagement.settings.IndexManagementSettings
import org.opensearch.indexmanagement.util.SecurityUtils.Companion.buildUser
import org.opensearch.jobscheduler.spi.JobDocVersion
import org.opensearch.jobscheduler.spi.JobExecutionContext
import org.opensearch.jobscheduler.spi.utils.LockService
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService
import java.time.Instant
import kotlin.Exception

private val log = LogManager.getLogger(TransportExecuteManagedIndexAction::class.java)

class TransportExecuteManagedIndexAction @Inject constructor(
    val client: NodeClient,
    transportService: TransportService,
    actionFilters: ActionFilters,
    val clusterService: ClusterService,
    val settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<ExecuteManagedIndexRequest, ExecuteManagedIndexResponse>(
    ExecuteManagedIndexAction.NAME, transportService, actionFilters, ::ExecuteManagedIndexRequest
) {

    @Volatile private var filterByEnabled = IndexManagementSettings.FILTER_BY_BACKEND_ROLES.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(IndexManagementSettings.FILTER_BY_BACKEND_ROLES) {
            filterByEnabled = it
        }
    }

    override fun doExecute(task: Task, request: ExecuteManagedIndexRequest, listener: ActionListener<ExecuteManagedIndexResponse>) {
        log.info("TransportExecuteManagedIndexAction ${request.index}")
        ExecuteManagedIndexHandler(client, listener, request).start()
    }

    inner class ExecuteManagedIndexHandler(
        private val client: NodeClient,
        private val actionListener: ActionListener<ExecuteManagedIndexResponse>,
        private val request: ExecuteManagedIndexRequest,
        private val user: User? = buildUser(client.threadPool().threadContext)
    ) {
        fun start() {
            // Maybe just do single index only - wildcard is dangerous if someone does it for 10k indices and it executes 10k jobs
            // as in ACTUALLY executes them immediately, not just schedules them

            // We are given.. index name or index patterns
            // ism/_execute/log* -> executes all the managed indices with log* name
            // how does security work in this case?
            // jane who has access to log-123 should be able to use log* but not have it execute log-456 or even see anything about log-456

            // We need a job and a context
            // We need to make sure the node trying to execute these successfully takes a lock..
            // Does which node is executing make a difference? e.g. something with routing or locality?
            val indexUuid = clusterService.state().metadata.index(request.index).index.uuid
            val getRequest = GetRequest(IndexManagementPlugin.INDEX_MANAGEMENT_INDEX, indexUuid).routing(indexUuid)
            client.get(
                getRequest,
                object : ActionListener<GetResponse> {
                    override fun onResponse(getResponse: GetResponse) {
                        val xcp = XContentHelper.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, getResponse.sourceAsBytesRef, XContentType.JSON)

                        val config = xcp.parseWithType(getResponse.id, getResponse.seqNo, getResponse.primaryTerm, ManagedIndexConfig.Companion::parse)

                        next(config)
                    }

                    override fun onFailure(e: Exception) {
                        log.error(e)
                        // TODO
                        actionListener.onFailure(e)
                    }
                }
            )
        }

        private fun next(config: ManagedIndexConfig) {
            // expectedExecutionTime
            // jobId
            // jobIndexName
            // jobVersion
            // lockService
            // we currently only use the context for locking
            val expectedExecutionTime = config.schedule.getNextExecutionTime(Instant.now())
            val jobDocVersion = JobDocVersion(config.primaryTerm, config.seqNo, -1L) // we do not store version on our jobs so passing -1L
            val lockService = LockService(client, clusterService)
            val jobExecutionContext = JobExecutionContext(expectedExecutionTime, jobDocVersion, lockService, INDEX_MANAGEMENT_INDEX, config.id)
            try {
                // TODO: It's possible some of these already have a lock on them and will fail to get a lock
                ManagedIndexRunner.runJob(config, jobExecutionContext)
            } catch (e: Exception) {
                log.error(e)
            }
            actionListener.onResponse(ExecuteManagedIndexResponse())
        }
    }
}
