/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.spring.bean.metrics;

import io.hekate.metrics.MetricsService;
import io.hekate.spring.bean.HekateBaseBean;

/**
 * Imports {@link MetricsService} into a Spring context.
 */
public class LocalMetricsServiceBean extends HekateBaseBean<MetricsService> {
    @Override
    public MetricsService getObject() throws Exception {
        return getSource().get(MetricsService.class);
    }

    @Override
    public Class<MetricsService> getObjectType() {
        return MetricsService.class;
    }
}