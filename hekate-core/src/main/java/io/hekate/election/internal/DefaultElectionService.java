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

package io.hekate.election.internal;

import io.hekate.cluster.ClusterNode;
import io.hekate.core.HekateException;
import io.hekate.core.internal.util.ArgAssert;
import io.hekate.core.internal.util.ConfigCheck;
import io.hekate.core.internal.util.HekateThreadFactory;
import io.hekate.core.internal.util.Utils;
import io.hekate.core.internal.util.Waiting;
import io.hekate.core.service.ConfigurableService;
import io.hekate.core.service.ConfigurationContext;
import io.hekate.core.service.DependencyContext;
import io.hekate.core.service.DependentService;
import io.hekate.core.service.InitializationContext;
import io.hekate.core.service.InitializingService;
import io.hekate.core.service.TerminatingService;
import io.hekate.election.Candidate;
import io.hekate.election.CandidateConfig;
import io.hekate.election.CandidateConfigProvider;
import io.hekate.election.ElectionService;
import io.hekate.election.ElectionServiceFactory;
import io.hekate.election.LeaderFuture;
import io.hekate.lock.DistributedLock;
import io.hekate.lock.LockConfigProvider;
import io.hekate.lock.LockRegionConfig;
import io.hekate.lock.LockService;
import io.hekate.util.StateGuard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toList;

public class DefaultElectionService implements ElectionService, DependentService, ConfigurableService, InitializingService,
    TerminatingService, LockConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(DefaultElectionService.class);

    private static final boolean DEBUG = log.isDebugEnabled();

    private static final String GROUPS_PROPERTY = "groups";

    private static final String ELECTION_THREAD_PREFIX = "ElectionWorker";

    private static final String ELECTION_LOCK_REGION = "election.service";

    private final StateGuard guard = new StateGuard(ElectionService.class);

    private final List<CandidateConfig> candidatesConfig = new ArrayList<>();

    private final Map<String, CandidateHandler> handlers = new HashMap<>();

    private LockService locks;

    private ClusterNode localNode;

    public DefaultElectionService(ElectionServiceFactory factory) {
        ArgAssert.check(factory != null, "Factory is null.");

        Utils.nullSafe(factory.getCandidates()).forEach(candidatesConfig::add);

        Utils.nullSafe(factory.getConfigProviders()).forEach(provider ->
            Utils.nullSafe(provider.getElectionConfig()).forEach(candidatesConfig::add)
        );
    }

    @Override
    public void resolve(DependencyContext ctx) {
        locks = ctx.require(LockService.class);
    }

    @Override
    public void configure(ConfigurationContext ctx) {
        // Collect configurations from providers.
        Collection<CandidateConfigProvider> providers = ctx.findComponents(CandidateConfigProvider.class);

        Utils.nullSafe(providers).forEach(provider ->
            Utils.nullSafe(provider.getElectionConfig()).forEach(candidatesConfig::add)
        );

        // Validate configs.
        ConfigCheck check = ConfigCheck.get(CandidateConfig.class);

        Set<String> uniqueGroups = new HashSet<>();

        candidatesConfig.forEach(cfg -> {
            check.that(cfg.getGroup() != null, "group must be not null.");
            check.that(cfg.getCandidate() != null, "candidate must be not null.");

            String group = cfg.getGroup().trim();

            check.that(!group.isEmpty(), "group must be a non empty string.");
            check.that(!uniqueGroups.contains(group), "duplicated group name [group=" + group + ']');

            uniqueGroups.add(group);
        });

        // Register group names as service property.
        candidatesConfig.forEach(cfg ->
            ctx.addServiceProperty(GROUPS_PROPERTY, cfg.getGroup().trim())
        );
    }

    @Override
    public Collection<LockRegionConfig> getLockingConfig() {
        return Collections.singletonList(new LockRegionConfig().withName(ELECTION_LOCK_REGION));
    }

    @Override
    public void initialize(InitializationContext ctx) throws HekateException {
        if (DEBUG) {
            log.debug("Initializing...");
        }

        guard.lockWrite();

        try {
            guard.becomeInitialized();

            localNode = ctx.getNode();

            candidatesConfig.forEach(this::doRegister);
        } finally {
            guard.unlockWrite();
        }

        if (DEBUG) {
            log.debug("Initialized.");
        }
    }

    @Override
    public void preTerminate() throws HekateException {
        Waiting waiting = null;

        guard.lockWrite();

        try {
            if (guard.becomeTerminating()) {
                waiting = Waiting.awaitAll(handlers.values().stream().map(CandidateHandler::terminate).collect(toList()));
            }
        } finally {
            guard.unlockWrite();
        }

        if (waiting != null) {
            waiting.awaitUninterruptedly();
        }
    }

    @Override
    public void terminate() throws HekateException {
        // Actual termination of handlers happens in posTerminate() in order to o make sure that worker threads are terminated after
        // the lock service termination. Otherwise it can lead to RejectedExecutionException if lock service tries to process async lock
        // events while election service is already terminated.
    }

    @Override
    public void postTerminate() throws HekateException {
        Waiting waiting = null;

        guard.lockWrite();

        try {
            if (guard.becomeTerminated()) {
                if (DEBUG) {
                    log.debug("Terminating...");
                }

                waiting = Waiting.awaitAll(handlers.values().stream().map(CandidateHandler::shutdown).collect(toList()));

                handlers.clear();

                localNode = null;
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
    public LeaderFuture getLeader(String group) {
        return getRequiredHandler(group).getLeaderFuture();
    }

    private CandidateHandler getRequiredHandler(String group) {
        ArgAssert.check(group != null, "Group name must be not null.");

        CandidateHandler handler;

        guard.lockReadWithStateCheck();

        try {
            handler = handlers.get(group);
        } finally {
            guard.unlockRead();
        }

        ArgAssert.check(handler != null, "Unknown group [name=" + group + ']');

        return handler;
    }

    private void doRegister(CandidateConfig cfg) {
        assert guard.isWriteLocked() : "Thread must hold a write lock.";
        assert guard.isInitialized() : "Service must be initialized.";
        assert cfg != null : "Configuration is null.";

        if (DEBUG) {
            log.debug("Registering new configuration [config={}]", cfg);
        }

        String group = cfg.getGroup().trim();
        Candidate candidate = cfg.getCandidate();

        DistributedLock lock = locks.get(ELECTION_LOCK_REGION).getLock(group);

        ExecutorService worker = Executors.newSingleThreadExecutor(new HekateThreadFactory(ELECTION_THREAD_PREFIX + '-' + group));

        CandidateHandler handler = new CandidateHandler(group, candidate, worker, lock, localNode);

        handlers.put(group, handler);

        handler.initialize();
    }

    @Override
    public String toString() {
        return ElectionService.class.getSimpleName() + "[candidates=" + Utils.toString(candidatesConfig, CandidateConfig::getGroup) + ']';
    }
}