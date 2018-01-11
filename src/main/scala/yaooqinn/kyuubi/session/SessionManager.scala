/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package yaooqinn.kyuubi.session

import java.io.{File, IOException}
import java.util.{Date, Map => JMap}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.collection.mutable.HashSet

import org.apache.commons.io.FileUtils
import org.apache.hive.service.cli.{HiveSQLException, SessionHandle}
import org.apache.hive.service.cli.thrift.TProtocolVersion
import org.apache.hive.service.server.ThreadFactoryWithGarbageCleanup
import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.KyuubiConf._
import org.apache.spark.sql.SparkSession

import yaooqinn.kyuubi.Logging
import yaooqinn.kyuubi.operation.OperationManager
import yaooqinn.kyuubi.service.CompositeService
import yaooqinn.kyuubi.ui.KyuubiServerMonitor

/**
 * A SessionManager for managing [[KyuubiSession]]s
 */
private[kyuubi] class SessionManager private(
    name: String) extends CompositeService(name) with Logging {
  private[this] val operationManager = new OperationManager()
  private[this] val handleToSession = new ConcurrentHashMap[SessionHandle, KyuubiSession]
  private[this] val handleToSessionUser = new ConcurrentHashMap[SessionHandle, String]
  private[this] val userToSparkSession =
    new ConcurrentHashMap[String, (SparkSession, AtomicInteger)]
  private[this] val userSparkContextBeingConstruct = new HashSet[String]()
  private[this] var backgroundOperationPool: ThreadPoolExecutor = _
  private[this] var isOperationLogEnabled = false
  private[this] var operationLogRootDir: File = _
  private[this] var checkInterval: Long = _
  private[this] var sessionTimeout: Long = _
  private[this] var checkOperation: Boolean = false

  private[this] var shutdown: Boolean = false

  def this() = this(getClass.getSimpleName)

  override def init(conf: SparkConf): Unit = synchronized {
    this.conf = conf
    // Create operation log root directory, if operation logging is enabled
    if (conf.getBoolean(KYUUBI_LOGGING_OPERATION_ENABLED.key, defaultValue = true)) {
      initOperationLogRootDir()
    }
    createBackgroundOperationPool()
    addService(operationManager)
    super.init(conf)
  }

  private[this] def createBackgroundOperationPool(): Unit = {
    val poolSize = conf.getInt(KYUUBI_ASYNC_EXEC_THREADS.key, 100)
    info("Background operation thread pool size: " + poolSize)
    val poolQueueSize = conf.getInt(KYUUBI_ASYNC_EXEC_WAIT_QUEUE_SIZE.key, 100)
    info("Background operation thread wait queue size: " + poolQueueSize)
    val keepAliveTime = conf.getTimeAsMs(KYUUBI_EXEC_KEEPALIVE_TIME.key, "10s")
    info("Background operation thread keepalive time: " + keepAliveTime + " seconds")
    val threadPoolName = "SparkThriftServer-Background-Pool"
    backgroundOperationPool =
      new ThreadPoolExecutor(
        poolSize,
        poolSize,
        keepAliveTime,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue[Runnable](poolQueueSize),
        new ThreadFactoryWithGarbageCleanup(threadPoolName))
    backgroundOperationPool.allowCoreThreadTimeOut(true)
    checkInterval = conf.getTimeAsMs(KYUUBI_SESSION_CHECK_INTERVAL.key, "6h")
    sessionTimeout = conf.getTimeAsMs(KYUUBI_IDLE_SESSION_TIMEOUT.key, "8h")
    checkOperation = conf.getBoolean(KYUUBI_IDLE_SESSION_CHECK_OPERATION.key,
      defaultValue = true)
  }

  private[this] def initOperationLogRootDir(): Unit = {
    operationLogRootDir =
      new File(conf.get(KYUUBI_LOGGING_OPERATION_LOG_LOCATION.key,
        s"${sys.env.getOrElse("SPARK_LOG_DIR", System.getProperty("java.io.tmpdir"))}"
          + File.separator + "operation_logs"))
    isOperationLogEnabled = true
    if (operationLogRootDir.exists && !operationLogRootDir.isDirectory) {
      info("The operation log root directory exists, but it is not a directory: "
        + operationLogRootDir.getAbsolutePath)
      isOperationLogEnabled = false
    }
    if (!operationLogRootDir.exists) {
      if (!operationLogRootDir.mkdirs) {
        warn("Unable to create operation log root directory: "
          + operationLogRootDir.getAbsolutePath)
        isOperationLogEnabled = false
      }
    }

    if (isOperationLogEnabled) {
      if (operationLogRootDir.exists()) {
        info("Operation log root directory is created: " + operationLogRootDir.getAbsolutePath)
        try {
          FileUtils.forceDeleteOnExit(operationLogRootDir)
        } catch {
          case e: IOException =>
            warn("Failed to schedule cleanup Spark Thrift Server's operation logging root" +
              " dir: " + operationLogRootDir.getAbsolutePath, e)
        }
      } else {
        warn("Operation log root directory is not created: "
          + operationLogRootDir.getAbsolutePath)
      }
    }
  }

  override def start(): Unit = {
    super.start()
    if (checkInterval > 0) {
      startTimeoutChecker()
    }
    startSparkSessionCleaner()
  }

  /**
   * Periodically close idle sessions in 'hive.server2.session.check.interval(default 6h)'
   */
  private[this] def startTimeoutChecker(): Unit = {
    val interval: Long = math.max(checkInterval, 3000L)
    // minimum 3 seconds
    val timeoutChecker: Runnable = new Runnable() {
      override def run(): Unit = {
        sleepInterval(interval)
        while (!shutdown) {
          val current: Long = System.currentTimeMillis
          handleToSession.values.asScala.foreach { session =>
            if (sessionTimeout > 0 && session.getLastAccessTime + sessionTimeout <= current
              && (!checkOperation || session.getNoOperationTime > sessionTimeout)) {
              val handle: SessionHandle = session.getSessionHandle
              warn("Session " + handle + " is Timed-out (last access: "
                + new Date(session.getLastAccessTime) + ") and will be closed")
              try {
                closeSession(handle)
              } catch {
                case e: HiveSQLException =>
                  warn("Exception is thrown closing idle session " + handle, e)
              }
            }
          }
          sleepInterval(interval)
        }
      }
    }
    backgroundOperationPool.execute(timeoutChecker)
  }

  /**
   * Periodically close idle SparkSessions in 'spark.kyuubi.session.clean.interval(default 20min)'
   */
  private[this] def startSparkSessionCleaner(): Unit = {
    // at least 10 min
    val interval = math.max(
      conf.getTimeAsMs(KYUUBI_SPARK_SESSION_CHECK_INTERVAL.key, "20min"),
      10 * 60 * 1000L)
    val sessionCleaner = new Runnable {
      override def run(): Unit = {
        sleepInterval(interval)
        while(!shutdown) {
          userToSparkSession.asScala.foreach {
            case (user, (session, times)) =>
              if (times.get() <= 0 || session.sparkContext.isStopped) {
                removeSparkSession(user)
                KyuubiServerMonitor.detachUITab(user)
                session.stop()
              }
            case _ =>
          }
          sleepInterval(interval)
        }
      }
    }
    backgroundOperationPool.execute(sessionCleaner)
  }

  private[this] def sleepInterval(interval: Long): Unit = {
    try
      Thread.sleep(interval)
    catch {
      case _: InterruptedException =>
      // ignore
    }
  }

  /**
   * Opens a new session and creates a session handle.
   * The username passed to this method is the effective username.
   * If withImpersonation is true (==doAs true) we wrap all the calls in HiveSession
   * within a UGI.doAs, where UGI corresponds to the effective user.
   */
  def openSession(
      protocol: TProtocolVersion,
      username: String,
      password: String,
      ipAddress: String,
      sessionConf: JMap[String, String],
      withImpersonation: Boolean): SessionHandle = {
    val kyuubiSession =
      new KyuubiSession(
        protocol,
        username,
        password,
        conf.clone(),
        ipAddress,
        withImpersonation,
        this,
        operationManager)
    kyuubiSession.open(sessionConf)

    if (isOperationLogEnabled) {
      kyuubiSession.setOperationLogSessionDir(operationLogRootDir)
    }

    val sessionHandle = kyuubiSession.getSessionHandle
    handleToSession.put(sessionHandle, kyuubiSession)
    handleToSessionUser.put(sessionHandle, username)

    KyuubiServerMonitor.getListener(username).onSessionCreated(
      kyuubiSession.getIpAddress,
      sessionHandle.getSessionId.toString,
      kyuubiSession.getUserName)

    sessionHandle
  }

  def closeSession(sessionHandle: SessionHandle) {
    val sessionUser = handleToSessionUser.remove(sessionHandle)
    KyuubiServerMonitor.getListener(sessionUser)
      .onSessionClosed(sessionHandle.getSessionId.toString)
    val sessionAndTimes = userToSparkSession.get(sessionUser)
    if (sessionAndTimes != null) {
      sessionAndTimes._2.decrementAndGet()
    } else {
      throw new SparkException(s"SparkSession for [$sessionUser] does not exist")
    }
    val session = handleToSession.remove(sessionHandle)
    if (session == null) {
      throw new HiveSQLException(s"Session for [$sessionUser] does not exist!")
    }
    session.close()
  }

  @throws[HiveSQLException]
  def getSession(sessionHandle: SessionHandle): KyuubiSession = {
    val session = handleToSession.get(sessionHandle)
    if (session == null) {
      throw new HiveSQLException("Invalid SessionHandle: " + sessionHandle)
    }
    session
  }

  def getOperationMgr: OperationManager = operationManager

  override def stop(): Unit = {
    super.stop()
    shutdown = true
    if (backgroundOperationPool != null) {
      backgroundOperationPool.shutdown()
      val timeout = conf.getTimeAsSeconds(KYUUBI_ASYNC_EXEC_SHUTDOWN_TIMEOUT.key, "10s")
      try {
        backgroundOperationPool.awaitTermination(timeout, TimeUnit.SECONDS)
      } catch {
        case e: InterruptedException =>
          warn("KYUUBI_ASYNC_EXEC_SHUTDOWN_TIMEOUT = " + timeout +
            " seconds has been exceeded. RUNNING background operations will be shut down", e)
      }
      backgroundOperationPool = null
    }
    cleanupLoggingRootDir()
    userToSparkSession.asScala.values.foreach { kv => kv._1.stop() }
    userToSparkSession.clear()
  }

  private[this] def cleanupLoggingRootDir(): Unit = {
    if (isOperationLogEnabled) {
      try {
        FileUtils.forceDelete(operationLogRootDir)
      } catch {
        case e: Exception =>
          warn("Failed to cleanup root dir of KyuubiServer logging: "
            + operationLogRootDir.getAbsolutePath, e)
      }
    }
  }

  def getOpenSessionCount: Int = handleToSession.size

  def submitBackgroundOperation(r: Runnable): Future[_] = backgroundOperationPool.submit(r)

  def getExistSparkSession(user: String): Option[(SparkSession, AtomicInteger)] = {
    Some(userToSparkSession.get(user))
  }

  def setSparkSession(user: String, sparkSession: SparkSession): Unit = {
    userToSparkSession.put(user, (sparkSession, new AtomicInteger(1)))
  }

  def removeSparkSession(user: String): Unit = userToSparkSession.remove(user)

  def setSCPartiallyConstructed(user: String): Unit = {
    removeSparkSession(user)
    userSparkContextBeingConstruct.add(user)
  }

  def isSCPartiallyConstructed(user: String): Boolean = {
    userSparkContextBeingConstruct.contains(user)
  }

  def setSCFullyConstructed(user: String): Unit = {
    userSparkContextBeingConstruct.remove(user)
  }

}