/*
 * Copyright 2018 The Hekate Project
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

package io.hekate.metrics.local.internal;

import io.hekate.core.HekateException;
import io.hekate.core.internal.util.ArgAssert;
import io.hekate.core.internal.util.ConfigCheck;
import io.hekate.core.internal.util.HekateThreadFactory;
import io.hekate.core.internal.util.StreamUtils;
import io.hekate.core.internal.util.Utils;
import io.hekate.core.jmx.JmxService;
import io.hekate.core.jmx.JmxServiceException;
import io.hekate.core.service.ConfigurableService;
import io.hekate.core.service.ConfigurationContext;
import io.hekate.core.service.DependencyContext;
import io.hekate.core.service.DependentService;
import io.hekate.core.service.InitializationContext;
import io.hekate.core.service.InitializingService;
import io.hekate.core.service.TerminatingService;
import io.hekate.metrics.Metric;
import io.hekate.metrics.MetricValue;
import io.hekate.metrics.local.CounterConfig;
import io.hekate.metrics.local.CounterMetric;
import io.hekate.metrics.local.LocalMetricsService;
import io.hekate.metrics.local.LocalMetricsServiceFactory;
import io.hekate.metrics.local.MetricConfigBase;
import io.hekate.metrics.local.MetricsConfigProvider;
import io.hekate.metrics.local.MetricsListener;
import io.hekate.metrics.local.MetricsSnapshot;
import io.hekate.metrics.local.ProbeConfig;
import io.hekate.util.StateGuard;
import io.hekate.util.async.AsyncUtils;
import io.hekate.util.async.Waiting;
import io.hekate.util.format.ToString;
import io.hekate.util.format.ToStringIgnore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultLocalMetricsService implements LocalMetricsService, DependentService, InitializingService, ConfigurableService,
    TerminatingService {
    private static final Logger log = LoggerFactory.getLogger(DefaultLocalMetricsService.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    private static final ConfigCheck COUNTER_CHECK = ConfigCheck.get(CounterConfig.class);

    private static final ConfigCheck PROBE_CHECK = ConfigCheck.get(ProbeConfig.class);

    private final long refreshInterval;

    @ToStringIgnore
    private final List<MetricsListener> initListeners = new ArrayList<>();

    @ToStringIgnore
    private final List<MetricConfigBase<?>> metricsConfig = new ArrayList<>();

    @ToStringIgnore
    private final StateGuard guard = new StateGuard(LocalMetricsService.class);

    @ToStringIgnore
    private final Map<String, Metric> allMetrics = new HashMap<>();

    @ToStringIgnore
    private final Map<String, DefaultCounterMetric> counters = new HashMap<>();

    @ToStringIgnore
    private final Map<String, DefaultProbeMetric> probes = new HashMap<>();

    @ToStringIgnore
    private final List<MetricsListener> listeners = new CopyOnWriteArrayList<>();

    @ToStringIgnore
    private JmxService jmx;

    @ToStringIgnore
    private ScheduledExecutorService worker;

    private volatile MetricsSnapshot snapshot = emptySnapshot();

    public DefaultLocalMetricsService(LocalMetricsServiceFactory factory) {
        assert factory != null : "Factory is null.";

        ConfigCheck.get(LocalMetricsServiceFactory.class).positive(factory.getRefreshInterval(), "refresh interval");

        refreshInterval = factory.getRefreshInterval();

        // Register JVM metrics.
        StreamUtils.nullSafe(new JvmMetricsProvider().configureMetrics()).forEach(metricsConfig::add);

        // Register pre-configured metrics.
        StreamUtils.nullSafe(factory.getMetrics()).forEach(metricsConfig::add);

        // Register metrics from pre-configured providers.
        StreamUtils.nullSafe(factory.getConfigProviders()).forEach(provider ->
            StreamUtils.nullSafe(provider.configureMetrics()).forEach(metricsConfig::add)
        );

        // Register pre-configured listeners.
        StreamUtils.nullSafe(factory.getListeners()).forEach(initListeners::add);
    }

    @Override
    public void resolve(DependencyContext ctx) {
        jmx = ctx.optional(JmxService.class);
    }

    @Override
    public void configure(ConfigurationContext ctx) {
        Collection<MetricsConfigProvider> providers = ctx.findComponents(MetricsConfigProvider.class);

        // Collect configurations from providers.
        StreamUtils.nullSafe(providers).forEach(provider ->
            StreamUtils.nullSafe(provider.configureMetrics()).forEach(metricsConfig::add)
        );
    }

    @Override
    public void initialize(InitializationContext ctx) throws HekateException {
        guard.lockWrite();

        try {
            guard.becomeInitialized();

            if (DEBUG) {
                log.debug("Initializing...");
            }

            // Initialize pre-configured metrics.
            initializeMetrics();

            // Register pre-configured listeners.
            listeners.addAll(initListeners);

            // Register JMX bean (optional).
            if (jmx != null) {
                jmx.register(new DefaultLocalMetricsServiceJmx(this));
            }

            // Start metrics updates.
            worker = Executors.newSingleThreadScheduledExecutor(new HekateThreadFactory("LocalMetrics"));

            worker.scheduleAtFixedRate(() -> {
                try {
                    updateMetrics();
                } catch (RuntimeException | Error e) {
                    log.error("Got an unexpected runtime error while updating and publishing metrics.", e);
                }
            }, refreshInterval, refreshInterval, TimeUnit.MILLISECONDS);

            if (DEBUG) {
                log.debug("Initialized.");
            }
        } finally {
            guard.unlockWrite();
        }
    }

    @Override
    public void terminate() throws HekateException {
        Waiting waiting = null;

        guard.lockWrite();

        try {
            if (guard.becomeTerminated()) {
                if (DEBUG) {
                    log.debug("Terminating...");
                }

                if (worker != null) {
                    waiting = AsyncUtils.shutdown(worker);

                    worker = null;
                }

                allMetrics.clear();
                counters.clear();
                probes.clear();
                listeners.clear();

                snapshot = emptySnapshot();
            }
        } finally {
            guard.unlockWrite();
        }

        if (waiting != null) {
            waiting.awaitUninterruptedly();

            if (DEBUG) {
                log.debug("Terminated.");
            }
        }
    }

    @Override
    public MetricsSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public CounterMetric register(CounterConfig cfg) {
        String name = checkCounterConfig(cfg);

        // Check for an existing counter.
        guard.lockReadWithStateCheck();

        try {
            CounterMetric existing = counters.get(name);

            if (existing != null) {
                return existing;
            }
        } finally {
            guard.unlockRead();
        }

        // Register if counter doesn't exist.
        return doRegisterCounter(name, cfg);
    }

    @Override
    public Metric register(ProbeConfig cfg) {
        String name = checkProbeConfig(cfg);

        guard.lockWriteWithStateCheck();

        try {
            return doRegisterProbe(name, cfg);
        } finally {
            guard.unlockWrite();
        }
    }

    @Override
    public void addListener(MetricsListener listener) {
        ArgAssert.notNull(listener, "Listener");

        guard.lockReadWithStateCheck();

        try {
            listeners.add(listener);
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public void removeListener(MetricsListener listener) {
        ArgAssert.notNull(listener, "Listener");

        listeners.remove(listener);
    }

    @Override
    public CounterMetric counter(String name) {
        String safeName = ArgAssert.notEmpty(name, "counter name");

        guard.lockReadWithStateCheck();

        try {
            CounterMetric counter = counters.get(safeName);

            if (counter != null) {
                return counter;
            }
        } finally {
            guard.unlockRead();
        }

        return register(new CounterConfig(safeName));
    }

    @Override
    public Map<String, Metric> allMetrics() {
        guard.lockReadWithStateCheck();

        try {
            return new HashMap<>(allMetrics);
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public Metric metric(String name) {
        guard.lockReadWithStateCheck();

        try {
            return allMetrics.get(name);
        } finally {
            guard.unlockRead();
        }
    }

    @Override
    public long refreshInterval() {
        return refreshInterval;
    }

    /**
     * Returns all registered {@link #addListener(MetricsListener) listeners}.
     *
     * @return Listeners.
     */
    public List<MetricsListener> listeners() {
        return new ArrayList<>(listeners);
    }

    private void initializeMetrics() {
        Map<String, CounterConfig> countersCfg = new HashMap<>();
        Map<String, ProbeConfig> probesCfg = new HashMap<>();

        metricsConfig.forEach(cfg -> {
            if (cfg instanceof CounterConfig) {
                CounterConfig newCfg = (CounterConfig)cfg;

                String name = checkCounterConfig(newCfg);

                CounterConfig oldCfg = countersCfg.get(name);

                if (oldCfg == null) {
                    countersCfg.put(name, newCfg);
                } else {
                    oldCfg.setAutoReset(oldCfg.isAutoReset() | newCfg.isAutoReset());

                    String oldTotal = Utils.nullOrTrim(oldCfg.getTotalName());
                    String newTotal = Utils.nullOrTrim(newCfg.getTotalName());

                    if (newTotal != null) {
                        if (oldTotal == null) {
                            oldCfg.setTotalName(newTotal);
                        } else {
                            COUNTER_CHECK.isTrue(Objects.equals(oldTotal, newTotal),
                                "can't merge configurations of a counter metric with different 'total' names "
                                    + "[counter=" + name
                                    + ", total-name-1=" + oldTotal
                                    + ", total-name-2=" + newTotal
                                    + ']');
                        }
                    }
                }
            } else if (cfg instanceof ProbeConfig) {
                ProbeConfig newCfg = (ProbeConfig)cfg;

                String name = checkProbeConfig(newCfg);

                ProbeConfig oldCfg = probesCfg.get(name);

                if (oldCfg == null) {
                    probesCfg.put(name, newCfg);
                } else {
                    oldCfg.setInitValue(Math.max(oldCfg.getInitValue(), newCfg.getInitValue()));
                }
            } else {
                throw new IllegalArgumentException("Unsupported metric type: " + cfg);
            }
        });

        countersCfg.forEach(this::doRegisterCounter);
        probesCfg.forEach(this::doRegisterProbe);
    }

    private Metric doRegisterProbe(String name, ProbeConfig cfg) {
        assert cfg != null : "Probe configuration is null.";

        guard.lockWriteWithStateCheck();

        try {
            if (DEBUG) {
                log.debug("Registering probe [config={}]", cfg);
            }

            PROBE_CHECK.unique(name, allMetrics.keySet(), "name");

            DefaultProbeMetric metricProbe = new DefaultProbeMetric(name, cfg.getProbe(), cfg.getInitValue());

            probes.put(name, metricProbe);

            allMetrics.put(name, metricProbe);

            if (jmx != null) {
                try {
                    jmx.register(new DefaultMetricJmx(name, this), name);
                } catch (JmxServiceException e) {
                    throw PROBE_CHECK.fail(e);
                }
            }

            return metricProbe;
        } finally {
            guard.unlockWrite();
        }
    }

    private CounterMetric doRegisterCounter(String name, CounterConfig cfg) {
        assert cfg != null : "Counter configuration is null.";

        guard.lockWriteWithStateCheck();

        try {
            // Double check that counter wasn't registered while we were waiting for the write lock.
            CounterMetric existing = counters.get(name);

            if (existing == null) {
                if (DEBUG) {
                    log.debug("Registering counter [config={}]", cfg);
                }

                COUNTER_CHECK.unique(name, allMetrics.keySet(), "metric name");

                // Try register 'total' metric for this counter (if required).
                CounterMetric total = null;

                String totalName = totalName(cfg);

                if (totalName != null) {
                    COUNTER_CHECK.unique(totalName, allMetrics.keySet(), "metric name");

                    total = new DefaultCounterMetric(totalName, false);

                    allMetrics.put(totalName, total);

                    // Register JMX bean for the total metric (optional).
                    if (jmx != null) {
                        try {
                            jmx.register(new DefaultMetricJmx(totalName, this), totalName);
                        } catch (JmxServiceException e) {
                            throw COUNTER_CHECK.fail(e);
                        }
                    }
                }

                // Register counter.
                DefaultCounterMetric counter = new DefaultCounterMetric(name, cfg.isAutoReset(), total);

                counters.put(name, counter);

                allMetrics.put(name, counter);

                // Register JMX bean for this counter (optional).
                if (jmx != null) {
                    try {
                        jmx.register(new DefaultMetricJmx(name, this), name);
                    } catch (JmxServiceException e) {
                        throw COUNTER_CHECK.fail(e);
                    }
                }

                return counter;
            } else {
                return existing;
            }
        } finally {
            guard.unlockWrite();
        }
    }

    // Package level for testing purposes.
    void updateMetrics() {
        DefaultMetricsUpdateEvent event;

        guard.lockWrite();

        try {
            if (guard.isInitialized()) {
                Map<String, Metric> metrics = new HashMap<>(allMetrics.size(), 1.0f);

                // Update probes.
                probes.forEach((name, probe) -> {
                    if (!probe.isFailed()) {
                        try {
                            long newValue = probe.update();

                            metrics.put(name, new MetricValue(name, newValue));
                        } catch (RuntimeException | Error err) {
                            log.error("Unexpected error while getting the probe value. "
                                + "Probe will not be tried any more [name={}]", name, err);

                            probe.setFailed(true);
                        }
                    }
                });

                // Update counters.
                counters.values().forEach(cnt -> {
                    long val;

                    if (cnt.isAutoReset()) {
                        val = cnt.reset();
                    } else {
                        val = cnt.value();
                    }

                    metrics.put(cnt.name(), new MetricValue(cnt.name(), val));
                });

                // Collect other (artificial) metrics.
                allMetrics.forEach((name, metric) -> {
                    if (!metrics.containsKey(name)) {
                        metrics.put(name, new MetricValue(metric.name(), metric.value()));
                    }
                });

                // Prepare event.
                event = new DefaultMetricsUpdateEvent(snapshot.tick() + 1, Collections.unmodifiableMap(metrics));

                // Update the latest snapshot.
                this.snapshot = event;
            } else {
                event = null;
            }
        } finally {
            guard.unlockWrite();
        }

        // Notify listeners.
        if (event != null) {
            listeners.forEach(listener -> {
                try {
                    listener.onUpdate(event);
                } catch (RuntimeException | Error e) {
                    log.error("Failed to notify metrics listener [listener={}]", listener, e);
                }
            });
        }
    }

    private String checkCounterConfig(CounterConfig cfg) {
        COUNTER_CHECK.notNull(cfg, "configuration");
        COUNTER_CHECK.notEmpty(cfg.getName(), "name");
        COUNTER_CHECK.validSysName(cfg.getName(), "name");
        COUNTER_CHECK.validSysName(cfg.getTotalName(), "total name");

        return cfg.getName().trim();
    }

    private String checkProbeConfig(ProbeConfig cfg) {
        PROBE_CHECK.notNull(cfg, "configuration");
        PROBE_CHECK.notEmpty(cfg.getName(), "name");
        PROBE_CHECK.validSysName(cfg.getName(), "name");
        PROBE_CHECK.notNull(cfg.getProbe(), "probe");

        return cfg.getName().trim();
    }

    private String totalName(CounterConfig cfg) {
        return cfg.getTotalName() != null ? cfg.getTotalName().trim() : null;
    }

    private DefaultMetricsUpdateEvent emptySnapshot() {
        return new DefaultMetricsUpdateEvent(0, Collections.emptyMap());
    }

    @Override
    public String toString() {
        return ToString.format(LocalMetricsService.class, this);
    }
}
