/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.indexmanagement.settings

import org.opensearch.common.settings.Setting
import org.opensearch.common.unit.TimeValue

class IndexManagementSettings {

    companion object {

        val FILTER_BY_BACKEND_ROLES: Setting<Boolean> = Setting.boolSetting(
            "plugins.index_management.filter_by_backend_roles",
            false,
            Setting.Property.NodeScope,
            Setting.Property.Dynamic
        )

        val SKIP_EXECUTION_CHECK_PERIOD: Setting<TimeValue> = Setting.positiveTimeSetting(
            "plugins.index_management.skip_execution_check_period",
            TimeValue.timeValueMinutes(10),
            Setting.Property.NodeScope,
            Setting.Property.Dynamic
        )
    }
}
