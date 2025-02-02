/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static com.google.common.collect.Lists.newArrayListWithExpectedSize;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.apache.phoenix.util.TestUtil.analyzeTable;
import static org.apache.phoenix.util.TestUtil.getAllSplits;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.curator.shaded.com.google.common.collect.Lists;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.coprocessor.BaseMetaDataEndpointObserver;
import org.apache.phoenix.coprocessor.MetaDataEndpointObserver;
import org.apache.phoenix.coprocessor.PhoenixMetaDataCoprocessorHost;
import org.apache.phoenix.coprocessor.PhoenixMetaDataCoprocessorHost.PhoenixMetaDataControllerEnvironment;
import org.apache.phoenix.exception.PhoenixIOException;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.ConcurrentTableMutationException;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.ReadOnlyTableException;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.transaction.PhoenixTransactionProvider.Feature;
import org.apache.phoenix.transaction.TransactionFactory;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.Maps;
@RunWith(Parameterized.class)
public class ViewIT extends SplitSystemCatalogIT {

    protected String tableDDLOptions;
    protected String transactionProvider;
    protected boolean columnEncoded;
    
    private static final String FAILED_VIEWNAME = SchemaUtil.getTableName(SCHEMA2, "FAILED_VIEW");
    private static final String SLOW_VIEWNAME_PREFIX = SchemaUtil.getTableName(SCHEMA2, "SLOW_VIEW");

    private static volatile CountDownLatch latch1 = null;
    private static volatile CountDownLatch latch2 = null;

    public ViewIT(String transactionProvider, boolean columnEncoded) {
        StringBuilder optionBuilder = new StringBuilder();
        this.transactionProvider = transactionProvider;
        this.columnEncoded = columnEncoded;
        if (transactionProvider != null) {
            optionBuilder.append(" TRANSACTION_PROVIDER='" + transactionProvider + "'");
        }
        if (!columnEncoded) {
            if (optionBuilder.length()!=0)
                optionBuilder.append(",");
            optionBuilder.append("COLUMN_ENCODED_BYTES=0");
        }
        this.tableDDLOptions = optionBuilder.toString();
    }

    @Parameters(name="ViewIT_transactionProvider={0}, columnEncoded={1}") // name is used by failsafe as file name in reports
    public static Collection<Object[]> data() {
        return TestUtil.filterTxParamData(Arrays.asList(new Object[][] { 
            { "TEPHRA", false }, { "TEPHRA", true },
            { "OMID", false }, 
            { null, false }, { null, true }}),0);
    }
    
    @BeforeClass
    public static void doSetup() throws Exception {
        NUM_SLAVES_BASE = 6;
        Map<String, String> props = Collections.emptyMap();
        boolean splitSystemCatalog = (driver == null);
        Map<String, String> serverProps = Maps.newHashMapWithExpectedSize(1);
        serverProps.put(QueryServices.PHOENIX_ACLS_ENABLED, "true");
        serverProps.put(PhoenixMetaDataCoprocessorHost.PHOENIX_META_DATA_COPROCESSOR_CONF_KEY,
            TestMetaDataRegionObserver.class.getName());
        serverProps.put("hbase.coprocessor.abortonerror", "false");
        setUpTestDriver(new ReadOnlyProps(serverProps.entrySet().iterator()), new ReadOnlyProps(props.entrySet().iterator()));
        // Split SYSTEM.CATALOG once after the mini-cluster is started
        if (splitSystemCatalog) {
            splitSystemCatalog();
        }
    }
    
    public static class TestMetaDataRegionObserver extends BaseMetaDataEndpointObserver {
        
        @Override
        public Optional<MetaDataEndpointObserver> getPhoenixObserver() {
            return Optional.of(this);
        }
        
        @Override
        public void preAlterTable(ObserverContext<PhoenixMetaDataControllerEnvironment> ctx,
                String tenantId, String tableName, TableName physicalTableName,
                TableName parentPhysicalTableName, PTableType type) throws IOException {
            processTable(tableName);
        }
        
        @Override
        public void preCreateTable(ObserverContext<PhoenixMetaDataControllerEnvironment> ctx,
                String tenantId, String tableName, TableName physicalTableName,
                TableName parentPhysicalTableName, PTableType tableType, Set<byte[]> familySet,
                Set<TableName> indexes) throws IOException {
            processTable(tableName);
        }

        @Override
        public void preDropTable(ObserverContext<PhoenixMetaDataControllerEnvironment> ctx,
                String tenantId, String tableName, TableName physicalTableName,
                TableName parentPhysicalTableName, PTableType tableType, List<PTable> indexes)
                throws IOException {
            processTable(tableName);
        }

