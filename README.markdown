Description
===========

JDBCRowLoader is an implementation of an SQLFire RowLoader, which is used to lazily load rows into VMware vFabric SQLFire
from an archival database. For more information on SQLFire and RowLoader, see 
http://www.vmware.com/products/datacenter-virtualization/vfabric-sqlfire/overview.html.

This implementation is based on the JDBCRowLoader example in the SQLFire documentation. It works with the "SQLFire10Beta" version of
SQLFire.

Building
========

Apache Maven is required to build JDBCRowLoader. JDBCRowLoader requires to jar files from the SQLFire distribution to compile. 
These should be added to your local Maven repository before building JDBCRowLoader. The jar files can be found in the "lib"
directory of the SQLFire distribution. To install them to Maven, change to the SQLFire "lib" directory and run the following commands:

    mvn install:install-file -DgroupId=com.vmware.sqlfire -DartifactId=gemfire -Dversion=1.0-beta -Dpackaging=jar -Dfile=sqlfire.jar
    mvn install:install-file -DgroupId=com.vmware.sqlfire -DartifactId=sqlfire -Dversion=1.0-beta -Dpackaging=jar -Dfile=gemfire.jar
