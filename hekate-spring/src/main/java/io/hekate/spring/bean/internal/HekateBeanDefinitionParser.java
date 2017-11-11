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

package io.hekate.spring.bean.internal;

import io.hekate.cluster.ClusterServiceFactory;
import io.hekate.cluster.health.DefaultFailureDetector;
import io.hekate.cluster.health.DefaultFailureDetectorConfig;
import io.hekate.cluster.seed.StaticSeedNodeProvider;
import io.hekate.cluster.seed.StaticSeedNodeProviderConfig;
import io.hekate.cluster.seed.fs.FsSeedNodeProvider;
import io.hekate.cluster.seed.fs.FsSeedNodeProviderConfig;
import io.hekate.cluster.seed.jclouds.BasicCredentialsSupplier;
import io.hekate.cluster.seed.jclouds.CloudSeedNodeProvider;
import io.hekate.cluster.seed.jclouds.CloudSeedNodeProviderConfig;
import io.hekate.cluster.seed.jclouds.CloudStoreSeedNodeProvider;
import io.hekate.cluster.seed.jclouds.CloudStoreSeedNodeProviderConfig;
import io.hekate.cluster.seed.jclouds.aws.AwsCredentialsSupplier;
import io.hekate.cluster.seed.jdbc.JdbcSeedNodeProvider;
import io.hekate.cluster.seed.jdbc.JdbcSeedNodeProviderConfig;
import io.hekate.cluster.seed.multicast.MulticastSeedNodeProvider;
import io.hekate.cluster.seed.multicast.MulticastSeedNodeProviderConfig;
import io.hekate.cluster.split.AddressReachabilityDetector;
import io.hekate.cluster.split.HostReachabilityDetector;
import io.hekate.cluster.split.SplitBrainDetectorGroup;
import io.hekate.coordinate.CoordinationProcessConfig;
import io.hekate.coordinate.CoordinationServiceFactory;
import io.hekate.election.CandidateConfig;
import io.hekate.election.ElectionServiceFactory;
import io.hekate.lock.LockRegionConfig;
import io.hekate.lock.LockServiceFactory;
import io.hekate.messaging.MessagingBackPressureConfig;
import io.hekate.messaging.MessagingChannelConfig;
import io.hekate.messaging.MessagingServiceFactory;
import io.hekate.metrics.cluster.ClusterMetricsServiceFactory;
import io.hekate.metrics.local.CounterConfig;
import io.hekate.metrics.local.LocalMetricsServiceFactory;
import io.hekate.metrics.local.ProbeConfig;
import io.hekate.network.NetworkConnectorConfig;
import io.hekate.network.NetworkServiceFactory;
import io.hekate.rpc.RpcClientConfig;
import io.hekate.rpc.RpcServerConfig;
import io.hekate.rpc.RpcServiceFactory;
import io.hekate.spring.bean.HekateSpringBootstrap;
import io.hekate.spring.bean.cluster.ClusterServiceBean;
import io.hekate.spring.bean.codec.CodecServiceBean;
import io.hekate.spring.bean.coordinate.CoordinationServiceBean;
import io.hekate.spring.bean.election.ElectionServiceBean;
import io.hekate.spring.bean.lock.LockBean;
import io.hekate.spring.bean.lock.LockRegionBean;
import io.hekate.spring.bean.lock.LockServiceBean;
import io.hekate.spring.bean.messaging.MessagingChannelBean;
import io.hekate.spring.bean.messaging.MessagingServiceBean;
import io.hekate.spring.bean.metrics.ClusterMetricsServiceBean;
import io.hekate.spring.bean.metrics.CounterMetricBean;
import io.hekate.spring.bean.metrics.LocalMetricsServiceBean;
import io.hekate.spring.bean.metrics.MetricBean;
import io.hekate.spring.bean.network.NetworkConnectorBean;
import io.hekate.spring.bean.network.NetworkServiceBean;
import io.hekate.spring.bean.rpc.RpcClientBean;
import io.hekate.spring.bean.rpc.RpcServiceBean;
import io.hekate.spring.bean.task.TaskServiceBean;
import io.hekate.task.TaskServiceFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.ManagedSet;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import static java.util.stream.Collectors.toSet;
import static org.springframework.util.xml.DomUtils.getChildElementByTagName;
import static org.springframework.util.xml.DomUtils.getChildElementsByTagName;
import static org.springframework.util.xml.DomUtils.getTextValue;

public class HekateBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {
    private final Map<BeanDefinitionBuilder, String> deferredBaseBeans = new HashMap<>();

    @Override
    protected Class<?> getBeanClass(Element element) {
        return HekateSpringBootstrap.class;
    }

    @Override
    protected boolean shouldGenerateId() {
        return false;
    }

    @Override
    protected boolean shouldGenerateIdAsFallback() {
        return true;
    }

    @Override
    protected String resolveId(Element el, AbstractBeanDefinition def, ParserContext ctx) {
        String id = super.resolveId(el, def, ctx);

        deferredBaseBeans.forEach((baseBeanBuilder, baseBeanName) -> {
            baseBeanBuilder.addPropertyValue("source", new RuntimeBeanReference(id));

            AbstractBeanDefinition baseBean = baseBeanBuilder.getBeanDefinition();

            if (baseBeanName == null) {
                ctx.getRegistry().registerBeanDefinition(ctx.getReaderContext().generateBeanName(baseBean), baseBean);
            } else {
                ctx.getRegistry().registerBeanDefinition(baseBeanName, baseBean);
            }
        });

        return id;
    }