        private void processTable(String tableName) throws DoNotRetryIOException {
            if (tableName.equals(FAILED_VIEWNAME)) {
                // throwing anything other than instances of IOException result
                // in this coprocessor being unloaded
                // DoNotRetryIOException tells HBase not to retry this mutation
                // multiple times
                throw new DoNotRetryIOException();
            } else if (tableName.startsWith(SLOW_VIEWNAME_PREFIX)) {
                // simulate a slow write to SYSTEM.CATALOG
                if (latch1 != null) {
                    latch1.countDown();
                }
                if (latch2 != null) {
                    try {
                        // wait till the second task is complete before completing the first task
                        boolean result = latch2.await(2, TimeUnit.MINUTES);
                        if (!result) {
                            throw new RuntimeException("Second task took took long to complete");
                        }
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        
    }
    
    @Test
    public void testReadOnlyOnUpdatableView() throws Exception {
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
        String fullViewName1 = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());
        String fullViewName2 = SchemaUtil.getTableName(SCHEMA3, generateUniqueName());
        String ddl =
                "CREATE VIEW " + fullViewName2 + " AS SELECT * FROM " + fullViewName1
                        + " WHERE k3 > 1 and k3 < 50";
        testUpdatableView(fullTableName, fullViewName1, fullViewName2, ddl, null, tableDDLOptions);
        Connection conn = DriverManager.getConnection(getUrl());
        ResultSet rs = conn.createStatement().executeQuery("SELECT k1, k2, k3 FROM " + fullViewName2);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(109, rs.getInt(2));
        assertEquals(2, rs.getInt(3));
        assertFalse(rs.next());
        try {
            conn.createStatement().execute("UPSERT INTO " + fullViewName2 + " VALUES(1)");
            fail();
        } catch (ReadOnlyTableException e) {
            
        }
        
        conn.createStatement().execute("UPSERT INTO " + fullTableName + "(k1, k2,k3) VALUES(1, 122, 5)");
        conn.commit();
        rs = conn.createStatement().executeQuery("SELECT k1, k2, k3 FROM " + fullViewName2 + " WHERE k2 >= 120");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(122, rs.getInt(2));
        assertEquals(5, rs.getInt(3));
        assertFalse(rs.next());
    }

    @Test
    public void testReadOnlyViewWithCaseSensitiveTableNames() throws Exception {
        Connection earlierCon = DriverManager.getConnection(getUrl());
        Connection conn = DriverManager.getConnection(getUrl());
        String schemaName = TestUtil.DEFAULT_SCHEMA_NAME + "_" + generateUniqueName();
        String caseSensitiveTableName = "\"t_" + generateUniqueName() + "\"" ;
        String fullTableName = SchemaUtil.getTableName(schemaName, caseSensitiveTableName);
        String caseSensitiveViewName = "\"v_" + generateUniqueName() + "\"" ;

        String ddl = "CREATE TABLE " + fullTableName + " (k INTEGER NOT NULL PRIMARY KEY, v1 DATE)" + tableDDLOptions;
        conn.createStatement().execute(ddl);
        ddl = "CREATE VIEW " + caseSensitiveViewName + " (v2 VARCHAR) AS SELECT * FROM " + fullTableName + " WHERE k > 5";
        conn.createStatement().execute(ddl);
        
        try {
            conn.createStatement().execute("UPSERT INTO " + caseSensitiveViewName + " VALUES(1)");
            fail();
        } catch (ReadOnlyTableException e) {
            
        }
        for (int i = 0; i < 10; i++) {
            conn.createStatement().execute("UPSERT INTO " + fullTableName + " VALUES(" + i + ")");
        }
        conn.commit();
        
        int count = 0;
        ResultSet rs = conn.createStatement().executeQuery("SELECT k FROM " + caseSensitiveViewName);
        while (rs.next()) {
            count++;
            assertEquals(count + 5, rs.getInt(1));
        }
        assertEquals(4, count);
        count = 0;
        rs = earlierCon.createStatement().executeQuery("SELECT k FROM " + caseSensitiveViewName);
        while (rs.next()) {
            count++;
            assertEquals(count + 5, rs.getInt(1));
        }
        assertEquals(4, count);
    }
    
    @Test
    public void testReadOnlyViewWithCaseSensitiveColumnNames() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
        String viewName = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());
        String ddl = "CREATE TABLE " + fullTableName + " (\"k\" INTEGER NOT NULL PRIMARY KEY, \"v1\" INTEGER, \"a\".v2 VARCHAR)" + tableDDLOptions;

        conn.createStatement().execute(ddl);
        ddl = "CREATE VIEW " + viewName + " (v VARCHAR) AS SELECT * FROM " + fullTableName + " WHERE \"k\" > 5 and \"v1\" > 1";
        conn.createStatement().execute(ddl);
        
        try {
            conn.createStatement().execute("UPSERT INTO " + viewName + " VALUES(1)");
            fail();
        } catch (ReadOnlyTableException e) {
            
        }
        for (int i = 0; i < 10; i++) {
            conn.createStatement().execute("UPSERT INTO " + fullTableName + " VALUES(" + i + ", " + (i+10) + ",'A')");
        }
        conn.commit();
        
        int count = 0;
        ResultSet rs = conn.createStatement().executeQuery("SELECT \"k\", \"v1\",\"a\".v2 FROM " + viewName);
        while (rs.next()) {
            count++;
            assertEquals(count + 5, rs.getInt(1));
        }
        assertEquals(4, count);
    }
    
    @Test
    public void testViewWithCurrentDate() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
        String viewName = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());

        String ddl = "CREATE TABLE " + fullTableName + " (k INTEGER NOT NULL PRIMARY KEY, v1 INTEGER, v2 DATE)" + tableDDLOptions;
        conn.createStatement().execute(ddl);
        ddl = "CREATE VIEW " + viewName + " (v VARCHAR) AS SELECT * FROM " + fullTableName + " WHERE v2 > CURRENT_DATE()-5 AND v2 > DATE '2010-01-01'";
        conn.createStatement().execute(ddl);
        
