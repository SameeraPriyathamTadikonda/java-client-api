/*
 * Copyright (c) 2022 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.client.datamovement.impl;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.DatabaseClientFactory;
import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.document.ServerTransform;
import com.marklogic.client.document.XMLDocumentManager;
import com.marklogic.client.document.DocumentWriteOperation.OperationType;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.Format;
import com.marklogic.client.impl.DocumentWriteOperationImpl;
import com.marklogic.client.impl.Utilities;
import com.marklogic.client.io.marker.AbstractWriteHandle;
import com.marklogic.client.io.marker.ContentHandle;
import com.marklogic.client.io.marker.DocumentMetadataWriteHandle;

import com.marklogic.client.datamovement.DataMovementException;
import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.Forest;
import com.marklogic.client.datamovement.ForestConfiguration;
import com.marklogic.client.datamovement.JobTicket;
import com.marklogic.client.datamovement.WriteBatch;
import com.marklogic.client.datamovement.WriteBatchListener;
import com.marklogic.client.datamovement.WriteEvent;
import com.marklogic.client.datamovement.WriteFailureListener;
import com.marklogic.client.datamovement.WriteBatcher;

/**
 * The implementation of WriteBatcher.
 * Features
 *   - multiple threads can concurrently call add/addAs
 *     - we don't manage these threads, they're outside this
 *     - no synchronization or unnecessary delays while queueing
 *     - won't launch extra threads until a batch is ready to write
 *     - (warning) we don't proactively read streams, so don't leave them in the queue too long
 *   - topology-aware by calling /v1/forestinfo
 *     - get list of hosts which have writeable forests
 *     - each write hits the next writeable host for round-robin network calls
 *   - manage an internal threadPool of size threadCount for network calls
 *   - when batchSize reached, writes a batch
 *     - using a thread from threadPool
 *     - no synchronization or unnecessary delays while emptying queue
 *     - and calls each successListener
 *   - when a batch fails, calls each failureListener
 *   - flush() writes all queued documents whether the last batch is full or not
 *     - and resets counter so the next batch will be a normal batch size
 *   - awaitCompletion allows the calling thread to block until all tasks queued to that point
 *     are finished writing batches
 *
 * Design
 *   - think asynchronously
 *     - so that many external threads and many internal threads can be constantly
 *       updating state without creating conflict
 *     - avoid race conditions and logic which depends on state remaining unchanged
 *       from one statement to the next
 *     - when triggering periodic processing such as writing a batch
 *       or choosing the next host to use
 *       - use logic where multiple concurrent threads can arrive at the same point and
 *         see the same state yet only one of the threads will perform the processing
 *         - do this by using AtomicLong.incrementAndGet() so each thread gets a different
 *           number, then trigger the logic with the thread that gets the correct number
 *         - for example, we decide to write a batch by
 *           timeToWriteBatch = (recordNum % getBatchSize()) == 0;
 *           - in other words, when we reach a recordNum which is a multiple of getBatchSize
 *           - only one thread will get the correct number and that thread will have
 *             timeToWriteBatch == true
 *           - we don't reset recordNum at each batch as that would introduce a race condition
 *           - however, when flush is called we want subsequent batches to start over, so
 *             in that case we reset recordNum to 0
 *     - use classes from java.util.concurrent and java.util.concurrent.atomic
 *       - so external threads don't block when calling add/addAs
 *       - so internal state doesn't get confused by race conditions
 *     - avoid deadlock
 *       - don't ask threads to block
 *       - use non-blocking queues where possible
 *       - we use a blocking queue for the thread pool since that's required and it makes sense
 *         for threads to block while awaiting more tasks
 *       - we use a blocking queue for the DocumentWriteOperation main queue just so we can have
 *         the atomic drainTo method used by flush.  But LinkedBlockingQueue is unbounded so
 *         nothing should block on put() and we use poll() to get things so we don't block there either.
 *       - we only use one synchronized block inside initialize() to ensure it only runs once
 *         - after the first call is complete, calls to initialize() won't hit the synchronized block
 *   - try to do what's expected
 *     - try to write documents in the order they are sent to add/addAs
 *       - accepting that asynchronous threads will proceed unpredictably
 *         - for example, thread A might start before thread B and perform less work, but
 *           thread B might still complete first
 *     - try to match batch sizes to batchSize
 *       - except when flush is called, then immediately write all queued docs
 *     - when awaitCompletion is called, block until existing tasks are complete but ignore any
 *       tasks added after awaitCompletion is called
 *       - for more on the design of awaitCompletion, see comments above CompletableThreadPoolExecutor
 *         and CompletableRejectedExecutionHandler
 *   - track
 *     - one queue of DocumentWriteOperation
 *     - batchCounter to decide if it's time to write a batch
 *       - flush resets this so after flush batch sizes will be normal
 *     - batchNumber to decide which host to use next (round-robin)
 *     - initialized to ensure configuration doesn't change after add/addAs are called
 *     - threadPool of threadCount size for most calls to the server
 *       - not calls during forestinfo or flush
 *     - each host
 *       - host name
 *       - client (contains http connection pool)
 *         - auth challenge once per client
 *       - number of batches
 *     - each task (Runnable) in the thread pool task queue
 *       - so we can know which tasks to monitor when awaitCompletion is called
 *       - we remove each task when it's complete
 *       - for more details, see comments above CompletableThreadPoolExecutor and
 *         CompletableRejectedExecutionHandler
 */
