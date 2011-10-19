package com.vmware.sqlfire.loader;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.mockobjects.sql.MockConnection2;
import com.mockobjects.sql.MockPreparedStatement;
import com.mockobjects.sql.MockSingleRowResultSet;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;

public class JDBCRowLoaderTest {
	private static final String JDBC_URL = "jdbc:db://host:port";
	private static final String QUERY_STRING = "select * from schema.table where id=?";

	private static ClientCache cache;

	private JDBCRowLoader loader;
	private MockConnectionManager connectionManager;
	private MockConnection2 connection;
	private MockPreparedStatement preparedStatement;

	@BeforeClass
	public static void setUpClass() throws Exception {
		cache = new ClientCacheFactory().create();
	}
	
	@AfterClass
	public static void tearDownClass() throws Exception {
		cache.close();
	}

	@Before
	public void setUp() throws Exception {
		connectionManager = new MockConnectionManager();
		connection = new MockConnectionWithReadOnly();
		preparedStatement = new MockPreparedStatement();
		
		loader = new JDBCRowLoader() {
			protected Connection getDatabaseConnection() throws SQLException {
				return connectionManager.createConnection();
			}
		};
	}
	
	@Test
	public void testInit_WithDefaults() {
		loader.init("");	
		
		assertEquals("", loader.url);
		assertEquals("", loader.queryString);
		assertEquals(1, loader.minConnections);
		assertEquals(1, loader.maxConnections);
		assertEquals(3000, loader.connectionTimeout);
		assertTrue(loader.props.isEmpty());
	}

	@Test
	public void testInit_WithCustomProperties() {
		loader.init("|user=me|password=pass");
		
		assertEquals(2, loader.props.size());
		assertEquals("me", loader.props.getProperty("user"));
		assertEquals("pass", loader.props.getProperty("password"));
	}
	
	@Test
	public void testInit_WithQueryString() {
		connection.setupAddPreparedStatement(QUERY_STRING, preparedStatement);
		connection.setupAddPreparedStatement(QUERY_STRING, preparedStatement);

		loader.init("|url=" + JDBC_URL +	
				"|query-string=" + QUERY_STRING + 
				"|min-connections=2" +
				"|max-connections=4" +
				"|connection-timeout=1000");
		
		assertEquals(JDBC_URL, loader.url);
		assertEquals(QUERY_STRING, loader.queryString);
		assertEquals(2, loader.minConnections);
		assertEquals(4, loader.maxConnections);
		assertEquals(1000, loader.connectionTimeout);
	}
	
	@Test
	public void testGetRow_MinimumStatementsCreated() throws Exception {
		int minConnections = 5;
		
		for (int i = 0; i < minConnections; i++) {
			connection.setupAddPreparedStatement(QUERY_STRING, preparedStatement);
		}
		
		MockSingleRowResultSet results = new MockSingleRowResultSet();
		preparedStatement.addResultSet(results);
		
		CountDownLatch latch = new CountDownLatch(minConnections);
		connectionManager.setLatch(latch);

		loader.init("|url=" + JDBC_URL + "|query-string=" + QUERY_STRING + "|min-connections=" + minConnections);
		loader.getRow("schema", "table", new Object[0]);

		latch.await(2, TimeUnit.SECONDS);
		
		assertEquals(0, latch.getCount());
		assertEquals(minConnections, connectionManager.getCreationCount());
	}
	
	@Test
	public void testPopulatePreparedStatement_WithQueryString() throws Exception {
		Object[] params = new Object[] { 123 };		
		preparedStatement.addExpectedSetParameters(params);

		MockSingleRowResultSet results = new MockSingleRowResultSet();
		preparedStatement.addResultSet(results);

		connection.setupAddPreparedStatement(QUERY_STRING, preparedStatement);

		loader.init("|url=" + JDBC_URL + "|query-string=" + QUERY_STRING);
		Object actual = loader.getRow("schema", "table", params);
		
		preparedStatement.verify();
		connection.verify();
		assertSame(actual, results);
	}
	
	@Test
	public void testPopulatePreparedStatement_WithQueryColumns() throws Exception {
		Object[] params = new Object[] { 123, "me", 456 };
		preparedStatement.addExpectedSetParameters(params);

		MockSingleRowResultSet results = new MockSingleRowResultSet();
		preparedStatement.addResultSet(results);

		connection.setupAddPreparedStatement("SELECT * FROM schema.table WHERE id=? AND name=? AND value=?", preparedStatement);

		loader.init("|url=" + JDBC_URL + "|query-columns=id,name,value");
		Object actual = loader.getRow("schema", "table", params);
		
		preparedStatement.verify();
		connection.verify();
		assertSame(actual, results);
	}
	
	@Test
	public void testPopulatePreparedStatement_WithQueryColumns_NoSchema() throws Exception {
		Object[] params = new Object[] { 123, "me", 456 };
		preparedStatement.addExpectedSetParameters(params);

		MockSingleRowResultSet results = new MockSingleRowResultSet();
		preparedStatement.addResultSet(results);

		connection.setupAddPreparedStatement("SELECT * FROM table WHERE id=? AND name=? AND value=?", preparedStatement);

		loader.init("|url=" + JDBC_URL + "|query-columns=id,name,value");
		Object actual = loader.getRow("", "table", params);
		
		preparedStatement.verify();
		connection.verify();
		assertSame(actual, results);
	}
	
	class MockConnectionManager {
    	private Integer creationCount = 0;
		private CountDownLatch latch = new CountDownLatch(0); 
    	
		public Connection createConnection() {
			synchronized(latch) {
				creationCount++;
				latch.countDown();
			}
			return connection;
		}

		public int getCreationCount() {
			return creationCount;
		}
		
		public void setLatch(CountDownLatch latch) {
			this.latch = latch;
		}
	}
    
    class MockConnectionWithReadOnly extends MockConnection2 {
    	@Override
    	public void setReadOnly(boolean readOnly) {
    	}
    }
}
