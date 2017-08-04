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

package org.apache.hive.jdbc;

import org.apache.commons.codec.binary.Base64;
import org.apache.hive.service.cli.RowSet;
import org.apache.hive.service.cli.RowSetFactory;
import org.apache.hive.service.rpc.thrift.TCLIService;
import org.apache.hive.service.rpc.thrift.TCancelOperationReq;
import org.apache.hive.service.rpc.thrift.TCancelOperationResp;
import org.apache.hive.service.rpc.thrift.TCloseOperationReq;
import org.apache.hive.service.rpc.thrift.TCloseOperationResp;
import org.apache.hive.service.rpc.thrift.TExecuteStatementReq;
import org.apache.hive.service.rpc.thrift.TExecuteStatementResp;
import org.apache.hive.service.rpc.thrift.TFetchOrientation;
import org.apache.hive.service.rpc.thrift.TFetchResultsReq;
import org.apache.hive.service.rpc.thrift.TFetchResultsResp;
import org.apache.hive.service.rpc.thrift.TGetOperationStatusReq;
import org.apache.hive.service.rpc.thrift.TGetOperationStatusResp;
import org.apache.hive.service.rpc.thrift.TOperationHandle;
import org.apache.hive.service.rpc.thrift.TSessionHandle;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HiveStatement.
 *
 */
public class HiveStatement implements java.sql.Statement {
  public static final Logger LOG = LoggerFactory.getLogger(HiveStatement.class.getName());
  public static final int DEFAULT_FETCH_SIZE = 1000;
  private final HiveConnection connection;
  private TCLIService.Iface client;
  private TOperationHandle stmtHandle = null;
  private final TSessionHandle sessHandle;
  Map<String,String> sessConf = new HashMap<String,String>();
  private int fetchSize = DEFAULT_FETCH_SIZE;
  private boolean isScrollableResultset = false;
  private boolean isOperationComplete = false;
  /**
   * We need to keep a reference to the result set to support the following:
   * <code>
   * statement.execute(String sql);
   * statement.getResultSet();
   * </code>.
   */
  private ResultSet resultSet = null;

  /**
   * Sets the limit for the maximum number of rows that any ResultSet object produced by this
   * Statement can contain to the given number. If the limit is exceeded, the excess rows
   * are silently dropped. The value must be >= 0, and 0 means there is not limit.
   */
  private int maxRows = 0;

  /**
   * Add SQLWarnings to the warningChain if needed.
   */
  private SQLWarning warningChain = null;

  /**
   * Keep state so we can fail certain calls made after close().
   */
  private boolean isClosed = false;

  /**
   * Keep state so we can fail certain calls made after cancel().
   */
  private boolean isCancelled = false;

  /**
   * Keep this state so we can know whether the query in this statement is closed.
   */
  private boolean isQueryClosed = false;

  /**
   * Keep this state so we can know whether the query logs are being generated in HS2.
   */
  private boolean isLogBeingGenerated = true;

  /**
   * Keep this state so we can know whether the statement is submitted to HS2 and start execution
   * successfully.
   */
  private boolean isExecuteStatementFailed = false;

  private int queryTimeout = 0;

  public HiveStatement(HiveConnection connection, TCLIService.Iface client,
      TSessionHandle sessHandle) {
    this(connection, client, sessHandle, false, DEFAULT_FETCH_SIZE);
  }

  public HiveStatement(HiveConnection connection, TCLIService.Iface client,
      TSessionHandle sessHandle, int fetchSize) {
    this(connection, client, sessHandle, false, fetchSize);
  }

  public HiveStatement(HiveConnection connection, TCLIService.Iface client,
                       TSessionHandle sessHandle, boolean isScrollableResultset) {
    this(connection, client, sessHandle, isScrollableResultset, DEFAULT_FETCH_SIZE);
  }