public class WriteBatcherImpl
  extends BatcherImpl
  implements WriteBatcher
{
  private static Logger logger = LoggerFactory.getLogger(WriteBatcherImpl.class);
  private String temporalCollection;
  private ServerTransform transform;
  private ForestConfiguration forestConfig;
  private LinkedBlockingQueue<DocumentWriteOperation> queue = new LinkedBlockingQueue<>();
  private List<WriteBatchListener> successListeners = new ArrayList<>();
  private List<WriteFailureListener> failureListeners = new ArrayList<>();
  private AtomicLong batchNumber = new AtomicLong(0);
  private AtomicLong batchCounter = new AtomicLong(0);
  private AtomicLong itemsSoFar = new AtomicLong(0);
  private HostInfo[] hostInfos;
  private boolean initialized = false;
  private CompletableThreadPoolExecutor threadPool = null;
  private DocumentMetadataHandle defaultMetadata;

  public WriteBatcherImpl(DataMovementManager moveMgr, ForestConfiguration forestConfig) {
    super(moveMgr);
    withForestConfig( forestConfig );
  }

  public void initialize() {
    if ( initialized == true ) return;
    synchronized(this) {
      if ( initialized == true ) return;
      if ( getBatchSize() <= 0 ) {
        withBatchSize(1);
        logger.warn("batchSize should be 1 or greater--setting batchSize to 1");
      }
      // if threadCount is negative or 0, use one thread per host
      if ( getThreadCount() <= 0 ) {
        withThreadCount( hostInfos.length );
        logger.warn("threadCount should be 1 or greater--setting threadCount to number of hosts ({})", hostInfos.length);
      }
      // create a thread pool where threads are kept alive for up to one minute of inactivity,
      // max queue size is threadCount * 3, and callers run tasks past the max queue size
      threadPool = new CompletableThreadPoolExecutor(getThreadCount(), getThreadCount(), 1, TimeUnit.MINUTES,
        new LinkedBlockingQueue<Runnable>(getThreadCount() * 3));
      threadPool.allowCoreThreadTimeOut(true);

      initialized = true;

	  if (logger.isDebugEnabled()) {
		  logger.debug("threadCount={}", getThreadCount());
		  logger.debug("batchSize={}", getBatchSize());
	  }
      super.setJobStartTime();
      super.getStarted().set(true);
    }
  }

  @Override
  public WriteBatcher add(String uri, AbstractWriteHandle contentHandle) {
    add(uri, null, contentHandle);
    return this;
  }

  @Override
  public WriteBatcher addAs(String uri, Object content) {
    return addAs(uri, null, content);
  }

  @Override
  public WriteBatcher add(DocumentWriteOperation writeOperation) {
	  if (writeOperation.getUri() == null) throw new IllegalArgumentException("uri must not be null");
	  // Prior to 6.6.1 and higher, threw an exception here if the content was null. But that was not necessary - the
	  // v1/documents endpoint supports writing a 'naked' properties fragment with no content.
    initialize();
    requireNotStopped();
    queue.add(writeOperation);
    logger.trace("add uri={}", writeOperation.getUri());
    // if we have queued batchSize, it's time to flush a batch
    long recordNum = batchCounter.incrementAndGet();
    boolean timeToWriteBatch = (recordNum % getBatchSize()) == 0;
    if ( timeToWriteBatch ) {
      BatchWriteSet writeSet = newBatchWriteSet();
      int minBatchSize = 0;
      if(defaultMetadata != null) {
        writeSet.getWriteSet().add(new DocumentWriteOperationImpl(OperationType.METADATA_DEFAULT, null, defaultMetadata, null));
        minBatchSize = 1;
      }
      for (int i=0; i < getBatchSize(); i++ ) {
        DocumentWriteOperation doc = queue.poll();
        if ( doc == null ) {
          // strange, there should have been a full batch of docs in the queue...
          break;
        }
        writeSet.getWriteSet().add(doc);
      }
      if ( writeSet.getWriteSet().size() > minBatchSize ) {
        threadPool.submit( new BatchWriter(writeSet) );
      }
    }
    return this;
  }

  @Override
  public WriteBatcher add(String uri, DocumentMetadataWriteHandle metadataHandle, AbstractWriteHandle contentHandle) {
    add(new DocumentWriteOperationImpl(OperationType.DOCUMENT_WRITE, uri, metadataHandle, contentHandle));
    return this;
  }

  @Override
  public WriteBatcher add(WriteEvent... docs) {
    for ( WriteEvent doc : docs ) {
      add( doc.getTargetUri(), doc.getMetadata(), doc.getContent() );
    }
    return this;
  }

  @Override
  public WriteBatcher addAs(String uri, DocumentMetadataWriteHandle metadataHandle,
                            Object content) {
    if (content == null) throw new IllegalArgumentException("content must not be null");

    AbstractWriteHandle handle;
    Class<?> as = content.getClass();
    if (AbstractWriteHandle.class.isAssignableFrom(as)) {
      handle = (AbstractWriteHandle) content;
    } else {
      ContentHandle<?> contentHandle = DatabaseClientFactory.getHandleRegistry().makeHandle(as);
      Utilities.setHandleContent(contentHandle, content);
      handle = contentHandle;
    }
    return add(uri, metadataHandle, handle);
  }

  private void requireInitialized() {
    if ( initialized == false ) {
      throw new IllegalStateException("This operation must be called after starting this job");
    }
  }

  private void requireNotInitialized() {
    if ( initialized == true ) {
      throw new IllegalStateException("Configuration cannot be changed after starting this job or calling add or addAs");
    }
  }

  private void requireNotStopped() {
    if ( isStopped() == true ) throw new IllegalStateException("This instance has been stopped");
  }

  private BatchWriteSet newBatchWriteSet() {
    long batchNum = batchNumber.incrementAndGet();
    return newBatchWriteSet(batchNum);
  }

  private BatchWriteSet newBatchWriteSet(long batchNum) {
    int hostToUse = (int) (batchNum % hostInfos.length);
    HostInfo host = hostInfos[hostToUse];
    DatabaseClient hostClient = host.client;
    BatchWriteSet batchWriteSet = new BatchWriteSet(this, hostClient.newDocumentManager().newWriteSet(),
      hostClient, getTransform(), getTemporalCollection());
    batchWriteSet.setBatchNumber(batchNum);
    batchWriteSet.onSuccess( () -> {
      sendSuccessToListeners(batchWriteSet);
    });
    batchWriteSet.onFailure( (throwable) -> {
      sendThrowableToListeners(throwable, "Error writing batch: {}", batchWriteSet);
    });
    return batchWriteSet;
  }

  @Override
  public WriteBatcher onBatchSuccess(WriteBatchListener listener) {
    if ( listener == null ) throw new IllegalArgumentException("listener must not be null");
    successListeners.add(listener);
    return this;
  }
  @Override
  public WriteBatcher onBatchFailure(WriteFailureListener listener) {
    if ( listener == null ) throw new IllegalArgumentException("listener must not be null");
    failureListeners.add(listener);
    return this;
  }

  @Override
  public void retryWithFailureListeners(WriteBatch batch) {
    retry(batch, true);
  }

  @Override
  public void retry(WriteBatch batch) {
    retry(batch, false);
  }

  private void retry(WriteBatch batch, boolean callFailListeners) {
    if ( isStopped() == true ) {
      logger.warn("Job is now stopped, aborting the retry");
      return;
    }
    if ( batch == null ) throw new IllegalArgumentException("batch must not be null");
    BatchWriteSet writeSet = newBatchWriteSet(batch.getJobBatchNumber());
    if ( !callFailListeners ) {
      writeSet.onFailure(throwable -> {
        if ( throwable instanceof RuntimeException )
          throw (RuntimeException) throwable;
        else
          throw new DataMovementException("Failed to retry batch", throwable);
      });
    }
    for (WriteEvent doc : batch.getItems()) {
      writeSet.getWriteSet().add(doc.getTargetUri(), doc.getMetadata(), doc.getContent());
    }
    BatchWriter runnable = new BatchWriter(writeSet);
    runnable.run();
  }
  @Override
  public WriteBatchListener[]        getBatchSuccessListeners() {
    return successListeners.toArray(new WriteBatchListener[successListeners.size()]);
  }

  @Override
  public WriteFailureListener[] getBatchFailureListeners() {
    return failureListeners.toArray(new WriteFailureListener[failureListeners.size()]);
  }

  @Override
  public void setBatchSuccessListeners(WriteBatchListener... listeners) {
    requireNotInitialized();
    successListeners.clear();
    if ( listeners != null ) {
      for ( WriteBatchListener listener : listeners ) {
        successListeners.add(listener);
      }
    }
  }

  @Override
  public void setBatchFailureListeners(WriteFailureListener... listeners) {
    requireNotInitialized();
    failureListeners.clear();
    if ( listeners != null ) {
      for ( WriteFailureListener listener : listeners ) {
        failureListeners.add(listener);
      }
    }
  }

  @Override
  public void flushAsync() {
    flush(false);
  }

  @Override
  public void flushAndWait() {
    flush(true);
  }

  private void flush(boolean waitForCompletion) {
    requireInitialized();
    requireNotStopped();
    // drain any docs left in the queue
    List<DocumentWriteOperation> docs = new ArrayList<>();
    batchCounter.set(0);
    queue.drainTo(docs);
	if (logger.isTraceEnabled()) {
		logger.trace("flushing {} queued docs", docs.size());
	}
    Iterator<DocumentWriteOperation> iter = docs.iterator();
    for ( int i=0; iter.hasNext(); i++ ) {
      if ( isStopped() == true ) {
        logger.warn("Job is now stopped, preventing the flush of {} queued docs", docs.size() - i);
        if ( waitForCompletion == true ) awaitCompletion();
        return;
      }
      BatchWriteSet writeSet = newBatchWriteSet();
      if(defaultMetadata != null) {
          writeSet.getWriteSet().add(new DocumentWriteOperationImpl(OperationType.METADATA_DEFAULT, null, defaultMetadata, null));
        }
      int j=0;
      for ( ; j < getBatchSize() && iter.hasNext(); j++ ) {
        DocumentWriteOperation doc = iter.next();
        writeSet.getWriteSet().add(doc);
      }
      threadPool.submit( new BatchWriter(writeSet) );
    }

    if ( waitForCompletion == true ) awaitCompletion();
  }

  private void sendSuccessToListeners(BatchWriteSet batchWriteSet) {
    batchWriteSet.setItemsSoFar(itemsSoFar.addAndGet(batchWriteSet.getWriteSet().size()));
    WriteBatch batch = batchWriteSet.getBatchOfWriteEvents();
    for ( WriteBatchListener successListener : successListeners ) {
      try {
        successListener.processEvent(batch);
      } catch (Throwable t) {
        logger.error("Exception thrown by an onBatchSuccess listener", t);
      }
    }
  }

  private void sendThrowableToListeners(Throwable t, String message, BatchWriteSet batchWriteSet) {
    batchWriteSet.setItemsSoFar(itemsSoFar.get());
    WriteBatch batch = batchWriteSet.getBatchOfWriteEvents();
    for ( WriteFailureListener failureListener : failureListeners ) {
      try {
        failureListener.processFailure(batch, t);
      } catch (Throwable t2) {
        logger.error("Exception thrown by an onBatchFailure listener", t2);
      }
    }
    if ( message != null ) logger.warn(message, t.toString());
  }

  @Override
  public void start(JobTicket ticket) {
    super.setJobTicket(ticket);
    initialize();
  }

  @Override
  public void stop() {
    super.setJobEndTime();
    super.getStopped().set(true);
    if ( threadPool != null ) threadPool.shutdownNow();
    closeAllListeners();
  }

  private void closeAllListeners() {
    for (WriteBatchListener listener : getBatchSuccessListeners()) {
      if ( listener instanceof AutoCloseable ) {
        try {
          ((AutoCloseable) listener).close();
        } catch (Exception e) {
          logger.error("onBatchSuccess listener cannot be closed", e);
        }
      }
    }
    for (WriteFailureListener listener : getBatchFailureListeners()) {
      if ( listener instanceof AutoCloseable ) {
        try {
          ((AutoCloseable) listener).close();
        } catch (Exception e) {
          logger.error("onBatchFailure listener cannot be closed", e);
        }
      }
    }
  }

  @Override
  public JobTicket getJobTicket() {
    requireInitialized();
    return super.getJobTicket();
  }

  @Override
  public Calendar getJobStartTime() {
    if (!this.isStarted()) {
      return null;
    } else {
      return super.getJobStartTime();
    }
  }

  @Override
  public Calendar getJobEndTime() {
    if (!this.isStopped()) {
      return null;
    } else {
      return super.getJobEndTime();
    }
  }

  @Override
  public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
    return threadPool.awaitCompletion(timeout, unit);
  }

  @Override
  public boolean awaitCompletion() {
    try {
      return awaitCompletion(Long.MAX_VALUE, TimeUnit.DAYS);
    } catch(InterruptedException e) {
      logger.debug("awaitCompletion caught InterruptedException");
      return false;
    }
  }

  @Override
  public WriteBatcher withJobName(String jobName) {
    requireNotInitialized();
    super.withJobName(jobName);
    return this;
  }

  @Override
  public WriteBatcher withJobId(String jobId) {
    requireNotInitialized();
    setJobId(jobId);
    return this;
  }

  @Override
  public WriteBatcher withBatchSize(int batchSize) {
    requireNotInitialized();
    super.withBatchSize(batchSize);
    return this;
  }

  @Override
  public WriteBatcher withThreadCount(int threadCount) {
    requireNotInitialized();
    super.withThreadCount(threadCount);
    return this;
  }

  @Override
  public WriteBatcher withTemporalCollection(String collection) {
    requireNotInitialized();
    this.temporalCollection = collection;
    return this;
  }

  @Override
  public String getTemporalCollection() {
    return temporalCollection;
  }

  @Override
  public WriteBatcher withTransform(ServerTransform transform) {
    requireNotInitialized();
    this.transform = transform;
    return this;
  }

  @Override
  public ServerTransform getTransform() {
    return transform;
  }

  @Override
  public synchronized WriteBatcher withForestConfig(ForestConfiguration forestConfig) {
    super.withForestConfig(forestConfig);
    // get the list of hosts to use
    Forest[] forests = forests(forestConfig);
    Set<String> hosts = hosts(forests);
    Map<String,HostInfo> existingHostInfos = new HashMap<>();
    Map<String,HostInfo> removedHostInfos = new HashMap<>();
    if ( hostInfos != null ) {
      for ( HostInfo hostInfo : hostInfos ) {
        existingHostInfos.put(hostInfo.hostName, hostInfo);
        removedHostInfos.put(hostInfo.hostName, hostInfo);
      }
    }
    logger.info("(withForestConfig) Using forests on {} hosts for \"{}\"", hosts, forests[0].getDatabaseName());
    // initialize a DatabaseClient for each host
    HostInfo[] newHostInfos = new HostInfo[hosts.size()];
    int i=0;
    for (String host: hosts) {
      if ( existingHostInfos.get(host) != null ) {
        newHostInfos[i] = existingHostInfos.get(host);
        removedHostInfos.remove(host);
      } else {
        newHostInfos[i] = new HostInfo();
        newHostInfos[i].hostName = host;
        // this is a host-specific client (no DatabaseClient is actually forest-specific)
        newHostInfos[i].client = getMoveMgr().getHostClient(host);
        if (getMoveMgr().getConnectionType() == DatabaseClient.ConnectionType.DIRECT) {
          logger.info("Adding DatabaseClient on port {} for host \"{}\" to the rotation",
                newHostInfos[i].client.getPort(), host);
        }
      }
      i++;
    }
    this.forestConfig = forestConfig;
    this.hostInfos = newHostInfos;

    if ( removedHostInfos.size() > 0 ) {
      DataMovementManagerImpl moveMgrImpl = getMoveMgr();
      String primaryHost = moveMgrImpl.getPrimaryClient().getHost();
      if ( removedHostInfos.containsKey(primaryHost) ) {
        int randomPos = new Random().nextInt(newHostInfos.length);
        moveMgrImpl.setPrimaryClient(newHostInfos[randomPos].client);
      }
      // since some hosts have been removed, let's remove from the queue any jobs that were targeting that host
      List<Runnable> tasks = new ArrayList<>();
      if ( threadPool != null ) threadPool.getQueue().drainTo(tasks);
      for ( Runnable task : tasks ) {
        if ( task instanceof BatchWriter ) {
          BatchWriter writerTask = (BatchWriter) task;
          if ( removedHostInfos.containsKey(writerTask.writeSet.getClient().getHost()) ) {
            // this batch was targeting a host that's no longer on the list
            // if we re-add these docs they'll now be in batches that target acceptable hosts
            BatchWriteSet writeSet = newBatchWriteSet(writerTask.writeSet.getBatchNumber());
            writeSet.onFailure(throwable -> {
              if ( throwable instanceof RuntimeException ) throw (RuntimeException) throwable;
              else throw new DataMovementException("Failed to retry batch after failover", throwable);
            });
            for ( WriteEvent doc : writerTask.writeSet.getBatchOfWriteEvents().getItems() ) {
              writeSet.getWriteSet().add(doc.getTargetUri(), doc.getMetadata(), doc.getContent());
            }
            BatchWriter retryWriterTask = new BatchWriter(writeSet);
            Runnable fretryWriterTask = (Runnable) threadPool.submit(retryWriterTask);
            threadPool.replaceTask(writerTask, fretryWriterTask);
            // jump to the next task
            continue;
          }
        }
        // this task is still valid so add it back to the queue
        Runnable fTask = (Runnable) threadPool.submit(task);
        threadPool.replaceTask(task, fTask);
      }
    }
    return this;
  }

  @Override
  public ForestConfiguration getForestConfig() {
    return forestConfig;
  }

  public static class HostInfo {
    public String hostName;
    public DatabaseClient client;
  }

  public static class BatchWriter implements Runnable {
    private BatchWriteSet writeSet;

    public BatchWriter(BatchWriteSet writeSet) {
      if ( writeSet.getWriteSet().size() == 0 ) {
        throw new IllegalStateException("Attempt to write an empty batch");
      }
      this.writeSet = writeSet;
    }

    @Override
    public void run() {
      try {
        Runnable onBeforeWrite = writeSet.getOnBeforeWrite();
        if ( onBeforeWrite != null ) {
          onBeforeWrite.run();
        }
        logger.trace("begin write batch {} to forest on host \"{}\"", writeSet.getBatchNumber(), writeSet.getClient().getHost());
        if ( writeSet.getTemporalCollection() == null ) {
          writeSet.getClient().newDocumentManager().write(
                  writeSet.getWriteSet(), writeSet.getTransform(), null
          );
        } else {
          // to get access to the TemporalDocumentManager write overload we need to instantiate
          // a JSONDocumentManager or XMLDocumentManager, but we don't want to make assumptions about content
          // format, so we'll set the default content format to unknown
          XMLDocumentManager docMgr = writeSet.getClient().newXMLDocumentManager();
          docMgr.setContentFormat(Format.UNKNOWN);
          docMgr.write(
                  writeSet.getWriteSet(), writeSet.getTransform(), null, writeSet.getTemporalCollection()
          );
        }
        closeAllHandles();
        Runnable onSuccess = writeSet.getOnSuccess();
        if ( onSuccess != null ) {
          onSuccess.run();
        }
      } catch (Throwable t) {
        logger.trace("failed batch sent to forest on host \"{}\"", writeSet.getClient().getHost());
        Consumer<Throwable> onFailure = writeSet.getOnFailure();
        if ( onFailure != null ) {
          onFailure.accept(t);
        }
      }
    }

    private void closeAllHandles() throws Throwable {
      Throwable lastThrowable = null;
      for ( DocumentWriteOperation doc : writeSet.getWriteSet() ) {
        try {
          if ( doc.getContent() instanceof Closeable ) {
            ((Closeable) doc.getContent()).close();
          }
          if ( doc.getMetadata() instanceof Closeable ) {
            ((Closeable) doc.getMetadata()).close();
          }
        } catch (Throwable t) {
          logger.error("error calling close()", t);
          lastThrowable = t;
        }
      }
      if ( lastThrowable != null ) throw lastThrowable;
    }
  }

  /**
   * The following classes and CompletableThreadPoolExecutor
   * CompletableRejectedExecutionHandler exist exclusively to enable the
   * desired behavior for awaitCompletion.  The desired behavior is that at any
   * moment one can call awaitCompletion and it will block until all tasks
   * added up to that point have completed (and if flushAndWait was called, all
   * documents added up to that point are written to the database).  This is
   * tricky behavior because tasks will continue to be added asynchronously
   * after that point, but we only want to wait for the completion of tasks
   * added up to that point.
   *
   * This behavior is desired so a developer can add a document to a
   * WriteBatcher instance and call awaitCompletion to know that document and
   * all documents added previously are commited in the database when
   * awaitCompletion returns (assuming it didn't timeout).  While a developer
   * could achieve the same behavior asynchronously by checking for documents
   * in the onBatchSuccess callback, that logic is complex, so we're taking on
   * that burden for them.
   *
   * To achieve this behavior we keep a set of all tasks
   * (queuedAndExecutingTasks) and we snapshot that set at the point
   * awaitCompletion is called.  Then we loop through all tasks in the
   * snapshot, waiting for each to complete.  We know a task has completed when
   * it has been removed from the snapshot.  We use Object methods wait and
   * notify so we know when to re-check the snapshot to see if a task has been
   * removed.
   *
   * A task can complete three ways:
   * 1) Normal execution by a thread from the thread pool
   * 2) Rejected execution because the task queue is full.  We use
   *    CallerRunsPolicy so the calling thread performs execution.
   * 3) Shutdown of the thread pool
   *
   * After each of the three cases we remove the task from
   * queuedAndExecutingTasks and from any active snapshots.  This avoids
   * accumulation of tasks after they're finished.  To avoid accumulation of
   * snapshots they remove themselves when they are no longer needed (when the
   * awaitCompletion call is finished).
   */
  public static class CompletableRejectedExecutionHandler extends ThreadPoolExecutor.CallerRunsPolicy {
    CompletableThreadPoolExecutor threadPool = null;

    public void setThreadPool(CompletableThreadPoolExecutor threadPool) {
      this.threadPool = threadPool;
    }

    // After completing the task (Runnable), remove it from
    // queuedAndExecutingTasks and any active snapshots.  This is called when
    // the task queue is full.  Since this RejectedExecutionHandler extends
    // CallerRunsPolicy it first allows the calling thread to execute the task
    // to completion, then it removes it.
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
      super.rejectedExecution(r, e);
      threadPool.taskComplete(r);
    }
  }

  public static class CompletableThreadPoolExecutor extends ThreadPoolExecutor {
    // we use ConcurrentHashMap so modifications are thread-safe but
    // unsychronized for better performance
    Set<Runnable> queuedAndExecutingTasks = ConcurrentHashMap.<Runnable>newKeySet();

    // we tried a Set for activeSnapshots, but ConcurrentHashMap instances don't have
    // a reliable hashCode method, so the Set often overwrote snapshots
    // thinking they were the same.  So while the thread isn't needed, it works
    // as a key.  We are trusting that shapshots will always get removed when
    // each call to awaitCompletion is done.
    Map<Thread, ConcurrentLinkedQueue<Runnable>> activeSnapshots = new ConcurrentHashMap<>();

    public CompletableThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                                         TimeUnit unit, BlockingQueue<Runnable> queue)
    {
      super(corePoolSize, maximumPoolSize, keepAliveTime, unit, queue, new CompletableRejectedExecutionHandler());
      // now that super() has been called we can reference "this" to add it to
      // the RejectedExecutionHandler
      ((CompletableRejectedExecutionHandler) getRejectedExecutionHandler()).setThreadPool(this);
    }

    // execute is called whenever a task is added to the Executor we tried
    // overriding submit (since that's what we call) but it seems to wrap the
    // runnables passed to it, so they aren't the same runnables passed to
    // afterExecute.  We've had better luck with runnables matching after
    // overriding execute.
    public void execute(Runnable r) {
      queuedAndExecutingTasks.add(r);
      super.execute(r);
    }

    // afterExecute is called when a task has run to completion in a thread
    // from the thread pool
    protected void afterExecute(Runnable r, Throwable t) {
      taskComplete(r);
      super.afterExecute(r, t);
    }

    public ConcurrentLinkedQueue<Runnable> snapshotQueuedAndExecutingTasks() {
      ConcurrentLinkedQueue<Runnable> snapshot = new ConcurrentLinkedQueue<>();
      activeSnapshots.put( Thread.currentThread(), snapshot );
      snapshot.addAll(queuedAndExecutingTasks);
      // There is inconsistency between queuedAndExecutingTasks and snapshot
      // taken here. If there are a large number of tasks, by the time we
      // iterate queuedAndExecutingTasks and get all the tasks into the snapshot
      // queue, some tasks complete and are removed from
      // queuedAndExecutingTasks. So, iterate over the snapshot again and remove
      // those completed tasks so that they both are consistent.
      for (Runnable task : snapshot) {
        if ( !(queuedAndExecutingTasks.contains(task)) )
          snapshot.remove(task);
      }
      return snapshot;
    }

    public void removeSnapshot() {
      activeSnapshots.remove(Thread.currentThread());
    }

    // taskComplete is called when a task finishes by:
    //   1) Normal execution by a thread from the thread pool
    //   2) Rejected execution because the task queue is full.  We use
    //      CallerRunsPolicy so the calling thread performs execution.
    //   3) Shutdown of the thread pool
    // taskComplete removes the completed Runnable from queuedAndExecutingTasks
    // and all active snapshots.
    public void taskComplete(Runnable r) {
      boolean removedFromASnapshot = false;
      queuedAndExecutingTasks.remove(r);
      for ( ConcurrentLinkedQueue<Runnable> snapshot : activeSnapshots.values() ) {
        if ( snapshot.remove(r) ) {
          removedFromASnapshot = true;
        }
      }
      // if no snapshots contained this task, we can avoid the synchronized block
      if ( removedFromASnapshot == true ) {
        synchronized(r) { r.notifyAll(); }
      }
    }

    // During failover, in order to re-submit the tasks which are meant
    // for a failed host, we drain the thread pool and re-submit all the tasks appropriately.
    // We would need awaitCompletion() to wait until these resubmitted tasks are also finished.
    // Hence we need to remove the old tasks from queuedAndExecutingTasks and any active
    // snapshots which contains them and replace it with new tasks which are submitted.
    public void replaceTask(Runnable oldTask, Runnable newTask) {
      boolean removedFromASnapshot = false;
      if(queuedAndExecutingTasks.remove(oldTask)) {
        queuedAndExecutingTasks.add(newTask);
      }

      for ( ConcurrentLinkedQueue<Runnable> snapshot : activeSnapshots.values() ) {
        if ( snapshot.remove(oldTask) ) {
          snapshot.add(newTask);
          removedFromASnapshot = true;
        }
      }
      // if no snapshots contained this task, we can avoid the synchronized block
      if ( removedFromASnapshot == true ) {
        synchronized(oldTask) { oldTask.notifyAll(); }
      }
    }
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
      if ( unit == null ) throw new IllegalArgumentException("unit cannot be null");
      // get a snapshot so we only look at tasks already queued, not any that
      // get asynchronously queued after this point
      ConcurrentLinkedQueue<Runnable> snapshotQueuedAndExecutingTasks = snapshotQueuedAndExecutingTasks();
      try {
        long duration = TimeUnit.MILLISECONDS.convert(timeout, unit);
        // we can iterate even when the underlying set is being modified
        // since we're using ConcurrentHashMap
        Runnable task = null;
        while((task = snapshotQueuedAndExecutingTasks.peek()) != null) {
          // Lock task before we re-check whether it is queued or executing in
          // the main set and in the snapshot.  Thus there's no way for the
          // notifyAll to sneak in right after our check and leave us waiting
          // forever.  Also we already have the lock required to call
          // task.wait().  Normally we religiously avoid any synchronized
          // blocks, but we couldn't find a way to avoid this one.
          synchronized(task) {
            while ( snapshotQueuedAndExecutingTasks.contains(task) &&
              queuedAndExecutingTasks.contains(task) )
            {
              long startTime = System.currentTimeMillis();
              // block until task is complete or timeout expires
              task.wait(duration);
              duration -= System.currentTimeMillis() - startTime;
              if ( duration <= 0 ) {
                // times up!  We didn't finish before timeout...
                logger.debug("[awaitCompletion] timeout");
                return false;
              }
            }
          }
        }
      } finally {
        removeSnapshot();
      }
      return true;
    }

    // shutdown the thread pool and remove any unstarted tasks from
    // queuedAndExecutingTasks and any active snapshots
    public List<Runnable> shutdownNow() {
      List<Runnable> tasks = super.shutdownNow();
      for ( Runnable task : tasks ) {
        taskComplete(task);
      }
      return tasks;
    }
  }

  @Override
  public WriteBatcher withDefaultMetadata(DocumentMetadataHandle handle) {
    this.defaultMetadata = handle;
    return this;
  }

  @Override
  public void addAll(Stream<? extends DocumentWriteOperation> operations) {
    operations.forEach(this::add);
}

  @Override
  public DocumentMetadataHandle getDocumentMetadata() {
  return defaultMetadata;
}
}
