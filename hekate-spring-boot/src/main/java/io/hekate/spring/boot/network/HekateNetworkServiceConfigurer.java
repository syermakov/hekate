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

import io.hekate.core.Hekate;
import io.hekate.network.NetworkConnector;
import io.hekate.network.NetworkConnectorConfig;
import io.hekate.network.NetworkService;
import io.hekate.network.NetworkServiceFactory;
import io.hekate.network.address.AddressSelector;
import io.hekate.network.address.DefaultAddressSelector;
import io.hekate.network.address.DefaultAddressSelectorConfig;
import io.hekate.spring.bean.network.NetworkConnectorBean;
import io.hekate.spring.bean.network.NetworkServiceBean;
import io.hekate.spring.boot.ConditionalOnHekateEnabled;
import io.hekate.spring.boot.HekateConfigurer;
import io.hekate.spring.boot.internal.AnnotationInjectorBase;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * <span class="startHere">&laquo; start here</span>Auto-configuration for {@link NetworkService}.
 *
 * <h2>Overview</h2>
 * <p>
 * This auto-configuration constructs a {@link Bean} of {@link NetworkServiceFactory} type and automatically {@link
 * NetworkServiceFactory#setConnectors(List) registers} all {@link Bean}s of {@link NetworkConnectorConfig} type.
 * </p>
 *
 * <p>
 * <b>Note: </b> this auto-configuration is available only if application doesn't provide its own {@link Bean} of {@link
 * NetworkServiceFactory} type.
 * </p>
 *
 * <h2>Configuration properties</h2>
 * <p>
 * It is possible to configure {@link NetworkServiceFactory} via application properties prefixed with {@code 'hekate.network'}.
 * For example:
 * </p>
 * <ul>
 * <li>{@link NetworkServiceFactory#setHost(String) 'hekate.network.host'}</li>
 * <li>{@link NetworkServiceFactory#setPort(int) 'hekate.network.port'}</li>
 * <li>{@link NetworkServiceFactory#setPortRange(int) 'hekate.network.port-range'}</li>
 * <li>{@link NetworkServiceFactory#setConnectTimeout(int) 'hekate.network.connect-timeout'}</li>
 * <li>{@link NetworkServiceFactory#setAcceptRetryInterval(long) 'hekate.network.server-failover-interval'}</li>
 * <li>{@link NetworkServiceFactory#setHeartbeatInterval(int) 'hekate.network.heartbeat-interval'}</li>
 * <li>{@link NetworkServiceFactory#setHeartbeatLossThreshold(int) 'hekate.network.heartbeat-loss-threshold'}</li>
 * <li>{@link NetworkServiceFactory#setNioThreads(int) 'hekate.network.nio-thread-pool-size'}</li>
 * <li>{@link NetworkServiceFactory#setTcpNoDelay(boolean) 'hekate.network.tcp-no-delay'}</li>
 * <li>{@link NetworkServiceFactory#setTcpReceiveBufferSize(Integer) 'hekate.network.tcp-receive-buffer-size'}</li>
 * <li>{@link NetworkServiceFactory#setTcpSendBufferSize(Integer) 'hekate.network.tcp-send-buffer-size'}</li>
 * <li>{@link NetworkServiceFactory#setTcpReuseAddress(Boolean) 'hekate.network.tcp-reuse-address'}</li>
 * <li>{@link NetworkServiceFactory#setTcpBacklog(Integer) 'hekate.network.tcp-backlog'}</li>
 * </ul>
 *
 * <h2>Connectors injections</h2>
 * <p>
 * This auto-configuration provides support for injecting beans of {@link NetworkConnector} type into other beans with the help of {@link
 * NamedNetworkConnector} annotation. Please see its documentation for more details.
 * </p>
 *
 * @see NetworkService
 * @see HekateConfigurer
 */
@Configuration
@ConditionalOnHekateEnabled
@AutoConfigureBefore(HekateConfigurer.class)
@ConditionalOnMissingBean(NetworkServiceFactory.class)
public class HekateNetworkServiceConfigurer {
    @Component
    static class NamedNetworkConnectorInjector extends AnnotationInjectorBase<NamedNetworkConnector> {
        public NamedNetworkConnectorInjector() {
            super(NamedNetworkConnector.class, NetworkConnectorBean.class);
        }

        @Override
        protected String injectedBeanName(NamedNetworkConnector annotation) {
            return NetworkConnectorBean.class.getName() + "-" + annotation.value();
        }

        @Override
        protected Object qualifierValue(NamedNetworkConnector annotation) {
            return annotation.value();
        }

        @Override
        protected void configure(BeanDefinitionBuilder builder, NamedNetworkConnector annotation) {
            builder.addPropertyValue("protocol", annotation.value());
        }
    }

    private final List<NetworkConnectorConfig<?>> connectors;

    /**
     * Constructs new instance.
     *
     * @param connectors {@link NetworkConnectorConfig}s that were found in the application context.
     */
    public HekateNetworkServiceConfigurer(Optional<List<NetworkConnectorConfig<?>>> connectors) {
        this.connectors = connectors.orElse(null);
    }

    /**
     * Conditionally constructs a configuration for the default address selector if application doesn't provide its own {@link Bean} of
     * {@link AddressSelector} type.
     *
     * @return Default configuration for {@link #defaultAddressSelector(DefaultAddressSelectorConfig)}.
     *
     * @see #defaultAddressSelector(DefaultAddressSelectorConfig)
     * @see #networkServiceFactory(AddressSelector)
     */
    @Bean
    @ConfigurationProperties(prefix = "hekate.address")
    @ConditionalOnMissingBean({DefaultAddressSelectorConfig.class, AddressSelector.class})
    public DefaultAddressSelectorConfig defaultAddressSelectorConfig() {
        return new DefaultAddressSelectorConfig();
    }

    /**
     * Conditionally constructs the default address selector if application doesn't provide its own {@link Bean} of
     * {@link AddressSelector} type.
     *
     * @param cfg Configuration (see {@link #defaultAddressSelectorConfig()}).
     *
     * @return Address selector.
     *
     * @see #defaultAddressSelectorConfig()
     * @see #networkServiceFactory(AddressSelector)
     */
    @Bean
    @ConditionalOnMissingBean(AddressSelector.class)
    public DefaultAddressSelector defaultAddressSelector(DefaultAddressSelectorConfig cfg) {
        return new DefaultAddressSelector(cfg);
    }

    /**
     * Constructs the {@link NetworkServiceFactory}.
     *
     * @param addressSelector Address selector (see {@link #defaultAddressSelector(DefaultAddressSelectorConfig)}).
     *
     * @return Service factory.
     */
    @Bean
    @ConfigurationProperties(prefix = "hekate.network")
    public NetworkServiceFactory networkServiceFactory(AddressSelector addressSelector) {
        NetworkServiceFactory factory = new NetworkServiceFactory();

        factory.setConnectors(connectors);
        factory.setAddressSelector(addressSelector);

        return factory;
    }

    /**
     * Returns the factory bean that makes it possible to inject {@link NetworkService} directly into other beans instead of accessing it
     * via {@link Hekate#network()} method.
     *
     * @return Service bean.
     */
    @Bean
    public NetworkServiceBean networkService() {
        return new NetworkServiceBean();
    }
}
