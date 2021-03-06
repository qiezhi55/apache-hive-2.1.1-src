/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This source file is based on code taken from SQLLine 1.0.2
 * See SQLLine notice in LICENSE
 */
package org.apache.hive.beeline;

import jline.console.completer.ArgumentCompleter;
import jline.console.completer.Completer;
import org.apache.hive.jdbc.HiveConnection;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

class DatabaseConnection {
  private static final String HIVE_VAR_PREFIX = "hivevar:";
  private static final String HIVE_CONF_PREFIX = "hiveconf:";

  private final BeeLine beeLine;
  private Connection connection;
  private DatabaseMetaData meta;
  private final String driver;
  private final String url;
  private final Properties info;
  private Schema schema = null;
  private Completer sqlCompleter = null;

  public boolean isClosed() {
    return (null == connection);
  }

  public DatabaseConnection(BeeLine beeLine, String driver, String url,
       Properties info) throws SQLException {
    this.beeLine = beeLine;
    this.driver = driver;
    this.url = url;
    // info中包含url, user, password等信息
    this.info = info;
  }

  @Override
  public String toString() {
    return getUrl() + "";
  }


  void setCompletions(boolean skipmeta) throws SQLException, IOException {
    // HiveDatabaseMetaData的getExtraNameCharacters返回的是""
    final String extraNameCharacters =
        getDatabaseMetaData() == null || getDatabaseMetaData().getExtraNameCharacters() == null ? ""
            : getDatabaseMetaData().getExtraNameCharacters();

    // setup the completer for the database
    sqlCompleter = new ArgumentCompleter(
        new ArgumentCompleter.AbstractArgumentDelimiter() {
          // delimiters for SQL statements are any
          // non-letter-or-number characters, except
          // underscore and characters that are specified
          // by the database to be valid name identifiers.
          @Override
          public boolean isDelimiterChar(CharSequence buffer, int pos) {
            char c = buffer.charAt(pos);
            if (Character.isWhitespace(c)) {
              return true;
            }
            return !(Character.isLetterOrDigit(c))
                && c != '_'
                && extraNameCharacters.indexOf(c) == -1;
          }
        },
        new SQLCompleter(SQLCompleter.getSQLCompleters(beeLine, skipmeta)));
    // not all argument elements need to hold true
    ((ArgumentCompleter) sqlCompleter).setStrict(false);
  }


  /**
   * Connection to the specified data source.
   */
  boolean connect() throws SQLException {
    try {
      if (driver != null && driver.length() != 0) {
        Class.forName(driver);
      }
    } catch (ClassNotFoundException cnfe) {
      return beeLine.error(cnfe);
    }

    boolean isDriverRegistered = false;
    try {
      // 在Commands的connect(Properties props)方法中, 已经注册了org.apache.hive.jdbc.HiveDriver, 所以DriverManager.getDriver(getUrl())不为null, isDriverRegistered设置为true
      isDriverRegistered = DriverManager.getDriver(getUrl()) != null;
    } catch (Exception e) {
    }

    try {
      // 如果当前连接未关闭, 关闭连接
      close();
    } catch (Exception e) {
      return beeLine.error(e);
    }

    Map<String, String> hiveVars = beeLine.getOpts().getHiveVariables();
    if (hiveVars != null){
      for (Map.Entry<String, String> var : hiveVars.entrySet()) {
        // 每个key加前缀"hivevar:"
        info.put(HIVE_VAR_PREFIX + var.getKey(), var.getValue());
      }
    }

    Map<String, String> hiveConfVars = beeLine.getOpts().getHiveConfVariables();
    if (hiveConfVars != null){
      for (Map.Entry<String, String> var : hiveConfVars.entrySet()) {
        // 每个key加前缀"hiveconf:"
        info.put(HIVE_CONF_PREFIX + var.getKey(), var.getValue());
      }
    }

    // isDriverRegistered设置为true
    if (isDriverRegistered) {
      // if the driver registered in the driver manager, get the connection via the driver manager
      // 实际是调用HiveDriver的connect(String url, Properties info)方法, 返回一个new HiveConnection(url, info)
      // 调用new HiveConnection(url, info)构造方法会建立到HiveServer2的client并打开会话
      setConnection(DriverManager.getConnection(getUrl(), info));
    } else {
      beeLine.debug("Use the driver from local added jar file.");
      setConnection(getConnectionFromLocalDriver(getUrl(), info));
    }
    // 调用HiveConnection的getMetaData()方法, 该方法并没有与hiveserver2通信, 而是返回了一个由HiveConnection构造的HiveDatabaseMetaData对象, 该对象封装了HiveConnection, client和sessHandle
    setDatabaseMetaData(getConnection().getMetaData());

    try {
      // 输出信息: Connected to: Apache Hive (version 2.1.1)
      // 见BeeLine.properties的connected输入格式, getDatabaseProductName和getDatabaseProductVersion方法会通过client调用两次HiveServer2的GetInfo方法
      beeLine.info(beeLine.loc("connected", new Object[] {
          getDatabaseMetaData().getDatabaseProductName(),
          getDatabaseMetaData().getDatabaseProductVersion()}));
    } catch (Exception e) {
      beeLine.handleException(e);
    }

    try {
      // 输出信息: Driver: Hive JDBC (version 2.1.1)
      beeLine.info(beeLine.loc("driver", new Object[] {
          getDatabaseMetaData().getDriverName(),
          getDatabaseMetaData().getDriverVersion()}));
    } catch (Exception e) {
      beeLine.handleException(e);
    }

    try {
      // autoCommit默认为false, 会在控制台打印警告信息: WARN jdbc.HiveConnection: Request to set autoCommit to false; Hive does not support autoCommit=false.
      getConnection().setAutoCommit(beeLine.getOpts().getAutoCommit());
      // TODO: Setting autocommit should not generate an exception as long as it is set to false
      // beeLine.autocommitStatus(getConnection());
    } catch (Exception e) {
      beeLine.handleException(e);
    }

    try {
      // isolation默认值为: "TRANSACTION_REPEATABLE_READ", 会打印日志: Transaction isolation: TRANSACTION_REPEATABLE_READ
      beeLine.getCommands().isolation("isolation: " + beeLine.getOpts().getIsolation());
    } catch (Exception e) {
      beeLine.handleException(e);
    }

    return true;
  }

