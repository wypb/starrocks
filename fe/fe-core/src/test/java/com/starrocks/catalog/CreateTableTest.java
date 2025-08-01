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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/catalog/CreateTableTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.google.api.client.util.Lists;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.starrocks.alter.AlterJobException;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.catalog.constraint.UniqueConstraint;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.ConfigBase;
import com.starrocks.common.DdlException;
import com.starrocks.common.ExceptionChecker;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.DynamicPartitionUtil;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.persist.CreateTableInfo;
import com.starrocks.persist.OperationType;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.DDLStmtExecutor;
import com.starrocks.qe.ShowExecutor;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.server.RunMode;
import com.starrocks.sql.analyzer.AlterSystemStmtAnalyzer;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.ShowCreateTableStmt;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class CreateTableTest {
    private static ConnectContext connectContext;
    private static StarRocksAssert starRocksAssert;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        Backend be = UtFrameUtils.addMockBackend(10002);
        UtFrameUtils.addMockBackend(10003);
        UtFrameUtils.addMockBackend(10004);
        Config.enable_strict_storage_medium_check = true;
        Config.enable_auto_tablet_distribution = true;
        Config.enable_experimental_rowstore = true;
        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(connectContext);
        // create database
        String createDbStmtStr = "create database test;";
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseStmtWithNewParser(createDbStmtStr, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().createDb(createDbStmt.getFullDbName());
        UtFrameUtils.setUpForPersistTest();
        starRocksAssert.useDatabase("test");
    }

    private static void createTable(String sql) throws Exception {
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        StarRocksAssert.utCreateTableWithRetry(createTableStmt);
    }

    private static void alterTableWithNewParser(String sql) throws Exception {
        AlterTableStmt alterTableStmt = (AlterTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().alterTable(connectContext, alterTableStmt);
    }

    @Test
    public void testNotSpecifyReplicateNum() {
        assertThrows(DdlException.class, () -> createTable(
                    "CREATE TABLE test.`duplicate_table_with_null` ( `k1`  date, `k2`  datetime,`k3`  " +
                            "char(20), `k4`  varchar(20), `k5`  boolean, `k6`  tinyint, `k7`  smallint, " +
                            "`k8`  int, `k9`  bigint, `k10` largeint, `k11` float, `k12` double, " +
                            "`k13` decimal(27,9)) DUPLICATE KEY(`k1`, `k2`, `k3`, `k4`, `k5`) PARTITION BY " +
                            "time_slice(k2, interval 1 hour) DISTRIBUTED BY HASH(`k1`, `k2`, `k3`); "
            ));
    }

    @Test
    public void testCreateUnsupportedType() {
        assertThrows(SemanticException.class, () -> createTable(
                    "CREATE TABLE test.ods_warehoused (\n" +
                            " warehouse_id                                bigint(20)                 COMMENT        ''\n" +
                            ",company_id                                        bigint(20)                 COMMENT        ''\n" +
                            ",company_name                                string                        COMMENT        ''\n" +
                            ",is_sort_express_by_cost        tinyint(1)                COMMENT        ''\n" +
                            ",is_order_intercepted                tinyint(1)                COMMENT        ''\n" +
                            ",intercept_time_type                tinyint(3)                 COMMENT        ''\n" +
                            ",intercept_time                                time                        COMMENT        ''\n" +
                            ",intercept_begin_time                time                        COMMENT        ''\n" +
                            ",intercept_end_time                        time                        COMMENT        ''\n" +
                            ")\n" +
                            "PRIMARY KEY(warehouse_id)\n" +
                            "COMMENT \"\"\n" +
                            "DISTRIBUTED BY HASH(warehouse_id)\n" +
                            "PROPERTIES (\n" +
                            "\"replication_num\" = \"1\"\n" +
                            ");"
            ));
    }

    @Test
    public void testNormal() throws DdlException {

        ExceptionChecker.expectThrowsNoException(
                () -> createTable(
                        "CREATE TABLE test.case_insensitive (\n" +
                                "    A1 TINYINT,\n" +
                                "    A2 DATE\n" +
                                ") ENGINE=OLAP\n" +
                                "DUPLICATE KEY(A1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "PARTITION BY RANGE (a2) (\n" +
                                "START (\"2021-01-01\") END (\"2022-01-01\") EVERY (INTERVAL 1 year)\n" +
                                ")\n" +
                                "DISTRIBUTED BY HASH(A1) BUCKETS 20\n" +
                                "PROPERTIES(\"replication_num\" = \"1\");"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable(
                        "create table test.lp_tbl0\n" + "(k1 bigint, k2 varchar(16) not null)\n" + "duplicate key(k1)\n"
                                + "partition by list(k2)\n" + "(partition p1 values in (\"shanghai\",\"beijing\"))\n"
                                + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.lp_tbl1\n" + "(k1 bigint, k2 varchar(16) not null," +
                        " dt varchar(10) not null)\n duplicate key(k1)\n"
                        + "partition by list(k2,dt)\n" + "(partition p1 values in ((\"2022-04-01\", \"shanghai\")) )\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.list_lp_tbl1\n" + "(k1 bigint, k2 varchar(16)," +
                        " dt varchar(10))\n duplicate key(k1)\n"
                        + "partition by list(k2, dt)\n" + "(partition p1 values in ((\"2022-04-01\", \"shanghai\")) )\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.lp_tbl2\n" + "(k1 bigint, k2 varchar(16), dt varchar(10))\n" +
                        "duplicate key(k1)\n"
                        + "partition by range(k1)\n" + "(partition p1 values [(\"1\"), (MAXVALUE)) )\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.tbl1\n" + "(k1 int, k2 int)\n" + "duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1'); "));

        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.tbl2\n" + "(k1 int, k2 int)\n"
                + "duplicate key(k1)\n" + "partition by range(k2)\n" + "(partition p1 values less than(\"10\"))\n"
                + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1'); "));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.tbl3\n" + "(k1 varchar(40), k2 int)\n" + "duplicate key(k1)\n"
                        + "partition by range(k2)\n" + "(partition p1 values less than(\"10\"))\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.tbl4\n" + "(k1 varchar(40), k2 int, v1 int sum)\n"
                        + "partition by range(k2)\n" + "(partition p1 values less than(\"10\"))\n"
                        + "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.tbl5\n" + "(k1 varchar(40), k2 int, v1 int sum)\n" + "aggregate key(k1,k2)\n"
                        + "partition by range(k2)\n" + "(partition p1 values less than(\"10\"))\n"
                        + "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.tbl6\n" + "(k1 varchar(40), k2 int, k3 int)\n" + "duplicate key(k1, k2, k3)\n"
                        + "partition by range(k2)\n" + "(partition p1 values less than(\"10\"))\n"
                        + "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tbl7\n" + "(k1 varchar(40), k2 int)\n"
                        + "partition by range(k2)\n" + "(partition p1 values less than(\"10\"))\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1');"));

        ConfigBase.setMutableConfig("enable_strict_storage_medium_check", "false", false, "");
        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tb7(key1 int, key2 varchar(10)) \n"
                        +
                        "distributed by hash(key1) buckets 1 properties('replication_num' = '1', 'storage_medium' = 'ssd');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tb8(key1 int, key2 varchar(10)) \n"
                        + "distributed by hash(key1) buckets 1 \n"
                        + "properties('replication_num' = '1', 'compression' = 'lz4_frame');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tb9(key1 int, key2 varchar(10)) \n"
                        + "distributed by hash(key1) buckets 1 \n"
                        + "properties('replication_num' = '1', 'compression' = 'lz4');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tb10(key1 int, key2 varchar(10)) \n"
                        + "distributed by hash(key1) buckets 1 \n"
                        + "properties('replication_num' = '1', 'compression' = 'zstd');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tb11(key1 int, key2 varchar(10)) \n"
                        + "distributed by hash(key1) buckets 1 \n"
                        + "properties('replication_num' = '1', 'compression' = 'zlib');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("create table test.tb12(col1 bigint AUTO_INCREMENT, \n"
                        + "col2 varchar(10)) \n"
                        + "Primary KEY (col1) distributed by hash(col1) buckets 1 \n"
                        + "properties('replication_num' = '1', 'replicated_storage' = 'true');"));

        ExceptionChecker
                .expectThrowsNoException(
                        () -> createTable("create table test.tb13(col1 bigint, col2 bigint AUTO_INCREMENT) \n"
                                + "Primary KEY (col1) distributed by hash(col1) buckets 1 \n"
                                + "properties('replication_num' = '1', 'replicated_storage' = 'true');"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("CREATE TABLE test.full_width_space (\n" +
                        "    datekey DATE,\n" +
                        "    site_id INT,\n" +
                        "    city_code SMALLINT,\n" +
                        "    user_name VARCHAR(32),\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "ENGINE=olap\n" +
                        "DUPLICATE KEY(datekey, site_id, city_code, user_name)\n" +
                        "PARTITION BY RANGE (datekey) (\n" +
                        "　START (\"2019-01-01\") END (\"2021-01-01\") EVERY (INTERVAL 1 YEAR)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(site_id) BUCKETS 10\n" +
                        "PROPERTIES (\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");"));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("CREATE TABLE test.dynamic_partition_without_prefix (\n" +
                        "event_day DATE,\n" +
                        "site_id INT DEFAULT '10',\n" +
                        "city_code VARCHAR(\n" +
                        "100\n" +
                        "),\n" +
                        "user_name VARCHAR(\n" +
                        "32\n" +
                        ") DEFAULT '',\n" +
                        "pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY RANGE(event_day)(\n" +
                        "PARTITION p20200321 VALUES LESS THAN (\"2020-03-22\"),\n" +
                        "PARTITION p20200322 VALUES LESS THAN (\"2020-03-23\"),\n" +
                        "PARTITION p20200323 VALUES LESS THAN (\"2020-03-24\"),\n" +
                        "PARTITION p20200324 VALUES LESS THAN (\"2020-03-25\")\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id)\n" +
                        "PROPERTIES(\n" +
                        "\t\"replication_num\" = \"1\",\n" +
                        "    \"dynamic_partition.enable\" = \"true\",\n" +
                        "    \"dynamic_partition.time_unit\" = \"DAY\",\n" +
                        "    \"dynamic_partition.start\" = \"-3\",\n" +
                        "    \"dynamic_partition.end\" = \"3\",\n" +
                        "    \"dynamic_partition.history_partition_num\" = \"0\"\n" +
                        ");"));

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable tbl6 = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "tbl6");
        Assertions.assertTrue(tbl6.getColumn("k1").isKey());
        Assertions.assertTrue(tbl6.getColumn("k2").isKey());
        Assertions.assertTrue(tbl6.getColumn("k3").isKey());

        OlapTable tbl7 = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "tbl7");
        Assertions.assertTrue(tbl7.getColumn("k1").isKey());
        Assertions.assertFalse(tbl7.getColumn("k2").isKey());
        Assertions.assertTrue(tbl7.getColumn("k2").getAggregationType() == AggregateType.NONE);
    }

    @Test
    public void testPartitionByExprDynamicPartition() {
        ExceptionChecker
                .expectThrowsNoException(() -> createTable("CREATE TABLE test.partition_str2date (\n" +
                        "        create_time varchar(100),\n" +
                        "        sku_id varchar(100),\n" +
                        "        total_amount decimal,\n" +
                        "        id int\n" +
                        ")\n" +
                        "PARTITION BY RANGE(str2date(create_time, '%Y-%m-%d %H:%i:%s'))(\n" +
                        "START (\"2021-01-01\") END (\"2021-01-10\") EVERY (INTERVAL 1 DAY)\n" +
                        ")\n" +
                        "PROPERTIES(\n" +
                        "\t\"replication_num\" = \"1\",\n" +
                        "    \"dynamic_partition.enable\" = \"true\",\n" +
                        "    \"dynamic_partition.time_unit\" = \"DAY\",\n" +
                        "    \"dynamic_partition.start\" = \"-3\",\n" +
                        "    \"dynamic_partition.end\" = \"3\",\n" +
                        "    \"dynamic_partition.prefix\" = \"p\",\n" +
                        "    \"dynamic_partition.buckets\" = \"32\",\n" +
                        "    \"dynamic_partition.history_partition_num\" = \"0\"\n" +
                        ");"));
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        Table str2dateTable = db.getTable("partition_str2date");
        Assertions.assertTrue(DynamicPartitionUtil.isDynamicPartitionTable(str2dateTable));

        ExceptionChecker
                .expectThrowsNoException(() -> createTable("CREATE TABLE test.partition_from_unixtime (\n" +
                        "        create_time bigint,\n" +
                        "        sku_id varchar(100),\n" +
                        "        total_amount decimal,\n" +
                        "        id int\n" +
                        ")\n" +
                        "PARTITION BY RANGE(from_unixtime(create_time))(\n" +
                        "START (\"2021-01-01\") END (\"2021-01-10\") EVERY (INTERVAL 1 DAY)\n" +
                        ")\n" +
                        "PROPERTIES(\n" +
                        "\t\"replication_num\" = \"1\",\n" +
                        "    \"dynamic_partition.enable\" = \"true\",\n" +
                        "    \"dynamic_partition.time_unit\" = \"DAY\",\n" +
                        "    \"dynamic_partition.start\" = \"-3\",\n" +
                        "    \"dynamic_partition.end\" = \"3\",\n" +
                        "    \"dynamic_partition.prefix\" = \"p\",\n" +
                        "    \"dynamic_partition.buckets\" = \"32\",\n" +
                        "    \"dynamic_partition.history_partition_num\" = \"0\"\n" +
                        ");"));
        Table fromUnixtimeTable = db.getTable("partition_from_unixtime");
        Assertions.assertTrue(DynamicPartitionUtil.isDynamicPartitionTable(fromUnixtimeTable));
    }

    @Test
    public void testAbnormal() throws DdlException {
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "FLOAT column can not be distribution column",
                () -> createTable("create table test.atbl1\n" + "(k1 int, k2 float)\n" + "duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1'); "));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Invalid partition column 'k3': invalid data type FLOAT",
                () -> createTable("create table test.atbl3\n" + "(k1 int, k2 int, k3 float)\n" + "duplicate key(k1)\n"
                        + "partition by range(k3)\n" + "(partition p1 values less than(\"10\"))\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1'); "));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Varchar should not in the middle of short keys",
                () -> createTable("create table test.atbl3\n" + "(k1 varchar(40), k2 int, k3 int)\n"
                        + "duplicate key(k1, k2, k3)\n" + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1', 'short_key' = '3');"));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class, "Short key is too large. should less than: 3",
                () -> createTable("create table test.atbl4\n" + "(k1 int, k2 int, k3 int)\n"
                        + "duplicate key(k1, k2, k3)\n" + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1', 'short_key' = '4');"));

        ExceptionChecker
                .expectThrowsWithMsg(DdlException.class, "Failed to find enough hosts with storage " +
                                "medium HDD at all backends, number of replicas needed: 3",
                        () -> createTable("create table test.atbl5\n" + "(k1 int, k2 int, k3 int)\n"
                                + "duplicate key(k1, k2, k3)\n" + "distributed by hash(k1) buckets 1\n"
                                + "properties('replication_num' = '3');"));

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.atbl6\n" + "(k1 int, k2 int)\n" + "duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n" + "properties('replication_num' = '1'); "));

        ExceptionChecker
                .expectThrowsWithMsg(AnalysisException.class, "Table 'atbl6' already exists",
                        () -> createTable("create table test.atbl6\n" + "(k1 int, k2 int, k3 int)\n"
                                + "duplicate key(k1, k2, k3)\n" + "distributed by hash(k1) buckets 1\n"
                                + "properties('replication_num' = '1');"));

        ConfigBase.setMutableConfig("enable_strict_storage_medium_check", "true", false, "");
        ExceptionChecker
                .expectThrowsWithMsg(DdlException.class,
                        "Failed to find enough hosts with storage " +
                                "medium SSD at all backends, number of replicas needed: 1",
                        () -> createTable(
                                "create table test.tb7(key1 int, key2 varchar(10)) distributed by hash(key1) \n"
                                        + "buckets 1 properties('replication_num' = '1', 'storage_medium' = 'ssd');"));

        ExceptionChecker
                .expectThrowsWithMsg(DdlException.class, "unknown compression type: xxx",
                        () -> createTable("create table test.atbl8\n" + "(key1 int, key2 varchar(10))\n"
                                + "distributed by hash(key1) buckets 1\n"
                                + "properties('replication_num' = '1', 'compression' = 'xxx');"));

        ExceptionChecker
                .expectThrowsWithMsg(AnalysisException.class, "Getting analyzing error from line 1, " +
                                "column 24 to line 1, column 33. Detail message: The AUTO_INCREMENT column must be BIGINT.",
                        () -> createTable("create table test.atbl9(col1 int AUTO_INCREMENT, col2 varchar(10)) \n"
                                + "Primary KEY (col1) distributed by hash(col1) buckets 1 \n"
                                + "properties('replication_num' = '1', 'replicated_storage' = 'true');"));

        ExceptionChecker
                .expectThrowsWithMsg(AnalysisException.class, "Getting syntax error at line 1, column 25. " +
                                "Detail message: AUTO_INCREMENT column col1 must be NOT NULL.",
                        () -> createTable(
                                "create table test.atbl10(col1 bigint NULL AUTO_INCREMENT, col2 varchar(10)) \n"
                                        + "Primary KEY (col1) distributed by hash(col1) buckets 1 \n"
                                        + "properties('replication_num' = '1', 'replicated_storage' = 'true');"));

        ExceptionChecker
                .expectThrowsWithMsg(AnalysisException.class,
                        "More than one AUTO_INCREMENT column defined in CREATE TABLE Statement.",
                        () -> createTable(
                                "create table test.atbl11(col1 bigint AUTO_INCREMENT, col2 bigint AUTO_INCREMENT) \n"
                                        + "Primary KEY (col1) distributed by hash(col1) buckets 1 \n"
                                        + "properties('replication_num' = '1', 'replicated_storage' = 'true');"));

        ExceptionChecker
                .expectThrowsWithMsg(DdlException.class, "Table with AUTO_INCREMENT column must use Replicated Storage",
                        () -> createTable("create table test.atbl12(col1 bigint AUTO_INCREMENT, col2 varchar(10)) \n"
                                + "Primary KEY (col1) distributed by hash(col1) buckets 1 \n"
                                + "properties('replication_num' = '1', 'replicated_storage' = 'FALSE');"));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class, "Unknown properties: {wrong_key=value}",
                () -> createTable("create table test.atbl13 (k1 int, k2 int) duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n"
                        + "properties('replication_num' = '1', 'wrong_key' = 'value'); "));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Unknown properties: {wrong_key=value}",
                () -> createTable("create table test.atbl14 (k1 int, k2 int, k3 float) duplicate key(k1)\n"
                        + "partition by range(k1) (partition p1 values less than(\"10\") ('wrong_key' = 'value'))\n"
                        + "distributed by hash(k2) buckets 1 properties('replication_num' = '1'); "));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Illegal expression type for Generated Column "
                        + "Column Type: INT, Expression Type: DOUBLE",
                () -> createTable("CREATE TABLE test.atbl15 ( id BIGINT NOT NULL,  array_data ARRAY<int> NOT NULL, \n"
                        + "mc INT AS (array_avg(array_data)) ) Primary KEY (id) \n"
                        + "DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Generated Column must be nullable column.",
                () -> createTable("CREATE TABLE test.atbl16 ( id BIGINT NOT NULL,  array_data ARRAY<int> NOT NULL, \n"
                        + "mc DOUBLE NOT NULL AS (array_avg(array_data)) ) \n"
                        + "Primary KEY (id) DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES"
                        + " ('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Unexpected input 'AS', " +
                        "the most similar input is {',', ')'}.",
                () -> createTable("CREATE TABLE test.atbl17 ( id BIGINT NOT NULL,  array_data ARRAY<int> NOT NULL, \n"
                        + "mc DOUBLE AUTO_INCREMENT AS (array_avg(array_data)) ) \n"
                        + "Primary KEY (id) DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES"
                        + "('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Unexpected input 'AS', " +
                        "the most similar input is {',', ')'}.",
                () -> createTable("CREATE TABLE test.atbl18 ( id BIGINT NOT NULL,  array_data ARRAY<int> NOT NULL, \n"
                        + "mc DOUBLE DEFAULT '1.0' AS (array_avg(array_data)) ) \n"
                        + "Primary KEY (id) DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES"
                        + "('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Expression can not refers to AUTO_INCREMENT columns",
                () -> createTable("CREATE TABLE test.atbl19 ( id BIGINT NOT NULL,  incr BIGINT AUTO_INCREMENT, \n"
                        + "array_data ARRAY<int> NOT NULL, mc BIGINT AS (incr) )\n"
                        + "Primary KEY (id) DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES"
                        + "('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Expression can not refers to other generated columns",
                () -> createTable("CREATE TABLE test.atbl20 ( id BIGINT NOT NULL,  array_data ARRAY<int> NOT NULL, \n"
                        + "mc DOUBLE AS (array_avg(array_data)), \n"
                        + "mc_1 DOUBLE AS (mc) ) Primary KEY (id) \n"
                        + "DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Generated Column don't support aggregation function",
                () -> createTable("CREATE TABLE test.atbl21 ( id BIGINT NOT NULL,  array_data ARRAY<int> NOT NULL, \n"
                        + "mc BIGINT AS (sum(id)) ) \n"
                        + "Primary KEY (id) DISTRIBUTED BY HASH(id) BUCKETS 7 PROPERTIES \n"
                        + "('replication_num' = '1');\n"));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Unknown properties: {datacache.enable=true, asd=true}",
                () -> createTable("CREATE TABLE test.demo (k0 tinyint NOT NULL, k1 date NOT NULL, k2 int NOT NULL," +
                        " k3 datetime not NULL, k4 bigint not NULL, k5 largeint not NULL) \n" +
                        "ENGINE = OLAP \n" +
                        "PRIMARY KEY( k0, k1, k2) \n" +
                        "PARTITION BY RANGE (k1) (START (\"1970-01-01\") END (\"2022-09-30\") " +
                        "EVERY (INTERVAL 60 day)) DISTRIBUTED BY HASH(k0) BUCKETS 1 " +
                        "PROPERTIES (\"replication_num\"=\"1\",\"enable_persistent_index\" = \"true\"," +
                        "\"datacache.enable\" = \"true\",\"asd\" = \"true\");"));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Unknown properties: {abc=def}",
                () -> createTable("CREATE TABLE test.lake_table\n" +
                        "(\n" +
                        "    k1 DATE,\n" +
                        "    k2 INT,\n" +
                        "    k3 SMALLINT,\n" +
                        "    v1 VARCHAR(2048),\n" +
                        "    v2 DATETIME DEFAULT \"2014-02-04 15:36:00\"\n" +
                        ")\n" +
                        "DUPLICATE KEY(k1, k2, k3)\n" +
                        "PARTITION BY RANGE (k1, k2, k3)\n" +
                        "(\n" +
                        "    PARTITION p1 VALUES [(\"2014-01-01\", \"10\", \"200\"), (\"2014-01-01\", \"20\", \"300\")),\n" +
                        "    PARTITION p2 VALUES [(\"2014-06-01\", \"100\", \"200\"), (\"2014-07-01\", \"100\", \"300\"))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 32\n" +
                        "PROPERTIES ( \"replication_num\" = \"1\", \"abc\" = \"def\");"));

        ExceptionChecker.expectThrowsWithMsg(SemanticException.class,
                "Date type partition does not support dynamic partitioning granularity of hour",
                () -> createTable("CREATE TABLE test.test_hour_partition2 (\n" +
                        "  `event_day` date NULL COMMENT \"\",\n" +
                        "  `site_id` int(11) NULL DEFAULT \"10\" COMMENT \"\",\n" +
                        "  `city_code` varchar(100) NULL COMMENT \"\",\n" +
                        "  `user_name` varchar(32) NULL DEFAULT \"\" COMMENT \"\",\n" +
                        "  `pv` bigint(20) NULL DEFAULT \"0\" COMMENT \"\"\n" +
                        ") ENGINE=OLAP \n" +
                        "DUPLICATE KEY(`event_day`, `site_id`, `city_code`, `user_name`)\n" +
                        "PARTITION BY RANGE(`event_day`)\n" +
                        "()\n" +
                        "DISTRIBUTED BY HASH(`event_day`, `site_id`) BUCKETS 32 \n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\",\n" +
                        "\"dynamic_partition.enable\" = \"true\",\n" +
                        "\"dynamic_partition.time_unit\" = \"HOUR\",\n" +
                        "\"dynamic_partition.time_zone\" = \"Asia/Shanghai\",\n" +
                        "\"dynamic_partition.start\" = \"-1\",\n" +
                        "\"dynamic_partition.end\" = \"10\",\n" +
                        "\"dynamic_partition.prefix\" = \"p\",\n" +
                        "\"dynamic_partition.buckets\" = \"3\",\n" +
                        "\"dynamic_partition.history_partition_num\" = \"0\",\n" +
                        "\"in_memory\" = \"false\",\n" +
                        "\"storage_format\" = \"DEFAULT\",\n" +
                        "\"enable_persistent_index\" = \"true\",\n" +
                        "\"compression\" = \"LZ4\"\n" +
                        ");"));
    }

    @Test
    public void testCreateJsonTable() {
        // success
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.json_tbl1\n" +
                        "(k1 int, j json)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.json_tbl2\n" +
                        "(k1 int, j json, j1 json, j2 json)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.json_tbl3\n"
                        + "(k1 int, k2 json)\n"
                        + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.json_tbl4 \n" +
                "(k1 int(40), j json, j1 json, j2 json)\n" +
                "unique key(k1)\n" +
                "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.json_tbl5 \n" +
                "(k1 int(40), j json, j1 json, j2 json)\n" +
                "primary key(k1)\n" +
                "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        // failed
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Invalid data type of key column 'k2': 'JSON'",
                () -> createTable("create table test.json_tbl0\n"
                        + "(k1 int, k2 json)\n"
                        + "duplicate key(k1, k2)\n"
                        + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "JSON column can not be distribution column",
                () -> createTable("create table test.json_tbl0\n"
                        + "(k1 int, k2 json)\n"
                        + "duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Column[j] type[JSON] cannot be a range partition key",
                () -> createTable("create table test.json_tbl0\n" +
                        "(k1 int(40), j json, j1 json, j2 json)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1, j)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
    }

    /**
     * Disable json on unique/primary/aggregate key
     */
    @Test
    public void testAlterJsonTable() {
        // use json as bloomfilter
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_json_bloomfilter (\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20),\n" +
                        "k3 JSON\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ")"
        ));
        ExceptionChecker.expectThrowsWithMsg(AlterJobException.class,
                "Invalid bloom filter column 'k3': unsupported type JSON",
                () -> alterTableWithNewParser(
                        "ALTER TABLE test.t_json_bloomfilter set (\"bloom_filter_columns\"= \"k3\");"));

        // Modify column in unique key
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_json_unique_key (\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "UNIQUE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ")"
        ));
        // Add column in unique key
        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.t_json_unique_key ADD COLUMN k3 JSON"));

        // Add column in primary key
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_json_primary_key (\n" +
                        "k1 INT,\n" +
                        "k2 INT\n" +
                        ") ENGINE=OLAP\n" +
                        "PRIMARY KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"
        ));
        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.t_json_primary_key ADD COLUMN k3 JSON"));
    }

    @Test
    public void testCreateTableWithoutDistribution() {
        ConnectContext.get().getSessionVariable().setAllowDefaultPartition(true);

        ExceptionChecker.expectThrowsNoException(
                () -> createTable("create table test.tmp1\n" + "(k1 int, k2 int)\n"));
        ExceptionChecker.expectThrowsNoException(
                () -> createTable(
                        "create table test.tmp2\n" + "(k1 int, k2 float) PROPERTIES(\"replication_num\" = \"1\");\n"));
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Data type of first column cannot be HLL",
                () -> createTable("create table test.tmp3\n" + "(k1 hll, k2 float)\n"));
    }

    @Test
    public void testCreateTableWithReserveColumn() {
        Config.allow_system_reserved_names = true;
        ExceptionChecker.expectThrowsWithMsg(DdlException.class, "Column name '__op' is reserved for primary key table",
                () -> createTable(
                "CREATE TABLE test.test_op (\n" +
                        "k1 INT,\n" +
                        "__op INT\n" +
                        ") ENGINE=OLAP\n" +
                        "PRIMARY KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class, "Column name '__row' is reserved for primary key table",
                        () -> createTable(
                        "CREATE TABLE test.test_row (\n" +
                                "k1 INT,\n" +
                                "__row INT\n" +
                                ") ENGINE=OLAP\n" +
                                "PRIMARY KEY(k1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                                "PROPERTIES (\n" +
                                "\"replication_num\" = \"1\"\n" +
                                ");"));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class, "Column name '__ROW' is reserved for primary key table",
                        () -> createTable(
                        "CREATE TABLE test.test_row (\n" +
                                "k1 INT,\n" +
                                "__ROW INT\n" +
                                ") ENGINE=OLAP\n" +
                                "PRIMARY KEY(k1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                                "PROPERTIES (\n" +
                                "\"replication_num\" = \"1\"\n" +
                                ");"));

        Config.allow_system_reserved_names = false;
    }

    @Test
    public void testCreateSumAgg() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        starRocksAssert.withTable("CREATE TABLE aggregate_table_sum\n" +
                "(\n" +
                "    id_int INT,\n" +
                "    sum_decimal decimal(5, 4) SUM DEFAULT '0',\n" +
                "    sum_bigint bigint SUM DEFAULT '0'\n" +
                ")\n" +
                "AGGREGATE KEY(id_int)\n" +
                "DISTRIBUTED BY HASH(id_int) BUCKETS 10\n" +
                "PROPERTIES(\"replication_num\" = \"1\");");
        final Table table = starRocksAssert.getCtx().getGlobalStateMgr().getLocalMetastore().getDb(connectContext.getDatabase())
                .getTable("aggregate_table_sum");
        String columns = table.getColumns().toString();
        System.out.println("columns = " + columns);
        Assertions.assertTrue(columns.contains("`sum_decimal` decimal(38, 4) SUM"));
        Assertions.assertTrue(columns.contains("`sum_bigint` bigint(20) SUM "));
    }

    @Test
    public void testDecimal() throws Exception {
        String sql = "CREATE TABLE create_decimal_tbl\n" +
                "(\n" +
                "    c1 decimal(38, 1),\n" +
                "    c2 numeric(38, 1),\n" +
                "    c3 number(38, 1) \n" +
                ")\n" +
                "DUPLICATE KEY(c1)\n" +
                "DISTRIBUTED BY HASH(c1) BUCKETS 1\n" +
                "PROPERTIES(\"replication_num\" = \"1\");";
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
    }

    @Test
    public void testCreateSumSmallTypeAgg() {
        assertThrows(AnalysisException.class, () -> {
            StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
            starRocksAssert.useDatabase("test");
            starRocksAssert.withTable("CREATE TABLE aggregate_table_sum\n" +
                    "(\n" +
                    "    id_int INT,\n" +
                    "    sum_int int SUM DEFAULT '0',\n" +
                    "    sum_smallint smallint SUM DEFAULT '0',\n" +
                    "    sum_tinyint tinyint SUM DEFAULT '0'\n" +
                    ")\n" +
                    "AGGREGATE KEY(id_int)\n" +
                    "DISTRIBUTED BY HASH(id_int) BUCKETS 10\n" +
                    "PROPERTIES(\"replication_num\" = \"1\");");
        });
    }

    @Test
    public void testLongColumnName() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql = "CREATE TABLE long_column_table (oh_my_gosh_this_is_a_long_column_name_look_at_it_it_has_more_" +
                "than_64_chars VARCHAR(100)) DISTRIBUTED BY HASH(oh_my_gosh_this_is_a_long_column_name_look_at_it_it_" +
                "has_more_than_64_chars) BUCKETS 8 PROPERTIES(\"replication_num\" = \"1\");";
        starRocksAssert.withTable(sql);
        final Table table = starRocksAssert.getCtx().getGlobalStateMgr().getLocalMetastore().getDb(connectContext.getDatabase())
                .getTable("long_column_table");
        Assertions.assertEquals(1, table.getColumns().size());
        Assertions.assertNotNull(
                table.getColumn("oh_my_gosh_this_is_a_long_column_name_look_at_it_it_has_more_than_64_chars"));
    }

    @Test
    public void testCreateTableDefaultCurrentTimestamp() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql = "CREATE TABLE `test_create_default_current_timestamp` (\n" +
                "    k1 int,\n" +
                "    ts datetime NOT NULL DEFAULT CURRENT_TIMESTAMP\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ");";
        starRocksAssert.withTable(sql);
        final Table table = starRocksAssert.getCtx().getGlobalStateMgr().getLocalMetastore().getDb(connectContext.getDatabase())
                .getTable("test_create_default_current_timestamp");
        Assertions.assertEquals(2, table.getColumns().size());
    }

    @Test
    public void testCreateTableDefaultUUID() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql = "CREATE TABLE `test_create_default_uuid` (\n" +
                "    k1 int,\n" +
                "    uuid VARCHAR(36) NOT NULL DEFAULT (uuid())\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ");";
        starRocksAssert.withTable(sql);
        final Table table = starRocksAssert.getCtx().getGlobalStateMgr().getLocalMetastore().getDb(connectContext.getDatabase())
                .getTable("test_create_default_uuid");
        Assertions.assertEquals(2, table.getColumns().size());

        String sql2 = "CREATE TABLE `test_create_default_uuid_numeric` (\n" +
                "    k1 int,\n" +
                "    uuid LARGEINT NOT NULL DEFAULT (uuid_numeric())\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ");";
        starRocksAssert.withTable(sql2);

        final Table table2 = starRocksAssert.getCtx().getGlobalStateMgr().getLocalMetastore().getDb(connectContext.getDatabase())
                .getTable("test_create_default_uuid_numeric");
        Assertions.assertEquals(2, table2.getColumns().size());
    }

    @Test
    public void testCreateTableDefaultUUIDFailed() {
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Varchar type length must be greater than 36 for uuid function",
                () -> createTable("CREATE TABLE test.`test_default_uuid_size_not_enough` (\n" +
                        "    k1 int,\n" +
                        "    uuid VARCHAR(35) NOT NULL DEFAULT (uuid())\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`k1`)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                        "PROPERTIES (\n" +
                        "    \"replication_num\" = \"1\",\n" +
                        "    \"in_memory\" = \"false\"\n" +
                        ");"));
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Default function uuid() for type INT is not supported",
                () -> createTable("CREATE TABLE test.`test_default_uuid_type_not_match` (\n" +
                        "    k1 int,\n" +
                        "    uuid INT NOT NULL DEFAULT (uuid())\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`k1`)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                        "PROPERTIES (\n" +
                        "    \"replication_num\" = \"1\",\n" +
                        "    \"in_memory\" = \"false\"\n" +
                        ");"));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Default function uuid_numeric() for type VARCHAR(1) is not supported",
                () -> createTable("CREATE TABLE test.`test_default_uuid_type_not_match` (\n" +
                        "    k1 int,\n" +
                        "    uuid VARCHAR NOT NULL DEFAULT (uuid_numeric())\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`k1`)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                        "PROPERTIES (\n" +
                        "    \"replication_num\" = \"1\",\n" +
                        "    \"in_memory\" = \"false\"\n" +
                        ");"));
    }

    @Test
    public void testCreateTableWithLocation() throws Exception {
        Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");

        // add label to backend
        SystemInfoService systemInfoService = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo();
        System.out.println(systemInfoService.getBackends());
        List<Long> backendIds = systemInfoService.getBackendIds();
        Backend backend = systemInfoService.getBackend(backendIds.get(0));
        String modifyBackendPropSqlStr = "alter system modify backend '" + backend.getHost() +
                ":" + backend.getHeartbeatPort() + "' set ('" +
                AlterSystemStmtAnalyzer.PROP_KEY_LOCATION + "' = 'rack:rack1')";
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(modifyBackendPropSqlStr, connectContext),
                connectContext);

        // **test create table without location explicitly specified, and the location is set to default("*")
        createTable("CREATE TABLE test.`test_location_no_prop` (\n" +
                "    k1 int,\n" +
                "    k2 VARCHAR NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ");");

        OlapTable table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(testDb.getFullName(), "test_location_no_prop");
        Assertions.assertNotNull(table.getLocation());
        System.out.println(table.getLocation());
        Assertions.assertTrue(table.getLocation().containsKey("*"));

        // verify the location property in show create table result, should be "*"
        String showSql = "show create table test.`test_location_no_prop`";
        ShowCreateTableStmt showCreateTableStmt = (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(showSql,
                connectContext);

        ShowResultSet showResultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);
        System.out.println(showResultSet.getResultRows());
        Assertions.assertTrue(showResultSet.getResultRows().get(0).toString().contains("\"" +
                PropertyAnalyzer.PROPERTIES_LABELS_LOCATION + "\" = \"*\""));

        // remove the location property from backend
        modifyBackendPropSqlStr = "alter system modify backend '" + backend.getHost() +
                ":" + backend.getHeartbeatPort() + "' set ('" +
                AlterSystemStmtAnalyzer.PROP_KEY_LOCATION + "' = '')";
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(modifyBackendPropSqlStr, connectContext),
                connectContext);

        // **test create table with no backend has location property, and the table won't have location property either
        createTable("CREATE TABLE test.`test_location_no_backend_prop` (\n" +
                "    k1 int,\n" +
                "    k2 VARCHAR NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ");");
        table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(testDb.getFullName(), "test_location_no_backend_prop");
        Assertions.assertNull(table.getLocation());

        // verify the location property in show create table result, shouldn't exist
        showSql = "show create table test.`test_location_no_backend_prop`";
        showCreateTableStmt = (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(showSql,
                connectContext);
        showResultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);
        System.out.println(showResultSet.getResultRows());
        Assertions.assertFalse(showResultSet.getResultRows().get(0).toString().contains("\"" +
                PropertyAnalyzer.PROPERTIES_LABELS_LOCATION + "\" = \"*\""));

        // set 5 backends with location: key:a, key:b, key:c, key1:a, key_2:b
        UtFrameUtils.addMockBackend(12005);
        backendIds = systemInfoService.getBackendIds();
        String[] backendLocationProps = {"key:a", "key:b", "key:c", "key1:a", "key_2:b"};
        for (int i = 0; i < 5; i++) {
            backend = systemInfoService.getBackend(backendIds.get(i));
            modifyBackendPropSqlStr = "alter system modify backend '" + backend.getHost() +
                    ":" + backend.getHeartbeatPort() + "' set ('" +
                    AlterSystemStmtAnalyzer.PROP_KEY_LOCATION + "' = '" + backendLocationProps[i] + "')";
            DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(modifyBackendPropSqlStr, connectContext),
                    connectContext);
        }

        for (int i = 0; i < 5; i++) {
            backend = systemInfoService.getBackend(backendIds.get(i));
            System.out.println("backend " + backend.getId() + " location: " + backend.getLocation());
        }

        // **test create table with valid/invalid location property format
        String[] tableLocationProps = {"*", "*, *", "*, key : b,", "*, key: b, key:   c",
                "key : a, key: b, key: c", "key1: a, key_2: b, key1: *", "key1:a, key3  : b",
                "not", "", "*, a", "key: b c", "**", "*:*", "key:a,b,c"};
        String[] expectedAnalyzedProps = {"*", "*", null, "*",
                "key:a,key:b,key:c", "key1:*,key_2:b", null,
                null, "", null, null, null, null, null};
        for (int i = 0; i < tableLocationProps.length; i++) {
            String tableLocationProp = tableLocationProps[i];
            System.out.println(tableLocationProp);
            String expectedAnalyzedProp = expectedAnalyzedProps[i];
            String createTableSql = "CREATE TABLE test.`test_location_prop_" + i + "` (\n" +
                    "    k1 int,\n" +
                    "    k2 VARCHAR NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`k1`)\n" +
                    "COMMENT \"OLAP\"\n" +
                    "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"" + PropertyAnalyzer.PROPERTIES_LABELS_LOCATION + "\" = \"" + tableLocationProp + "\"\n" +
                    ");";
            if (expectedAnalyzedProp == null) {
                if (tableLocationProp.equals("key1:a, key3  : b")) {
                    ExceptionChecker.expectThrowsWithMsg(SemanticException.class,
                            "Cannot find any backend with location: key3:b",
                            () -> createTable(createTableSql));
                } else {
                    ExceptionChecker.expectThrowsWithMsg(SemanticException.class,
                            "Invalid location format: " + tableLocationProp,
                            () -> createTable(createTableSql));
                }
            } else {
                createTable(createTableSql);
                table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                        .getTable(testDb.getFullName(), "test_location_prop_" + i);
                if (tableLocationProp.isEmpty()) {
                    Assertions.assertNull(table.getLocation());
                    continue;
                }
                Assertions.assertNotNull(table.getLocation());
                System.out.println(table.getLocation());
                Assertions.assertEquals(PropertyAnalyzer.convertLocationMapToString(table.getLocation()),
                        expectedAnalyzedProp);

                // verify the location property in show create table result
                showSql = "show create table test.`test_location_prop_" + i + "`";
                showCreateTableStmt = (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(showSql,
                        connectContext);
                showResultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);
                System.out.println(showResultSet.getResultRows());
                Assertions.assertTrue(showResultSet.getResultRows().get(0).toString().contains("\"" +
                        PropertyAnalyzer.PROPERTIES_LABELS_LOCATION + "\" = \"" + expectedAnalyzedProp + "\""));
            }
        }

        // clean: remove backend 12005
        backend = systemInfoService.getBackend(12005);
        systemInfoService.dropBackend(backend);
    }

    /**
     * Test persist into image and log and recover from image and log
     */
    @Test
    public void testCreateTableLocationPropPersist() throws Exception {
        // add label to backend
        SystemInfoService systemInfoService = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo();
        System.out.println(systemInfoService.getBackends());
        List<Long> backendIds = systemInfoService.getBackendIds();
        Backend backend = systemInfoService.getBackend(backendIds.get(0));
        String modifyBackendPropSqlStr = "alter system modify backend '" + backend.getHost() +
                ":" + backend.getHeartbeatPort() + "' set ('" +
                AlterSystemStmtAnalyzer.PROP_KEY_LOCATION + "' = 'rack:rack1')";
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(modifyBackendPropSqlStr, connectContext),
                connectContext);

        UtFrameUtils.PseudoJournalReplayer.resetFollowerJournalQueue();
        UtFrameUtils.PseudoImage initialImage = new UtFrameUtils.PseudoImage();
        GlobalStateMgr.getCurrentState().getLocalMetastore().save(initialImage.getImageWriter());

        createTable("CREATE TABLE test.`test_location_persist_t1` (\n" +
                "    k1 int,\n" +
                "    k2 VARCHAR NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(`k1`) BUCKETS 2\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"" + PropertyAnalyzer.PROPERTIES_LABELS_LOCATION + "\" = \"rack:*\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ");");

        // make final image
        UtFrameUtils.PseudoImage finalImage = new UtFrameUtils.PseudoImage();
        GlobalStateMgr.getCurrentState().getLocalMetastore().save(finalImage.getImageWriter());

        // ** test replay from edit log
        LocalMetastore localMetastoreFollower = new LocalMetastore(GlobalStateMgr.getCurrentState(), null, null);
        localMetastoreFollower.load(initialImage.getMetaBlockReader());
        CreateTableInfo info = (CreateTableInfo)
                UtFrameUtils.PseudoJournalReplayer.replayNextJournal(OperationType.OP_CREATE_TABLE_V2);
        localMetastoreFollower.replayCreateTable(info);
        OlapTable olapTable = (OlapTable) localMetastoreFollower.getDb("test")
                .getTable("test_location_persist_t1");
        System.out.println(olapTable.getLocation());
        Assertions.assertEquals(1, olapTable.getLocation().size());
        Assertions.assertTrue(olapTable.getLocation().containsKey("rack"));

        // ** test load from image(simulate restart)
        LocalMetastore localMetastoreLeader = new LocalMetastore(GlobalStateMgr.getCurrentState(), null, null);
        localMetastoreLeader.load(finalImage.getMetaBlockReader());
        olapTable = (OlapTable) localMetastoreLeader.getDb("test")
                .getTable("test_location_persist_t1");
        System.out.println(olapTable.getLocation());
        Assertions.assertEquals(1, olapTable.getLocation().size());
        Assertions.assertTrue(olapTable.getLocation().containsKey("rack"));
    }

    @Test
    public void testCreateVarBinaryTable() {
        // duplicate table
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.varbinary_tbl\n" +
                        "(k1 int, j varbinary(10))\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.varbinary_tbl1\n" +
                        "(k1 int, j varbinary)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.varbinary_tbl2\n" +
                        "(k1 int, j varbinary(1), j1 varbinary(10), j2 varbinary)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        // default table
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.varbinary_tbl3\n"
                        + "(k1 int, k2 varbinary)\n"
                        + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1');"));

        // unique key table
        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.varbinary_tbl4 \n" +
                "(k1 int(40), j varbinary, j1 varbinary(1), j2 varbinary(10))\n" +
                "unique key(k1)\n" +
                "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        // primary key table
        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.varbinary_tbl5 \n" +
                "(k1 int(40), j varbinary, j1 varbinary, j2 varbinary(10))\n" +
                "primary key(k1)\n" +
                "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        // failed
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Invalid data type of key column 'k2': 'VARBINARY'",
                () -> createTable("create table test.varbinary_tbl0\n"
                        + "(k1 int, k2 varbinary)\n"
                        + "duplicate key(k1, k2)\n"
                        + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "VARBINARY(10) column can not be distribution column",
                () -> createTable("create table test.varbinary_tbl0 \n"
                        + "(k1 int, k2 varbinary(10) )\n"
                        + "duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Column[j] type[VARBINARY] cannot be a range partition key",
                () -> createTable("create table test.varbinary_tbl0 \n" +
                        "(k1 int(40), j varbinary, j1 varbinary(20), j2 varbinary)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1, j)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
    }

    @Test
    public void testCreateBinaryTable() {
        // duplicate table
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.binary_tbl\n" +
                        "(k1 int, j binary(10))\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.binary_tbl1\n" +
                        "(k1 int, j binary)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.binary_tbl2\n" +
                        "(k1 int, j binary(1), j1 binary(10), j2 binary)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
        // default table
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.binary_tbl3\n"
                        + "(k1 int, k2 binary)\n"
                        + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1');"));

        // unique key table
        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.binary_tbl4 \n" +
                "(k1 int(40), j binary, j1 binary(1), j2 binary(10))\n" +
                "unique key(k1)\n" +
                "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        // primary key table
        ExceptionChecker.expectThrowsNoException(() -> createTable("create table test.binary_tbl5 \n" +
                "(k1 int(40), j binary, j1 binary, j2 binary(10))\n" +
                "primary key(k1)\n" +
                "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));

        // failed
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Invalid data type of key column 'k2': 'VARBINARY'",
                () -> createTable("create table test.binary_tbl0\n"
                        + "(k1 int, k2 binary)\n"
                        + "duplicate key(k1, k2)\n"
                        + "distributed by hash(k1) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "VARBINARY(10) column can not be distribution column",
                () -> createTable("create table test.binary_tbl0 \n"
                        + "(k1 int, k2 binary(10) )\n"
                        + "duplicate key(k1)\n"
                        + "distributed by hash(k2) buckets 1\n"
                        + "properties('replication_num' = '1');"));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Column[j] type[VARBINARY] cannot be a range partition key",
                () -> createTable("create table test.binary_tbl0 \n" +
                        "(k1 int(40), j binary, j1 binary(20), j2 binary)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1, j)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
    }

    /**
     * Disable varbinary on unique/primary/aggregate key
     */
    @Test
    public void testAlterVarBinaryTable() {
        // use json as bloomfilter
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_varbinary_bf(\n" +
                        "k1 INT,\n" +
                        "k2 INT,\n" +
                        "k3 VARBINARY\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ")"
        ));
        ExceptionChecker.expectThrowsWithMsg(AlterJobException.class,
                "Invalid bloom filter column 'k3': unsupported type VARBINARY",
                () -> alterTableWithNewParser(
                        "ALTER TABLE test.t_varbinary_bf set (\"bloom_filter_columns\"= \"k3\");"));

        // Modify column in unique key
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_varbinary_unique_key (\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "UNIQUE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ")"
        ));
        // Add column in unique key
        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.t_varbinary_unique_key ADD COLUMN k3 VARBINARY(12)"));

        // Add column in primary key
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_varbinary_primary_key (\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "PRIMARY KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"
        ));
        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.t_varbinary_primary_key ADD COLUMN k3 VARBINARY(21)"));
    }

    /**
     * Disable binary on unique/primary/aggregate key
     */
    @Test
    public void testAlterBinaryTable() {
        // use json as bloomfilter
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_binary_bf(\n" +
                        "k1 INT,\n" +
                        "k2 INT,\n" +
                        "k3 BINARY\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ")"
        ));
        ExceptionChecker.expectThrowsWithMsg(AlterJobException.class,
                "Invalid bloom filter column 'k3': unsupported type VARBINARY",
                () -> alterTableWithNewParser("ALTER TABLE test.t_binary_bf set (\"bloom_filter_columns\"= \"k3\");"));

        // Modify column in unique key
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_binary_unique_key (\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "UNIQUE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ")"
        ));
        // Add column in unique key
        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.t_binary_unique_key ADD COLUMN k3 BINARY(12)"));

        // Add column in primary key
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.t_binary_primary_key (\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "PRIMARY KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"
        ));
        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.t_binary_primary_key ADD COLUMN k3 VARBINARY(21)"));
    }

    @Test
    public void testCreateTableWithBinlogProperties() {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.binlog_table(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n," +
                        "\"binlog_max_size\" = \"100\"\n," +
                        "\"binlog_enable\" = \"true\"\n," +
                        "\"binlog_ttl_second\" = \"100\"\n" +
                        ");"
        ));

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable table =
                (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "binlog_table");
        Assertions.assertNotNull(table.getCurBinlogConfig());
        Assertions.assertTrue(table.isBinlogEnabled());

        long version = table.getBinlogVersion();
        Assertions.assertEquals(0, version);
        long binlogMaxSize = table.getCurBinlogConfig().getBinlogMaxSize();
        Assertions.assertEquals(100, binlogMaxSize);
        long binlogTtlSecond = table.getCurBinlogConfig().getBinlogTtlSecond();
        Assertions.assertEquals(100, binlogTtlSecond);

        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.binlog_table SET " +
                        "(\"binlog_enable\" = \"false\",\"binlog_max_size\" = \"200\")"));
        Assertions.assertFalse(table.isBinlogEnabled());
        Assertions.assertEquals(1, table.getBinlogVersion());
        Assertions.assertEquals(200, table.getCurBinlogConfig().getBinlogMaxSize());

    }

    @Test
    public void testCreateTableWithoutBinlogProperties() {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.not_binlog_table(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"
        ));

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(db.getFullName(), "not_binlog_table");

        Assertions.assertFalse(table.containsBinlogConfig());
        Assertions.assertFalse(table.isBinlogEnabled());

        ExceptionChecker.expectThrowsNoException(
                () -> alterTableWithNewParser("ALTER TABLE test.not_binlog_table SET " +
                        "(\"binlog_enable\" = \"true\",\"binlog_max_size\" = \"200\")"));
        Assertions.assertTrue(table.isBinlogEnabled());
        Assertions.assertEquals(0, table.getBinlogVersion());
        Assertions.assertEquals(200, table.getCurBinlogConfig().getBinlogMaxSize());
    }

    @Test
    public void testCreateTableWithConstraint() {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.parent_table1(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n," +
                        "\"unique_constraints\" = \"k1,k2\"\n" +
                        ");"
        ));

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable table =
                (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "parent_table1");

        Assertions.assertTrue(table.hasUniqueConstraints());
        List<UniqueConstraint> uniqueConstraint = table.getUniqueConstraints();
        Assertions.assertEquals(1, uniqueConstraint.size());
        Assertions.assertEquals(2, uniqueConstraint.get(0).getUniqueColumnNames(table).size());
        Assertions.assertEquals("k1", uniqueConstraint.get(0).getUniqueColumnNames(table).get(0));
        Assertions.assertEquals("k2", uniqueConstraint.get(0).getUniqueColumnNames(table).get(1));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.parent_table2(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n," +
                        "\"unique_constraints\" = \"k1;k2\"\n" +
                        ");"
        ));

        OlapTable table2 =
                (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "parent_table2");

        Assertions.assertTrue(table2.hasUniqueConstraints());
        List<UniqueConstraint> uniqueConstraint2 = table2.getUniqueConstraints();
        Assertions.assertEquals(2, uniqueConstraint2.size());
        Assertions.assertEquals(1, uniqueConstraint2.get(0).getUniqueColumnNames(table2).size());
        Assertions.assertEquals("k1", uniqueConstraint2.get(0).getUniqueColumnNames(table2).get(0));
        Assertions.assertEquals(1, uniqueConstraint2.get(1).getUniqueColumnNames(table2).size());
        Assertions.assertEquals("k2", uniqueConstraint2.get(1).getUniqueColumnNames(table2).get(0));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.parent_primary_key_table1(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "PRIMARY KEY(k1, k2)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1, k2) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.parent_unique_key_table1(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "UNIQUE KEY(k1, k2)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1, k2) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.parent_table3(\n" +
                        "_k1 INT,\n" +
                        "_k2 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(_k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(_k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n," +
                        "\"unique_constraints\" = \"_k1,_k2\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.base_table1(\n" +
                        "k1 INT,\n" +
                        "k2 VARCHAR(20),\n" +
                        "k3 INT,\n" +
                        "k4 VARCHAR(20),\n" +
                        "k5 INT,\n" +
                        "k6 VARCHAR(20),\n" +
                        "k7 INT,\n" +
                        "k8 VARCHAR(20),\n" +
                        "k9 INT,\n" +
                        "k10 VARCHAR(20)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(k1)\n" +
                        "COMMENT \"OLAP\"\n" +
                        "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\",\n" +
                        "\"foreign_key_constraints\" = \"(k3,k4) REFERENCES parent_table1(k1, k2);" +
                        " (k5, k6) REFERENCES parent_primary_key_table1(k1, k2 );" +
                        " (k9, k10) references parent_table3(_k1, _k2 );" +
                        " (k7, k8) REFERENCES parent_unique_key_table1(k1, k2 )\"\n" +
                        ");"
        ));

        // column types do not match
        ExceptionChecker.expectThrowsWithMsg(SemanticException.class,
                "column:k3 type does mot match referenced column:k2 type",
                () -> createTable(
                        "CREATE TABLE test.base_table2(\n" +
                                "k1 INT,\n" +
                                "k2 VARCHAR(20),\n" +
                                "k3 INT,\n" +
                                "k4 VARCHAR(20),\n" +
                                "k5 INT,\n" +
                                "k6 VARCHAR(20),\n" +
                                "k7 INT,\n" +
                                "k8 VARCHAR(20),\n" +
                                "k9 INT,\n" +
                                "k10 VARCHAR(20)\n" +
                                ") ENGINE=OLAP\n" +
                                "DUPLICATE KEY(k1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                                "PROPERTIES (\n" +
                                "\"replication_num\" = \"1\",\n" +
                                "\"foreign_key_constraints\" = \"(k3,k4) REFERENCES parent_table1(k2, k1)\"\n" +
                                ");"
                ));

        // key size does not match
        ExceptionChecker.expectThrowsWithMsg(SemanticException.class,
                "columns:[k1, k2] are not dup table:parent_table2's unique constraint",
                () -> createTable(
                        "CREATE TABLE test.base_table2(\n" +
                                "k1 INT,\n" +
                                "k2 VARCHAR(20),\n" +
                                "k3 INT,\n" +
                                "k4 VARCHAR(20),\n" +
                                "k5 INT,\n" +
                                "k6 VARCHAR(20),\n" +
                                "k7 INT,\n" +
                                "k8 VARCHAR(20)\n" +
                                ") ENGINE=OLAP\n" +
                                "DUPLICATE KEY(k1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                                "PROPERTIES (\n" +
                                "\"replication_num\" = \"1\",\n" +
                                "\"foreign_key_constraints\" = \"(k3,k4) REFERENCES parent_table2(k1, k2)\"\n" +
                                ");"
                ));

        ExceptionChecker.expectThrowsWithMsg(SemanticException.class,
                "invalid foreign key constraint:(k3,k4) REFERENCES parent_table2(k1)",
                () -> createTable(
                        "CREATE TABLE test.base_table2(\n" +
                                "k1 INT,\n" +
                                "k2 VARCHAR(20),\n" +
                                "k3 INT,\n" +
                                "k4 VARCHAR(20),\n" +
                                "k5 INT,\n" +
                                "k6 VARCHAR(20),\n" +
                                "k7 INT,\n" +
                                "k8 VARCHAR(20)\n" +
                                ") ENGINE=OLAP\n" +
                                "DUPLICATE KEY(k1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                                "PROPERTIES (\n" +
                                "\"replication_num\" = \"1\",\n" +
                                "\"foreign_key_constraints\" = \"(k3,k4) REFERENCES parent_table2(k1)\"\n" +
                                ");"
                ));

        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "Not support MAXVALUE in multi partition range values.",
                () -> createTable(
                        "create table test_multi_partition_max_value (\n" +
                                "f1 bigint, f2 date, f3 string, f4 bigint\n" +
                                ")\n" +
                                "partition by range(f1, f2, f4) (\n" +
                                "        partition p1 values less than('10', '2020-01-01', '100'),\n" +
                                "        partition p2 values less than('20', '2020-01-01', '200'),\n" +
                                "        partition p3 values less than(MAXVALUE)\n" +
                                ");"
                ));
    }

    @Test
    public void testAutomaticPartitionTableLimit() {

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE test.site_access_part_partition(\n" +
                        "    event_day DATE NOT NULL,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ") \n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('month', event_day)(\n" +
                        "    START (\"2023-05-01\") END (\"2023-05-03\") EVERY (INTERVAL 1 month)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES(\n" +
                        "    \"partition_live_number\" = \"3\",\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE test.site_access_interval_not_1 (\n" +
                        "    event_day DATE NOT NULL,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ") \n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('month', event_day)(\n" +
                        "    START (\"2023-05-01\") END (\"2023-10-01\") EVERY (INTERVAL 2 month)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES(\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE test.site_access_granularity_does_not_match (\n" +
                        "    event_day DATE NOT NULL,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ") \n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('month', event_day)(\n" +
                        "    START (\"2023-05-01\") END (\"2023-10-01\") EVERY (INTERVAL 1 day)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES(\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE test.site_access_granularity_does_not_match (\n" +
                        "    event_day DATE NOT NULL,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ") \n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('month', event_day)(\n" +
                        "    START (\"2023-05-01\") END (\"2023-10-01\") EVERY (INTERVAL 1 month)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES(\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE site_access_use_time_slice (\n" +
                        "    event_day datetime,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY time_slice(event_day, interval 1 day)(\n" +
                        "\tSTART (\"2023-05-01\") END (\"2023-05-03\") EVERY (INTERVAL 1 day)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES(\n" +
                        "    \"partition_live_number\" = \"3\",\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");"
        ));

    }

    @Test
    public void testCannotCreateOlapTable() {
        new MockUp<RunMode>() {
            @Mock
            public RunMode getCurrentRunMode() {
                return RunMode.SHARED_DATA;
            }
        };

        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Cannot create table without persistent volume in current run mode \"shared_data\"",
                () -> createTable(
                        "CREATE TABLE test.base_table2(\n" +
                                "k1 INT,\n" +
                                "k2 VARCHAR(20),\n" +
                                "k3 INT,\n" +
                                "k4 VARCHAR(20),\n" +
                                "k5 INT,\n" +
                                "k6 VARCHAR(20),\n" +
                                "k7 INT,\n" +
                                "k8 VARCHAR(20)\n" +
                                ") ENGINE=OLAP\n" +
                                "DUPLICATE KEY(k1)\n" +
                                "COMMENT \"OLAP\"\n" +
                                "DISTRIBUTED BY HASH(k1) BUCKETS 3\n" +
                                "PROPERTIES (\n" +
                                "\"storage_volume\" = \"local\"\n" +
                                ");"
                ));
    }

    @Test
    public void testCreateTableInSystemDb() {
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Can't create table 'goods' (errno: cannot create table in system database)",
                () -> createTable(
                        "CREATE TABLE information_schema.goods(\n" +
                                "    item_id1          INT,\n" +
                                "    item_name         STRING,\n" +
                                "    price             FLOAT\n" +
                                ") DISTRIBUTED BY HASH(item_id1)\n" +
                                "PROPERTIES(\"replication_num\" = \"1\");"
                ));
    }

    @Test
    public void testDropTableInSystemDb() {
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "cannot drop table in system database: information_schema",
                () -> starRocksAssert.dropTable("information_schema.tables")
        );
    }

    @Test

    public void testCreatePartitionByExprTable() {
        ExceptionChecker.expectThrowsNoException(
                () -> createTable(
                        "CREATE TABLE test.`bill_detail` (\n" +
                                "  `bill_code` varchar(200) NOT NULL DEFAULT \"\" COMMENT \"\"\n" +
                                ") ENGINE=OLAP \n" +
                                "PRIMARY KEY(`bill_code`)\n" +
                                "PARTITION BY RANGE(cast(substring(bill_code, 3) as bigint))\n" +
                                "(PARTITION p1 VALUES [('0'), ('5000000')),\n" +
                                "PARTITION p2 VALUES [('5000000'), ('10000000')),\n" +
                                "PARTITION p3 VALUES [('10000000'), ('15000000')),\n" +
                                "PARTITION p4 VALUES [('15000000'), ('20000000'))\n" +
                                ")\n" +
                                "DISTRIBUTED BY HASH(`bill_code`) BUCKETS 10 \n" +
                                "PROPERTIES (\n" +
                                "\"replication_num\" = \"1\",\n" +
                                "\"in_memory\" = \"false\",\n" +
                                "\"storage_format\" = \"DEFAULT\"\n" +
                                ");"
                ));

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE test.`bill_detail_1` (\n" +
                        "  `day` datetime \n" +
                        ") ENGINE=OLAP \n" +
                        "PRIMARY KEY(`day`)\n" +
                        "PARTITION BY RANGE(cast(substr(day, 1, 10) as datetime))\n" +
                        "(PARTITION p201704 VALUES LESS THAN (\"20170501\"),\n" +
                        "PARTITION p201705 VALUES LESS THAN (\"20170601\"),\n" +
                        "PARTITION p201706 VALUES LESS THAN (\"20170701\"))\n" +
                        "DISTRIBUTED BY HASH(`bill_code`) BUCKETS 10 \n" +
                        "PROPERTIES (\"replication_num\" = \"1\",\n" +
                        "\"in_memory\" = \"false\",\n" +
                        "\"storage_format\" = \"DEFAULT\");"
        ));

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE test.`bill_detail_2` (\n" +
                        "  `bill_code` varchar(200) NOT NULL DEFAULT \"\" COMMENT \"\"\n" +
                        ") ENGINE=OLAP \n" +
                        "PRIMARY KEY(`bill_code`)\n" +
                        "PARTITION BY RANGE(cast(substr(bill_code, '3', 13) as bigint))\n" +
                        "(PARTITION p1 VALUES [('0'), ('5000000')),\n" +
                        "PARTITION p2 VALUES [('5000000'), ('10000000')),\n" +
                        "PARTITION p3 VALUES [('10000000'), ('15000000')),\n" +
                        "PARTITION p4 VALUES [('15000000'), ('20000000')),\n" +
                        "PARTITION p999 VALUES[('2921712368983'), ('2921712368985'))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(`bill_code`) BUCKETS 10 \n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\",\n" +
                        "\"in_memory\" = \"false\",\n" +
                        "\"storage_format\" = \"DEFAULT\"\n" +
                        ");"
        ));

        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "CREATE TABLE test.`bill_detail_3` (\n" +
                        "  `bill_code` varchar(200) NOT NULL DEFAULT \"\" COMMENT \"\"\n" +
                        ") ENGINE=OLAP \n" +
                        "PRIMARY KEY(`bill_code`)\n" +
                        "PARTITION BY RANGE(cast(substr(bill_code, 3, '13') as bigint))\n" +
                        "(PARTITION p1 VALUES [('0'), ('5000000')),\n" +
                        "PARTITION p2 VALUES [('5000000'), ('10000000')),\n" +
                        "PARTITION p3 VALUES [('10000000'), ('15000000')),\n" +
                        "PARTITION p4 VALUES [('15000000'), ('20000000')),\n" +
                        "PARTITION p999 VALUES[('2921712368983'), ('2921712368985'))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(`bill_code`) BUCKETS 10 \n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\",\n" +
                        "\"in_memory\" = \"false\",\n" +
                        "\"storage_format\" = \"DEFAULT\"\n" +
                        ");"
        ));
    }

    @Test
    public void testCreateTextTable() {
        // duplicate tabl
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table test.text_tbl\n" +
                        "(k1 int, j text)\n" +
                        "duplicate key(k1)\n" +
                        "partition by range(k1)\n" +
                        "(partition p1 values less than(\"10\"))\n" +
                        "distributed by hash(k1) buckets 1\n" + "properties('replication_num' = '1');"));
    }

    @Test
    public void testCreateCrossDatabaseColocateTable() throws Exception {
        starRocksAssert.withDatabase("dwd");
        String sql1 = "CREATE TABLE dwd.dwd_site_scan_dtl_test (\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "sub_ship_id bigint(20) NOT NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(ship_id, sub_ship_id) COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(ship_id) BUCKETS 48\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"colocate_with\" = \"ship_id_public\",\n" +
                "\"in_memory\" = \"false\",\n" +
                "\"storage_format\" = \"DEFAULT\",\n" +
                "\"enable_persistent_index\" = \"true\",\n" +
                "\"replicated_storage\" = \"true\",\n" +
                "\"compression\" = \"LZ4\"\n" +
                ");";
        starRocksAssert.withTable(sql1);

        starRocksAssert.withDatabase("ods");
        String sql2 = "CREATE TABLE ods.reg_bill_info_test (\n" +
                "unit_tm datetime NOT NULL COMMENT \" \",\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "ins_db_tm datetime NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(unit_tm, ship_id)\n" +
                "DISTRIBUTED BY HASH(ship_id) BUCKETS 48\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"colocate_with\" = \"ship_id_public\",\n" +
                "\"in_memory\" = \"false\",\n" +
                "\"storage_format\" = \"DEFAULT\",\n" +
                "\"enable_persistent_index\" = \"true\",\n" +
                "\"compression\" = \"LZ4\"\n" +
                ");";
        starRocksAssert.withTable(sql2);

        List<List<String>> result = GlobalStateMgr.getCurrentState().getColocateTableIndex().getInfos();
        System.out.println(result);
        List<String> groupIds = new ArrayList<>();
        for (List<String> e : result) {
            if (e.get(1).contains("ship_id_public")) {
                groupIds.add(e.get(0));
            }
        }
        Assertions.assertEquals(2, groupIds.size());
        System.out.println(groupIds);
        // colocate groups in different db should have same `GroupId.grpId`
        Assertions.assertEquals(groupIds.get(0).split("\\.")[1], groupIds.get(1).split("\\.")[1]);
    }

    @Test
    public void testRandomColocateTable() {
        String sql1 = "CREATE TABLE dwd.dwd_site_scan_dtl_test (\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "sub_ship_id bigint(20) NOT NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(ship_id, sub_ship_id) COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY RANDOM " +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"colocate_with\" = \"ship_id_public\"" +
                ");";
        Assertions.assertThrows(AnalysisException.class, () -> starRocksAssert.withTable(sql1));
    }

    @Test
    public void testPrimaryKeyDisableInMemoryIndex() {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE test.disable_inmemory_index (\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "sub_ship_id bigint(20) NOT NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "PRIMARY KEY(ship_id) COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(ship_id) " +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"enable_persistent_index\" = \"false\"" +
                ");";
        ExceptionChecker.expectThrows(DdlException.class, () -> starRocksAssert.withTable(sql1));
    }

    @Test
    public void testPrimaryKeyNotSupportCoolDown() {
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Primary key table does not support storage medium cool down currently.",
                () -> createTable(
                        "CREATE TABLE test.`primary_table_not_support_cool_down`\n" +
                                "             ( `k1`  date, `k2`  datetime,`k3`  string, `k4`  varchar(20), " +
                                "`k5`  boolean, `k6`  tinyint, `k7`  smallint, `k8`  int, `k9`  bigint, " +
                                "`k10` largeint, `k11` float, `k12` double, `k13` decimal(27,9))\n" +
                                "             primary KEY(`k1`, `k2`, `k3`, `k4`, `k5`)\n" +
                                "             PARTITION BY range(k1)\n" +
                                "             (\n" +
                                "                 PARTITION p1 VALUES LESS THAN (\"2021-01-02\"),\n" +
                                "                 PARTITION p2 VALUES LESS THAN (\"2021-08-18\"),\n" +
                                "                 PARTITION p3 VALUES LESS THAN (\"2022-08-17\"),\n" +
                                "                 PARTITION p4 VALUES LESS THAN (\"2022-08-18\"),\n" +
                                "                 PARTITION p5 VALUES LESS THAN (\"2022-08-19\"),\n" +
                                "                 PARTITION p6 VALUES LESS THAN (\"2023-08-18\"),\n" +
                                "                 PARTITION p7 VALUES LESS THAN (\"2024-08-18\")\n" +
                                "             ) DISTRIBUTED BY HASH(`k1`, `k2`, `k3`)\n" +
                                "  PROPERTIES (\"storage_medium\" = \"SSD\", \"storage_cooldown_ttl\" = \"0 year\");"
                ));
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "List partition table does not support storage medium cool down currently.",
                () -> createTable(
                        "CREATE TABLE test.list_partition_table_not_support_cool_down (\n" +
                                "                    `k1`  date not null, `k2`  datetime,`k3`  char(20), " +
                                "`k4`  varchar(20), `k5`  boolean, `k6`  tinyint, `k7`  smallint, `k8`  int, " +
                                "`k9`  bigint, `k10` largeint, `k11` float, `k12` double, `k13` decimal(27,9)\n" +
                                "                )\n" +
                                "                DUPLICATE KEY(k1)\n" +
                                "                PARTITION BY LIST (k1) (\n" +
                                "                   PARTITION p1 VALUES IN (\"2020-01-01\",\"2020-01-02\"),\n" +
                                "                   PARTITION p2 VALUES IN (\"2021-01-01\")\n" +
                                "                )\n" +
                                "                DISTRIBUTED BY HASH(k1)\n" +
                                "    PROPERTIES (\"storage_medium\" = \"SSD\", \"storage_cooldown_ttl\" = \"0 day\");"
                ));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Only support partition is date type for storage medium cool down currently.",
                () -> createTable(
                        "CREATE TABLE test.t_partition_table_only_support_date (\n" +
                                "                        `k1` int, v1 int, v2 int\n" +
                                "                    )\n" +
                                "                    DUPLICATE KEY(k1)\n" +
                                "                    PARTITION BY range (k1) (\n" +
                                "                       PARTITION p1 VALUES less than (\"20200101\"),\n" +
                                "                       PARTITION p2 VALUES less than (\"20210101\")\n" +
                                "                    )\n" +
                                "                    DISTRIBUTED BY HASH(k1)\n" +
                                " PROPERTIES (\"storage_medium\" = \"SSD\", \"storage_cooldown_ttl\" = \"0 day\");"
                ));

        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Invalid data property. storage medium property is not found",
                () -> createTable(
                        "CREATE TABLE `cooldown_ttl_month1_table_with_null`\n" +
                                "        ( `k1`  date, `k2`  datetime,`k3`  string, `k4`  varchar(20), " +
                                "`k5`  boolean, `k6`  tinyint, `k7`  smallint, `k8`  int, `k9`  bigint, " +
                                "`k10` largeint, `k11` float, `k12` double, `k13` decimal(27,9))\n" +
                                "        unique KEY(`k1`, `k2`, `k3`, `k4`, `k5`)\n" +
                                "        PARTITION BY time_slice(k2, interval 1 month)\n" +
                                "        DISTRIBUTED BY HASH(`k1`, `k2`, `k3`)\n" +
                                "        PROPERTIES (\"storage_cooldown_ttl\" = \"1 month\");"
                ));
    }

    @Test
    public void testDuplicateTableNotSupportColumnWithRow() {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE test.dwd_site_scan_dtl_test (\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "sub_ship_id bigint(20) NOT NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(ship_id, sub_ship_id) COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(ship_id, sub_ship_id) " +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"storage_type\" = \"column_with_row\"" +
                ");";
        ExceptionChecker.expectThrows(DdlException.class, () -> starRocksAssert.withTable(sql1));
    }

    @Test
    public void testColumnWithRowNotSupportAllKeys() {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE test.dwd_column_with_row_test (\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "sub_ship_id bigint(20) NOT NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "PRIMARY KEY(ship_id, sub_ship_id) COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(ship_id, sub_ship_id) " +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"storage_type\" = \"column_with_row\"" +
                ");";

        ExceptionChecker.expectThrows(DdlException.class, () -> starRocksAssert.withTable(sql1));
    }

    @Test
    public void testColumnWithRowSuccess() {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE test.dwd_column_with_row_success_test (\n" +
                "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                "sub_ship_id bigint(20) NOT NULL COMMENT \"\" ,\n" +
                "address_code bigint(20) NOT NULL COMMENT \" \"\n" +
                ") ENGINE=OLAP\n" +
                "PRIMARY KEY(ship_id, sub_ship_id) COMMENT \"OLAP\"\n" +
                "DISTRIBUTED BY HASH(ship_id, sub_ship_id) " +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"storage_type\" = \"column_with_row\"" +
                ");";

        ExceptionChecker.expectThrowsNoException(() -> starRocksAssert.withTable(sql1));
    }

    @Test
    public void testColumnWithRowExperimental() {
        Config.enable_experimental_rowstore = false;
        try {
            StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
            starRocksAssert.useDatabase("test");
            String sql1 = "CREATE TABLE test.dwd_column_with_row_experimental_test (\n" +
                    "ship_id int(11) NOT NULL COMMENT \" \",\n" +
                    "sub_ship_id bigint(20) NOT NULL COMMENT \"\" ,\n" +
                    "address_code bigint(20) NOT NULL COMMENT \" \"\n" +
                    ") ENGINE=OLAP\n" +
                    "PRIMARY KEY(ship_id, sub_ship_id) COMMENT \"OLAP\"\n" +
                    "DISTRIBUTED BY HASH(ship_id, sub_ship_id) " +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\",\n" +
                    "\"storage_type\" = \"column_with_row\"" +
                    ");";

            ExceptionChecker.expectThrows(DdlException.class, () -> starRocksAssert.withTable(sql1));
        } finally {
            Config.enable_experimental_rowstore = true;
        }
    }

    @Test
    public void testReservedColumnName() {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "create table tbl_simple_pk(key0 string, __op boolean) primary key(key0)" +
                " distributed by hash(key0) properties(\"replication_num\"=\"1\");";
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class, "Getting analyzing error." +
                        " Detail message: Column name [__op] is a system reserved name." +
                        " Please choose a different one.",
                () -> starRocksAssert.withTable(sql1));
    }

    @Test
    public void testDefaultValueHasEscapeString() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE `news_rt` (\n" +
                "  `id` bigint(20) NOT NULL COMMENT \"pkid\",\n" +
                "  `title` varchar(65533) NOT NULL DEFAULT \"\\\"\" COMMENT \"title\"\n" +
                ") ENGINE=OLAP \n" +
                "PRIMARY KEY(`id`)\n" +
                "COMMENT \"news\"\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 1 \n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");";
        starRocksAssert.withTable(sql1);
        String createTableSql = starRocksAssert.showCreateTable("show create table news_rt;");
        starRocksAssert.dropTable("news_rt");
        starRocksAssert.withTable(createTableSql);
    }

    @Test
    public void testDefaultValueHasEscapeStringNonPK() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE `news_rt_non_pk` (\n" +
                "  `id` bigint(20) NOT NULL COMMENT \"pkid\",\n" +
                "  `title` varchar(65533) NOT NULL DEFAULT \"\\\"\" COMMENT \"title\"\n" +
                ") ENGINE=OLAP \n" +
                "DUPLICATE KEY(`id`)\n" +
                "COMMENT \"news\"\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 1 \n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");";
        starRocksAssert.withTable(sql1);
        String createTableSql = starRocksAssert.showCreateTable("show create table news_rt_non_pk;");
        starRocksAssert.dropTable("news_rt_non_pk");
        starRocksAssert.withTable(createTableSql);
    }

    @Test
    public void testDefaultValueHasChineseChars() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE `news_rt1` (\n" +
                "  `id` bigint(20) NOT NULL COMMENT \"pkid\",\n" +
                "  `title` varchar(65533) NOT NULL DEFAULT \"撒\" COMMENT \"撒\"\n" +
                ") ENGINE=OLAP \n" +
                "PRIMARY KEY(`id`)\n" +
                "COMMENT \"news\"\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 1 \n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");";
        starRocksAssert.withTable(sql1);
        String createTableSql = starRocksAssert.showCreateTable("show create table news_rt1;");
        starRocksAssert.dropTable("news_rt1");
        starRocksAssert.withTable(createTableSql);
        Assertions.assertTrue(createTableSql.contains("撒"), createTableSql);
    }

    @Test
    public void testDefaultValueHasChineseCharsNonPK() throws Exception {
        StarRocksAssert starRocksAssert = new StarRocksAssert(connectContext);
        starRocksAssert.useDatabase("test");
        String sql1 = "CREATE TABLE `news_rt1_non_pk` (\n" +
                "  `id` bigint(20) NOT NULL COMMENT \"pkid\",\n" +
                "  `title` varchar(65533) NOT NULL DEFAULT \"撒\" COMMENT \"撒\"\n" +
                ") ENGINE=OLAP \n" +
                "DUPLICATE KEY(`id`)\n" +
                "COMMENT \"news\"\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 1 \n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");";
        starRocksAssert.withTable(sql1);
        String createTableSql = starRocksAssert.showCreateTable("show create table news_rt1_non_pk;");
        starRocksAssert.dropTable("news_rt1_non_pk");
        starRocksAssert.withTable(createTableSql);
        Assertions.assertTrue(createTableSql.contains("撒"), createTableSql);
    }

    @Test
    public void testCreateTableWithNullableColumns1() throws Exception {
        String createSQL = "CREATE TABLE list_partition_tbl1 (\n" +
                "      id BIGINT,\n" +
                "      age SMALLINT,\n" +
                "      dt VARCHAR(10),\n" +
                "      province VARCHAR(64) \n" +
                ")\n" +
                "DUPLICATE KEY(id)\n" +
                "PARTITION BY LIST (province) (\n" +
                "     PARTITION p1 VALUES IN ((NULL),(\"chongqing\")) ,\n" +
                "     PARTITION p2 VALUES IN ((\"guangdong\")) \n" +
                ")\n" +
                "DISTRIBUTED BY RANDOM\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");";
        starRocksAssert.withTable(createSQL);
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(),
                "list_partition_tbl1");
        PartitionInfo info = table.getPartitionInfo();
        Assertions.assertTrue(info.isListPartition());
        ListPartitionInfo listPartitionInfo = (ListPartitionInfo) info;
        Map<Long, List<List<LiteralExpr>>> long2Literal =  listPartitionInfo.getMultiLiteralExprValues();
        Assertions.assertEquals(2, long2Literal.size());
    }

    @Test
    public void testCreateTableWithNullableColumns2() {
        String createSQL = "\n" +
                "CREATE TABLE t3 (\n" +
                "  dt date,\n" +
                "  city varchar(20),\n" +
                "  name varchar(20),\n" +
                "  num int\n" +
                ") ENGINE=OLAP\n" +
                "PRIMARY KEY(dt, city, name)\n" +
                "PARTITION BY LIST (dt) (\n" +
                "    PARTITION p1 VALUES IN ((NULL), (\"2022-04-01\")),\n" +
                "    PARTITION p2 VALUES IN ((\"2022-04-02\")),\n" +
                "    PARTITION p3 VALUES IN ((\"2022-04-03\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(dt) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\"\n" +
                ");";
        try {
            starRocksAssert.withTable(createSQL);
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Partition column[dt] could not be null but contains null " +
                    "value in partition[p1]."));
        }
    }

    @Test
    public void testChosenBackendIdBySeqWhenDiskOffline() {
        List<Backend> backends = Lists.newArrayList();
        Backend be0 = new Backend(10000, "127.0.0.1", 9050);
        DiskInfo disk = new DiskInfo("/path");
        disk.setState(DiskInfo.DiskState.ONLINE);
        be0.setDisks(ImmutableMap.of("/path", disk));
        backends.add(be0);
        Backend be1 = new Backend(10001, "127.0.0.2", 9050);
        be1.setDisks(ImmutableMap.of("/path", disk));
        backends.add(be1);
        Backend be2 = new Backend(10002, "127.0.0.3", 9050);
        DiskInfo disk2 = new DiskInfo("/path");
        disk2.setState(DiskInfo.DiskState.OFFLINE);
        be2.setDisks(ImmutableMap.of("/path", disk2));
        backends.add(be2);

        new MockUp<SystemInfoService>() {
            @Mock
            public List<Backend> getAvailableBackends() {
                return backends;
            }
        };

        try {
            LocalMetastore metastore = new LocalMetastore(GlobalStateMgr.getCurrentState(), null, null);
            Deencapsulation.invoke(metastore, "chosenBackendIdBySeq", 3, HashMultimap.create());
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Current available backends: [10000,10001]"));
            Assertions.assertTrue(e.getMessage().contains("backends without enough disk space: [10002]"));
        }
    }
}
