package com.vmware.sqlfire.loader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.CacheFactory;
import com.vmware.sqlfire.callbacks.RowLoader;

/** 
 * JDBCRowLoader is an implementation of the RowLoader interface that loads rows from a JDBC data source.
 * 
 * JDBCRowLoader has the following features:
 * 
 *    <ul>
 *    <li>It can be used for any JDBC data source (provided the driver is available in the classpath of the server).</li>
 *    <li>It can be used for any table, although a separate instance of the RowLoader is created for each table.</li>
 *    <li>It will pool JDBC Connections and PreparedStatements, with a configurable minimum and maximum number of connections.</li>
 *    <li>It uses the Connection.isReadOnly(true) setting to request the driver to optimize the transaction settings for reads.</li>
 *    </ul>
 * 
 * The elements of the primary key will be passed into the JDBCRowLoader when it is invoked, in the order that the columns are 
 * defined in the SQLFire table. They will be passed as parameters into the PreparedStatement in that order, so you must 
 * structure the WHERE clause of the query string so that the elements are passed in the correct order.
 * 
 * The JDBCRowLoader is configured with a string passed as the 4th parameter to the SYS.ATTACH_ROWLOADER procedure. The init 
 * string should contain a delimited set of parameters for the RowLoader.
 * 
 * The first character in the init string is used as the delimiter for the rest of the parameters, so the string should start
 * with a delimiter character. 
 * 
 * Accepted parameters are:
 * 
 *     <ul>
 *     <li>url (required) - the JDBC URL of the database to connect to</li>
 *     <li>query-string (see note) - a SELECT statement</li>
 *     <li>query-columns (see note) - a comma-delimited list of column names</li>
 *     <li>min-connections (optional, default is 1) - the minimum number of connections to maintain in the connection pool</li>
 *     <li>max-connections (optional, default is 1) - the maximum number of connections to maintain in the connection pool</li>
 *     <li>connection-timeout (optional, default is 3000) - the maximum amount of time to wait, in milliseconds, for a connection
 *         to become available in the connection pool</li>
 *     </ul>
 *       
 * Note: Either the query-string or query-columns parameter is required. If the query-string parameter is provided, the 
 * statement will be used as-is to query the archive database. If the query-columns parameter is provided, the comma-delimited 
 * column names will be used in the WHERE clause of a SELECT statement that is used to query the archive database. If both 
 * query-string and query-columns are provided, query-string will be used.  
 * 
 * Any other parameters passed in the init string are passed to the JDBC connection when it is created.
 * 
 * There is no requirement that the schema or table name in SQLFire match the schema and/or table name in the archive database.
 * If the column layout of the archive table matches the column layout of the SQLFire table, you may use SELECT * in the query string.
 * If the column layout of the archive table does not match the column layout of the SQLFire table, you must explicitly provide 
 * and order the column names in the query-string SELECT statement or the query-columns list so that the result set will match the 
 * layout of the SQLFire table.
 * 
 */
public class JDBCRowLoader implements RowLoader {
	private static final int VENDOR_CODE_ARCHIVE_ERROR = 0;
	private static final int VENDOR_CODE_TIMEOUT = 1;
	private static final String QUERY_SELECT_STRING = "SELECT *";
	private static final String QUERY_FROM_STRING = " FROM ";
	private static final String QUERY_WHERE_STRING = " WHERE ";
	private static final String QUERY_AND_STRING = " AND ";

	private final Queue<PreparedStatement[]> waitingQueries = new LinkedList<PreparedStatement[]>();
	private final Queue<PreparedStatement> availableStatements = new LinkedList<PreparedStatement>();
	
	private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();

	private final LogWriter log = CacheFactory.getAnyInstance().getLogger();

	protected Properties props = new Properties();
	protected String url = "";
	protected String queryString = "";
	protected String queryColumns = "";
	protected int minConnections;
	protected int maxConnections;
	protected long connectionTimeout;

	private Integer connectionCount = 0;

	public static JDBCRowLoader create() {
		return new JDBCRowLoader();
	}

	public void init(String initStr) {
		log.entering("JDBCRowLoader", "init()");
		
		loadParametersFromInitString(initStr);
	}

	public Object getRow(String schema, String table, Object[] params) throws SQLException {
		logGetRowEntering(schema, table, params);
		
		if (connectionCount < minConnections) {
			initPreparedStatements(schema, table);
		}

		PreparedStatement pstmt = getPreparedStatement(schema, table);
		populatePreparedStatement(pstmt, schema, table, params);
		return executePreparedStatement(pstmt);
	}