    @Override
    protected void doParse(Element rootEl, ParserContext ctx, BeanDefinitionBuilder boot) {
        setProperty(boot, rootEl, "nodeName", "name");
        setProperty(boot, rootEl, "clusterName", "cluster");

        parseNodeRoles(boot, rootEl);

        parseProperties(rootEl).ifPresent(props ->
            boot.addPropertyValue("properties", props)
        );

        parseNodePropertyProviders(boot, rootEl, ctx);

        parseDefaultCodec(boot, rootEl, ctx);

        ManagedList<RuntimeBeanReference> services = new ManagedList<>();

        parseClusterService(rootEl, ctx).ifPresent(services::add);
        parseNetworkService(rootEl, ctx).ifPresent(services::add);
        parseMessagingService(rootEl, ctx).ifPresent(services::add);
        parseRpcService(rootEl, ctx).ifPresent(services::add);
        parseTaskService(rootEl, ctx).ifPresent(services::add);
        parseLockService(rootEl, ctx).ifPresent(services::add);
        parseCoordinationService(rootEl, ctx).ifPresent(services::add);
        parseElectionService(rootEl, ctx).ifPresent(services::add);
        parseLocalMetricsService(rootEl, ctx).ifPresent(services::add);
        parseClusterMetricsService(rootEl, ctx).ifPresent(services::add);

        subElements(rootEl, "custom-services", "service").forEach(serviceEl ->
            parseRefOrBean(serviceEl, ctx).ifPresent(services::add)
        );

        if (!services.isEmpty()) {
            boot.addPropertyValue("services", services);
        }

        ManagedList<RuntimeBeanReference> plugins = new ManagedList<>();

        subElements(rootEl, "plugins", "plugin").forEach(pluginEl ->
            parseRefOrBean(pluginEl, ctx).ifPresent(plugins::add)
        );

        if (!plugins.isEmpty()) {
            boot.addPropertyValue("plugins", plugins);
        }
    }

    private void parseNodeRoles(BeanDefinitionBuilder def, Element el) {
        ManagedSet<String> roles = new ManagedSet<>();

        roles.addAll(subElements(el, "roles", "role").stream()
            .map(roleEl -> getTextValue(roleEl).trim())
            .filter(role -> !role.isEmpty())
            .collect(toSet())
        );

        def.addPropertyValue("roles", roles);
    }

    private void parseNodePropertyProviders(BeanDefinitionBuilder boot, Element rootEl, ParserContext ctx) {
        ManagedList<RuntimeBeanReference> propertyProviders = new ManagedList<>();

        subElements(rootEl, "property-providers", "provider").forEach(provider ->
            parseRefOrBean(provider, ctx).ifPresent(propertyProviders::add)
        );

        if (!propertyProviders.isEmpty()) {
            boot.addPropertyValue("propertyProviders", propertyProviders);
        }
    }

    private void parseDefaultCodec(BeanDefinitionBuilder boot, Element rootEl, ParserContext ctx) {
        setBeanOrRef(boot, rootEl, "defaultCodec", "default-codec", ctx);

        BeanDefinitionBuilder serviceDef = newBean(CodecServiceBean.class, rootEl);

        deferredBaseBeans.put(serviceDef, null);
    }

    private Optional<RuntimeBeanReference> parseClusterService(Element rootEl, ParserContext ctx) {
        Element clusterEl = getChildElementByTagName(rootEl, "cluster");

        if (clusterEl != null) {
            BeanDefinitionBuilder cluster = newBean(ClusterServiceFactory.class, clusterEl);

            setProperty(cluster, clusterEl, "gossipInterval", "gossip-interval-ms");
            setProperty(cluster, clusterEl, "speedUpGossipSize", "gossip-speedup-size");

            parseClusterSeedNodeProvider(cluster, clusterEl, ctx);

            parseClusterFailureDetection(cluster, clusterEl, ctx);

            parseClusterSplitBrainDetection(cluster, clusterEl, ctx);

            parseClusterAcceptors(cluster, clusterEl, ctx);

            parseClusterListeners(cluster, clusterEl, ctx);

            String id = clusterEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(ClusterServiceBean.class, clusterEl), id);
            }