  public HiveStatement(HiveConnection connection, TCLIService.Iface client,
      TSessionHandle sessHandle, boolean isScrollableResultset, int fetchSize) {
    this.connection = connection;
    this.client = client;
    this.sessHandle = sessHandle;
    this.isScrollableResultset = isScrollableResultset;
    this.fetchSize = fetchSize;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#addBatch(java.lang.String)
   */

  @Override
  public void addBatch(String sql) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#cancel()
   */

  @Override
  public void cancel() throws SQLException {
    checkConnection("cancel");
    if (isCancelled) {
      return;
    }

    try {
      if (stmtHandle != null) {
        TCancelOperationReq cancelReq = new TCancelOperationReq(stmtHandle);
        TCancelOperationResp cancelResp = client.CancelOperation(cancelReq);
        Utils.verifySuccessWithInfo(cancelResp.getStatus());
      }
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e.toString(), "08S01", e);
    }
    isCancelled = true;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#clearBatch()
   */

  @Override
  public void clearBatch() throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#clearWarnings()
   */

  @Override
  public void clearWarnings() throws SQLException {
    warningChain = null;
  }

  void closeClientOperation() throws SQLException {
    try {
      // runAsyncOnServer方法中调用该方法时, stmtHandle是null
      connection.LOG.info("closeClientOperation ----------> stmtHandle is null:" + (stmtHandle == null));
      if (stmtHandle != null) {
        TCloseOperationReq closeReq = new TCloseOperationReq(stmtHandle);
        TCloseOperationResp closeResp = client.CloseOperation(closeReq);
        Utils.verifySuccessWithInfo(closeResp.getStatus());
      }
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e.toString(), "08S01", e);
    }
    isQueryClosed = true;
    isExecuteStatementFailed = false;
    stmtHandle = null;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#close()
   */
  @Override
  public void close() throws SQLException {
    if (isClosed) {
      return;
    }
    closeClientOperation();
    client = null;
    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }
    isClosed = true;
  }

  // JDK 1.7
  public void closeOnCompletion() throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#execute(java.lang.String)
   */

  @Override
  public boolean execute(String sql) throws SQLException {
    // 通过client调用hiveserver2来执行hql, 主要逻辑是获取返回的TOperationHandle赋值给stmtHandle
    runAsyncOnServer(sql);
    // 等待hql执行完毕, 主要是用上面生成的stmtHandle, 通过client不断调用服务端的GetOperationStatus方法获取hql的执行状态, 直到获取FINISHED_STATE
    waitForOperationToComplete();

    // The query should be completed by now
    connection.LOG.info("execute----------stmtHandle.isHasResultSet():" + stmtHandle.isHasResultSet());
    if (!stmtHandle.isHasResultSet()) {
      return false;
    }
    // 构造resultSet, 返回true, 如果没有resultSet上面会返回false, maxRows默认值是0, fetchSize默认值是1000, isScrollableResultset默认值是false
    resultSet =  new HiveQueryResultSet.Builder(this).setClient(client).setSessionHandle(sessHandle)
        .setStmtHandle(stmtHandle).setMaxRows(maxRows).setFetchSize(fetchSize)
        .setScrollable(isScrollableResultset)
        .build();
    connection.LOG.info("+++++++++> maxRows:" + maxRows + ", fetchSize:" + fetchSize + ", isScrollableResultset" + isScrollableResultset);
    return true;
  }

  /**
   * Starts the query execution asynchronously on the server, and immediately returns to the client.
   * The client subsequently blocks on ResultSet#next or Statement#getUpdateCount, depending on the
   * query type. Users should call ResultSet.next or Statement#getUpdateCount (depending on whether
   * query returns results) to ensure that query completes successfully. Calling another execute*
   * method, or close before query completion would result in the async query getting killed if it
   * is not already finished.
   * Note: This method is an API for limited usage outside of Hive by applications like Apache Ambari,
   * although it is not part of the interface java.sql.Statement.
   *
   * @param sql
   * @return true if the first result is a ResultSet object; false if it is an update count or there
   *         are no results
   * @throws SQLException
   */
  public boolean executeAsync(String sql) throws SQLException {
    runAsyncOnServer(sql);
    if (!stmtHandle.isHasResultSet()) {
      return false;
    }
    resultSet =
        new HiveQueryResultSet.Builder(this).setClient(client).setSessionHandle(sessHandle)
            .setStmtHandle(stmtHandle).setMaxRows(maxRows).setFetchSize(fetchSize)
            .setScrollable(isScrollableResultset).build();
    return true;
  }

