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

package io.hekate.spring.boot.network;

import io.hekate.network.NetworkConnector;
import io.hekate.network.NetworkConnectorConfig;
import io.hekate.network.NetworkService;
import io.hekate.spring.boot.HekateAutoConfigurerTestBase;
import io.hekate.spring.boot.HekateTestConfigBase;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class HekateNetworkServiceConfigurerTest extends HekateAutoConfigurerTestBase {
    @EnableAutoConfiguration
    static class NetworkTestConfig extends HekateTestConfigBase {
        private static class InnerBean {
            @NamedNetworkConnector("test2")
            private NetworkConnector<Object> innerConnector;
        }

        @NamedNetworkConnector("test1")
        private NetworkConnector<Object> connector;

        @Bean
        public InnerBean innerBean() {
            return new InnerBean();
        }

        @Bean
        public NetworkConnector<Object> connector1(NetworkService networkService) {
            return networkService.get("test1");
        }

        @Bean
        public NetworkConnector<Object> connector2(NetworkService networkService) {
            return networkService.get("test2");
        }

        @Bean
        public NetworkConnectorConfig<Object> connector1Config() {
            return new NetworkConnectorConfig<>().withProtocol("test1");
        }

        @Bean
        public NetworkConnectorConfig<Object> connector2Config() {
            return new NetworkConnectorConfig<>().withProtocol("test2");
        }
    }

    @Test
    public void testConnectors() {
        registerAndRefresh(NetworkTestConfig.class);

        assertNotNull(get("networkService", NetworkService.class));

        assertNotNull(get(NetworkTestConfig.class).connector);
        assertNotNull(get(NetworkTestConfig.InnerBean.class).innerConnector);

        assertEquals("test1", get(NetworkTestConfig.class).connector.getProtocol());
        assertEquals("test2", get(NetworkTestConfig.InnerBean.class).innerConnector.getProtocol());

        assertNotNull(getNode().get(NetworkService.class).get("test1"));
        assertNotNull(getNode().get(NetworkService.class).get("test2"));

        class TestAutowire {
            @Autowired
            private NetworkService networkService;

            @Autowired
            @Qualifier("connector1")
            private NetworkConnector<Object> connector1;

            @Autowired
            @Qualifier("connector2")
            private NetworkConnector<Object> connector2;
        }

        assertNotNull(autowire(new TestAutowire()).connector1);
        assertNotNull(autowire(new TestAutowire()).connector2);
        assertNotNull(autowire(new TestAutowire()).networkService);
    }
}