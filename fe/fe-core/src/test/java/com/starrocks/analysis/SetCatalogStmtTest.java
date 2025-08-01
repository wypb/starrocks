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

package com.starrocks.analysis;

import com.google.common.collect.Sets;
import com.starrocks.authorization.PrivilegeBuiltinConstants;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.CatalogMgr;
import com.starrocks.sql.analyzer.AnalyzeTestUtil;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SetCatalogStmtTest {
    private static ConnectContext ctx;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        AnalyzeTestUtil.init();
        StarRocksAssert starRocksAssert = new StarRocksAssert();
        starRocksAssert.withDatabase("db1").useDatabase("tbl1");
        String createCatalog = "create external catalog hive_catalog properties(" +
                "\"type\" = \"hive\", \"hive.metastore.uris\" = \"thrift://127.0.0.1:3333\")";
        starRocksAssert.withCatalog(createCatalog);
        ctx = new ConnectContext(null);
        ctx.setGlobalStateMgr(AccessTestUtil.fetchAdminCatalog());
    }

    @Test
    public void testParserAndAnalyzer() {
        String sql = "SET CATALOG hive_catalog'";
        AnalyzeTestUtil.analyzeSuccess(sql);

        String sql_2 = "SET CATALOG default_catalog'";
        AnalyzeTestUtil.analyzeSuccess(sql_2);

        String sql_3 = "SET xxxx default_catalog'";
        AnalyzeTestUtil.analyzeFail(sql_3);
    }

    @Test
    public void testSetCatalog(@Mocked CatalogMgr catalogMgr) throws Exception {
        new Expectations() {
            {
                catalogMgr.catalogExists("hive_catalog");
                result = true;
                minTimes = 0;

                catalogMgr.catalogExists("default_catalog");
                result = true;
                minTimes = 0;
            }
        };

        ctx.setQueryId(UUIDUtil.genUUID());
        ctx.setCurrentUserIdentity(UserIdentity.ROOT);
        ctx.setCurrentRoleIds(Sets.newHashSet(PrivilegeBuiltinConstants.ROOT_ROLE_ID));
        StmtExecutor executor = new StmtExecutor(ctx, SqlParser.parseSingleStatement(
                "set catalog hive_catalog", ctx.getSessionVariable().getSqlMode()));
        executor.execute();

        Assertions.assertEquals("hive_catalog", ctx.getCurrentCatalog());

        executor = new StmtExecutor(ctx, SqlParser.parseSingleStatement("set catalog default_catalog",
                ctx.getSessionVariable().getSqlMode()));
        executor.execute();

        Assertions.assertEquals("default_catalog", ctx.getCurrentCatalog());

        AnalyzeTestUtil.analyzeFail("set xxx default_catalog");
        AnalyzeTestUtil.analyzeFail("set catalog default_catalog xxx");
    }
}