  private void runAsyncOnServer(String sql) throws SQLException {
    checkConnection("execute");

    closeClientOperation();
    initFlags();

    TExecuteStatementReq execReq = new TExecuteStatementReq(sessHandle, sql);
    /**
     * Run asynchronously whenever possible
     * Currently only a SQLOperation can be run asynchronously,
     * in a background operation thread
     * Compilation is synchronous and execution is asynchronous
     */
    execReq.setRunAsync(true);
    execReq.setConfOverlay(sessConf);
    // queryTimeout默认是0
    execReq.setQueryTimeout(queryTimeout);
    try {
      TExecuteStatementResp execResp = client.ExecuteStatement(execReq);
      // 判断返回状态是成功, 否则抛出异常HiveSQLException
      Utils.verifySuccessWithInfo(execResp.getStatus());
      // 获取返回的TOperationHandle赋值给stmtHandle
      stmtHandle = execResp.getOperationHandle();
      connection.LOG.info("runAsyncOnServer ******** stmtHandle is null:" + (stmtHandle == null));
      isExecuteStatementFailed = false;
    } catch (SQLException eS) {
      isExecuteStatementFailed = true;
      throw eS;
    } catch (Exception ex) {
      isExecuteStatementFailed = true;
      throw new SQLException(ex.toString(), "08S01", ex);
    }
  }

  void waitForOperationToComplete() throws SQLException {
    TGetOperationStatusReq statusReq = new TGetOperationStatusReq(stmtHandle);
    TGetOperationStatusResp statusResp;

    // Poll on the operation status, till the operation is complete
    while (!isOperationComplete) {
      try {
        /**
         * For an async SQLOperation, GetOperationStatus will use the long polling approach It will
         * essentially return after the HIVE_SERVER2_LONG_POLLING_TIMEOUT (a server config) expires
         */
        statusResp = client.GetOperationStatus(statusReq);
        Utils.verifySuccessWithInfo(statusResp.getStatus());
        if (statusResp.isSetOperationState()) {
          switch (statusResp.getOperationState()) {
          case CLOSED_STATE:
          case FINISHED_STATE:
            isOperationComplete = true;
            isLogBeingGenerated = false;
            break;
          case CANCELED_STATE:
            // 01000 -> warning
            throw new SQLException("Query was cancelled", "01000");
          case TIMEDOUT_STATE:
            throw new SQLTimeoutException("Query timed out after " + queryTimeout + " seconds");
          case ERROR_STATE:
            // Get the error details from the underlying exception
            throw new SQLException(statusResp.getErrorMessage(), statusResp.getSqlState(),
                statusResp.getErrorCode());
          case UKNOWN_STATE:
            throw new SQLException("Unknown query", "HY000");
          case INITIALIZED_STATE:
          case PENDING_STATE:
          case RUNNING_STATE:
            break;
          }
        }
      } catch (SQLException e) {
        isLogBeingGenerated = false;
        throw e;
      } catch (Exception e) {
        isLogBeingGenerated = false;
        throw new SQLException(e.toString(), "08S01", e);
      }
    }
  }

  private void checkConnection(String action) throws SQLException {
    if (isClosed) {
      throw new SQLException("Can't " + action + " after statement has been closed");
    }
  }

