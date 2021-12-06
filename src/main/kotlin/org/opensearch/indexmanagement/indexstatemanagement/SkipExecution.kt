/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement

import org.apache.logging.log4j.LogManager
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.cluster.node.info.NodesInfoAction
import org.opensearch.action.admin.cluster.node.info.NodesInfoRequest
import org.opensearch.action.admin.cluster.node.info.NodesInfoResponse
import org.opensearch.action.admin.cluster.node.info.PluginsAndModules
import org.opensearch.client.Client
import org.opensearch.cluster.ClusterChangedEvent
import org.opensearch.cluster.ClusterStateListener
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.indexmanagement.settings.IndexManagementSettings
import org.opensearch.indexmanagement.util.OpenForTesting
import org.opensearch.threadpool.Scheduler
import org.opensearch.threadpool.ThreadPool

// TODO this can be moved to job scheduler, so that all extended plugin
//  can avoid running jobs in an upgrading cluster
@OpenForTesting
class SkipExecution(
    settings: Settings,
    private val client: Client,
    private val clusterService: ClusterService,
    private val threadPool: ThreadPool
) : ClusterStateListener {
    private val logger = LogManager.getLogger(javaClass)

    @Volatile final var flag: Boolean = false
        private set
    // To track if there are any legacy IM plugin nodes part of the cluster
    @Volatile final var hasLegacyPlugin: Boolean = false
        private set
    @Volatile private var skipExecutionCheckPeriod = IndexManagementSettings.SKIP_EXECUTION_CHECK_PERIOD.get(settings)
    private var scheduledCheck: Scheduler.Cancellable? = null

    init {
        clusterService.addListener(this)

        scheduledCheck = threadPool.scheduleWithFixedDelay(
            { scheduledSweep() },
            skipExecutionCheckPeriod, ThreadPool.Names.MANAGEMENT
        )
    }

    override fun clusterChanged(event: ClusterChangedEvent) {
        // 1.0 B/G w/ 1.0 -> should stay false
        // 1.0 B/G w/ 1.1 -> should go true during B/G and then false once set back
        // true should always be a temporary thing, as we skip executions during it and that cannot be healthy long term
        // so we should introduce a periodic sweep of plugin versions in case the flag hasn't set back correctly
        // if 100 node domain.. don't want them constantly doing node info requests every x minutes
        // so we will only do it if the flag is set to true to see if the upgrade is done, if flag is false then nothing to check
        // is this true? if flag is false can we skip? no... because what if that is the check that would
        // move us to the new flagged state?
        if (event.nodesChanged() || event.isNewCluster) {
            sweepISMPluginVersion()
        }
    }

    fun scheduledSweep() {
        if (flag) {
            // can this start running... is getting back false and before it has a chance to update
            // an event comes in with new nodes that runs and gets back true
            // and then true is set and then overriden back to false from first?
            sweepISMPluginVersion()
        } else {
            logger.debug("Skipping scheduled sweep as the skip execution flag is already set to false")
        }
    }

    fun sweepISMPluginVersion() {
        logger.info("Sweeping ISM plugin versions")
        // if old version ISM plugin exists (2 versions ISM in one cluster), set skip flag to true
        val request = NodesInfoRequest().clear().addMetric("plugins")
        client.execute(
            NodesInfoAction.INSTANCE, request,
            object : ActionListener<NodesInfoResponse> {
                override fun onResponse(response: NodesInfoResponse) {
                    val versionSet = mutableSetOf<String>()
                    val legacyVersionSet = mutableSetOf<String>()

                    response.nodes.map { it.getInfo(PluginsAndModules::class.java).pluginInfos }
                        .forEach {
                            it.forEach { nodePlugin ->
                                if (nodePlugin.name == "opensearch-index-management" ||
                                    nodePlugin.name == "opensearch_index_management"
                                ) {
                                    versionSet.add(nodePlugin.version)
                                }

                                if (nodePlugin.name == "opendistro-index-management" ||
                                    nodePlugin.name == "opendistro_index_management"
                                ) {
                                    legacyVersionSet.add(nodePlugin.version)
                                }
                            }
                        }

                    if ((versionSet.size + legacyVersionSet.size) > 1) {
                        flag = true
                        logger.info("There are multiple versions of Index Management plugins in the cluster: [$versionSet, $legacyVersionSet]")
                    } else flag = false

                    if (versionSet.isNotEmpty() && legacyVersionSet.isNotEmpty()) {
                        hasLegacyPlugin = true
                        logger.info("Found legacy plugin versions [$legacyVersionSet] and opensearch plugins versions [$versionSet] in the cluster")
                    } else hasLegacyPlugin = false
                }

                override fun onFailure(e: Exception) {
                    logger.error("Failed sweeping nodes for ISM plugin versions: $e")
                    flag = false
                }
            }
        )
    }
}
