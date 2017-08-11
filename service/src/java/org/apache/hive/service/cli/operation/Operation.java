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
package org.apache.hive.service.cli.operation;

import com.google.common.collect.Sets;
import org.apache.hadoop.hive.common.metrics.common.Metrics;
import org.apache.hadoop.hive.common.metrics.common.MetricsConstant;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.common.metrics.common.MetricsScope;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.QueryState;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.OperationLog;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hive.service.cli.FetchOrientation;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.OperationHandle;
import org.apache.hive.service.cli.OperationState;
import org.apache.hive.service.cli.OperationStatus;
import org.apache.hive.service.cli.OperationType;
import org.apache.hive.service.cli.RowSet;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hive.service.cli.session.HiveSession;
import org.apache.hive.service.rpc.thrift.TProtocolVersion;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public abstract class Operation {
  // Constants of the key strings for the log4j ThreadContext.
  public static final String SESSIONID_LOG_KEY = "sessionId";
  public static final String QUERYID_LOG_KEY = "queryId";

  protected final HiveSession parentSession;
  private volatile OperationState state = OperationState.INITIALIZED;
  private volatile MetricsScope currentStateScope;
  private final OperationHandle opHandle;
  public static final FetchOrientation DEFAULT_FETCH_ORIENTATION = FetchOrientation.FETCH_NEXT;
  public static final Logger LOG = LoggerFactory.getLogger(Operation.class.getName());
  public static final long DEFAULT_FETCH_MAX_ROWS = 100;
  protected boolean hasResultSet;
  protected volatile HiveSQLException operationException;
  protected volatile Future<?> backgroundHandle;
  protected OperationLog operationLog;
  protected boolean isOperationLogEnabled;
  protected Map<String, String> confOverlay = new HashMap<String, String>();

  private long operationTimeout;
  private volatile long lastAccessTime;
  private final long beginTime;

  protected long operationStart;
  protected long operationComplete;

  protected final QueryState queryState;

  protected static final EnumSet<FetchOrientation> DEFAULT_FETCH_ORIENTATION_SET =
      EnumSet.of(FetchOrientation.FETCH_NEXT,FetchOrientation.FETCH_FIRST);


  protected Operation(HiveSession parentSession, OperationType opType) {
    this(parentSession, null, opType);
  }
  
  protected Operation(HiveSession parentSession, Map<String, String> confOverlay,
      OperationType opType) {
    this(parentSession, confOverlay, opType, false);
  }

  protected Operation(HiveSession parentSession,
      Map<String, String> confOverlay, OperationType opType, boolean isAsyncQueryState) {
    this.parentSession = parentSession;
    if (confOverlay != null) {
      this.confOverlay = confOverlay;
    }
    // 构造OperationHandle
    this.opHandle = new OperationHandle(opType, parentSession.getProtocolVersion());
    beginTime = System.currentTimeMillis();
    lastAccessTime = beginTime;
    // 超时时间默认5day
    operationTimeout = HiveConf.getTimeVar(parentSession.getHiveConf(),
        HiveConf.ConfVars.HIVE_SERVER2_IDLE_OPERATION_TIMEOUT, TimeUnit.MILLISECONDS);
    setMetrics(state);
    // 构造queryState, isAsyncQueryState默认是false, 传入的是HiveSessionImpl的sessionConf
    queryState = new QueryState(parentSession.getHiveConf(), confOverlay, isAsyncQueryState);
  }

  public Future<?> getBackgroundHandle() {
    return backgroundHandle;
  }

  protected void setBackgroundHandle(Future<?> backgroundHandle) {
    this.backgroundHandle = backgroundHandle;
  }

  public boolean shouldRunAsync() {
    return false; // Most operations cannot run asynchronously.
  }

  public HiveSession getParentSession() {
    return parentSession;
  }

  public OperationHandle getHandle() {
    return opHandle;
  }

  public TProtocolVersion getProtocolVersion() {
    return opHandle.getProtocolVersion();
  }

  public OperationType getType() {
    return opHandle.getOperationType();
  }

  public OperationStatus getStatus() {
    String taskStatus = null;
    try {
      // 调用子类的getTaskStatus方法,
      // taskStatus示例: [{"beginTime":1502420492448,"taskId":"Stage-1","externalHandle":"job_1496195987638_0513","taskState":"RUNNING","taskType":"MAPRED","name":"MAPRED","elapsedTime":5005}]
      taskStatus = getTaskStatus();
      LOG.info("+++++++++++++++ taskStatus:" + taskStatus);
    } catch (HiveSQLException sqlException) {
      LOG.error("Error getting task status for " + opHandle.toString(), sqlException);
    }
    // 任务执行过程中, state是会不断更新的. operationStart是任务起始时间, 在设置state为RUNNING是会设置该值, operationComplete是完成时间, 在设置state为ERROR, FINISHED, CANCELED时会设置该值
    // 任务执行过程中如果有异常信息, 会设置给operationException
    return new OperationStatus(state, taskStatus, operationStart, operationComplete, operationException);
  }

  public boolean hasResultSet() {
    return hasResultSet;
  }

  protected void setHasResultSet(boolean hasResultSet) {
    this.hasResultSet = hasResultSet;
    opHandle.setHasResultSet(hasResultSet);
  }

  public OperationLog getOperationLog() {
    return operationLog;
  }

  protected final OperationState setState(OperationState newState) throws HiveSQLException {
    state.validateTransition(newState);
    OperationState prevState = state;
    this.state = newState;
    setMetrics(state);
    // 更新任务起始和结束时间
    onNewState(state, prevState);
    // 更新最后访问时间
    this.lastAccessTime = System.currentTimeMillis();
    return this.state;
  }

  public boolean isTimedOut(long current) {
    if (operationTimeout == 0) {
      return false;
    }
    if (operationTimeout > 0) {
      // check only when it's in terminal state
      return state.isTerminal() && lastAccessTime + operationTimeout <= current;
    }
    return lastAccessTime + -operationTimeout <= current;
  }

  public long getLastAccessTime() {
    return lastAccessTime;
  }

  public long getOperationTimeout() {
    return operationTimeout;
  }

  public void setOperationTimeout(long operationTimeout) {
    this.operationTimeout = operationTimeout;
  }

  protected void setOperationException(HiveSQLException operationException) {
    this.operationException = operationException;
  }

  protected final void assertState(List<OperationState> states) throws HiveSQLException {
    if (!states.contains(state)) {
      throw new HiveSQLException("Expected states: " + states.toString() + ", but found "
          + this.state);
    }
    this.lastAccessTime = System.currentTimeMillis();
  }

  public boolean isRunning() {
    return OperationState.RUNNING.equals(state);
  }

  public boolean isFinished() {
    return OperationState.FINISHED.equals(state);
  }

  public boolean isCanceled() {
    return OperationState.CANCELED.equals(state);
  }

  public boolean isFailed() {
    return OperationState.ERROR.equals(state);
  }

  protected void createOperationLog() {
    // 默认为true
    if (parentSession.isOperationLogEnabled()) {
      // logFile,创建文件, 比如: /tmp/hadoop/operation_logs/dae1d9d8-4a01-4a80-b66c-634333661b7c/553f5538-c485-4a39-9d22-0780e19e1958
      // 后面两个串分别是SessionHandle的Identifier和operationHandler的Identifier
      File operationLogFile = new File(parentSession.getOperationLogSessionDir(),
          opHandle.getHandleIdentifier().toString());
      LOG.info("++++++++++++++++ operationLogFile:" + operationLogFile.getAbsolutePath());
      isOperationLogEnabled = true;

      // create log file
      try {
        if (operationLogFile.exists()) {
          LOG.warn("The operation log file should not exist, but it is already there: " +
              operationLogFile.getAbsolutePath());
          operationLogFile.delete();
        }
        // 创建文件
        if (!operationLogFile.createNewFile()) {
          // the log file already exists and cannot be deleted.
          // If it can be read/written, keep its contents and use it.
          if (!operationLogFile.canRead() || !operationLogFile.canWrite()) {
            LOG.warn("The already existed operation log file cannot be recreated, " +
                "and it cannot be read or written: " + operationLogFile.getAbsolutePath());
            isOperationLogEnabled = false;
            return;
          }
        }
      } catch (Exception e) {
        LOG.warn("Unable to create operation log file: " + operationLogFile.getAbsolutePath(), e);
        isOperationLogEnabled = false;
        return;
      }

      // create OperationLog object with above log file
      try {
        operationLog = new OperationLog(opHandle.toString(), operationLogFile, parentSession.getHiveConf());
      } catch (FileNotFoundException e) {
        LOG.warn("Unable to instantiate OperationLog object for operation: " +
            opHandle, e);
        isOperationLogEnabled = false;
        return;
      }

      // register this operationLog to current thread
      OperationLog.setCurrentOperationLog(operationLog);
    }
  }

  protected void unregisterOperationLog() {
    if (isOperationLogEnabled) {
      OperationLog.removeCurrentOperationLog();
    }
  }

  /**
   * Invoked before runInternal().
   * Set up some preconditions, or configurations.
   */
  protected void beforeRun() {
    // 注册OperationLog对象创建OperationLog文件, 比如: /tmp/hadoop/operation_logs/dae1d9d8-4a01-4a80-b66c-634333661b7c/553f5538-c485-4a39-9d22-0780e19e1958
    createOperationLog();
    // 注册context
    registerLoggingContext();
  }

  /**
   * Register logging context so that Log4J can print QueryId and/or SessionId for each message
   */
  protected void registerLoggingContext() {
    // SESSIONID_LOG_KEY, 即获取sessionId, 实际就是sessionHandler的Identifier
    ThreadContext.put(SESSIONID_LOG_KEY, SessionState.get().getSessionId());
    LOG.info("++++++++++++++++++ sessionId:" + SessionState.get().getSessionId());
    // QUERYID_LOG_KEY, 默认是null
    ThreadContext.put(QUERYID_LOG_KEY, confOverlay.get(HiveConf.ConfVars.HIVEQUERYID.varname));
    LOG.info("++++++++++++++++++ queryId:" + confOverlay.get(HiveConf.ConfVars.HIVEQUERYID.varname));
  }

  /**
   * Unregister logging context
   */
  protected void unregisterLoggingContext() {
    ThreadContext.clearAll();
  }

  /**
   * Invoked after runInternal(), even if an exception is thrown in runInternal().
   * Clean up resources, which was set up in beforeRun().
   */
  protected void afterRun() {
    // 取消注册context
    unregisterLoggingContext();
    // 移除注册的OperationLog对象
    unregisterOperationLog();
  }

  /**
   * Implemented by subclass of Operation class to execute specific behaviors.
   * @throws HiveSQLException
   */
  protected abstract void runInternal() throws HiveSQLException;

  public void run() throws HiveSQLException {
    // 创建日志目录, 注册context
    beforeRun();
    try {
      Metrics metrics = MetricsFactory.getInstance();
      if (metrics != null) {
        try {
          metrics.incrementCounter(MetricsConstant.OPEN_OPERATIONS);
        } catch (Exception e) {
          LOG.warn("Error Reporting open operation to Metrics system", e);
        }
      }
      // 调用子类的runInternal方法, 通常是SQLOperation的runInternal方法
      runInternal();
    } finally {
      afterRun();
    }
  }

  protected synchronized void cleanupOperationLog() {
    if (isOperationLogEnabled) {
      if (opHandle == null) {
        LOG.warn("Operation seems to be in invalid state, opHandle is null");
        return;
      }
      if (operationLog == null) {
        LOG.warn("Operation [ " + opHandle.getHandleIdentifier() + " ] " + "logging is enabled, "
            + "but its OperationLog object cannot be found. "
            + "Perhaps the operation has already terminated.");
      } else {
        operationLog.close();
      }
    }
  }

  public abstract void cancel(OperationState stateAfterCancel) throws HiveSQLException;

  public abstract void close() throws HiveSQLException;

  public abstract TableSchema getResultSetSchema() throws HiveSQLException;

  public abstract RowSet getNextRowSet(FetchOrientation orientation, long maxRows) throws HiveSQLException;

  public RowSet getNextRowSet() throws HiveSQLException {
    return getNextRowSet(FetchOrientation.FETCH_NEXT, DEFAULT_FETCH_MAX_ROWS);
  }

  public String getTaskStatus() throws HiveSQLException {
    return null;
  }

  /**
   * Verify if the given fetch orientation is part of the default orientation types.
   * @param orientation
   * @throws HiveSQLException
   */
  protected void validateDefaultFetchOrientation(FetchOrientation orientation)
      throws HiveSQLException {
    validateFetchOrientation(orientation, DEFAULT_FETCH_ORIENTATION_SET);
  }

  /**
   * Verify if the given fetch orientation is part of the supported orientation types.
   * @param orientation
   * @param supportedOrientations
   * @throws HiveSQLException
   */
  protected void validateFetchOrientation(FetchOrientation orientation,
      EnumSet<FetchOrientation> supportedOrientations) throws HiveSQLException {
    if (!supportedOrientations.contains(orientation)) {
      throw new HiveSQLException("The fetch type " + orientation.toString() +
          " is not supported for this resultset", "HY106");
    }
  }

  protected HiveSQLException toSQLException(String prefix, CommandProcessorResponse response) {
    HiveSQLException ex = new HiveSQLException(prefix + ": " + response.getErrorMessage(),
        response.getSQLState(), response.getResponseCode());
    if (response.getException() != null) {
      ex.initCause(response.getException());
    }
    return ex;
  }

  //list of operation states to measure duration of.
  protected static Set<OperationState> scopeStates = Sets.immutableEnumSet(
    OperationState.INITIALIZED,
    OperationState.PENDING,
    OperationState.RUNNING
  );

  //list of terminal operation states.  We measure only completed counts for operations in these states.
  protected static Set<OperationState> terminalStates = Sets.immutableEnumSet(
    OperationState.CLOSED,
    OperationState.CANCELED,
    OperationState.FINISHED,
    OperationState.ERROR,
    OperationState.UNKNOWN
  );

  private void setMetrics(OperationState state) {
    currentStateScope = setMetrics(currentStateScope, MetricsConstant.OPERATION_PREFIX,
      MetricsConstant.COMPLETED_OPERATION_PREFIX, state);
  }

  protected static MetricsScope setMetrics(MetricsScope stateScope, String operationPrefix,
      String completedOperationPrefix, OperationState state) {
    Metrics metrics = MetricsFactory.getInstance();
    if (metrics != null) {
      try {
        if (stateScope != null) {
          metrics.endScope(stateScope);
          stateScope = null;
        }
        if (scopeStates.contains(state)) {
          stateScope = metrics.createScope(operationPrefix + state);
        }
        if (terminalStates.contains(state)) {
          metrics.incrementCounter(completedOperationPrefix + state);
        }
      } catch (IOException e) {
        LOG.warn("Error metrics", e);
      }
    }
    return stateScope;
  }

  public long getBeginTime() {
    return beginTime;
  }

  protected OperationState getState() {
    return state;
  }

  protected void onNewState(OperationState state, OperationState prevState) {
    switch(state) {
      case RUNNING:
        markOperationStartTime();
        break;
      case ERROR:
      case FINISHED:
      case CANCELED:
        markOperationCompletedTime();
        break;
    }
  }

  public long getOperationComplete() {
    return operationComplete;
  }

  public long getOperationStart() {
    return operationStart;
  }

  protected void markOperationStartTime() {
    operationStart = System.currentTimeMillis();
  }

  protected void markOperationCompletedTime() {
    operationComplete = System.currentTimeMillis();
  }
}
