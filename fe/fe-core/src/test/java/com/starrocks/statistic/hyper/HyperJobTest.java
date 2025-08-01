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

package com.starrocks.statistic.hyper;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.ArrayType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.sql.plan.DistributedEnvPlanTestBase;
import com.starrocks.sql.plan.PlanTestBase;
import com.starrocks.statistic.AnalyzeStatus;
import com.starrocks.statistic.HyperStatisticsCollectJob;
import com.starrocks.statistic.NativeAnalyzeStatus;
import com.starrocks.statistic.StatisticUtils;
import com.starrocks.statistic.StatsConstants;
import com.starrocks.statistic.base.ColumnClassifier;
import com.starrocks.statistic.base.ColumnStats;
import com.starrocks.statistic.base.PartitionSampler;
import com.starrocks.statistic.base.PrimitiveTypeColumnStats;
import com.starrocks.statistic.base.SubFieldColumnStats;
import com.starrocks.statistic.base.TabletSampler;
import com.starrocks.statistic.sample.SampleInfo;
import com.starrocks.statistic.sample.TabletStats;
import com.starrocks.utframe.StarRocksAssert;
import mockit.Mock;
import mockit.MockUp;
import org.apache.velocity.VelocityContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class HyperJobTest extends DistributedEnvPlanTestBase {

    private static Database db;

    private static Table table;

    private static PartitionSampler sampler;

    private static long pid;

    @BeforeAll
    public static void beforeClass() throws Exception {
        PlanTestBase.beforeClass();
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        FeConstants.runningUnitTest = true;
        starRocksAssert.withTable("create table t_struct(c0 INT, " +
                "c1 date," +
                "c2 varchar(255)," +
                "c3 decimal(10, 2)," +
                "c4 struct<a int, b array<struct<a int, b int>>>," +
                "c5 struct<a int, b int>," +
                "c6 struct<a int, b int, c struct<a int, b int>, d array<int>>," +
                "c7 array<int>) " +
                "duplicate key(c0) distributed by hash(c0) buckets 1 " +
                "properties('replication_num'='1');");
        db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable("test", "t_struct");
        pid = table.getPartition("t_struct").getId();
        sampler = PartitionSampler.create(table, List.of(pid), Maps.newHashMap(), null);

        for (Partition partition : ((OlapTable) table).getAllPartitions()) {
            partition.getDefaultPhysicalPartition().getBaseIndex().setRowCount(10000);
        }
    }

    @Test
    public void generateComplexTypeColumnTask() {
        List<String> columnNames = table.getColumns().stream().map(Column::getName).collect(Collectors.toList());
        List<Type> columnTypes = table.getColumns().stream().map(Column::getType).collect(Collectors.toList());

        List<HyperQueryJob> job =
                HyperQueryJob.createFullQueryJobs(connectContext, db, table, columnNames, columnTypes, List.of(pid), 1, false);
        Assertions.assertEquals(2, job.size());
        Assertions.assertTrue(job.get(1) instanceof ConstQueryJob);
    }

    @Test
    public void generatePrimitiveTypeColumnTask() {
        List<String> columnNames = table.getColumns().stream().map(Column::getName).collect(Collectors.toList());
        List<Type> columnTypes = table.getColumns().stream().map(Column::getType).collect(Collectors.toList());

        ColumnClassifier cc = ColumnClassifier.of(columnNames, columnTypes, table, false);
        ColumnStats columnStat = cc.getColumnStats().stream().filter(c -> c instanceof PrimitiveTypeColumnStats)
                .findAny().orElse(null);

        VelocityContext context = HyperStatisticSQLs.buildBaseContext(db, table, table.getPartition(pid), columnStat);
        context.put("dataSize", columnStat.getFullDataSize());
        context.put("countNullFunction", columnStat.getFullNullCount());
        context.put("hllFunction", columnStat.getNDV());
        context.put("maxFunction", columnStat.getMax());
        context.put("minFunction", columnStat.getMin());
        String sql = HyperStatisticSQLs.build(context, HyperStatisticSQLs.BATCH_FULL_STATISTIC_TEMPLATE);
        assertContains(sql, "hex(hll_serialize(IFNULL(hll_raw(`c0`)");
        List<StatementBase> stmt = SqlParser.parse(sql, connectContext.getSessionVariable());
        Assertions.assertTrue(stmt.get(0) instanceof QueryStatement);
    }

    @Test
    public void generateSubFieldTypeColumnTask() {
        List<String> columnNames = Lists.newArrayList("c1", "c4.b", "c6.c.b");
        List<Type> columnTypes = Lists.newArrayList(Type.DATE, new ArrayType(Type.ANY_STRUCT), Type.INT);

        ColumnClassifier cc = ColumnClassifier.of(columnNames, columnTypes, table, false);
        List<ColumnStats> columnStat = cc.getColumnStats().stream().filter(c -> c instanceof SubFieldColumnStats)
                .collect(Collectors.toList());
        String sql = HyperStatisticSQLs.buildSampleSQL(db, table, table.getPartition(pid), columnStat, sampler,
                HyperStatisticSQLs.BATCH_SAMPLE_STATISTIC_SELECT_TEMPLATE);
        Assertions.assertEquals(2, columnStat.size());
        List<StatementBase> stmt = SqlParser.parse(sql, connectContext.getSessionVariable());
        Assertions.assertTrue(stmt.get(0) instanceof QueryStatement);
    }

    public Pair<List<String>, List<Type>> initColumn(List<String> cols) {
        List<String> columnNames = Lists.newArrayList();
        List<Type> columnTypes = Lists.newArrayList();
        for (String col : cols) {
            Column c = table.getColumn(col);
            columnNames.add(c.getName());
            columnTypes.add(c.getType());
        }
        return Pair.create(columnNames, columnTypes);
    }

    @Test
    public void testConstQueryJobs() {
        new MockUp<StmtExecutor>() {
            @Mock
            public void execute() throws Exception {
            }
        };
        Pair<List<String>, List<Type>> pair = initColumn(List.of("c4", "c5", "c6"));

        HyperStatisticsCollectJob job = new HyperStatisticsCollectJob(db, table, List.of(pid), pair.first, pair.second,
                StatsConstants.AnalyzeType.FULL,
                StatsConstants.ScheduleType.ONCE, Maps.newHashMap(), false);

        ConnectContext context = StatisticUtils.buildConnectContext();
        AnalyzeStatus status = new NativeAnalyzeStatus(1, 1, 1, pair.first, StatsConstants.AnalyzeType.FULL,
                StatsConstants.ScheduleType.ONCE, Maps.newHashMap(), LocalDateTime.now());
        try {
            job.collect(context, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testFullJobs() {
        Pair<List<String>, List<Type>> pair = initColumn(List.of("c1", "c2", "c3"));

        List<HyperQueryJob> jobs = HyperQueryJob.createFullQueryJobs(connectContext, db, table, pair.first,
                pair.second, List.of(pid), 1, false);

        Assertions.assertEquals(1, jobs.size());

        List<String> sql = jobs.get(0).buildQuerySQL();
        Assertions.assertEquals(3, sql.size());

        assertContains(sql.get(0), "hex(hll_serialize(IFNULL(hll_raw(`c1`),");
        assertContains(sql.get(1), "FROM `test`.`t_struct` partition `t_struct`");
    }

    @Test
    public void testSampleJobs() {
        Pair<List<String>, List<Type>> pair = initColumn(List.of("c1", "c2", "c3"));

        new MockUp<SampleInfo>() {
            @Mock
            public List<TabletStats> getMediumHighWeightTablets() {
                return List.of(new TabletStats(1, pid, 5000000));
            }
        };

        List<HyperQueryJob> jobs = HyperQueryJob.createSampleQueryJobs(connectContext, db, table, pair.first,
                pair.second, List.of(pid), 1, sampler, false);

        Assertions.assertEquals(2, jobs.size());
        Assertions.assertTrue(jobs.get(0) instanceof MetaQueryJob);
        Assertions.assertTrue(jobs.get(1) instanceof SampleQueryJob);

        List<String> sql = jobs.get(1).buildQuerySQL();
        Assertions.assertEquals(1, sql.size());

        assertContains(sql.get(0), "with base_cte_table as ( SELECT * FROM (SELECT * FROM `test`.`t_struct` " +
                "TABLET(1) SAMPLE('percent'='10')) t_medium_high)");
        assertContains(sql.get(0), "cast(IFNULL(SUM(CHAR_LENGTH(`c2`)) * 0/ COUNT(*), 0) as BIGINT), " +
                "hex(hll_serialize(IFNULL(hll_raw(`c2`), hll_empty())))," +
                " cast((COUNT(*) - COUNT(`c2`)) * 0 / COUNT(*) as BIGINT), " +
                "IFNULL(MAX(LEFT(`c2`, 200)), ''), IFNULL(MIN(LEFT(`c2`, 200)), ''), cast(-1.0 as BIGINT) " +
                "FROM base_cte_table ");
    }

    @Test
    public void testArrayNDV() {
        Pair<List<String>, List<Type>> pair = initColumn(List.of("c7"));

        List<HyperQueryJob> jobs = HyperQueryJob.createFullQueryJobs(connectContext, db, table, pair.first,
                pair.second, List.of(pid), 1, true);

        Assertions.assertEquals(1, jobs.size());

        List<String> sql = jobs.get(0).buildQuerySQL();
        assertContains(sql.get(0), "'00'");
        Config.enable_manual_collect_array_ndv = true;
        sql = jobs.get(0).buildQuerySQL();
        assertContains(sql.get(0), "hex(hll_serialize(IFNULL(hll_raw(crc32_hash(`c7`)), hll_empty())))");
        Config.enable_manual_collect_array_ndv = false;
    }

    @Test
    public void testSubfieldSampleJobs() {
        List<String> columnNames = Lists.newArrayList("c4.b", "c6.c.b");
        List<Type> columnTypes = Lists.newArrayList(new ArrayType(Type.ANY_STRUCT), Type.INT);

        new MockUp<SampleInfo>() {
            @Mock
            public List<TabletStats> getMediumHighWeightTablets() {
                return List.of(new TabletStats(1, pid, 5000000));
            }
        };

        List<HyperQueryJob> jobs = HyperQueryJob.createSampleQueryJobs(connectContext, db, table, columnNames,
                columnTypes, List.of(pid), 1, sampler, false);

        Assertions.assertEquals(1, jobs.size());
        Assertions.assertTrue(jobs.get(0) instanceof SampleQueryJob);

        List<String> sql = jobs.get(0).buildQuerySQL();
        Assertions.assertEquals(2, sql.size());

        assertContains(sql.get(1),
                "with base_cte_table as ( SELECT * FROM (SELECT * FROM `test`.`t_struct` TABLET(1) SAMPLE" +
                        "('percent'='10'))");
        assertContains(sql.get(1), "'c6.c.b', cast(0 as BIGINT), cast(4 * 0 as BIGINT), ");
        assertContains(sql.get(1), "hex(hll_serialize(IFNULL(hll_raw(`c6`.`c`.`b`), hll_empty()))), ");
        assertContains(sql.get(1), "cast((COUNT(*) - COUNT(`c6`.`c`.`b`)) * 0 / COUNT(*) as BIGINT), " +
                "IFNULL(MAX(`c6`.`c`.`b`), ''), IFNULL(MIN(`c6`.`c`.`b`), ''), cast(-1.0 as BIGINT) FROM base_cte_table");
    }

    @Test
    public void testSampleRows() {
        new MockUp<TabletSampler>() {
            @Mock
            public List<TabletStats> sample() {
                return List.of(new TabletStats(1, pid, 5000000));
            }

        };
        PartitionSampler sampler = PartitionSampler.create(table, List.of(pid), Maps.newHashMap(), null);
        Assertions.assertEquals(5550000, sampler.getSampleInfo(pid).getSampleRowCount());
    }

    @Test
    public void testHyperQueryJobContextStartTime() {
        Pair<List<String>, List<Type>> pair = initColumn(List.of("c1", "c2", "c3"));

        new MockUp<SampleInfo>() {
            @Mock
            public List<TabletStats> getMediumHighWeightTablets() {
                return List.of(new TabletStats(1, pid, 5000000));
            }
        };

        List<HyperQueryJob> jobs = HyperQueryJob.createSampleQueryJobs(connectContext, db, table, pair.first,
                pair.second, List.of(pid), 1, sampler, false);
        long startTime = connectContext.getStartTime();
        for (HyperQueryJob job : jobs) {
            job.queryStatistics();
            Assertions.assertNotEquals(startTime, connectContext.getStartTime());
        }
    }

    @AfterAll
    public static void afterClass() {
        FeConstants.runningUnitTest = false;
    }
}