	private void initPreparedStatements(String schema, String table) {
		for (int i = 0; i < minConnections; i++) {
			synchronized (connectionCount) {
				connectionCount = connectionCount + 1;
			}
			Runnable creator = new StatementCreator(schema, table);
			this.backgroundExecutor.execute(creator);
		}
	}

	private PreparedStatement getPreparedStatement(String schema, String table) throws SQLException {
		PreparedStatement[] holder = new PreparedStatement[1];
		synchronized (holder) {
			getPooledStatement(holder, schema, table);
			if (holder[0] == null) {
				try {
					holder.wait(connectionTimeout);
				} catch (InterruptedException e) {
					log.warning("JDBCRowLoader interrupted while waiting for an available pooled statement.");
					log.warning(e);
					Thread.currentThread().interrupt();
				}
			}
		}
		
		PreparedStatement pstmt = holder[0];
		
		if (pstmt == null) {
			throw new SQLException(
					"Timeout waiting for pooled connection to archive database",
					"08001", VENDOR_CODE_TIMEOUT);
		}
		
		return pstmt;
	}

	private void populatePreparedStatement(PreparedStatement pstmt,	String schema, String table, Object[] params) 
			throws SQLException {
		for (int i = 0; i < params.length; i++) {
			pstmt.setObject(i + 1, params[i]);
		}
	}

	private Object executePreparedStatement(PreparedStatement pstmt) throws SQLException {
		try {
			log.info("Executing query " + pstmt.toString());
			ResultSet result = pstmt.executeQuery();
			// even if this result set is empty (i.e. no row found), just return
			// the empty result set
			log.info("Query succeeded");
			recyclePooledStatement(pstmt);
			return result;
		} catch (SQLException e) {
			// throw away the pooled statement, just in case it was the problem
			releasePooledStatement(pstmt);
			logGetRowError(e);
			throw new SQLException(
					"Error executing query from archive database",
					e.getSQLState(), VENDOR_CODE_ARCHIVE_ERROR, e);
		}
	}

	private synchronized void getPooledStatement(PreparedStatement[] holder, String schema, String table) {
		// Take the next available prepared statement. If there isn't one,
		// add the holder to the list of waiting queries.
		// The calling thread must call holder.wait() if it finds the holder empty.
		// holder.notify() will be called when a prepared statement is available
		// and put into the holder.
		holder[0] = this.availableStatements.poll();
		if (holder[0] == null) {
			this.waitingQueries.add(holder);
			synchronized (connectionCount) {
				if ((connectionCount) < maxConnections) {
					connectionCount = connectionCount + 1;
					Runnable creator = new StatementCreator(schema, table);
					this.backgroundExecutor.execute(creator);
				}
			}
		}
	}

	private synchronized void returnPooledStatement(PreparedStatement pstmt) {
		// Check to see if there are queries waiting on a statement.
		// If not, add the statement back into the pool.
		PreparedStatement[] holder = this.waitingQueries.poll();
		if (holder == null) {
			this.availableStatements.offer(pstmt);
		} else {
			synchronized (holder) {
				holder[0] = pstmt;
				holder.notify();
			}
		}
	}

	private void recyclePooledStatement(PreparedStatement pstmt) {
		StatementRecycler recycler = new StatementRecycler(pstmt);
		this.backgroundExecutor.execute(recycler);
	}

	private void releasePooledStatement(PreparedStatement pstmt) {
		StatementReleaser releaser = new StatementReleaser(pstmt);
		this.backgroundExecutor.execute(releaser);
	}

	private class StatementCreator implements Runnable {
		private final String schema;
		private final String table;

		public StatementCreator(String schema, String table) {
			this.schema = schema;
			this.table = table;
		}

		public void run() {
			if (url.isEmpty()) {
				log.error("Connection url not provided for JDBCRowLoader");
				return;
			}
			try {
				Connection con = getDatabaseConnection();
				log.info(" Successful connection to target database: " + url);
				con.setReadOnly(true);
				PreparedStatement pstmt = con.prepareStatement(buildQueryString(schema, table));
				recyclePooledStatement(pstmt);
			} catch (SQLException e) {
				// Connection count is incremented when the job is scheduled.
				// Since it has failed, decrement the counter
				synchronized (connectionCount) {
					connectionCount = connectionCount - 1;
				}
				log.error("Error connecting to target database");
				log.error(e);
			}
		}

	}