            return Optional.of(registerInnerBean(cluster, ctx));
        } else {
            return Optional.empty();
        }
    }

    private void parseClusterSeedNodeProvider(BeanDefinitionBuilder cluster, Element clusterEl, ParserContext ctx) {
        Element providerEl = getChildElementByTagName(clusterEl, "seed-node-provider");

        if (providerEl != null) {
            Element multicastEl = getChildElementByTagName(providerEl, "multicast");
            Element staticEl = getChildElementByTagName(providerEl, "static");
            Element jdbcEl = getChildElementByTagName(providerEl, "jdbc");
            Element sharedFsEl = getChildElementByTagName(providerEl, "shared-folder");
            Element cloudEl = getChildElementByTagName(providerEl, "cloud");
            Element cloudStoreEl = getChildElementByTagName(providerEl, "cloud-store");

            if (multicastEl != null) {
                BeanDefinitionBuilder cfg = newBean(MulticastSeedNodeProviderConfig.class, multicastEl);

                setProperty(cfg, multicastEl, "group", "group");
                setProperty(cfg, multicastEl, "port", "port");
                setProperty(cfg, multicastEl, "ttl", "ttl");
                setProperty(cfg, multicastEl, "interval", "interval-ms");
                setProperty(cfg, multicastEl, "waitTime", "wait-time-ms");
                setProperty(cfg, multicastEl, "loopBackDisabled", "loopback-disabled");

                BeanDefinitionBuilder provider = newBean(MulticastSeedNodeProvider.class, multicastEl);

                provider.addConstructorArgValue(registerInnerBean(cfg, ctx));

                cluster.addPropertyValue("seedNodeProvider", registerInnerBean(provider, ctx));
            } else if (staticEl != null) {
                BeanDefinitionBuilder cfg = newBean(StaticSeedNodeProviderConfig.class, staticEl);

                List<String> addresses = new ArrayList<>();

                getChildElementsByTagName(staticEl, "address").forEach(addrEl ->
                    addresses.add(getTextValue(addrEl))
                );

                if (!addresses.isEmpty()) {
                    cfg.addPropertyValue("addresses", addresses);
                }

                BeanDefinitionBuilder provider = newBean(StaticSeedNodeProvider.class, staticEl);

                provider.addConstructorArgValue(registerInnerBean(cfg, ctx));

                cluster.addPropertyValue("seedNodeProvider", registerInnerBean(provider, ctx));
            } else if (jdbcEl != null) {
                BeanDefinitionBuilder cfg = newBean(JdbcSeedNodeProviderConfig.class, jdbcEl);

                setProperty(cfg, jdbcEl, "queryTimeout", "query-timeout-sec");
                setProperty(cfg, jdbcEl, "cleanupInterval", "cleanup-interval-ms");
                setProperty(cfg, jdbcEl, "table", "table");
                setProperty(cfg, jdbcEl, "hostColumn", "host-column");
                setProperty(cfg, jdbcEl, "portColumn", "port-column");
                setProperty(cfg, jdbcEl, "clusterColumn", "cluster-column");

                setBeanOrRef(cfg, jdbcEl, "dataSource", "datasource", ctx);

                BeanDefinitionBuilder provider = newBean(JdbcSeedNodeProvider.class, jdbcEl);

                provider.addConstructorArgValue(registerInnerBean(cfg, ctx));

                cluster.addPropertyValue("seedNodeProvider", registerInnerBean(provider, ctx));
            } else if (sharedFsEl != null) {
                BeanDefinitionBuilder cfg = newBean(FsSeedNodeProviderConfig.class, sharedFsEl);

                setProperty(cfg, sharedFsEl, "workDir", "work-dir");
                setProperty(cfg, sharedFsEl, "cleanupInterval", "cleanup-interval-ms");

                BeanDefinitionBuilder provider = newBean(FsSeedNodeProvider.class, sharedFsEl);

                provider.addConstructorArgValue(registerInnerBean(cfg, ctx));

                cluster.addPropertyValue("seedNodeProvider", registerInnerBean(provider, ctx));
            } else if (cloudEl != null) {
                BeanDefinitionBuilder cfg = newBean(CloudSeedNodeProviderConfig.class, cloudStoreEl);

                setProperty(cfg, cloudEl, "provider", "provider");
                setProperty(cfg, cloudEl, "endpoint", "endpoint");

                parseProperties(cloudEl).ifPresent(props ->
                    cfg.addPropertyValue("properties", props)
                );

                parseCloudProviderCredentials(cloudEl, ctx).ifPresent(credRef ->
                    cfg.addPropertyValue("credentials", credRef)
                );

                // Regions.
                ManagedSet<String> regions = new ManagedSet<>();

                regions.addAll(subElements(cloudEl, "regions", "region").stream()
                    .map(regionEl -> getTextValue(regionEl).trim())
                    .filter(region -> !region.isEmpty())
                    .collect(toSet())
                );

                if (!regions.isEmpty()) {
                    cfg.addPropertyValue("regions", regions);
                }

                // Zones.
                ManagedSet<String> zones = new ManagedSet<>();

                zones.addAll(subElements(cloudEl, "zones", "zone").stream()
                    .map(zoneEl -> getTextValue(zoneEl).trim())
                    .filter(zone -> !zone.isEmpty())
                    .collect(toSet())
                );

                if (!zones.isEmpty()) {
                    cfg.addPropertyValue("zones", zones);
                }

                // Tags.
                ManagedMap<String, String> tags = new ManagedMap<>();

                subElements(cloudEl, "tags", "tag").forEach(tagEl -> {
                    String name = tagEl.getAttribute("name").trim();
                    String value = tagEl.getAttribute("value").trim();

                    if (!name.isEmpty() && !value.isEmpty()) {
                        tags.put(name, value);
                    }
                });

                if (!tags.isEmpty()) {
                    cfg.addPropertyValue("tags", tags);
                }

                BeanDefinitionBuilder provider = newBean(CloudSeedNodeProvider.class, cloudEl);

                provider.addConstructorArgValue(registerInnerBean(cfg, ctx));

                cluster.addPropertyValue("seedNodeProvider", registerInnerBean(provider, ctx));
            } else if (cloudStoreEl != null) {
                BeanDefinitionBuilder cfg = newBean(CloudStoreSeedNodeProviderConfig.class, cloudStoreEl);

                setProperty(cfg, cloudStoreEl, "provider", "provider");
                setProperty(cfg, cloudStoreEl, "container", "container");
                setProperty(cfg, cloudStoreEl, "cleanupInterval", "cleanup-interval-ms");

                parseProperties(cloudStoreEl).ifPresent(props ->
                    cfg.addPropertyValue("properties", props)
                );

                parseCloudProviderCredentials(cloudStoreEl, ctx).ifPresent(credRef ->
                    cfg.addPropertyValue("credentials", credRef)
                );

                BeanDefinitionBuilder provider = newBean(CloudStoreSeedNodeProvider.class, cloudStoreEl);

                provider.addConstructorArgValue(registerInnerBean(cfg, ctx));

                cluster.addPropertyValue("seedNodeProvider", registerInnerBean(provider, ctx));
            } else {
                setBeanOrRef(cluster, providerEl, "seedNodeProvider", "custom-provider", ctx);
            }
        }
    }

    private Optional<RuntimeBeanReference> parseCloudProviderCredentials(Element cloudEl, ParserContext ctx) {
        String providerName = cloudEl.getAttribute("provider").trim();

        Element credEl = getChildElementByTagName(cloudEl, "credentials");

        RuntimeBeanReference credRef = null;

        if (credEl != null) {
            Element basicCredEl = getChildElementByTagName(credEl, "basic");

            if (basicCredEl != null) {
                BeanDefinitionBuilder supplier;

                if (providerName.contains("aws")) {
                    supplier = newBean(AwsCredentialsSupplier.class, basicCredEl);
                } else {
                    supplier = newBean(BasicCredentialsSupplier.class, basicCredEl);
                }

                setProperty(supplier, basicCredEl, "identity", "identity");
                setProperty(supplier, basicCredEl, "credential", "credential");

                credRef = registerInnerBean(supplier, ctx);
            } else {
                credRef = parseRefOrBean(getChildElementByTagName(credEl, "custom-supplier"), ctx).orElse(null);
            }
        }

        if (credRef == null && providerName.contains("aws")) {
            credRef = registerInnerBean(newBean(AwsCredentialsSupplier.class, cloudEl), ctx);
        }

        return Optional.ofNullable(credRef);
    }

    private void parseClusterFailureDetection(BeanDefinitionBuilder cluster, Element clusterEl, ParserContext ctx) {
        Element detectionEl = getChildElementByTagName(clusterEl, "failure-detection");

        if (detectionEl != null) {
            Element heartbeatEl = getChildElementByTagName(detectionEl, "heartbeat");

            if (heartbeatEl != null) {
                BeanDefinitionBuilder heartbeatCfg = newBean(DefaultFailureDetectorConfig.class, heartbeatEl);

                setProperty(heartbeatCfg, heartbeatEl, "heartbeatInterval", "interval-ms");
                setProperty(heartbeatCfg, heartbeatEl, "heartbeatLossThreshold", "loss-threshold");
                setProperty(heartbeatCfg, heartbeatEl, "failureDetectionQuorum", "quorum");

                BeanDefinitionBuilder heartbeat = newBean(DefaultFailureDetector.class, heartbeatEl);

                heartbeat.addConstructorArgValue(registerInnerBean(heartbeatCfg, ctx));

                cluster.addPropertyValue("failureDetector", registerInnerBean(heartbeat, ctx));
            } else {
                setBeanOrRef(cluster, detectionEl, "failureDetector", "custom-detector", ctx);
            }
        }
    }

    private void parseClusterSplitBrainDetection(BeanDefinitionBuilder cluster, Element clusterEl, ParserContext ctx) {
        Element splitBrainEl = getChildElementByTagName(clusterEl, "split-brain-detection");

        if (splitBrainEl != null) {
            setProperty(cluster, splitBrainEl, "splitBrainAction", "action");

            Element groupEl = getChildElementByTagName(splitBrainEl, "group");

            if (groupEl != null) {
                cluster.addPropertyValue("splitBrainDetector", parseClusterSplitBrainGroup(groupEl, ctx));
            }
        }
    }

    private RuntimeBeanReference parseClusterSplitBrainGroup(Element groupEl, ParserContext ctx) {
        List<Element> hostEls = getChildElementsByTagName(groupEl, "host-reachable");
        List<Element> addressEls = getChildElementsByTagName(groupEl, "address-reachable");
        List<Element> customEls = getChildElementsByTagName(groupEl, "custom-detector");
        List<Element> nestedGroupEls = getChildElementsByTagName(groupEl, "group");

        ManagedList<RuntimeBeanReference> detectors = new ManagedList<>();

        for (Element hostEl : hostEls) {
            BeanDefinitionBuilder detector = newBean(HostReachabilityDetector.class, hostEl);

            String host = hostEl.getAttribute("host").trim();
            String timeout = hostEl.getAttribute("timeout").trim();

            detector.addConstructorArgValue(host);

            if (!timeout.isEmpty()) {
                detector.addConstructorArgValue(timeout);
            }

            detectors.add(registerInnerBean(detector, ctx));
        }

        for (Element addressEl : addressEls) {
            BeanDefinitionBuilder detector = newBean(AddressReachabilityDetector.class, addressEl);

            String address = addressEl.getAttribute("address").trim();
            String timeout = addressEl.getAttribute("timeout").trim();

            detector.addConstructorArgValue(address);

            if (!timeout.isEmpty()) {
                detector.addConstructorArgValue(timeout);
            }

            detectors.add(registerInnerBean(detector, ctx));
        }

        for (Element customEl : customEls) {
            parseRefOrBean(customEl, ctx).ifPresent(detectors::add);
        }

        detectors.addAll(nestedGroupEls.stream()
            .map(nestedGroupEl -> parseClusterSplitBrainGroup(nestedGroupEl, ctx))
            .collect(Collectors.toList())
        );

        BeanDefinitionBuilder group = newBean(SplitBrainDetectorGroup.class, groupEl);

        setProperty(group, groupEl, "groupPolicy", "require");

        if (!detectors.isEmpty()) {
            group.addPropertyValue("detectors", detectors);
        }

        return registerInnerBean(group, ctx);
    }

    private void parseClusterAcceptors(BeanDefinitionBuilder cluster, Element clusterEl, ParserContext ctx) {
        ManagedList<RuntimeBeanReference> acceptors = new ManagedList<>();

        subElements(clusterEl, "acceptors", "acceptor").forEach(valEl ->
            parseRefOrBean(valEl, ctx).ifPresent(acceptors::add)
        );

        if (!acceptors.isEmpty()) {
            cluster.addPropertyValue("acceptors", acceptors);
        }
    }

    private void parseClusterListeners(BeanDefinitionBuilder cluster, Element clusterEl, ParserContext ctx) {
        ManagedList<RuntimeBeanReference> listeners = new ManagedList<>();

        subElements(clusterEl, "listeners", "listener").forEach(valEl ->
            parseRefOrBean(valEl, ctx).ifPresent(listeners::add)
        );

        if (!listeners.isEmpty()) {
            cluster.addPropertyValue("clusterListeners", listeners);
        }
    }

    private Optional<RuntimeBeanReference> parseNetworkService(Element rootEl, ParserContext ctx) {
        Element netEl = getChildElementByTagName(rootEl, "network");

        if (netEl != null) {
            BeanDefinitionBuilder net = newBean(NetworkServiceFactory.class, netEl);

            setProperty(net, netEl, "host", "host");
            setProperty(net, netEl, "port", "port");
            setProperty(net, netEl, "portRange", "port-range");
            setProperty(net, netEl, "connectTimeout", "connect-timeout-ms");
            setProperty(net, netEl, "acceptRetryInterval", "accept-retry-interval-ms");
            setProperty(net, netEl, "heartbeatInterval", "heartbeat-interval-ms");
            setProperty(net, netEl, "heartbeatLossThreshold", "heartbeat-loss-threshold");
            setProperty(net, netEl, "nioThreads", "nio-threads");
            setProperty(net, netEl, "transport", "transport");
            setProperty(net, netEl, "tcpNoDelay", "tcp-no-delay");
            setProperty(net, netEl, "tcpReceiveBufferSize", "tcp-receive-buffer-size");
            setProperty(net, netEl, "tcpSendBufferSize", "tcp-send-buffer-size");
            setProperty(net, netEl, "tcpReuseAddress", "tcp-reuse-address");
            setProperty(net, netEl, "tcpBacklog", "tcp-backlog");

            setBeanOrRef(net, netEl, "hostSelector", "host-selector", ctx);

            ManagedList<RuntimeBeanReference> connectors = new ManagedList<>();

            for (Element connEl : subElements(netEl, "connectors", "connector")) {
                BeanDefinitionBuilder conn = newBean(NetworkConnectorConfig.class, connEl);

                setProperty(conn, connEl, "protocol", "protocol");
                setProperty(conn, connEl, "idleSocketTimeout", "idle-socket-timeout-ms");
                setProperty(conn, connEl, "nioThreads", "nio-threads");
                setProperty(conn, connEl, "logCategory", "log-category");

                setBeanOrRef(conn, connEl, "serverHandler", "server-handler", ctx);
                setBeanOrRef(conn, connEl, "messageCodec", "message-codec", ctx);

                connectors.add(registerInnerBean(conn, ctx));

                String protocol = connEl.getAttribute("protocol");

                if (!protocol.isEmpty()) {
                    BeanDefinitionBuilder connBean = newBean(NetworkConnectorBean.class, netEl);

                    setProperty(connBean, connEl, "protocol", "protocol");

                    deferredBaseBeans.put(connBean, protocol);
                }
            }

            if (!connectors.isEmpty()) {
                net.addPropertyValue("connectors", connectors);
            }

            String id = netEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(NetworkServiceBean.class, netEl), id);
            }

            return Optional.of(registerInnerBean(net, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseMessagingService(Element rootEl, ParserContext ctx) {
        Element msgEl = getChildElementByTagName(rootEl, "messaging");

        if (msgEl != null) {
            BeanDefinitionBuilder msg = newBean(MessagingServiceFactory.class, msgEl);

            ManagedList<RuntimeBeanReference> channels = new ManagedList<>();

            for (Element channelEl : getChildElementsByTagName(msgEl, "channel")) {
                BeanDefinitionBuilder channel = newBean(MessagingChannelConfig.class, channelEl);

                // Channel type.
                String type = channelEl.getAttribute("base-type").trim();

                if (!type.isEmpty()) {
                    channel.addConstructorArgValue(type);
                }

                // Attributes.
                setProperty(channel, channelEl, "name", "name");
                setProperty(channel, channelEl, "nioThreads", "nio-threads");
                setProperty(channel, channelEl, "workerThreads", "worker-threads");
                setProperty(channel, channelEl, "backupNodes", "backup-nodes");
                setProperty(channel, channelEl, "partitions", "partitions");
                setProperty(channel, channelEl, "idleSocketTimeout", "idle-socket-timeout-ms");
                setProperty(channel, channelEl, "logCategory", "log-category");
                setProperty(channel, channelEl, "messagingTimeout", "messaging-timeout-ms");

                // Nested elements.
                setBeanOrRef(channel, channelEl, "receiver", "receiver", ctx);
                setBeanOrRef(channel, channelEl, "loadBalancer", "load-balancer", ctx);
                setBeanOrRef(channel, channelEl, "messageCodec", "message-codec", ctx);
                setBeanOrRef(channel, channelEl, "clusterFilter", "cluster-filter", ctx);
                setBeanOrRef(channel, channelEl, "failoverPolicy", "failover-policy", ctx);

                // Back pressure element.
                parseMessagingBackPressure(channelEl, ctx).ifPresent(backPressureRef ->
                    channel.addPropertyValue("backPressure", backPressureRef)
                );

                // Register channel bean definition.
                channels.add(registerInnerBean(channel, ctx));

                String name = channelEl.getAttribute("name");

                if (!name.isEmpty()) {
                    BeanDefinitionBuilder channelBean = newBean(MessagingChannelBean.class, channelEl);

                    setProperty(channelBean, channelEl, "channel", "name");

                    deferredBaseBeans.put(channelBean, name);
                }
            }

            if (!channels.isEmpty()) {
                msg.addPropertyValue("channels", channels);
            }

            String id = msgEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(MessagingServiceBean.class, msgEl), id);
            }

            return Optional.of(registerInnerBean(msg, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseMessagingBackPressure(Element channelEl, ParserContext ctx) {
        Element backPressureEl = getChildElementByTagName(channelEl, "back-pressure");

        if (backPressureEl != null) {
            BeanDefinitionBuilder backPressure = newBean(MessagingBackPressureConfig.class, backPressureEl);

            Element outboundEl = getChildElementByTagName(backPressureEl, "outbound");
            Element inboundEl = getChildElementByTagName(backPressureEl, "inbound");

            if (outboundEl != null) {
                setProperty(backPressure, outboundEl, "outLowWatermark", "low-watermark");
                setProperty(backPressure, outboundEl, "outHighWatermark", "high-watermark");
                setProperty(backPressure, outboundEl, "outOverflow", "overflow");
            }

            if (inboundEl != null) {
                setProperty(backPressure, inboundEl, "inLowWatermark", "low-watermark");
                setProperty(backPressure, inboundEl, "inHighWatermark", "high-watermark");
            }

            return Optional.of(registerInnerBean(backPressure, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseTaskService(Element rootEl, ParserContext ctx) {
        Element tasksEl = getChildElementByTagName(rootEl, "tasks");

        if (tasksEl != null) {
            BeanDefinitionBuilder tasks = newBean(TaskServiceFactory.class, tasksEl);

            setProperty(tasks, tasksEl, "workerThreads", "worker-threads");
            setProperty(tasks, tasksEl, "nioThreads", "nio-threads");
            setProperty(tasks, tasksEl, "idleSocketTimeout", "idle-socket-timeout-ms");

            String id = tasksEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(TaskServiceBean.class, tasksEl), id);
            }

            return Optional.of(registerInnerBean(tasks, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseRpcService(Element rootEl, ParserContext ctx) {
        Element rpcEl = getChildElementByTagName(rootEl, "rpc");

        if (rpcEl != null) {
            BeanDefinitionBuilder rpc = newBean(RpcServiceFactory.class, rpcEl);

            setProperty(rpc, rpcEl, "workerThreads", "worker-threads");
            setProperty(rpc, rpcEl, "nioThreads", "nio-threads");
            setProperty(rpc, rpcEl, "idleSocketTimeout", "idle-socket-timeout-ms");

            // RPC clients.
            ManagedList<RuntimeBeanReference> clients = new ManagedList<>();

            getChildElementsByTagName(rpcEl, "client").forEach(clientEl -> {
                BeanDefinitionBuilder client = newBean(RpcClientConfig.class, clientEl);

                // Attributes.
                setProperty(client, clientEl, "rpcInterface", "interface");
                setProperty(client, clientEl, "tag", "tag");
                setProperty(client, clientEl, "timeout", "timeout-ms");

                // Nested elements.
                setBeanOrRef(client, clientEl, "loadBalancer", "load-balancer", ctx);
                setBeanOrRef(client, clientEl, "failoverPolicy", "failover-policy", ctx);

                String name = clientEl.getAttribute("name");

                if (!name.isEmpty()) {
                    BeanDefinitionBuilder clientBean = newBean(RpcClientBean.class, clientEl);

                    setProperty(clientBean, clientEl, "rpcInterface", "interface");
                    setProperty(clientBean, clientEl, "tag", "tag");

                    deferredBaseBeans.put(clientBean, name);
                }
            });

            if (!clients.isEmpty()) {
                rpc.addPropertyValue("clients", clients);
            }

            // RPC servers.
            ManagedList<RuntimeBeanReference> servers = new ManagedList<>();

            getChildElementsByTagName(rpcEl, "server").forEach(serverEl -> {
                BeanDefinitionBuilder server = newBean(RpcServerConfig.class, serverEl);

                // Attributes.
                setProperty(server, serverEl, "rpcInterface", "interface");
                setProperty(server, serverEl, "tag", "tag");
                setProperty(server, serverEl, "timeout", "timeout-ms");

                // Nested elements.
                setBeanOrRef(server, serverEl, "handler", "handler", ctx);

                ManagedSet<String> tags = new ManagedSet<>();

                subElements(serverEl, "tags", "tag").stream()
                    .map(DomUtils::getTextValue)
                    .filter(it -> !it.isEmpty())
                    .forEach(tags::add);

                if (!tags.isEmpty()) {
                    server.addPropertyValue("tags", tags);
                }
            });

            if (!servers.isEmpty()) {
                rpc.addPropertyValue("servers", servers);
            }

            String id = rpcEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(RpcServiceBean.class, rpcEl), id);
            }

            return Optional.of(registerInnerBean(rpc, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseLockService(Element rootEl, ParserContext ctx) {
        Element locksEl = getChildElementByTagName(rootEl, "locks");

        if (locksEl != null) {
            BeanDefinitionBuilder locks = newBean(LockServiceFactory.class, locksEl);

            setProperty(locks, locksEl, "retryInterval", "retry-interval-ms");
            setProperty(locks, locksEl, "nioThreads", "nio-threads");
            setProperty(locks, locksEl, "workerThreads", "worker-threads");

            ManagedList<RuntimeBeanReference> regions = new ManagedList<>();

            getChildElementsByTagName(locksEl, "region").forEach(regionEl -> {
                BeanDefinitionBuilder region = newBean(LockRegionConfig.class, regionEl);

                setProperty(region, regionEl, "name", "name");

                regions.add(registerInnerBean(region, ctx));

                String name = regionEl.getAttribute("name");

                if (!name.isEmpty()) {
                    BeanDefinitionBuilder regionBean = newBean(LockRegionBean.class, regionEl);

                    setProperty(regionBean, regionEl, "region", "name");

                    deferredBaseBeans.put(regionBean, name);
                }

                getChildElementsByTagName(regionEl, "lock").forEach(lockEl -> {
                    String lockName = lockEl.getAttribute("name");

                    if (!lockName.isEmpty()) {
                        BeanDefinitionBuilder lockBean = newBean(LockBean.class, lockEl);

                        setProperty(lockBean, regionEl, "region", "name");
                        setProperty(lockBean, lockEl, "name", "name");

                        deferredBaseBeans.put(lockBean, lockName);
                    }
                });
            });

            if (!regions.isEmpty()) {
                locks.addPropertyValue("regions", regions);
            }

            String id = locksEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(LockServiceBean.class, locksEl), id);
            }

            return Optional.of(registerInnerBean(locks, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseCoordinationService(Element rootEl, ParserContext ctx) {
        Element coordinationEl = getChildElementByTagName(rootEl, "coordination");

        if (coordinationEl != null) {
            BeanDefinitionBuilder coordination = newBean(CoordinationServiceFactory.class, coordinationEl);

            ManagedList<RuntimeBeanReference> processes = new ManagedList<>();

            getChildElementsByTagName(coordinationEl, "process").forEach(processEl -> {
                BeanDefinitionBuilder process = newBean(CoordinationProcessConfig.class, processEl);

                setProperty(process, processEl, "name", "name");

                setBeanOrRef(process, processEl, "handler", "handler", ctx);
                setBeanOrRef(process, processEl, "messageCodec", "message-codec", ctx);

                processes.add(registerInnerBean(process, ctx));
            });

            if (!processes.isEmpty()) {
                coordination.addPropertyValue("processes", processes);
            }

            String id = coordinationEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(CoordinationServiceBean.class, coordinationEl), id);
            }

            return Optional.of(registerInnerBean(coordination, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseElectionService(Element rootEl, ParserContext ctx) {
        Element leaderEl = getChildElementByTagName(rootEl, "election");

        if (leaderEl != null) {
            BeanDefinitionBuilder leader = newBean(ElectionServiceFactory.class, leaderEl);

            ManagedList<RuntimeBeanReference> candidates = new ManagedList<>();

            getChildElementsByTagName(leaderEl, "candidate").forEach(candidateEl -> {
                BeanDefinitionBuilder candidate = newBean(CandidateConfig.class, candidateEl);

                setProperty(candidate, candidateEl, "group", "group");

                parseRefOrBean(candidateEl, ctx).ifPresent(bean ->
                    candidate.addPropertyValue("candidate", bean)
                );

                candidates.add(registerInnerBean(candidate, ctx));
            });

            if (!candidates.isEmpty()) {
                leader.addPropertyValue("candidates", candidates);
            }

            String id = leaderEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(ElectionServiceBean.class, leaderEl), id);
            }

            return Optional.of(registerInnerBean(leader, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseLocalMetricsService(Element rootEl, ParserContext ctx) {
        Element metricsEl = getChildElementByTagName(rootEl, "local-metrics");

        if (metricsEl != null) {
            BeanDefinitionBuilder metrics = newBean(LocalMetricsServiceFactory.class, metricsEl);

            ManagedList<RuntimeBeanReference> allMetrics = new ManagedList<>();
            ManagedList<RuntimeBeanReference> listeners = new ManagedList<>();

            subElements(metricsEl, "counters", "counter").forEach(counterEl -> {
                BeanDefinitionBuilder counter = newBean(CounterConfig.class, counterEl);

                setProperty(counter, counterEl, "name", "name");
                setProperty(counter, counterEl, "totalName", "total-name");
                setProperty(counter, counterEl, "autoReset", "auto-reset");

                allMetrics.add(registerInnerBean(counter, ctx));

                String name = counterEl.getAttribute("name");

                if (!name.isEmpty()) {
                    BeanDefinitionBuilder counterBean = newBean(CounterMetricBean.class, metricsEl);

                    setProperty(counterBean, counterEl, "name", "name");

                    deferredBaseBeans.put(counterBean, name);
                }
            });

            subElements(metricsEl, "probes", "probe").forEach(probeEl -> {
                BeanDefinitionBuilder probe = newBean(ProbeConfig.class, probeEl);

                setProperty(probe, probeEl, "name", "name");
                setProperty(probe, probeEl, "initValue", "init-value");

                parseRefOrBean(probeEl, ctx).ifPresent(bean ->
                    probe.addPropertyValue("probe", bean)
                );

                allMetrics.add(registerInnerBean(probe, ctx));

                String name = probeEl.getAttribute("name");

                if (!name.isEmpty()) {
                    BeanDefinitionBuilder probeBean = newBean(MetricBean.class, metricsEl);

                    setProperty(probeBean, probeEl, "name", "name");

                    deferredBaseBeans.put(probeBean, name);
                }
            });

            subElements(metricsEl, "listeners", "listener").forEach(listenerEl ->
                parseRefOrBean(listenerEl, ctx).ifPresent(listeners::add)
            );

            if (!allMetrics.isEmpty()) {
                metrics.addPropertyValue("metrics", allMetrics);
            }

            if (!listeners.isEmpty()) {
                metrics.addPropertyValue("listeners", listeners);
            }

            String id = metricsEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(LocalMetricsServiceBean.class, metricsEl), id);
            }

            return Optional.of(registerInnerBean(metrics, ctx));
        } else {
            return Optional.empty();
        }
    }

    private Optional<RuntimeBeanReference> parseClusterMetricsService(Element rootEl, ParserContext ctx) {
        Element metricsEl = getChildElementByTagName(rootEl, "cluster-metrics");

        if (metricsEl != null) {
            BeanDefinitionBuilder metrics = newBean(ClusterMetricsServiceFactory.class, metricsEl);

            setProperty(metrics, metricsEl, "replicationInterval", "replication-interval-ms");

            setBeanOrRef(metrics, metricsEl, "replicationFilter", "filter", ctx);

            String id = metricsEl.getAttribute("id");

            if (!id.isEmpty()) {
                deferredBaseBeans.put(newBean(ClusterMetricsServiceBean.class, metricsEl), id);
            }

            return Optional.of(registerInnerBean(metrics, ctx));
        } else {
            return Optional.empty();
        }
    }

    private RuntimeBeanReference registerInnerBean(BeanDefinitionBuilder def, ParserContext ctx) {
        String name = ctx.getReaderContext().generateBeanName(def.getRawBeanDefinition());

        ctx.registerBeanComponent(new BeanComponentDefinition(def.getBeanDefinition(), name));

        return new RuntimeBeanReference(name);
    }

    private void setProperty(BeanDefinitionBuilder def, Element el, String beanProperty, String xmlAttribute) {
        String val = el.getAttribute(xmlAttribute).trim();

        if (!val.isEmpty()) {
            def.addPropertyValue(beanProperty, val);
        }
    }

    private List<Element> subElements(Element root, String name, String subName) {
        Element elem = getChildElementByTagName(root, name);

        if (elem != null) {
            return getChildElementsByTagName(elem, subName);
        }

        return Collections.emptyList();
    }

    private Optional<Element> setBeanOrRef(BeanDefinitionBuilder def, Element parent, String propName, String elemName, ParserContext ctx) {
        Element elem = getChildElementByTagName(parent, elemName);

        parseRefOrBean(elem, ctx).ifPresent(ref ->
            def.addPropertyValue(propName, ref)
        );

        return Optional.ofNullable(elem);
    }

    private Optional<Map<String, String>> parseProperties(Element el) {
        List<Element> propEls = subElements(el, "properties", "prop");

        if (!propEls.isEmpty()) {
            Map<String, String> props = new ManagedMap<>();

            for (Element propEl : propEls) {
                String name = propEl.getAttribute("name").trim();
                String value = getTextValue(propEl).trim();

                props.put(name, value);
            }

            return Optional.of(props);
        }

        return Optional.empty();
    }

    private Optional<RuntimeBeanReference> parseRefOrBean(Element elem, ParserContext ctx) {
        if (elem != null) {
            String ref = elem.getAttribute("ref").trim();

            Element beanEl = getChildElementByTagName(elem, "bean");

            if (!ref.isEmpty() && beanEl != null) {
                String name = elem.getLocalName();

                ctx.getReaderContext().error('<' + name + ">'s 'ref' attribute can't be mixed with nested <bean> element.", elem);
            } else if (!ref.isEmpty()) {
                return Optional.of(new RuntimeBeanReference(ref));
            } else if (beanEl != null) {
                BeanDefinitionHolder holder = ctx.getDelegate().parseBeanDefinitionElement(beanEl);

                ctx.registerBeanComponent(new BeanComponentDefinition(holder.getBeanDefinition(), holder.getBeanName()));

                return Optional.of(new RuntimeBeanReference(holder.getBeanName()));
            }
        }

        return Optional.empty();
    }

    private BeanDefinitionBuilder newBean(Class<?> type, Element element) {
        BeanDefinitionBuilder def = BeanDefinitionBuilder.genericBeanDefinition(type);

        def.getRawBeanDefinition().setSource(element);

        return def;
    }
}