  private void initFlags() {
    isCancelled = false;
    isQueryClosed = false;
    isLogBeingGenerated = true;
    isExecuteStatementFailed = false;
    isOperationComplete = false;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#execute(java.lang.String, int)
   */

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#execute(java.lang.String, int[])
   */

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#execute(java.lang.String, java.lang.String[])
   */

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#executeBatch()
   */

  @Override
  public int[] executeBatch() throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#executeQuery(java.lang.String)
   */

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    if (!execute(sql)) {
      throw new SQLException("The query did not generate a result set!");
    }
    return resultSet;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#executeUpdate(java.lang.String)
   */

  @Override
  public int executeUpdate(String sql) throws SQLException {
    execute(sql);
    return 0;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#executeUpdate(java.lang.String, int)
   */

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#executeUpdate(java.lang.String, int[])
   */

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#executeUpdate(java.lang.String, java.lang.String[])
   */

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getConnection()
   */

  @Override
  public Connection getConnection() throws SQLException {
    checkConnection("getConnection");
    return this.connection;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getFetchDirection()
   */

  @Override
  public int getFetchDirection() throws SQLException {
    checkConnection("getFetchDirection");
    return ResultSet.FETCH_FORWARD;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getFetchSize()
   */

  @Override
  public int getFetchSize() throws SQLException {
    checkConnection("getFetchSize");
    return fetchSize;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getGeneratedKeys()
   */

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    throw new SQLFeatureNotSupportedException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getMaxFieldSize()
   */

  @Override
  public int getMaxFieldSize() throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getMaxRows()
   */

  @Override
  public int getMaxRows() throws SQLException {
    checkConnection("getMaxRows");
    return maxRows;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getMoreResults()
   */

  @Override
  public boolean getMoreResults() throws SQLException {
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getMoreResults(int)
   */

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    throw new SQLFeatureNotSupportedException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getQueryTimeout()
   */

  @Override
  public int getQueryTimeout() throws SQLException {
    checkConnection("getQueryTimeout");
    return 0;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getResultSet()
   */

  @Override
  public ResultSet getResultSet() throws SQLException {
    checkConnection("getResultSet");
    return resultSet;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getResultSetConcurrency()
   */

  @Override
  public int getResultSetConcurrency() throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getResultSetHoldability()
   */

  @Override
  public int getResultSetHoldability() throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getResultSetType()
   */

  @Override
  public int getResultSetType() throws SQLException {
    checkConnection("getResultSetType");
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getUpdateCount()
   */
  @Override
  public int getUpdateCount() throws SQLException {
    checkConnection("getUpdateCount");
    /**
     * Poll on the operation status, till the operation is complete. We want to ensure that since a
     * client might end up using executeAsync and then call this to check if the query run is
     * finished.
     */
    waitForOperationToComplete();
    return -1;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#getWarnings()
   */

  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkConnection("getWarnings");
    return warningChain;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#isClosed()
   */

  @Override
  public boolean isClosed() throws SQLException {
    return isClosed;
  }

  // JDK 1.7
  public boolean isCloseOnCompletion() throws SQLException {
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#isPoolable()
   */

  @Override
  public boolean isPoolable() throws SQLException {
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setCursorName(java.lang.String)
   */

  @Override
  public void setCursorName(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setEscapeProcessing(boolean)
   */

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    if (enable) {
      throw new SQLException("Method not supported");
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setFetchDirection(int)
   */

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkConnection("setFetchDirection");
    if (direction != ResultSet.FETCH_FORWARD) {
      throw new SQLException("Not supported direction " + direction);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setFetchSize(int)
   */

  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkConnection("setFetchSize");
    if (rows > 0) {
      fetchSize = rows;
    } else if (rows == 0) {
      // Javadoc for Statement interface states that if the value is zero
      // then "fetch size" hint is ignored.
      // In this case it means reverting it to the default value.
      fetchSize = DEFAULT_FETCH_SIZE;
    } else {
      throw new SQLException("Fetch size must be greater or equal to 0");
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setMaxFieldSize(int)
   */

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setMaxRows(int)
   */

  @Override
  public void setMaxRows(int max) throws SQLException {
    checkConnection("setMaxRows");
    if (max < 0) {
      throw new SQLException("max must be >= 0");
    }
    maxRows = max;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setPoolable(boolean)
   */

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    throw new SQLException("Method not supported");
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Statement#setQueryTimeout(int)
   */

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    this.queryTimeout = seconds;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Wrapper#isWrapperFor(java.lang.Class)
   */

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return false;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.sql.Wrapper#unwrap(java.lang.Class)
   */

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException("Cannot unwrap to " + iface);
  }

  /**
   * Check whether query execution might be producing more logs to be fetched.
   * This method is a public API for usage outside of Hive, although it is not part of the
   * interface java.sql.Statement.
   * @return true if query execution might be producing more logs. It does not indicate if last
   *         log lines have been fetched by getQueryLog.
   */
  public boolean hasMoreLogs() {
    return isLogBeingGenerated;
  }

  /**
   * Get the execution logs of the given SQL statement.
   * This method is a public API for usage outside of Hive, although it is not part of the
   * interface java.sql.Statement.
   * This method gets the incremental logs during SQL execution, and uses fetchSize holden by
   * HiveStatement object.
   * @return a list of logs. It can be empty if there are no new logs to be retrieved at that time.
   * @throws SQLException
   * @throws ClosedOrCancelledStatementException if statement has been cancelled or closed
   */
  public List<String> getQueryLog() throws SQLException, ClosedOrCancelledStatementException {
    return getQueryLog(true, fetchSize);
  }

  /**
   * Get the execution logs of the given SQL statement.
   * This method is a public API for usage outside of Hive, although it is not part of the
   * interface java.sql.Statement.
   * @param incremental indicate getting logs either incrementally or from the beginning,
   *                    when it is true or false.
   * @param fetchSize the number of lines to fetch
   * @return a list of logs. It can be empty if there are no new logs to be retrieved at that time.
   * @throws SQLException
   * @throws ClosedOrCancelledStatementException if statement has been cancelled or closed
   */
  public List<String> getQueryLog(boolean incremental, int fetchSize)
      throws SQLException, ClosedOrCancelledStatementException {
    checkConnection("getQueryLog");
    if (isCancelled) {
      throw new ClosedOrCancelledStatementException("Method getQueryLog() failed. The " +
          "statement has been closed or cancelled.");
    }

    List<String> logs = new ArrayList<String>();
    TFetchResultsResp tFetchResultsResp = null;
    try {
      // 第一次调用的时候(通常是前几次调用), 还未执行execute方法, 这时stmtHandle是null, 返回的logs是空的
      // 当execute方法中通过调用client.ExecuteStatement方法后, 才会给stmtHandle赋值, 此时stmtHandle不再是null
      connection.LOG.info("++++> stmtHandle is NULL: " + (stmtHandle == null));
      if (stmtHandle != null) {
        TFetchResultsReq tFetchResultsReq = new TFetchResultsReq(stmtHandle,
            getFetchOrientation(incremental), fetchSize);
        tFetchResultsReq.setFetchType((short)1);
        tFetchResultsResp = client.FetchResults(tFetchResultsReq);
        Utils.verifySuccessWithInfo(tFetchResultsResp.getStatus());
      } else {
        if (isQueryClosed) {
          throw new ClosedOrCancelledStatementException("Method getQueryLog() failed. The " +
              "statement has been closed or cancelled.");
        }
        if (isExecuteStatementFailed) {
          throw new SQLException("Method getQueryLog() failed. Because the stmtHandle in " +
              "HiveStatement is null and the statement execution might fail.");
        } else {
          return logs;
        }
      }
    } catch (SQLException e) {
      throw e;
    } catch (TException e) {
      throw new SQLException("Error when getting query log: " + e, e);
    } catch (Exception e) {
      throw new SQLException("Error when getting query log: " + e, e);
    }

    try {
      RowSet rowSet;
      rowSet = RowSetFactory.create(tFetchResultsResp.getResults(), connection.getProtocol());
      for (Object[] row : rowSet) {
        logs.add(String.valueOf(row[0]));
      }
    } catch (TException e) {
      throw new SQLException("Error building result set for query log: " + e, e);
    }

    return logs;
  }

  private TFetchOrientation getFetchOrientation(boolean incremental) {
    if (incremental) {
      return TFetchOrientation.FETCH_NEXT;
    } else {
      return TFetchOrientation.FETCH_FIRST;
    }
  }

  /**
   * Returns the Yarn ATS GUID.
   * This method is a public API for usage outside of Hive, although it is not part of the
   * interface java.sql.Statement.
   * @return Yarn ATS GUID or null if it hasn't been created yet.
   */
  public String getYarnATSGuid() {
    if (stmtHandle != null) {
      // Set on the server side.
      // @see org.apache.hive.service.cli.operation.SQLOperation#prepare
      String guid64 =
          Base64.encodeBase64URLSafeString(stmtHandle.getOperationId().getGuid()).trim();
      return guid64;
    }
    return null;
  }
}