	private class StatementRecycler implements Runnable {
		private PreparedStatement pstmt;

		StatementRecycler(PreparedStatement target) {
			this.pstmt = target;
		}

		public void run() {
			try {
				pstmt.clearParameters();
				returnPooledStatement(pstmt);
			} catch (SQLException e) {
				releasePooledStatement(pstmt);
				log.warning(e);
			}
		}
	}

	private class StatementReleaser implements Runnable {
		private PreparedStatement pstmt;

		StatementReleaser(PreparedStatement target) {
			this.pstmt = target;
		}

		public void run() {
			try {
				synchronized (connectionCount) {
					connectionCount = connectionCount - 1;
				}
				pstmt.getConnection().close();
			} catch (SQLException e) {
				log.warning(e);
			}
		}
	}
	
	protected Connection getDatabaseConnection() throws SQLException {
		return DriverManager.getConnection(url, props);
	}

	private void loadParametersFromInitString(String initStr) {
		parsePropertiesFromString(initStr);
		
		logInitParameters();
		
		this.url = getProperty("url", "");
		this.queryString = getProperty("query-string", "");
		this.queryColumns = getProperty("query-columns", "");
		this.minConnections = Integer.parseInt(getProperty("min-connections", "1"));
		this.maxConnections = Integer.parseInt(getProperty("max-connections", "1"));
		this.connectionTimeout = Long.parseLong(getProperty("connection-timeout", "3000"));
	}

	private void parsePropertiesFromString(String initStr) {
		if (initStr.length() > 1) {
			String delimiter = initStr.substring(0, 1);
			String[] params = initStr.substring(1).split("\\" + delimiter);
			
			for (String parameter : params) {
				int equalsIndex = parameter.indexOf('=');
				if ((equalsIndex > 0) & (parameter.length() > equalsIndex + 1)) {
					String key = parameter.substring(0, equalsIndex).trim();
					String value = parameter.substring(equalsIndex + 1).trim();
					props.put(key, value);
				}
			}
		}
	}
	
	private String buildQueryString(String schema, String table) {
		if (!queryString.isEmpty()) {
			return queryString;
		}
		
		if (queryColumns.isEmpty()) {
			return "";
		}
		
		StringBuilder query = new StringBuilder(QUERY_SELECT_STRING);
		
		if (!schema.isEmpty() || !table.isEmpty()) {
			query.append(QUERY_FROM_STRING);
			if (!schema.isEmpty()) {
				query.append(schema).append(".");	
			}
			
			if (!table.isEmpty()) {
				query.append(table);
			}
		}
		
		String[] cols = queryColumns.split(",");
		if (cols.length > 0) {
			query.append(QUERY_WHERE_STRING);
			for (int i = 0; i < cols.length; i++) {
				String column = cols[i];
				query.append(column).append("=?");
				if (i < cols.length - 1) {
					query.append(QUERY_AND_STRING);
				}
			}
		}
		
		return query.toString();
	}
	
	private String getProperty(String key, String defaultValue) {
		Object value = props.remove(key);
		if (value == null)
			return defaultValue;
		else
			return (String) value; 
	}

	private void logGetRowEntering(String schema, String table, Object[] params) {
		log.entering("JDBCRowLoader", "getRow(String schema, String table, Object[] params)");
		if (log.infoEnabled()) {
			log.info("JDBCRowLoader invoked to fetch from schema <" + schema + "> on table <" + table + ">.");
			for (int i = 0; i < params.length; i++) {
				log.info(" primary key element " + i + ": " + params[i]);
			}
		}
	}

	private void logGetRowError(SQLException e) {
		log.error("Error executing prepared statement in JDBCRowLoader");
		log.error(e);
	}
	
	private void logInitParameters() {
		if (log.infoEnabled()) {
			log.info("JDBCRowLoader initialized.");
			for (Entry<Object, Object> entry : props.entrySet()) {
				if ("password".equals(entry.getKey()))
					log.info("   " + entry.getKey() + ": " + maskString((String) entry.getValue()));
				else
					log.info("   " + entry.getKey() + ": " + entry.getValue());
			}
		}
	}
	
	private String maskString(String str) {
		if (str != null) {
			char[] masked = new char[str.length()];
			for (int i = 0; i < str.length(); i++) {
				masked[i] = 'x';
			}
			return String.copyValueOf(masked);
		}
		return "";
	}
}