  public Connection getConnectionFromLocalDriver(String url, Properties properties) {
    Collection<Driver> drivers = beeLine.getDrivers();
    for (Driver d : drivers) {
      try {
        if (d.acceptsURL(url) && beeLine.isSupportedLocalDriver(d)) {
          String clazzName = d.getClass().getName();
          beeLine.debug("Driver name is " + clazzName);
          Driver driver =
            (Driver) Class.forName(clazzName, true, Thread.currentThread().getContextClassLoader())
              .newInstance();
          return driver.connect(url, properties);
        }
      } catch (Exception e) {
        beeLine.error("Fail to connect with a local driver due to the exception:" + e);
        beeLine.error(e);
      }
    }
    return null;
  }

  public Connection getConnection() throws SQLException {
    // 初次调用connection为null
    if (connection != null) {
      return connection;
    }
    // 调用connect方法进行连接
    connect();
    return connection;
  }

  public Connection getCurrentConnection() {
    return connection;
  }

  public void reconnect() throws Exception {
    close();
    getConnection();
  }


  public void close() {
    try {
      try {
        if (connection != null && !connection.isClosed()) {
          beeLine.output(beeLine.loc("closing", connection));
          connection.close();
        }
      } catch (Exception e) {
        beeLine.handleException(e);
      }
    } finally {
      setConnection(null);
      setDatabaseMetaData(null);
    }
  }


  public String[] getTableNames(boolean force) {
    Schema.Table[] t = getSchema().getTables();
    Set<String> names = new TreeSet<String>();
    for (int i = 0; t != null && i < t.length; i++) {
      names.add(t[i].getName());
    }
    return names.toArray(new String[names.size()]);
  }

  Schema getSchema() {
    if (schema == null) {
      schema = new Schema();
    }
    return schema;
  }

  void setConnection(Connection connection) {
    this.connection = connection;
  }

  DatabaseMetaData getDatabaseMetaData() {
    return meta;
  }

  void setDatabaseMetaData(DatabaseMetaData meta) {
    this.meta = meta;
  }

  String getUrl() {
    return url;
  }

  public String getConnectedUrl() {
    if (connection instanceof HiveConnection) {
      return ((HiveConnection) connection).getConnectedUrl();
    }
    return getUrl();
  }

  Completer getSQLCompleter() {
    return sqlCompleter;
  }

  class Schema {
    private Table[] tables = null;

    Table[] getTables() {
      if (tables != null) {
        return tables;
      }

      List<Table> tnames = new LinkedList<Table>();

      try {
        ResultSet rs = getDatabaseMetaData().getTables(getConnection().getCatalog(),
            null, "%", new String[] {"TABLE"});
        try {
          while (rs.next()) {
            tnames.add(new Table(rs.getString("TABLE_NAME")));
          }
        } finally {
          try {
            rs.close();
          } catch (Exception e) {
          }
        }
      } catch (Throwable t) {
      }
      return tables = tnames.toArray(new Table[0]);
    }

    Table getTable(String name) {
      Table[] t = getTables();
      for (int i = 0; t != null && i < t.length; i++) {
        if (name.equalsIgnoreCase(t[i].getName())) {
          return t[i];
        }
      }
      return null;
    }

    class Table {
      final String name;
      Column[] columns;

      public Table(String name) {
        this.name = name;
      }


      public String getName() {
        return name;
      }

      class Column {
        final String name;
        boolean isPrimaryKey;

        public Column(String name) {
          this.name = name;
        }
      }
    }
  }
}
