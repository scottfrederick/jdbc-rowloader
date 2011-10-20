Description
===========

JDBCRowLoader is an implementation of an SQLFire RowLoader, which is used to lazily load rows into VMware vFabric SQLFire
from an archival database. For more information on SQLFire and RowLoader, see the SQLFire documentation at
http://www.vmware.com/products/datacenter-virtualization/vfabric-sqlfire/overview.html. 

This implementation (and documentation) is based on the JDBCRowLoader example in the SQLFire documentation. It works with 
the "SQLFire10Beta" version of SQLFire.

JDBCRowLoader has the following features:

 * It can be used for any JDBC data source (provided the driver is available in the classpath of the server).
 * It can be used for any table, although a separate instance of the RowLoader is created for each table.
 * It will pool JDBC Connections and PreparedStatements, with a configurable minimum and maximum number of connections.
 * It uses the Connection.isReadOnly(true) setting to request the driver to optimize the transaction settings for reads.

Building
========

An Apache Maven build file is provided to build JDBCRowLoader. 

The Maven build for JDBCRowLoader requires two jar files from the SQLFire distribution to compile. These jar files should be added 
to your local Maven repository before building JDBCRowLoader. The jar files can be found in the "lib" directory of the SQLFire 
distribution. To install them to Maven, change to the SQLFire "lib" directory and run the following commands:

    mvn install:install-file -DgroupId=com.vmware.sqlfire -DartifactId=sqlfire -Dversion=1.0-beta -Dpackaging=jar -Dfile=sqlfire.jar
    mvn install:install-file -DgroupId=com.vmware.sqlfire -DartifactId=gemfire -Dversion=1.0-beta -Dpackaging=jar -Dfile=gemfire.jar

After the SQLFire jar files are installed, you can build JDBCRowLoader with the following command:

    mvn clean package

This should generate a jdbc-rowloader-<version>.jar in the "target" directory.

Installation
============

The JDBCRowLoader jar file must be available in the classpath of any SQLFire server nodes that will contain tables with the JDBCRowLoader
attached to them. This is done by adding the "-classpath" argument when starting a SQLFire node. Assuming the jdbc-rowloader-<version>.jar 
file is copied into the "lib" directory under the SQLFire installation directly, and the "sqlf" command is run from the SQLFire installation 
directory, then the following command will start a SQLFire server node with JDBCRowLoader available:

    sqlf server start -dir=server1 -client-port=1527 -classpath=lib/jdbc-rowloader-<version>.jar 
	 
The JDBC driver jar file for the archival database will typically be included in the SQLFire classpath using the same mechanism. 

Configuration
=============

A RowLoader is attached to a table in SQLFire using the SYS.ATTACH_ROWLOADER built-in procedure. A RowLoader must be attached to each 
table that will be lazily loaded from an archival database. 

The JDBCRowLoader is configured with a string passed as the 4th parameter to the SYS.ATTACH_LOADER procedure. The init 
string should contain a delimited set of parameters for the RowLoader.

The first character in the init string is used as the delimiter for the rest of the parameters, so the string should start
with a delimiter character. 

Accepted parameters are:

 * url (required) - the JDBC URL of the archival database to connect to
 * query-string (see note) - a SELECT statement
 * query-columns (see note) - a comma-delimited list of column names
 * min-connections (optional, default is 1) - the minimum number of connections to maintain in the connection pool
 * max-connections (optional, default is 1) - the maximum number of connections to maintain in the connection pool
 * connection-timeout (optional, default is 3000) - the maximum amount of time to wait, in milliseconds, for a connection
        to become available in the connection pool
      
Note: Either the query-string or query-columns parameter is required. If the query-string parameter is provided, the 
statement will be used as-is to query the archive database. If the query-columns parameter is provided, the comma-delimited 
column names will be used in the WHERE clause of a SELECT statement that is used to query the archive database. If both 
query-string and query-columns are provided, query-string will be used.  

Any other parameters contained in the init string are passed to the JDBC connection when it is created.

There is no requirement that the schema or table name in SQLFire match the schema and/or table name in the archive database.
If the column layout of the archive table matches the column layout of the SQLFire table, you may use SELECT * in the query string.
If the column layout of the archive table does not match the column layout of the SQLFire table, you must explicitly provide 
and order the column names in the query-string SELECT statement or the query-columns list so that the result set will match the 
layout of the SQLFire table.

