/*
 * Copyright (C) 2014 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package com.indeed.imhotep.client;

import com.google.common.base.Predicates;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.primitives.Longs;
import com.indeed.util.core.Pair;
import com.indeed.imhotep.DatasetInfo;
import com.indeed.imhotep.RemoteImhotepMultiSession;
import com.indeed.imhotep.ImhotepRemoteSession;
import com.indeed.imhotep.ImhotepStatusDump;
import com.indeed.imhotep.ShardInfo;
import com.indeed.imhotep.api.ImhotepSession;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @author jsgroth
 */
public class ImhotepClient implements Closeable {
    private static final Logger log = Logger.getLogger(ImhotepClient.class);

    private final HostsReloader hostsSource;
    private final ExecutorService rpcExecutor;
    private final ScheduledExecutorService reloader;
    private final ImhotepClientShardListReloader shardListReloader;

    /**
     * create an imhotep client that will periodically reload its list of hosts from a text file
     * @param hostsFile hosts file
     */
    public ImhotepClient(String hostsFile) {
        this(new FileHostsReloader(hostsFile));
    }

    /**
     * create an imhotep client with a static list of hosts
     * @param hosts list of hosts
     */
    public ImhotepClient(List<Host> hosts) {
         this(new DummyHostsReloader(hosts));
    }

    public ImhotepClient(String zkNodes, boolean readHostsBeforeReturning) {
        this(new ZkHostsReloader(zkNodes, readHostsBeforeReturning));
    }

    public ImhotepClient(String zkNodes, String zkPath, boolean readHostsBeforeReturning) {
        this(new ZkHostsReloader(zkNodes, zkPath, readHostsBeforeReturning));
    }

