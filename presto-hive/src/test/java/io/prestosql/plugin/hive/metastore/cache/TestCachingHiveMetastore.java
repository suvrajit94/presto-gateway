/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive.metastore.cache;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.airlift.units.Duration;
import io.prestosql.plugin.hive.HiveConfig;
import io.prestosql.plugin.hive.HiveMetastoreClosure;
import io.prestosql.plugin.hive.PartitionStatistics;
import io.prestosql.plugin.hive.authentication.HiveAuthenticationConfig;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.Partition;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.plugin.hive.metastore.thrift.BridgingHiveMetastore;
import io.prestosql.plugin.hive.metastore.thrift.MetastoreLocator;
import io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient;
import io.prestosql.plugin.hive.metastore.thrift.ThriftHiveMetastore;
import io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreClient;
import io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreConfig;
import io.prestosql.plugin.hive.metastore.thrift.ThriftMetastoreStats;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.prestosql.plugin.hive.metastore.HiveColumnStatistics.createIntegerColumnStatistics;
import static io.prestosql.plugin.hive.metastore.cache.CachingHiveMetastore.cachingHiveMetastore;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.BAD_DATABASE;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.BAD_PARTITION;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_COLUMN;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_DATABASE;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_PARTITION1;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_PARTITION2;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_PARTITION_VALUES1;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_ROLES;
import static io.prestosql.plugin.hive.metastore.thrift.MockThriftMetastoreClient.TEST_TABLE;
import static io.prestosql.testing.TestingConnectorSession.SESSION;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestCachingHiveMetastore
{
    private static final HiveIdentity IDENTITY = new HiveIdentity(SESSION);
    private static final PartitionStatistics TEST_STATS = PartitionStatistics.builder()
            .setColumnStatistics(ImmutableMap.of(TEST_COLUMN, createIntegerColumnStatistics(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty())))
            .build();

    private MockThriftMetastoreClient mockClient;
    private CachingHiveMetastore metastore;
    private ThriftMetastoreStats stats;

    @BeforeMethod
    public void setUp()
    {
        mockClient = new MockThriftMetastoreClient();
        ThriftHiveMetastore thriftHiveMetastore = createThriftHiveMetastore();
        ListeningExecutorService executor = listeningDecorator(newCachedThreadPool(daemonThreadsNamed("test-%s")));
        metastore = (CachingHiveMetastore) cachingHiveMetastore(
                new BridgingHiveMetastore(thriftHiveMetastore),
                executor,
                new Duration(5, TimeUnit.MINUTES),
                Optional.of(new Duration(1, TimeUnit.MINUTES)),
                1000);
        stats = thriftHiveMetastore.getStats();
    }

    private ThriftHiveMetastore createThriftHiveMetastore()
    {
        MetastoreLocator metastoreLocator = new MockMetastoreLocator(mockClient);
        return new ThriftHiveMetastore(metastoreLocator, new HiveConfig(), new ThriftMetastoreConfig(), new HiveAuthenticationConfig(), HDFS_ENVIRONMENT);
    }

    @Test
    public void testGetAllDatabases()
    {
        assertEquals(mockClient.getAccessCount(), 0);
        assertEquals(metastore.getAllDatabases(), ImmutableList.of(TEST_DATABASE));
        assertEquals(mockClient.getAccessCount(), 1);
        assertEquals(metastore.getAllDatabases(), ImmutableList.of(TEST_DATABASE));
        assertEquals(mockClient.getAccessCount(), 1);

        metastore.flushCache();

        assertEquals(metastore.getAllDatabases(), ImmutableList.of(TEST_DATABASE));
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testGetAllTable()
    {
        assertEquals(mockClient.getAccessCount(), 0);
        assertEquals(metastore.getAllTables(TEST_DATABASE), ImmutableList.of(TEST_TABLE));
        assertEquals(mockClient.getAccessCount(), 1);
        assertEquals(metastore.getAllTables(TEST_DATABASE), ImmutableList.of(TEST_TABLE));
        assertEquals(mockClient.getAccessCount(), 1);

        metastore.flushCache();

        assertEquals(metastore.getAllTables(TEST_DATABASE), ImmutableList.of(TEST_TABLE));
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testInvalidDbGetAllTAbles()
    {
        assertTrue(metastore.getAllTables(BAD_DATABASE).isEmpty());
    }

    @Test
    public void testGetTable()
    {
        assertEquals(mockClient.getAccessCount(), 0);
        assertNotNull(metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE));
        assertEquals(mockClient.getAccessCount(), 1);
        assertNotNull(metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE));
        assertEquals(mockClient.getAccessCount(), 1);

        metastore.flushCache();

        assertNotNull(metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE));
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testInvalidDbGetTable()
    {
        assertFalse(metastore.getTable(IDENTITY, BAD_DATABASE, TEST_TABLE).isPresent());

        assertEquals(stats.getGetTable().getThriftExceptions().getTotalCount(), 0);
        assertEquals(stats.getGetTable().getTotalFailures().getTotalCount(), 0);
    }

    @Test
    public void testGetPartitionNames()
    {
        ImmutableList<String> expectedPartitions = ImmutableList.of(TEST_PARTITION1, TEST_PARTITION2);
        assertEquals(mockClient.getAccessCount(), 0);
        assertEquals(metastore.getPartitionNames(IDENTITY, TEST_DATABASE, TEST_TABLE).get(), expectedPartitions);
        assertEquals(mockClient.getAccessCount(), 1);
        assertEquals(metastore.getPartitionNames(IDENTITY, TEST_DATABASE, TEST_TABLE).get(), expectedPartitions);
        assertEquals(mockClient.getAccessCount(), 1);

        metastore.flushCache();

        assertEquals(metastore.getPartitionNames(IDENTITY, TEST_DATABASE, TEST_TABLE).get(), expectedPartitions);
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testInvalidGetPartitionNames()
    {
        assertEquals(metastore.getPartitionNames(IDENTITY, BAD_DATABASE, TEST_TABLE).get(), ImmutableList.of());
    }

    @Test
    public void testGetPartitionNamesByParts()
    {
        ImmutableList<String> parts = ImmutableList.of();
        ImmutableList<String> expectedPartitions = ImmutableList.of(TEST_PARTITION1, TEST_PARTITION2);

        assertEquals(mockClient.getAccessCount(), 0);
        assertEquals(metastore.getPartitionNamesByParts(IDENTITY, TEST_DATABASE, TEST_TABLE, parts).get(), expectedPartitions);
        assertEquals(mockClient.getAccessCount(), 1);
        assertEquals(metastore.getPartitionNamesByParts(IDENTITY, TEST_DATABASE, TEST_TABLE, parts).get(), expectedPartitions);
        assertEquals(mockClient.getAccessCount(), 1);

        metastore.flushCache();

        assertEquals(metastore.getPartitionNamesByParts(IDENTITY, TEST_DATABASE, TEST_TABLE, parts).get(), expectedPartitions);
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testInvalidGetPartitionNamesByParts()
    {
        ImmutableList<String> parts = ImmutableList.of();
        assertFalse(metastore.getPartitionNamesByParts(IDENTITY, BAD_DATABASE, TEST_TABLE, parts).isPresent());
    }

    @Test
    public void testGetPartitionsByNames()
    {
        assertEquals(mockClient.getAccessCount(), 0);
        Table table = metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE).get();
        assertEquals(mockClient.getAccessCount(), 1);

        // Select half of the available partitions and load them into the cache
        assertEquals(metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(TEST_PARTITION1)).size(), 1);
        assertEquals(mockClient.getAccessCount(), 2);

        // Now select all of the partitions
        assertEquals(metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(TEST_PARTITION1, TEST_PARTITION2)).size(), 2);
        // There should be one more access to fetch the remaining partition
        assertEquals(mockClient.getAccessCount(), 3);

        // Now if we fetch any or both of them, they should not hit the client
        assertEquals(metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(TEST_PARTITION1)).size(), 1);
        assertEquals(metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(TEST_PARTITION2)).size(), 1);
        assertEquals(metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(TEST_PARTITION1, TEST_PARTITION2)).size(), 2);
        assertEquals(mockClient.getAccessCount(), 3);

        metastore.flushCache();

        // Fetching both should only result in one batched access
        assertEquals(metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(TEST_PARTITION1, TEST_PARTITION2)).size(), 2);
        assertEquals(mockClient.getAccessCount(), 5);
    }

    @Test
    public void testListRoles()
    {
        assertEquals(mockClient.getAccessCount(), 0);

        assertEquals(metastore.listRoles(), TEST_ROLES);
        assertEquals(mockClient.getAccessCount(), 1);

        assertEquals(metastore.listRoles(), TEST_ROLES);
        assertEquals(mockClient.getAccessCount(), 1);

        metastore.flushCache();

        assertEquals(metastore.listRoles(), TEST_ROLES);
        assertEquals(mockClient.getAccessCount(), 2);

        metastore.createRole("role", "grantor");

        assertEquals(metastore.listRoles(), TEST_ROLES);
        assertEquals(mockClient.getAccessCount(), 3);

        metastore.dropRole("testrole");

        assertEquals(metastore.listRoles(), TEST_ROLES);
        assertEquals(mockClient.getAccessCount(), 4);
    }

    @Test
    public void testGetTableStatistics()
    {
        assertEquals(mockClient.getAccessCount(), 0);

        Table table = metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE).get();
        assertEquals(mockClient.getAccessCount(), 1);

        assertEquals(metastore.getTableStatistics(IDENTITY, table), TEST_STATS);
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testGetPartitionStatistics()
    {
        assertEquals(mockClient.getAccessCount(), 0);

        Table table = metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE).get();
        assertEquals(mockClient.getAccessCount(), 1);

        Partition partition = metastore.getPartition(IDENTITY, table, TEST_PARTITION_VALUES1).get();
        assertEquals(mockClient.getAccessCount(), 2);

        assertEquals(metastore.getPartitionStatistics(IDENTITY, table, ImmutableList.of(partition)), ImmutableMap.of(TEST_PARTITION1, TEST_STATS));
        assertEquals(mockClient.getAccessCount(), 3);
    }

    @Test
    public void testUpdatePartitionStatistics()
    {
        assertEquals(mockClient.getAccessCount(), 0);

        HiveMetastoreClosure hiveMetastoreClosure = new HiveMetastoreClosure(metastore);

        Table table = hiveMetastoreClosure.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE).get();
        assertEquals(mockClient.getAccessCount(), 1);

        hiveMetastoreClosure.updatePartitionStatistics(IDENTITY, table.getDatabaseName(), table.getTableName(), TEST_PARTITION1, identity());
        assertEquals(mockClient.getAccessCount(), 5);
    }

    @Test
    public void testInvalidGetPartitionsByNames()
    {
        Table table = metastore.getTable(IDENTITY, TEST_DATABASE, TEST_TABLE).get();
        Map<String, Optional<Partition>> partitionsByNames = metastore.getPartitionsByNames(IDENTITY, table, ImmutableList.of(BAD_PARTITION));
        assertEquals(partitionsByNames.size(), 1);
        Optional<Partition> onlyElement = Iterables.getOnlyElement(partitionsByNames.values());
        assertFalse(onlyElement.isPresent());
    }

    @Test
    public void testNoCacheExceptions()
    {
        // Throw exceptions on usage
        mockClient.setThrowException(true);
        try {
            metastore.getAllDatabases();
        }
        catch (RuntimeException ignored) {
        }
        assertEquals(mockClient.getAccessCount(), 1);

        // Second try should hit the client again
        try {
            metastore.getAllDatabases();
        }
        catch (RuntimeException ignored) {
        }
        assertEquals(mockClient.getAccessCount(), 2);
    }

    @Test
    public void testCachingHiveMetastoreCreationWithTtlOnly()
    {
        CachingHiveMetastoreConfig config = new CachingHiveMetastoreConfig();
        config.setMetastoreCacheTtl(new Duration(10, TimeUnit.MILLISECONDS));

        CachingHiveMetastore metastore = createMetastoreWithDirectExecutor(config);

        assertThat(metastore).isNotNull();
    }

    private CachingHiveMetastore createMetastoreWithDirectExecutor(CachingHiveMetastoreConfig config)
    {
        return (CachingHiveMetastore) cachingHiveMetastore(
                new BridgingHiveMetastore(createThriftHiveMetastore()),
                directExecutor(),
                config);
    }

    private static class MockMetastoreLocator
            implements MetastoreLocator
    {
        private final ThriftMetastoreClient client;

        private MockMetastoreLocator(ThriftMetastoreClient client)
        {
            this.client = client;
        }

        @Override
        public ThriftMetastoreClient createMetastoreClient(Optional<String> delegationToken)
        {
            return client;
        }
    }
}