        try {
            conn.createStatement().execute("UPSERT INTO " + viewName + " VALUES(1)");
            fail();
        } catch (ReadOnlyTableException e) {
            
        }
        for (int i = 0; i < 10; i++) {
            conn.createStatement().execute("UPSERT INTO " + fullTableName + " VALUES(" + i + ", " + (i+10) + ",CURRENT_DATE()-" + i + ")");
        }
        conn.commit();
        
        int count = 0;
        ResultSet rs = conn.createStatement().executeQuery("SELECT k FROM " + viewName);
        while (rs.next()) {
            assertEquals(count, rs.getInt(1));
            count++;
        }
        assertEquals(5, count);
    }

    @Test
    public void testViewUsesTableGlobalIndex() throws Exception {
        testViewUsesTableIndex(false);
    }
    
    @Test
    public void testViewUsesTableLocalIndex() throws Exception {
        if (transactionProvider == null ||
                !TransactionFactory.getTransactionProvider(
                        TransactionFactory.Provider.valueOf(transactionProvider)).isUnsupported(Feature.ALLOW_LOCAL_INDEX)) {
            testViewUsesTableIndex(true);
        }
    }

    
    private void testViewUsesTableIndex(boolean localIndex) throws Exception {
        ResultSet rs;
        // Use unique name for table with local index as otherwise we run into issues
        // when we attempt to drop the table (with the drop metadata option set to false
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName()) + (localIndex ? "_WITH_LI" : "_WITHOUT_LI");
        Connection conn = DriverManager.getConnection(getUrl());
        String ddl = "CREATE TABLE " + fullTableName + "  (k1 INTEGER NOT NULL, k2 INTEGER NOT NULL, k3 DECIMAL, s1 VARCHAR, s2 VARCHAR CONSTRAINT pk PRIMARY KEY (k1, k2, k3))" + tableDDLOptions;
        conn.createStatement().execute(ddl);
        String indexName1 = "I_" + generateUniqueName();
        String fullIndexName1 = SchemaUtil.getTableName(SCHEMA1, indexName1);
        conn.createStatement().execute("CREATE " + (localIndex ? "LOCAL " : "") + " INDEX " + indexName1 + " ON " + fullTableName + "(k3, k2) INCLUDE(s1, s2)");
        String indexName2 = "I_" + generateUniqueName();
        conn.createStatement().execute("CREATE INDEX " + indexName2 + " ON " + fullTableName + "(k3, k2, s2)");
        
        String fullViewName = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());
        ddl = "CREATE VIEW " + fullViewName + " AS SELECT * FROM " + fullTableName + " WHERE s1 = 'foo'";
        conn.createStatement().execute(ddl);
        String[] s1Values = {"foo","bar"};
        for (int i = 0; i < 10; i++) {
            conn.createStatement().execute("UPSERT INTO " + fullTableName + " VALUES(" + (i % 4) + "," + (i+100) + "," + (i > 5 ? 2 : 1) + ",'" + s1Values[i%2] + "','bas')");
        }
        conn.commit();
        
        rs = conn.createStatement().executeQuery("SELECT count(*) FROM " + fullViewName);
        assertTrue(rs.next());
        assertEquals(5, rs.getLong(1));
        assertFalse(rs.next());

        String viewIndexName = "I_" + generateUniqueName();
        conn.createStatement().execute("CREATE INDEX " + viewIndexName + " on " + fullViewName + "(k2)");
        
        String query = "SELECT k2 FROM " + fullViewName + " WHERE k2 IN (100,109) AND k3 IN (1,2) AND s2='bas'";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals(100, rs.getInt(1));
        assertFalse(rs.next());
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        String queryPlan = QueryUtil.getExplainPlan(rs);
        // Assert that in either case (local & global) that index from physical table used for query on view.
        if (localIndex) {
            assertEquals("CLIENT PARALLEL 1-WAY SKIP SCAN ON 4 KEYS OVER " + fullTableName + " [1,1,100] - [1,2,109]\n" + 
                    "    SERVER FILTER BY (\"S2\" = 'bas' AND \"S1\" = 'foo')\n" + 
                    "CLIENT MERGE SORT", queryPlan);
        } else {
            assertEquals(
                    "CLIENT PARALLEL 1-WAY SKIP SCAN ON 4 KEYS OVER " + fullIndexName1 + " [1,100] - [2,109]\n" + 
                    "    SERVER FILTER BY (\"S2\" = 'bas' AND \"S1\" = 'foo')", queryPlan);
        }
    }    

    @Test
    public void testCreateViewDefinesPKColumn() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
        String fullViewName = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());
        
        String ddl = "CREATE TABLE " + fullTableName + " (k1 INTEGER NOT NULL, k2 INTEGER NOT NULL, v1 DECIMAL, CONSTRAINT pk PRIMARY KEY (k1, k2))" + tableDDLOptions;
        conn.createStatement().execute(ddl);
        ddl = "CREATE VIEW " + fullViewName + "(v2 VARCHAR, k3 VARCHAR PRIMARY KEY) AS SELECT * FROM " + fullTableName + " WHERE K1 = 1";
        conn.createStatement().execute(ddl);

        // assert PK metadata
        ResultSet rs =
                conn.getMetaData().getPrimaryKeys(null,
                    SchemaUtil.getSchemaNameFromFullName(fullViewName),
                    SchemaUtil.getTableNameFromFullName(fullViewName));
        assertPKs(rs, new String[] {"K1", "K2", "K3"});
        
        // sanity check upserts into base table and view
        conn.createStatement().executeUpdate("upsert into " + fullTableName + " (k1, k2, v1) values (1, 1, 1)");
        conn.createStatement().executeUpdate("upsert into " + fullViewName + " (k1, k2, k3, v2) values (1, 1, 'abc', 'def')");
        conn.commit();
        
        // expect 2 rows in the base table
        rs = conn.createStatement().executeQuery("select count(*) from " + fullTableName);
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        
        // expect 2 row in the view
        rs = conn.createStatement().executeQuery("select count(*) from " + fullViewName);
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
    }
    
    @Test
    public void testQueryViewStatementOptimization() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
        String fullViewName1 = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());
        String fullViewName2 = SchemaUtil.getTableName(SCHEMA3, generateUniqueName());
        String sql = "CREATE TABLE " + fullTableName + " (k1 INTEGER NOT NULL, k2 INTEGER NOT NULL, v1 DECIMAL, CONSTRAINT pk PRIMARY KEY (k1, k2))" + tableDDLOptions;
        conn.createStatement().execute(sql);
        sql = "CREATE VIEW " + fullViewName1 + "  AS SELECT * FROM " + fullTableName;
        conn.createStatement().execute(sql);
        sql = "CREATE VIEW " + fullViewName2 + "  AS SELECT * FROM " + fullTableName + " WHERE k1 = 1.0";
        conn.createStatement().execute(sql);
        
        
        sql = "SELECT * FROM " + fullViewName1 + " order by k1, k2";
        PreparedStatement stmt = conn.prepareStatement(sql);
        QueryPlan plan = PhoenixRuntime.getOptimizedQueryPlan(stmt);
        assertEquals(0, plan.getOrderBy().getOrderByExpressions().size());
        
        sql = "SELECT * FROM " + fullViewName2 + " order by k1, k2";
        stmt = conn.prepareStatement(sql);
        plan = PhoenixRuntime.getOptimizedQueryPlan(stmt);
        assertEquals(0, plan.getOrderBy().getOrderByExpressions().size());
    }

    private void assertPKs(ResultSet rs, String[] expectedPKs) throws SQLException {
        List<String> pkCols = newArrayListWithExpectedSize(expectedPKs.length);
        while (rs.next()) {
            pkCols.add(rs.getString("COLUMN_NAME"));
        }
        String[] actualPKs = pkCols.toArray(new String[0]);
        assertArrayEquals(expectedPKs, actualPKs);
    }

    @Test
    public void testCompositeDescPK() throws Exception {
        Properties props = new Properties();
        try (Connection globalConn = DriverManager.getConnection(getUrl(), props)) {
            String tableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
            String viewName1 = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());
            String viewName2 = SchemaUtil.getTableName(SCHEMA3, generateUniqueName());
            String viewName3 = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
            String viewName4 = SchemaUtil.getTableName(SCHEMA2, generateUniqueName());

            String myTableDDLOptions = tableDDLOptions;
            if (myTableDDLOptions.length() != 0) myTableDDLOptions += ",";
            myTableDDLOptions += "VERSIONS=1, MULTI_TENANT=true, IMMUTABLE_ROWS=TRUE, REPLICATION_SCOPE=1";

            // create global base table
            globalConn.createStatement().execute("CREATE TABLE " + tableName
                    + " (TENANT_ID CHAR(15) NOT NULL, KEY_PREFIX CHAR(3) NOT NULL, CREATED_DATE DATE, CREATED_BY CHAR(15), SYSTEM_MODSTAMP DATE CONSTRAINT PK PRIMARY KEY (TENANT_ID, KEY_PREFIX)) " + myTableDDLOptions);
            
            String tenantId = "tenantId";
            Properties tenantProps = new Properties();
            tenantProps.setProperty(PhoenixRuntime.TENANT_ID_ATTRIB, tenantId);
            // create a tenant specific view
            try (Connection tenantConn = DriverManager.getConnection(getUrl(), tenantProps)) {
                // create various tenant specific views
                // view with composite PK with multiple PK values of VARCHAR values DESC
                tenantConn.createStatement()
                        .execute("CREATE VIEW " + viewName1
                                + " (pk1 VARCHAR(10) NOT NULL, pk2 VARCHAR(10) NOT NULL, col1 DATE, col3 DECIMAL CONSTRAINT PK PRIMARY KEY (pk1 DESC, pk2 DESC)) AS SELECT * FROM "
                                + tableName + " WHERE KEY_PREFIX = 'abc' ");
                // view with composite PK with single pk value DESC
                tenantConn.createStatement()
                        .execute("CREATE VIEW " + viewName2
                                + " (pk1 VARCHAR(10) NOT NULL, pk2 VARCHAR(10) NOT NULL, col1 DATE, col3 DECIMAL CONSTRAINT PK PRIMARY KEY (pk1 DESC, pk2 DESC)) AS SELECT * FROM "
                                + tableName + " WHERE KEY_PREFIX = 'abc' ");

                // view with composite PK with multiple Date PK values DESC
                tenantConn.createStatement()
                        .execute("CREATE VIEW " + viewName3
                                + " (pk1 DATE(10) NOT NULL, pk2 DATE(10) NOT NULL, col1 VARCHAR(10), col3 DECIMAL CONSTRAINT PK PRIMARY KEY (pk1 DESC, pk2 DESC)) AS SELECT * FROM "
                                + tableName + " WHERE KEY_PREFIX = 'ab3' ");
                
                tenantConn.createStatement()
                .execute("CREATE VIEW " + viewName4
                        + " (pk1 DATE(10) NOT NULL, pk2 DECIMAL NOT NULL, pk3 VARCHAR(10) NOT NULL, col3 DECIMAL CONSTRAINT PK PRIMARY KEY (pk1 DESC, pk2 DESC, pk3 DESC)) AS SELECT * FROM "
                        + tableName + " WHERE KEY_PREFIX = 'ab4' ");

                // upsert rows
                upsertRows(tableName, viewName1, tenantConn);
                upsertRows(tableName, viewName2, tenantConn);

                // run queries
                String[] whereClauses =
                        new String[] { "pk1 = 'testa'", "", "pk1 >= 'testa'", "pk1 <= 'testa'",
                                "pk1 > 'testa'", "pk1 < 'testa'" };
                long[] expectedArray = new long[] { 4, 5, 5, 4, 1, 0 };
                validate(viewName1, tenantConn, whereClauses, expectedArray);
                validate(viewName2, tenantConn, whereClauses, expectedArray);

                tenantConn.createStatement().execute("UPSERT INTO " + viewName3
                        + " (pk1, pk2, col1, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), TO_DATE('2017-10-16 21:00:00', 'yyyy-MM-dd HH:mm:ss'), 'txt1', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName3
                        + " (pk1, pk2, col1, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), TO_DATE('2017-10-16 21:01:00', 'yyyy-MM-dd HH:mm:ss'), 'txt1', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName3
                        + " (pk1, pk2, col1, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), TO_DATE('2017-10-16 21:02:00', 'yyyy-MM-dd HH:mm:ss'), 'txt1', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName3
                        + " (pk1, pk2, col1, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), TO_DATE('2017-10-16 21:03:00', 'yyyy-MM-dd HH:mm:ss'), 'txt1', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName3
                        + " (pk1, pk2, col1, col3) VALUES (TO_DATE('2017-10-16 23:00:00', 'yyyy-MM-dd HH:mm:ss'), TO_DATE('2017-10-16 21:04:00', 'yyyy-MM-dd HH:mm:ss'), 'txt1', 10)");
                tenantConn.commit();

                String[] view3WhereClauses =
                        new String[] {
                                "pk1 = TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')", "",
                                "pk1 >= TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 <= TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 > TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 < TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')" };
                validate(viewName3, tenantConn, view3WhereClauses, expectedArray);

                tenantConn.createStatement().execute("UPSERT INTO " + viewName4
                        + " (pk1, pk2, pk3, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 1, 'txt1', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName4
                        + " (pk1, pk2, pk3, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 2, 'txt2', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName4
                        + " (pk1, pk2, pk3, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 3, 'txt3', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName4
                        + " (pk1, pk2, pk3, col3) VALUES (TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 4, 'txt4', 10)");
                tenantConn.createStatement().execute("UPSERT INTO " + viewName4
                        + " (pk1, pk2, pk3, col3) VALUES (TO_DATE('2017-10-16 23:00:00', 'yyyy-MM-dd HH:mm:ss'), 1, 'txt1', 10)");
                tenantConn.commit();

                String[] view4WhereClauses =
                        new String[] {
                                "pk1 = TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 = TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss') AND pk2 = 2",
                                "pk1 = TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss') AND pk2 > 2",
                                "", "pk1 >= TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 <= TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 > TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')",
                                "pk1 < TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss')" };
                long[] view4ExpectedArray = new long[] { 4, 1, 2, 5, 5, 4, 1, 0 };
                validate(viewName4, tenantConn, view4WhereClauses, view4ExpectedArray);

            }
        }
    }

    private void validate(String viewName, Connection tenantConn, String[] whereClauseArray,
            long[] expectedArray) throws SQLException {
        for (int i = 0; i < whereClauseArray.length; ++i) {
            String where = !whereClauseArray[i].isEmpty() ? (" WHERE " + whereClauseArray[i]) : "";
            ResultSet rs =
                    tenantConn.createStatement()
                            .executeQuery("SELECT count(*) FROM " + viewName + where);
            assertTrue(rs.next());
            assertEquals(expectedArray[i], rs.getLong(1));
            assertFalse(rs.next());
        }
    }

    private void upsertRows(String tableName, String viewName1, Connection tenantConn) throws SQLException, IOException {
        tenantConn.createStatement().execute("UPSERT INTO " + viewName1
                + " (pk1, pk2, col1, col3) VALUES ('testa', 'testb', TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 10)");
        tenantConn.createStatement().execute("UPSERT INTO " + viewName1
                + " (pk1, pk2, col1, col3) VALUES ('testa', 'testc', TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 10)");
        tenantConn.createStatement().execute("UPSERT INTO " + viewName1
                + " (pk1, pk2, col1, col3) VALUES ('testa', 'testd', TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 10)");
        tenantConn.createStatement().execute("UPSERT INTO " + viewName1
                + " (pk1, pk2, col1, col3) VALUES ('testa', 'teste', TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 10)");
        tenantConn.createStatement().execute("UPSERT INTO " + viewName1
                + " (pk1, pk2, col1, col3) VALUES ('testb', 'testa', TO_DATE('2017-10-16 22:00:00', 'yyyy-MM-dd HH:mm:ss'), 10)");
        tenantConn.commit();
    }
    
    public static String testUpdatableView(String fullTableName, String fullViewName, String fullChildViewName,
            String childViewDDL, Integer saltBuckets, String tableDDLOptions) throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        if (saltBuckets != null) {
            if (tableDDLOptions.length() != 0)
                tableDDLOptions += ",";
            tableDDLOptions += (" SALT_BUCKETS=" + saltBuckets);
        }
        String ddl = "CREATE TABLE " + fullTableName
                + " (k1 INTEGER NOT NULL, k2 INTEGER NOT NULL, k3 DECIMAL, s VARCHAR CONSTRAINT pk PRIMARY KEY (k1, k2, k3))"
                + tableDDLOptions;
        conn.createStatement().execute(ddl);
        ddl = "CREATE VIEW " + fullViewName + " AS SELECT * FROM " + fullTableName + " WHERE k1 = 1";
        conn.createStatement().execute(ddl);
        ArrayList<String> splitPoints = Lists.newArrayList(fullTableName, fullViewName);
        if (fullChildViewName != null) {
            conn.createStatement().execute(childViewDDL);
            splitPoints.add(fullChildViewName);
        }

        for (int i = 0; i < 10; i++) {
            conn.createStatement().execute("UPSERT INTO " + fullTableName + " VALUES(" + (i % 4) + "," + (i + 100) + ","
                    + (i > 5 ? 2 : 1) + ")");
        }
        conn.commit();
        ResultSet rs;

        rs = conn.createStatement().executeQuery("SELECT count(*) FROM " + fullTableName);
        assertTrue(rs.next());
        assertEquals(10, rs.getInt(1));
        rs = conn.createStatement().executeQuery("SELECT count(*) FROM " + fullViewName);
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        rs = conn.createStatement().executeQuery("SELECT k1, k2, k3 FROM " + fullViewName);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(101, rs.getInt(2));
        assertEquals(1, rs.getInt(3));
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(105, rs.getInt(2));
        assertEquals(1, rs.getInt(3));
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(109, rs.getInt(2));
        assertEquals(2, rs.getInt(3));
        assertFalse(rs.next());

        conn.createStatement().execute("UPSERT INTO " + fullViewName + "(k2,S,k3) VALUES(120,'foo',50.0)");
        conn.createStatement().execute("UPSERT INTO " + fullViewName + "(k2,S,k3) VALUES(121,'bar',51.0)");
        conn.commit();
        rs = conn.createStatement().executeQuery("SELECT k1, k2 FROM " + fullViewName + " WHERE k2 >= 120");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(120, rs.getInt(2));
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(121, rs.getInt(2));
        assertFalse(rs.next());
        conn.close();
        return fullViewName;
    }

    public static Pair<String, Scan> testUpdatableViewIndex(String fullTableName, Integer saltBuckets,
            boolean localIndex, String viewName) throws Exception {
        ResultSet rs;
        Connection conn = DriverManager.getConnection(getUrl());
        String viewIndexName1 = "I_" + generateUniqueName();
        String viewIndexPhysicalName = MetaDataUtil.getViewIndexPhysicalName(fullTableName);
        if (localIndex) {
            conn.createStatement().execute("CREATE LOCAL INDEX " + viewIndexName1 + " on " + viewName + "(k3)");
        } else {
            conn.createStatement().execute("CREATE INDEX " + viewIndexName1 + " on " + viewName + "(k3) include (s)");
        }
        conn.createStatement().execute("UPSERT INTO " + viewName + "(k2,S,k3) VALUES(120,'foo',50.0)");
        conn.commit();

        analyzeTable(conn, viewName);
        List<KeyRange> splits = getAllSplits(conn, viewIndexName1);
        // More guideposts with salted, since it's already pre-split at salt
        // buckets
        assertEquals(saltBuckets == null ? 6 : 8, splits.size());

        String query = "SELECT k1, k2, k3, s FROM " + viewName + " WHERE k3 = 51.0";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(121, rs.getInt(2));
        assertTrue(BigDecimal.valueOf(51.0).compareTo(rs.getBigDecimal(3)) == 0);
        assertEquals("bar", rs.getString(4));
        assertFalse(rs.next());
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        String queryPlan = QueryUtil.getExplainPlan(rs);
        if (localIndex) {
            assertEquals("CLIENT PARALLEL " + (saltBuckets == null ? 1 : saltBuckets) + "-WAY RANGE SCAN OVER "
                    + fullTableName + " [1,51]\n" + "    SERVER FILTER BY FIRST KEY ONLY\n" + "CLIENT MERGE SORT",
                    queryPlan);
        } else {
            assertEquals(saltBuckets == null
                    ? "CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + viewIndexPhysicalName + " [" + Long.MIN_VALUE + ",51]"
                    : "CLIENT PARALLEL " + saltBuckets + "-WAY RANGE SCAN OVER " + viewIndexPhysicalName + " [0,"
                            + Long.MIN_VALUE + ",51] - [" + (saltBuckets.intValue() - 1) + "," + Long.MIN_VALUE
                            + ",51]\nCLIENT MERGE SORT",
                    queryPlan);
        }

        String viewIndexName2 = "I_" + generateUniqueName();
        if (localIndex) {
            conn.createStatement().execute("CREATE LOCAL INDEX " + viewIndexName2 + " on " + viewName + "(s)");
        } else {
            conn.createStatement().execute("CREATE INDEX " + viewIndexName2 + " on " + viewName + "(s)");
        }

        // new index hasn't been analyzed yet
        splits = getAllSplits(conn, viewIndexName2);
        assertEquals(saltBuckets == null ? 1 : 3, splits.size());

        // analyze table should analyze all view data
        analyzeTable(conn, fullTableName);
        splits = getAllSplits(conn, viewIndexName2);
        assertEquals(saltBuckets == null ? 6 : 8, splits.size());

        query = "SELECT k1, k2, s FROM " + viewName + " WHERE s = 'foo'";
        Statement statement = conn.createStatement();
        rs = statement.executeQuery(query);
        Scan scan = statement.unwrap(PhoenixStatement.class).getQueryPlan().getContext().getScan();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals(120, rs.getInt(2));
        assertEquals("foo", rs.getString(3));
        assertFalse(rs.next());
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        String physicalTableName;
        if (localIndex) {
            physicalTableName = fullTableName;
            assertEquals("CLIENT PARALLEL " + (saltBuckets == null ? 1 : saltBuckets) + "-WAY RANGE SCAN OVER "
                    + fullTableName + " [" + (2) + ",'foo']\n" + "    SERVER FILTER BY FIRST KEY ONLY\n"
                    + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
        } else {
            physicalTableName = viewIndexPhysicalName;
            assertEquals(
                    saltBuckets == null
                            ? "CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + viewIndexPhysicalName + " ["
                                    + (Long.MIN_VALUE + 1) + ",'foo']\n" + "    SERVER FILTER BY FIRST KEY ONLY"
                            : "CLIENT PARALLEL " + saltBuckets + "-WAY RANGE SCAN OVER " + viewIndexPhysicalName
                                    + " [0," + (Long.MIN_VALUE + 1) + ",'foo'] - [" + (saltBuckets.intValue() - 1)
                                    + "," + (Long.MIN_VALUE + 1) + ",'foo']\n"
                                    + "    SERVER FILTER BY FIRST KEY ONLY\n" + "CLIENT MERGE SORT",
                    QueryUtil.getExplainPlan(rs));
        }
        conn.close();
        return new Pair<>(physicalTableName, scan);
    }
    
    @Test
    public void testChildViewCreationFails() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
        String fullViewName1 = FAILED_VIEWNAME;
        String fullViewName2 = SchemaUtil.getTableName(SCHEMA3, generateUniqueName());
        
        String tableDdl = "CREATE TABLE " + fullTableName + "  (k INTEGER NOT NULL PRIMARY KEY, v1 DATE)" + tableDDLOptions;
        conn.createStatement().execute(tableDdl);
        String ddl = "CREATE VIEW " + fullViewName1 + " (v2 VARCHAR) AS SELECT * FROM " + fullTableName + " WHERE k > 5";
        try {
            conn.createStatement().execute(ddl);
            fail();
        }
        catch (PhoenixIOException e){
        }
        ddl = "CREATE VIEW " + fullViewName2 + "(v2 VARCHAR) AS SELECT * FROM " + fullTableName + " WHERE k > 10";
        conn.createStatement().execute(ddl);
        
        // the first child view should not exist
        try {
            PhoenixRuntime.getTableNoCache(conn, fullViewName1);
            fail();
        } catch (SQLException e) {
        }
        
        // we should be able to load the table
        PhoenixRuntime.getTableNoCache(conn, fullTableName);
        // we should be able to load the second view
        PhoenixRuntime.getTableNoCache(conn, fullViewName2);
    }

    @Test
    public void testConcurrentViewCreationAndTableDrop() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl())) {
            String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
            String fullViewName1 = SLOW_VIEWNAME_PREFIX + "_" + generateUniqueName();
            latch1 = new CountDownLatch(1);
            latch2 = new CountDownLatch(1);
            String tableDdl =
                    "CREATE TABLE " + fullTableName + "  (k INTEGER NOT NULL PRIMARY KEY, v1 DATE)"
                            + tableDDLOptions;
            conn.createStatement().execute(tableDdl);

            ExecutorService executorService = Executors.newFixedThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = Executors.defaultThreadFactory().newThread(r);
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

            // create the view in a separate thread (which will take some time
            // to complete)
            Future<Exception> future =
                    executorService.submit(new CreateViewRunnable(fullTableName, fullViewName1));
            // wait till the thread makes the rpc to create the view
            latch1.await();
            tableDdl = "DROP TABLE " + fullTableName;
            // drop table goes through first and so the view creation should fail
            conn.createStatement().execute(tableDdl);
            latch2.countDown();

            Exception e = future.get();
            assertTrue("Expected TableNotFoundException since drop table goes through first",
                    e instanceof TableNotFoundException &&
                            fullTableName.equals(((TableNotFoundException) e).getTableName()));

        }
    }

    @Test
    public void testConcurrentAddSameColumnDifferentType() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl())) {
            latch1 = null;
            latch2 = null;
            String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
            String fullViewName1 = SLOW_VIEWNAME_PREFIX + "_" + generateUniqueName();
            String fullViewName2 = SchemaUtil.getTableName(SCHEMA3, generateUniqueName());
            // create base table
            String tableDdl =
                    "CREATE TABLE " + fullTableName + "  (k INTEGER NOT NULL PRIMARY KEY, v1 DATE)"
                            + tableDDLOptions;
            conn.createStatement().execute(tableDdl);
            // create a view
            String ddl =
                    "CREATE VIEW " + fullViewName1 + " (v2 VARCHAR) AS SELECT * FROM "
                            + fullTableName + " WHERE k = 6";
            conn.createStatement().execute(ddl);

            latch1 = new CountDownLatch(1);
            latch2 = new CountDownLatch(1);
            ExecutorService executorService = Executors.newFixedThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = Executors.defaultThreadFactory().newThread(r);
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

            // add a column with the same name and different type to the view in a separate thread
            // (which will take some time to complete)
            Future<Exception> future = executorService.submit(new AddColumnRunnable(fullViewName1));
            // wait till the thread makes the rpc to add the column
            boolean result = latch1.await(2, TimeUnit.MINUTES);
            if (!result) {
                fail("The create view rpc look too long");
            }
            tableDdl = "ALTER TABLE " + fullTableName + " ADD v3 INTEGER";
            try {
                // add the same column to the base table with a different type
                conn.createStatement().execute(tableDdl);
                fail("Creating a view while concurrently dropping the base table should fail");
            } catch (ConcurrentTableMutationException e) {
            }
            latch2.countDown();

            Exception e = future.get();
            assertNull(e);

            // add a the same column to the another view  to ensure that the cell used
            // to prevent concurrent modifications was removed
            ddl = "CREATE VIEW " + fullViewName2 + " (v2 VARCHAR) AS SELECT * FROM " 
                    + fullTableName + " WHERE k = 6";
            conn.createStatement().execute(ddl);
            tableDdl = "ALTER VIEW " + fullViewName2 + " ADD v3 INTEGER";
            conn.createStatement().execute(tableDdl);
        }
    }
    
    @Test
    public void testConcurrentAddDifferentColumn() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl())) {
            latch1 = null;
            latch2 = null;
            String fullTableName = SchemaUtil.getTableName(SCHEMA1, generateUniqueName());
            String fullViewName1 = SLOW_VIEWNAME_PREFIX + "_" + generateUniqueName();
            String fullViewName2 = SchemaUtil.getTableName(SCHEMA3, generateUniqueName());
            String fullViewName3 = SchemaUtil.getTableName(SCHEMA4, generateUniqueName());
            // create base table
            String tableDdl =
                    "CREATE TABLE " + fullTableName + "  (k INTEGER NOT NULL PRIMARY KEY, v1 DATE)"
                            + tableDDLOptions;
            conn.createStatement().execute(tableDdl);
            // create two views
            String ddl =
                    "CREATE VIEW " + fullViewName1 + " (v2 VARCHAR) AS SELECT * FROM "
                            + fullTableName + " WHERE k = 6";
            conn.createStatement().execute(ddl);
            ddl =
                    "CREATE VIEW " + fullViewName3 + " (v2 VARCHAR) AS SELECT * FROM "
                            + fullTableName + " WHERE k = 7";
            conn.createStatement().execute(ddl);

            latch1 = new CountDownLatch(1);
            latch2 = new CountDownLatch(1);
            ExecutorService executorService = Executors.newFixedThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = Executors.defaultThreadFactory().newThread(r);
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

            // add a column to a view in a separate thread (we slow this operation down)
            Future<Exception> future = executorService.submit(new AddColumnRunnable(fullViewName1));
            // wait till the thread makes the rpc to add the column
            boolean result = latch1.await(2, TimeUnit.MINUTES);
            if (!result) {
                fail("The alter view rpc look too long");
            }
            tableDdl = "ALTER VIEW " + fullViewName3 + " ADD v4 INTEGER";
            try {
                // add a column to another view 
                conn.createStatement().execute(tableDdl);
                if (columnEncoded) {
                    // this should fail as the previous add column is still not complete
                    fail(
                        "Adding columns to two different views concurrently where the base table uses encoded column should fail");
                }
            } catch (ConcurrentTableMutationException e) {
                if (!columnEncoded) {
                    // this should not fail as we don't need to update the parent table for non
                    // column encoded tables
                    fail(
                        "Adding columns to two different views concurrently where the base table does not use encoded columns should succeed");
                }
            }
            latch2.countDown();

            Exception e = future.get();
            // if the base table uses column encoding then the add column operation for fullViewName1 fails
            assertNull(e);

            // add a the same column to the another view  to ensure that the cell used
            // to prevent concurrent modifications was removed
            ddl = "CREATE VIEW " + fullViewName2 + " (v2 VARCHAR) AS SELECT * FROM " 
                    + fullTableName + " WHERE k = 6";
            conn.createStatement().execute(ddl);
            tableDdl = "ALTER VIEW " + fullViewName2 + " ADD v3 INTEGER";
            conn.createStatement().execute(tableDdl);
        }
    }

    private class CreateViewRunnable implements Callable<Exception> {
        private final String fullTableName;
        private final String fullViewName;

        public CreateViewRunnable(String fullTableName, String fullViewName) {
            this.fullTableName = fullTableName;
            this.fullViewName = fullViewName;
        }

        @Override
        public Exception call() throws Exception {
            Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
            try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
                String ddl =
                        "CREATE VIEW " + fullViewName + " (v2 VARCHAR) AS SELECT * FROM "
                                + fullTableName + " WHERE k = 5";
                conn.createStatement().execute(ddl);
            } catch (SQLException e) {
                return e;
            }
            return null;
        }
    }

    private class AddColumnRunnable implements Callable<Exception> {
        private final String fullViewName;

        public AddColumnRunnable(String fullViewName) {
            this.fullViewName = fullViewName;
        }

        @Override
        public Exception call() throws Exception {
            Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
            try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
                String ddl = "ALTER VIEW " + fullViewName + " ADD v3 CHAR(15)";
                conn.createStatement().execute(ddl);
            } catch (SQLException e) {
                return e;
            }
            return null;
        }
    }
}