    public ImhotepClient(HostsReloader hostsSource) {
        this.hostsSource = hostsSource;

        rpcExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "ImhotepClient.RPCThread");
                t.setDaemon(true);
                return t;
            }
        });

        reloader = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "ImhotepClient.Reloader");
                t.setDaemon(true);
                return t;
            }
        });
        reloader.scheduleAtFixedRate(hostsSource, 60L, 60L, TimeUnit.SECONDS);
        shardListReloader = new ImhotepClientShardListReloader(hostsSource, rpcExecutor);
        shardListReloader.run();
        reloader.scheduleAtFixedRate(shardListReloader, 60L, 60L, TimeUnit.SECONDS);
    }

    public Map<Host, List<DatasetInfo>> getShardList() {
        return shardListReloader.getShardList();
    }

    // convenience methods
    public Map<String, DatasetInfo> getDatasetToShardList() {
        final Map<Host, List<DatasetInfo>> shardListMap = getShardList();
        final Map<String, DatasetInfo> ret = Maps.newHashMap();
        for (final List<DatasetInfo> datasetList : shardListMap.values()) {
            for (final DatasetInfo dataset : datasetList) {
                DatasetInfo current = ret.get(dataset.getDataset());
                if (current == null) {
                    ret.put(dataset.getDataset(), current = new DatasetInfo(dataset.getDataset(), new HashSet<ShardInfo>(), new HashSet<String>(), new HashSet<String>(), new HashSet<String>()));
                }
                current.getShardList().addAll(dataset.getShardList());
                current.getIntFields().addAll(dataset.getIntFields());
                current.getStringFields().addAll(dataset.getStringFields());
                current.getMetrics().addAll(dataset.getMetrics());
            }
        }
        return ret;
    }

    public List<String> getShardList(final String dataset) {
        return getShardList(dataset, new AcceptAllShardFilter());
    }

    public List<String> getShardList(final String dataset, final ShardFilter filterFunc) {
        final Map<Host, List<DatasetInfo>> shardListMap = getShardList();
        final SortedSet<String> set = new TreeSet<String>();
        for (final List<DatasetInfo> datasetList : shardListMap.values()) {
            for (final DatasetInfo datasetInfo : datasetList) {
                for (final ShardInfo shard : datasetInfo.getShardList()) {
                    if (dataset.equals(shard.dataset) && filterFunc.accept(shard)) {
                        set.add(shard.shardId);
                    }
                }
            }
        }
        return new ArrayList<String>(set);
    }

    public List<ShardIdWithVersion> getShardListWithVersion(final String dataset, final ShardFilter filterFunc) {
        final Map<Host, List<DatasetInfo>> shardListMap = getShardList();

        final Map<String,Long> latestVersionMap = new HashMap<String, Long>();
        for (final List<DatasetInfo> datasetList : shardListMap.values()) {
            for (final DatasetInfo datasetInfo : datasetList) {
                for (final ShardInfo shard : datasetInfo.getShardList()) {
                    if (dataset.equals(shard.dataset) && filterFunc.accept(shard)) {
                        //is in time range, check version
                        if(!latestVersionMap.containsKey(shard.shardId) || latestVersionMap.get(shard.shardId) < shard.version) {
                            latestVersionMap.put(shard.shardId, shard.version);
                        }
                    }
                }
            }
        }

        final List<ShardIdWithVersion> ret = Lists.newArrayListWithCapacity(latestVersionMap.size());
        for (final Map.Entry<String, Long> e : latestVersionMap.entrySet()) {
            ret.add(new ShardIdWithVersion(e.getKey(), e.getValue()));
        }
        Collections.sort(ret);
        return ret;
    }

    /**
     * Returns a list of non-overlapping Imhotep shards for the specified dataset and time range.
     * Shards in the list are sorted chronologically.
     */
    public List<ShardIdWithVersion> findShardsForTimeRange(String dataset, final DateTime start, final DateTime end) {
        // get shards intersecting with (start,end) time range
        final List<ShardIdWithVersion> shardsForTime = getShardListWithVersion(dataset, new DateRangeShardFilter(start, end));
        return removeIntersectingShards(shardsForTime, dataset, start);
    }

    // we are truncating the shard start point as part of removeIntersectingShards so we make a wrapper for the ShardIdWithVersion
    private static class ShardTruncatedStart {
        private final ShardIdWithVersion shard;
        private final DateTime start;
        private final DateTime end;
        private final long version;

        private ShardTruncatedStart(ShardIdWithVersion shard, DateTime start) {
            this.shard = shard;
            this.start = start;
            this.end = shard.getEnd();
            this.version = shard.getVersion();
        }
    }

    /**
     * Returns a non-intersecting list of shard ids and versions chosen from the shardsForTime list.
     * Shards in the list are sorted chronologically.
     */
    static List<ShardIdWithVersion> removeIntersectingShards(List<ShardIdWithVersion> shardsForTime, String dataset, final DateTime start) {
        // we have to limit shard start times to the requested start time to avoid
        // longer shards with the earlier start time taking precedence over newer smaller shards
        final List<ShardTruncatedStart> shardsForTimeTruncated = new ArrayList<ShardTruncatedStart>(shardsForTime.size());
        for(ShardIdWithVersion shard : shardsForTime) {
            ShardInfo.DateTimeRange range = shard.getRange();
            if(range == null) {
                log.warn("Unparseable shard id encountered in dataset '" + dataset + "': " + shard.getShardId());
                continue;
            }
            DateTime shardStart = range.start;
            if(start.isAfter(range.start)) {
                shardStart = start;
            }
            shardsForTimeTruncated.add(new ShardTruncatedStart(shard, shardStart));
        }

        // now we need to resolve potential time overlaps in shards
        // sort by: start date asc, version desc
        Collections.sort(shardsForTimeTruncated, new Comparator<ShardTruncatedStart>() {
            @Override
            public int compare(ShardTruncatedStart o1, ShardTruncatedStart o2) {
                final int c = o1.start.compareTo(o2.start);
                if(c != 0) return c;
                return -Longs.compare(o1.version, o2.version);
            }
        });

        final List<ShardIdWithVersion> chosenShards = Lists.newArrayList();
        DateTime processedUpTo = new DateTime(-2000000,1,1,0,0);  // 2M BC

        for(ShardTruncatedStart shard : shardsForTimeTruncated) {
            if(!shard.start.isBefore(processedUpTo)) {
                chosenShards.add(shard.shard);
                processedUpTo = shard.end;
            }
        }
        return chosenShards;
    }

    /**
     * Returns a builder that can be used to initialize an {@link ImhotepSession} instance.
     * @param dataset dataset/index name for the session
     */
    public SessionBuilder sessionBuilder(final String dataset, final DateTime start, final DateTime end) {
        return new SessionBuilder(dataset, start, end);
    }

    /**
     * Constructs {@link ImhotepSession} instances.
     * Set optional parameters and call {@link #build}() to get an instance.
     */
    public class SessionBuilder {
        private final String dataset;
        private final DateTime start;
        private final DateTime end;

        private Collection<String> requestedMetrics = Collections.emptyList();
        private int mergeThreadLimit = ImhotepRemoteSession.DEFAULT_MERGE_THREAD_LIMIT;
        private String username;
        private boolean optimizeGroupZeroLookups = false;
        private int socketTimeout = -1;

        private List<ShardIdWithVersion> chosenShards = null;
        private List<String> shardsOverride = null;

        public SessionBuilder(final String dataset, final DateTime start, final DateTime end) {
            this.dataset = dataset;
            this.start = start;
            this.end = end;
        }

        public SessionBuilder requestedMetrics(Collection<String> requestedMetrics) {
            this.requestedMetrics = Lists.newArrayList(requestedMetrics);
            return this;
        }

        public SessionBuilder mergeThreadLimit(int mergeThreadLimit) {
            this.mergeThreadLimit = mergeThreadLimit;
            return this;
        }
        @Deprecated
        public SessionBuilder priority(int priority) {
            return this;
        }
        public SessionBuilder socketTimeout(int socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }
        public SessionBuilder username(String username) {
            this.username = username;
            return this;
        }
        public SessionBuilder optimizeGroupZeroLookups(boolean optimizeGroupZeroLookups) {
            this.optimizeGroupZeroLookups = optimizeGroupZeroLookups;
            return this;
        }

        public SessionBuilder shardsOverride(List<String> requiredShards) {
            this.shardsOverride = Lists.newArrayList(requiredShards);
            return this;
        }

        /**
         * Returns shards that were selected for the time range requested in the constructor.
         * Shards in the list are sorted chronologically.
         */
        public List<ShardIdWithVersion> getChosenShards() {
            if(chosenShards == null) {
                if(start == null || end == null) {
                    throw new IllegalArgumentException("start and end times can't be null");
                }
                if(!end.isAfter(start)) {
                    throw new IllegalArgumentException("Illegal time range requested: " + start.toString() + " to " + end.toString());
                }
                this.chosenShards = findShardsForTimeRange(dataset, start, end);
            }
            return Lists.newArrayList(chosenShards);
        }

        /**
         * Returns a list of time intervals within the requested [start, end) range that are not covered by available shards.
         * Intervals in the list are sorted chronologically.
         */
        public List<Interval> getTimeIntervalsMissingShards() {
            // expects the returned shards to be sorted by start time
            final List<ShardIdWithVersion> chosenShards = getChosenShards();

            final List<Interval> timeIntervalsMissingShards = Lists.newArrayList();
            DateTime processedUpTo = start;
            for(ShardIdWithVersion shard : chosenShards) {
                if(processedUpTo.isBefore(shard.getStart())) {
                    timeIntervalsMissingShards.add(new Interval(processedUpTo, shard.getStart()));
                }
                processedUpTo = shard.getEnd();
            }

            if(processedUpTo.isBefore(end)) {
                timeIntervalsMissingShards.add(new Interval(processedUpTo, end));
            }
            return timeIntervalsMissingShards;
        }

        /**
         * Constructs an {@link ImhotepSession} instance.
         */
        public ImhotepSession build() {
            if(username == null) {
                username = ImhotepRemoteSession.getUsername();
            }
            List<String> chosenShardIDs = shardsOverride != null ? shardsOverride : ShardIdWithVersion.keepShardIds(getChosenShards());
            return getSessionForShards(dataset, chosenShardIDs, requestedMetrics, mergeThreadLimit, username, optimizeGroupZeroLookups, socketTimeout);
        }

    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards) {
        return getSession(dataset, requestedShards, Collections.<String>emptyList(), ImhotepRemoteSession.DEFAULT_MERGE_THREAD_LIMIT, -1);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final int socketTimeout) {
        return getSession(dataset, requestedShards, Collections.<String>emptyList(), ImhotepRemoteSession.DEFAULT_MERGE_THREAD_LIMIT, socketTimeout);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics) {
        return getSession(dataset, requestedShards, requestedMetrics, ImhotepRemoteSession.DEFAULT_MERGE_THREAD_LIMIT, -1);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
                                     final int mergeThreadLimit) {
        return getSession(dataset, requestedShards, requestedMetrics, mergeThreadLimit, -1);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
            final int mergeThreadLimit, final int priority) {
            return getSession(dataset, requestedShards, requestedMetrics, mergeThreadLimit, priority, ImhotepRemoteSession.getUsername(), false, -1);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
                                     final int mergeThreadLimit, final int priority, final int socketTimeout) {
        return getSession(dataset, requestedShards, requestedMetrics, mergeThreadLimit, priority, ImhotepRemoteSession.getUsername(), false, socketTimeout);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
                                     final int mergeThreadLimit, final int priority, final String username) {
        return getSession(dataset, requestedShards, requestedMetrics, mergeThreadLimit, priority, username, false, -1);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
                                     final int mergeThreadLimit, final int priority, final String username, final boolean optimizeGroupZeroLookups) {
        return getSession(dataset, requestedShards, requestedMetrics, mergeThreadLimit, priority, username, optimizeGroupZeroLookups, -1);
    }

    /**
     * @deprecated replaced by {@link #sessionBuilder}().build()
     */
    @Deprecated
    public ImhotepSession getSession(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
                                     final int mergeThreadLimit, final int priority, final String username,
                                     final boolean optimizeGroupZeroLookups, final int socketTimeout) {

        return getSessionForShards(dataset, requestedShards, requestedMetrics, mergeThreadLimit, username, optimizeGroupZeroLookups, socketTimeout);
    }

    private ImhotepSession getSessionForShards(final String dataset, final Collection<String> requestedShards, final Collection<String> requestedMetrics,
                                     final int mergeThreadLimit, final String username,
                                     final boolean optimizeGroupZeroLookups, final int socketTimeout) {

        if(requestedShards == null || requestedShards.size() == 0) {
            throw new IllegalArgumentException("No shards");
        }
        int retries = 3;
        while (retries > 0) {
            final String sessionId = UUID.randomUUID().toString();
            final ImhotepRemoteSession[] remoteSessions = internalGetSession(dataset, requestedShards, requestedMetrics, mergeThreadLimit, username, optimizeGroupZeroLookups, socketTimeout, sessionId);
            if (remoteSessions == null) {
                --retries;
                if (retries > 0) {
                    shardListReloader.run();
                }
                continue;
            }
            final InetSocketAddress[] nodes = new InetSocketAddress[remoteSessions.length];
            for (int i = 0; i < remoteSessions.length; i++) {
                nodes[i] = remoteSessions[i].getInetSocketAddress();
            }
            return new RemoteImhotepMultiSession(remoteSessions, sessionId, nodes);
        }
        throw new RuntimeException("unable to open session");
    }

    private static class IncrementalEvaluationState {

        private final Map<String, ShardData> unprocessedShards;
        private final Multimap<Host, String> unprocessedShardsByHost;

        public IncrementalEvaluationState(Map<String, ShardData> shards) {
            unprocessedShards = shards;

            unprocessedShardsByHost = HashMultimap.create();
            for (Map.Entry<String, ShardData> entry : shards.entrySet()) {
                String shardId = entry.getKey();
                for (Pair<Host, Integer> pair : entry.getValue().hostToLoadedMetrics) {
                    Host host = pair.getFirst();
                    unprocessedShardsByHost.put(host, shardId);
                }
                if (entry.getValue().hostToLoadedMetrics.isEmpty()) {
                    throw new IllegalStateException("no shards for host " + entry.getKey());
                };
            }
        }

        public synchronized List<String> getBatch(Host host, long maxDocs) {
            List<String> result = new ArrayList<String>();
            int docCount = 0;

            for (String shard : unprocessedShardsByHost.get(host)) {
                if (docCount >= maxDocs) break;

                ShardData data = unprocessedShards.get(shard);
                assert data != null;

                result.add(shard);
                docCount += data.numDocs;
            }

            for (String shard : result) {
                ShardData data = unprocessedShards.remove(shard);
                for (Pair<Host, Integer> pair : data.hostToLoadedMetrics) {
                    unprocessedShardsByHost.remove(pair.getFirst(), shard);
                }
            }

            return result;
        }
    }

    public void evaluateOnSessions(final SessionCallback callback, final String dataset, Collection<String> requestedShards,
                                   final long maxDocsPerSession) {

        // construct
        Map<String, ShardData> shardMap = constructPotentialShardMap(dataset, Collections.<String>emptySet());
        shardMap = Maps.newHashMap(
                Maps.filterKeys(shardMap, Predicates.in(ImmutableSet.copyOf(
                        requestedShards))));

        Set<Host> hosts = Sets.newTreeSet();
        for (ShardData data : shardMap.values()) {
            for (Pair<Host, Integer> pair : data.hostToLoadedMetrics) {
                hosts.add(pair.getFirst());
            }
        }

        final IncrementalEvaluationState state = new IncrementalEvaluationState(shardMap);

        final ExecutorService executor = Executors.newCachedThreadPool();
        final ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<Void>(executor);
        final List<Callable<Void>> callables = Lists.newArrayList();
        final String sessionId = UUID.randomUUID().toString();
        for (final Host host : hosts) {
            callables.add(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try {
                        while (true) {
                            if (Thread.interrupted()) {
                                throw new InterruptedException();
                            }

                            List<String> shards = state.getBatch(host, maxDocsPerSession);
                            if (shards.isEmpty()) break;
                            log.info("Processing " + shards.size() + " for " + host);

                            ImhotepRemoteSession session = ImhotepRemoteSession.openSession(host.getHostname(),
                                    host.getPort(), dataset, shards, sessionId);
                            callback.handle(session);
                        }
                        return null;
                    } catch (Exception e) {
                        throw new Exception("failed to get results for host " + host, e);
                    }
                }
            });
        }

        try {
            for (Callable<Void> callable : callables) {
                completionService.submit(callable);
            }

            for (int i = 0; i < callables.size(); i++) {
                Future<?> future = completionService.take(); // to wait for completion
                future.get(); // to propagate exceptions
            }
        } catch (ExecutionException e) {
            throw new RuntimeException("exception while executing operation", e);
        } catch (InterruptedException e) {
            throw new RuntimeException("interrupted while waiting for operation", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static class ShardData {
        final int numDocs;
        final long highestVersion;
        final List<Pair<Host, Integer>> hostToLoadedMetrics;

        private ShardData(int numDocs, long highestVersion, List<Pair<Host, Integer>> hostToLoadedMetrics) {
            this.numDocs = numDocs;
            this.highestVersion = highestVersion;
            this.hostToLoadedMetrics = hostToLoadedMetrics;
        }
    }

    // returns null on error
    private ImhotepRemoteSession[] internalGetSession(final String dataset, Collection<String> requestedShards, Collection<String> requestedMetrics, final int mergeThreadLimit,
                                                      final String username, final boolean optimizeGroupZeroLookups, final int socketTimeout, @Nullable final String sessionId) {

        final Map<Host, List<String>> shardRequestMap = buildShardRequestMap(dataset, requestedShards, requestedMetrics);

        if (shardRequestMap.isEmpty()) {
            log.error("unable to find all of the requested shards in dataset " + dataset + " (shard list = " + requestedShards + ")");
            return null;
        }

        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<Future<ImhotepRemoteSession>> futures = new ArrayList<Future<ImhotepRemoteSession>>(shardRequestMap.size());
        try {
            for (final Map.Entry<Host, List<String>> entry : shardRequestMap.entrySet()) {
                final Host host = entry.getKey();
                final List<String> shardList = entry.getValue();

                futures.add(executor.submit(new Callable<ImhotepRemoteSession>() {
                    @Override
                    public ImhotepRemoteSession call() throws Exception {
                        return ImhotepRemoteSession.openSession(host.hostname, host.port, dataset, shardList, mergeThreadLimit, username, optimizeGroupZeroLookups, socketTimeout, sessionId);
                    }
                }));
            }
        } finally {
            executor.shutdown();
        }

        final ImhotepRemoteSession[] remoteSessions = new ImhotepRemoteSession[shardRequestMap.size()];
        boolean error = false;
        for (int i = 0; i < futures.size(); ++i) {
            try {
                remoteSessions[i] = futures.get(i).get();
            } catch (ExecutionException e) {
                log.error("exception while opening session", e);
                error = true;
            } catch (InterruptedException e) {
                log.error("interrupted while opening session", e);
                error = true;
            }
        }

        if (error) {
            for (final ImhotepRemoteSession session : remoteSessions) {
                if (session != null) {
                    try {
                        session.close();
                    } catch (RuntimeException e) {
                        log.error("exception while closing session", e);
                    }
                }
            }
            return null;
        }
        
        return remoteSessions;
    }

    private Map<Host, List<String>> buildShardRequestMap(String dataset, Collection<String> requestedShards, Collection<String> requestedMetrics) {
        final Set<String> requestedMetricsSet = new HashSet<String>(requestedMetrics);
        final Map<String, ShardData> shardMap = constructPotentialShardMap(dataset, requestedMetricsSet);

        boolean error = false;
        for (final String shard : requestedShards) {
            if (!shardMap.containsKey(shard)) {
                log.error("shard " + shard + " not found");
                error = true;
            }
        }

        if (error) {
            return Maps.newHashMap();
        }

        final List<String> sortedShards = new ArrayList<String>(requestedShards);
        Collections.sort(sortedShards, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                final int c1 = shardMap.get(o1).numDocs;
                final int c2 = shardMap.get(o2).numDocs;
                return -(c1 < c2 ? -1 : c1 > c2 ? 1 : 0);
            }
        });

        final Map<Host, Integer> hostDocCounts = new HashMap<Host, Integer>();
        final Map<Host, List<String>> shardRequestMap = new TreeMap<Host, List<String>>();
        for (final String shard : sortedShards) {
            final List<Pair<Host, Integer>> potentialHosts = shardMap.get(shard).hostToLoadedMetrics;
            int minHostDocCount = Integer.MAX_VALUE;
            int minHostLoadedMetricCount = 0;
            Host minHost = null;
            for (final Pair<Host, Integer> p : potentialHosts) {
                final Host host = p.getFirst();
                final int loadedMetricCount = p.getSecond();

                if (!hostDocCounts.containsKey(host)) hostDocCounts.put(host, 0);
                if (loadedMetricCount > minHostLoadedMetricCount || hostDocCounts.get(host) < minHostDocCount) {
                    minHostDocCount = hostDocCounts.get(host);
                    minHostLoadedMetricCount = loadedMetricCount;
                    minHost = host;
                }
            }
            if (minHost == null) throw new RuntimeException("something has gone horribly wrong");

            if (!shardRequestMap.containsKey(minHost)) {
                shardRequestMap.put(minHost, new ArrayList<String>());
            }
            shardRequestMap.get(minHost).add(shard);
            hostDocCounts.put(minHost, hostDocCounts.get(minHost) + shardMap.get(shard).numDocs);
        }
        return shardRequestMap;
    }

    /**
     * Given a dataset and a list of requested metrics, compute a map from shard IDs to lists of
     * (host, # of loaded metrics) pairs.
     *
     * @param dataset The dataset name
     * @param requestedMetricsSet The set of metrics whose loaded status should be counted
     * @return The resulting map
     */
    private Map<String, ShardData> constructPotentialShardMap(String dataset, Set<String> requestedMetricsSet) {
        final Map<String, ShardData> shardMap = Maps.newHashMap();
        final Map<Host, List<DatasetInfo>> shardListMap = getShardList();
        for (final Map.Entry<Host, List<DatasetInfo>> e : shardListMap.entrySet()) {
            final Host host = e.getKey();
            final List<DatasetInfo> shardList = e.getValue();
            for (final DatasetInfo datasetInfo : shardList) {
                if (!dataset.equals(datasetInfo.getDataset())) continue;

                for (final ShardInfo shard : datasetInfo.getShardList()) {
                    if (!shardMap.containsKey(shard.shardId)) {
                        shardMap.put(shard.shardId, new ShardData(shard.numDocs, shard.version, new ArrayList<Pair<Host, Integer>>()));
                    } else {
                        final ShardData shardData = shardMap.get(shard.shardId);
                        final long highestKnownVersion = shardData.highestVersion;
                        if (highestKnownVersion < shard.version) {
                            // a newer version was found and all the previously encountered data for this shard should be removed
                            shardMap.put(shard.shardId, new ShardData(shard.numDocs, shard.version, new ArrayList<Pair<Host, Integer>>()));
                        } else if (highestKnownVersion > shard.version) {
                            continue; // this shard has an outdated version and should be skipped
                        } // else if (highestKnownVersion == shard.version) // just continue
                    }
                    final int loadedMetricsCount = Sets.intersection(requestedMetricsSet, new HashSet<String>(shard.loadedMetrics)).size();
                    shardMap.get(shard.shardId).hostToLoadedMetrics.add(Pair.of(host, loadedMetricsCount));
                }
            }
        }
        return shardMap;
    }

    public Map<Host, ImhotepStatusDump> getStatusDumps() {
        final List<Host> hosts = hostsSource.getHosts();

        final Map<Host, Future<ImhotepStatusDump>> futures = Maps.newHashMap();
        for (final Host host : hosts) {
            final Future<ImhotepStatusDump> future = rpcExecutor.submit(new Callable<ImhotepStatusDump>() {
                @Override
                public ImhotepStatusDump call() throws Exception {
                    return ImhotepRemoteSession.getStatusDump(host.hostname, host.port);
                }
            });
            futures.put(host, future);
        }

        final Map<Host, ImhotepStatusDump> ret = new HashMap<Host, ImhotepStatusDump>();
        for (final Host host : hosts) {
            try {
                final ImhotepStatusDump statusDump = futures.get(host).get();
                ret.put(host, statusDump);
            } catch (ExecutionException e) {
                log.error("error getting status dump from " + host, e);
            } catch (InterruptedException e) {
                log.error("error getting status dump from " + host, e);
            }
        }
        return ret;
    }

    @Override
    public void close() throws IOException {
        rpcExecutor.shutdownNow();
        reloader.shutdown();
        hostsSource.shutdown();

        try {
            if (!rpcExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IOException("RPC executor failed to terminate in time");
            }
            if (!reloader.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IOException("reloader failed to terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isConnectionHealthy() {
        return hostsSource.isLoadedDataSuccessfullyRecently() && shardListReloader.isLoadedDataSuccessfullyRecently();
    }
}
