// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.qe.scheduler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.starrocks.common.Reference;
import com.starrocks.common.StarRocksException;
import com.starrocks.qe.SessionVariableConstants.ComputationFragmentSchedulingPolicy;
import com.starrocks.qe.SimpleScheduler;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.SystemInfoService;
import com.starrocks.warehouse.cngroup.ComputeResource;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultWorkerProviderTest {
    private final ImmutableMap<Long, ComputeNode> id2Backend = genWorkers(0, 10, Backend::new, false);
    private final ImmutableMap<Long, ComputeNode> id2ComputeNode = genWorkers(10, 15, ComputeNode::new, false);
    private final ImmutableMap<Long, ComputeNode> availableId2Backend = ImmutableMap.of(
            0L, id2Backend.get(0L),
            2L, id2Backend.get(2L),
            3L, id2Backend.get(3L),
            5L, id2Backend.get(5L),
            7L, id2Backend.get(7L));
    private final ImmutableMap<Long, ComputeNode> availableId2ComputeNode = ImmutableMap.of(
            10L, id2ComputeNode.get(10L),
            12L, id2ComputeNode.get(12L),
            14L, id2ComputeNode.get(14L));
    private final Map<Long, ComputeNode> availableId2Worker = Stream.of(availableId2Backend, availableId2ComputeNode)
            .map(Map::entrySet)
            .flatMap(Collection::stream)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private static <C extends ComputeNode> ImmutableMap<Long, C> genWorkers(long startId, long endId,
                                                                            Supplier<C> factory, boolean halfDead) {
        Map<Long, C> res = new TreeMap<>();
        for (long i = startId; i < endId; i++) {
            C worker = factory.get();
            worker.setId(i);
            if (halfDead && i % 2 == 0) {
                worker.setAlive(false);
            } else {
                worker.setAlive(true);
            }
            worker.setHost("host#" + i);
            worker.setBePort(80);
            res.put(i, worker);
        }
        return ImmutableMap.copyOf(res);
    }

    @Test
    public void testCaptureAvailableWorkers() {

        long deadBEId = 1L;
        long deadCNId = 11L;
        long inBlacklistBEId = 3L;
        long inBlacklistCNId = 13L;
        Set<Long> nonAvailableWorkerId = ImmutableSet.of(deadBEId, deadCNId, inBlacklistBEId, inBlacklistCNId);
        id2Backend.get(deadBEId).setAlive(false);
        id2ComputeNode.get(deadCNId).setAlive(false);
        new MockUp<SimpleScheduler>() {
            @Mock
            public boolean isInBlocklist(long backendId) {
                return backendId == inBlacklistBEId || backendId == inBlacklistCNId;
            }
        };

        Reference<Integer> nextComputeNodeIndex = new Reference<>(0);
        new MockUp<DefaultWorkerProvider>() {
            @Mock
            int getNextComputeNodeIndex(ComputeResource computeResource) {
                int next = nextComputeNodeIndex.getRef();
                nextComputeNodeIndex.setRef(next + 1);
                return next;
            }
        };

        new MockUp<SystemInfoService>() {
            @Mock
            public ImmutableMap<Long, ComputeNode> getIdToBackend() {
                return id2Backend;
            }

            @Mock
            public ImmutableMap<Long, ComputeNode> getIdComputeNode() {
                return id2ComputeNode;
            }
        };

        DefaultWorkerProvider.Factory workerProviderFactory = new DefaultWorkerProvider.Factory();
        DefaultWorkerProvider workerProvider;
        List<Integer> numUsedComputeNodesList = ImmutableList.of(100, 0, -1, 1, 2, 3, 4, 5, 6);
        for (Integer numUsedComputeNodes : numUsedComputeNodesList) {
            // Reset nextComputeNodeIndex.
            nextComputeNodeIndex.setRef(0);

            workerProvider =
                    workerProviderFactory.captureAvailableWorkers(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                            true, numUsedComputeNodes, ComputationFragmentSchedulingPolicy.COMPUTE_NODES_ONLY,
                            WarehouseManager.DEFAULT_RESOURCE);

            int numAvailableComputeNodes = 0;
            for (long id = 0; id < 15; id++) {
                ComputeNode worker = workerProvider.getWorkerById(id);
                if (nonAvailableWorkerId.contains(id)
                        // Exceed the limitation of numUsedComputeNodes.
                        || (numUsedComputeNodes > 0 && numAvailableComputeNodes >= numUsedComputeNodes)) {
                    Assertions.assertNull(worker);
                } else {
                    Assertions.assertNotNull(worker, "numUsedComputeNodes=" + numUsedComputeNodes + ",id=" + id);
                    Assertions.assertEquals(id, worker.getId());

                    if (id2ComputeNode.containsKey(id)) {
                        numAvailableComputeNodes++;
                    }
                }
            }
        }
    }

    /**
     * The schedule policy is suitable in shared nothing mode.
     */
    @Test
    public void testSelectBackendAndComputeNode() {
        new MockUp<SystemInfoService>() {
            @Mock
            public ImmutableMap<Long, ComputeNode> getIdToBackend() {
                return availableId2Backend;
            }

            @Mock
            public ImmutableMap<Long, ComputeNode> getIdComputeNode() {
                return availableId2ComputeNode;
            }
        };

        DefaultWorkerProvider.Factory workerProviderFactory = new DefaultWorkerProvider.Factory();
        DefaultWorkerProvider workerProvider;
        List<Integer> numUsedComputeNodesList = ImmutableList.of(-1, 0, 2, 3, 5, 8, 10);

        // test ComputeNode only
        for (Integer numUsedComputeNodes : numUsedComputeNodesList) {
            workerProvider =
                    workerProviderFactory.captureAvailableWorkers(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                            true, numUsedComputeNodes, ComputationFragmentSchedulingPolicy.COMPUTE_NODES_ONLY,
                            WarehouseManager.DEFAULT_RESOURCE);
            List<Long> selectedWorkerIdsList = workerProvider.getAllAvailableNodes();
            for (Long selectedWorkerId : selectedWorkerIdsList) {
                Assertions.assertTrue(availableId2ComputeNode.containsKey(selectedWorkerId),
                        "selectedWorkerId:" + selectedWorkerId);
            }
        }
        // test Backend only
        for (Integer numUsedComputeNodes : numUsedComputeNodesList) {
            workerProvider =
                    workerProviderFactory.captureAvailableWorkers(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                            false, numUsedComputeNodes, ComputationFragmentSchedulingPolicy.COMPUTE_NODES_ONLY,
                            WarehouseManager.DEFAULT_RESOURCE);
            List<Long> selectedWorkerIdsList = workerProvider.getAllAvailableNodes();
            Assertions.assertEquals(availableId2Backend.size(), selectedWorkerIdsList.size());
            for (Long selectedWorkerId : selectedWorkerIdsList) {
                Assertions.assertTrue(availableId2Backend.containsKey(selectedWorkerId),
                        "selectedWorkerId:" + selectedWorkerId);
            }
        }
        // test Backend and ComputeNode
        for (Integer numUsedComputeNodes : numUsedComputeNodesList) {
            workerProvider =
                    workerProviderFactory.captureAvailableWorkers(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                            true, numUsedComputeNodes, ComputationFragmentSchedulingPolicy.ALL_NODES,
                            WarehouseManager.DEFAULT_RESOURCE);
            List<Long> selectedWorkerIdsList = workerProvider.getAllAvailableNodes();
            Collections.reverse(selectedWorkerIdsList); //put ComputeNode id to the front,Backend id to the back
            //test ComputeNode
            for (int i = 0; i < availableId2ComputeNode.size() && i < selectedWorkerIdsList.size(); i++) {
                Assertions.assertTrue(availableId2ComputeNode.containsKey(selectedWorkerIdsList.get(i)),
                        "selectedWorkerId:" + selectedWorkerIdsList.get(i));
            }
            //test Backend
            for (int i = availableId2ComputeNode.size(); i < selectedWorkerIdsList.size(); i++) {
                Assertions.assertTrue(availableId2Backend.containsKey(selectedWorkerIdsList.get(i)),
                        "selectedWorkerId:" + selectedWorkerIdsList.get(i));
            }
        }
    }

    @Test
    public void testSelectWorker() throws StarRocksException {
        DefaultWorkerProvider workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        for (long id = -1; id < 20; id++) {
            if (availableId2Worker.containsKey(id)) {
                workerProvider.selectWorker(id);
                testUsingWorkerHelper(workerProvider, id);
            } else {
                long finalId = id;
                Assertions.assertThrows(NonRecoverableException.class, () -> workerProvider.selectWorker(finalId));
            }
        }
    }

    private static <C extends ComputeNode> void testSelectNextWorkerHelper(DefaultWorkerProvider workerProvider,
                                                                           Map<Long, C> id2Worker)
            throws StarRocksException {

        Set<Long> selectedWorkers = new HashSet<>(id2Worker.size());
        for (int i = 0; i < id2Worker.size(); i++) {
            long workerId = workerProvider.selectNextWorker();

            Assertions.assertFalse(selectedWorkers.contains(workerId));
            selectedWorkers.add(workerId);

            testUsingWorkerHelper(workerProvider, workerId);
        }
    }

    @Test
    public void testSelectNextWorker() throws StarRocksException {
        DefaultWorkerProvider workerProvider;

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        testSelectNextWorkerHelper(workerProvider, availableId2ComputeNode);

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, ImmutableMap.of(),
                        true, WarehouseManager.DEFAULT_RESOURCE);
        testSelectNextWorkerHelper(workerProvider, availableId2Backend);

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        false, WarehouseManager.DEFAULT_RESOURCE);
        testSelectNextWorkerHelper(workerProvider, availableId2Backend);

        ImmutableMap<Long, ComputeNode> id2BackendHalfDead = genWorkers(0, 10, Backend::new, true);
        workerProvider =
                new DefaultWorkerProvider(id2BackendHalfDead, id2ComputeNode, ImmutableMap.of(), ImmutableMap.of(),
                        false, WarehouseManager.DEFAULT_RESOURCE);
        DefaultWorkerProvider finalWorkerProvider = workerProvider;

        SchedulerException e = Assertions.assertThrows(SchedulerException.class, finalWorkerProvider::selectNextWorker);
        Assertions.assertEquals(
                "Backend node not found. Check if any backend node is down.backend:" +
                        " [host#0 alive: false inBlacklist: false] " +
                        "[host#2 alive: false inBlacklist: false]" +
                        " [host#4 alive: false inBlacklist: false]" +
                        " [host#6 alive: false inBlacklist: false]" +
                        " [host#8 alive: false inBlacklist: false] ",
                e.getMessage());
        ImmutableMap<Long, ComputeNode> id2ComputeNodeHalfDead = genWorkers(10, 15, ComputeNode::new, true);
        workerProvider =
                new DefaultWorkerProvider(id2ComputeNodeHalfDead, ImmutableMap.of());
        finalWorkerProvider = workerProvider;
        e = Assertions.assertThrows(SchedulerException.class, finalWorkerProvider::selectNextWorker);
        Assertions.assertEquals(
                "Compute node not found. Check if any compute node is down.compute node:" +
                        " [host#10 alive: false inBlacklist: false]" +
                        " [host#12 alive: false inBlacklist: false]" +
                        " [host#14 alive: false inBlacklist: false] ",
                e.getMessage());
    }

    @Test
    public void testChooseAllComputedNodes() {
        DefaultWorkerProvider workerProvider;
        List<Long> computeNodes;

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        false, WarehouseManager.DEFAULT_RESOURCE);
        computeNodes = workerProvider.selectAllComputeNodes();
        Assertions.assertTrue(computeNodes.isEmpty());

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        computeNodes = workerProvider.selectAllComputeNodes();
        Assertions.assertEquals(availableId2ComputeNode.size(), computeNodes.size());
        Set<Long> computeNodeSet = new HashSet<>(computeNodes);
        for (ComputeNode computeNode : availableId2ComputeNode.values()) {
            Assertions.assertTrue(computeNodeSet.contains(computeNode.getId()));

            testUsingWorkerHelper(workerProvider, computeNode.getId());
        }
    }

    private static <C extends ComputeNode> void testGetBackendHelper(DefaultWorkerProvider workerProvider,
                                                                     Map<Long, C> availableId2Worker) {
        // not allow using backup node
        Assertions.assertFalse(workerProvider.allowUsingBackupNode());
        for (long id = -1; id < 10; id++) {
            ComputeNode backend = workerProvider.getBackend(id);
            boolean isContained = workerProvider.isDataNodeAvailable(id);
            if (!availableId2Worker.containsKey(id)) {
                Assertions.assertNull(backend);
                Assertions.assertFalse(isContained);
            } else {
                Assertions.assertNotNull(backend, "id=" + id);
                Assertions.assertEquals(availableId2Worker.get(id), backend);
                Assertions.assertTrue(isContained);
            }
            // chooseBackupNode always returns -1
            Assertions.assertEquals(-1, workerProvider.selectBackupWorker(id));
        }
    }

    @Test
    public void testGetBackend() {
        DefaultWorkerProvider workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        testGetBackendHelper(workerProvider, availableId2Backend);
    }

    @Test
    public void testGetWorkersPreferringComputeNode() {
        DefaultWorkerProvider workerProvider;

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        assertThat(workerProvider.getAllWorkers())
                .containsOnlyOnceElementsOf(availableId2ComputeNode.values());

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, ImmutableMap.of(),
                        true, WarehouseManager.DEFAULT_RESOURCE);
        assertThat(workerProvider.getAllWorkers())
                .containsOnlyOnceElementsOf(availableId2Backend.values());

        workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        false, WarehouseManager.DEFAULT_RESOURCE);
        assertThat(workerProvider.getAllWorkers())
                .containsOnlyOnceElementsOf(availableId2ComputeNode.values());
    }

    @Test
    public void testReportBackendNotFoundException() {
        DefaultWorkerProvider workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        Assertions.assertThrows(SchedulerException.class, workerProvider::reportDataNodeNotFoundException);
    }

    @Test
    public void testNextWorkerOverflow() throws NonRecoverableException {
        DefaultWorkerProvider workerProvider =
                new DefaultWorkerProvider(id2Backend, id2ComputeNode, availableId2Backend, availableId2ComputeNode,
                        true, WarehouseManager.DEFAULT_RESOURCE);
        for (int i = 0; i < 100; i++) {
            Long workerId = workerProvider.selectNextWorker();
            assertThat(workerId).isNotNegative();
        }
        DefaultWorkerProvider.getNextComputeNodeIndexer().set(Integer.MAX_VALUE);
        for (int i = 0; i < 100; i++) {
            Long workerId = workerProvider.selectNextWorker();
            assertThat(workerId).isNotNegative();
        }
    }

    public static void testUsingWorkerHelper(DefaultWorkerProvider workerProvider, Long workerId) {
        Assertions.assertTrue(workerProvider.isWorkerSelected(workerId));
        assertThat(workerProvider.getSelectedWorkerIds()).contains(workerId);
    }

    @Test
    public void getNextComputeNodeIndex_returnsIncrementedValueInSharedDataMode(@Mocked ComputeResource computeResource) {
        new MockUp<RunMode>() {
            @Mock
            public RunMode getCurrentRunMode() {
                return RunMode.SHARED_DATA;
            }
        };
        AtomicInteger mockIndex = new AtomicInteger(5);
        new MockUp<WarehouseManager>() {
            @Mock
            public AtomicInteger getNextComputeNodeIndexFromWarehouse(ComputeResource resource) {
                return mockIndex;
            }
        };

        int result = DefaultWorkerProvider.getNextComputeNodeIndex(computeResource);
        Assertions.assertEquals(5, result);
        Assertions.assertEquals(6, mockIndex.get());
    }

    @Test
    public void getNextComputeNodeIndex_returnsIncrementedValueInSharedNothingMode(@Mocked ComputeResource computeResource) {
        new MockUp<RunMode>() {
            @Mock
            public RunMode getCurrentRunMode() {
                return RunMode.SHARED_NOTHING;
            }
        };

        int initialIndex = DefaultWorkerProvider.getNextComputeNodeIndexer().get();
        int result = DefaultWorkerProvider.getNextComputeNodeIndex(computeResource);
        Assertions.assertEquals(initialIndex, result);
        Assertions.assertEquals(initialIndex + 1, DefaultWorkerProvider.getNextComputeNodeIndexer().get());
    }
}
