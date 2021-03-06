/*
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

package org.apache.hadoop.hive.llap.io.api.impl;

import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.llap.ConsumerFeedback;
import org.apache.hadoop.hive.llap.LlapHiveUtils;
import org.apache.hadoop.hive.llap.counters.FragmentCountersMap;
import org.apache.hadoop.hive.llap.counters.LlapIOCounters;
import org.apache.hadoop.hive.llap.counters.QueryFragmentCounters;
import org.apache.hadoop.hive.llap.daemon.impl.StatsRecordingThreadPool;
import org.apache.hadoop.hive.llap.io.decode.ColumnVectorProducer;
import org.apache.hadoop.hive.llap.io.decode.ColumnVectorProducer.Includes;
import org.apache.hadoop.hive.llap.io.decode.ColumnVectorProducer.SchemaEvolutionFactory;
import org.apache.hadoop.hive.llap.io.decode.ReadPipeline;
import org.apache.hadoop.hive.llap.tezplugins.LlapTezUtils;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatchCtx;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcRecordUpdater;
import org.apache.hadoop.hive.ql.io.orc.OrcSplit;
import org.apache.hadoop.hive.ql.io.orc.VectorizedOrcAcidRowBatchReader;
import org.apache.hadoop.hive.ql.io.orc.encoded.Consumer;
import org.apache.hadoop.hive.ql.io.orc.encoded.Reader;
import org.apache.hadoop.hive.ql.io.sarg.ConvertAstToSearchArg;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.orc.TypeDescription;
import org.apache.orc.impl.SchemaEvolution;
import org.apache.tez.common.counters.TezCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LlapRecordReader implements RecordReader<NullWritable, VectorizedRowBatch>, Consumer<ColumnVectorBatch> {

  private static final Logger LOG = LoggerFactory.getLogger(LlapRecordReader.class);
  private static final Object DONE_OBJECT = new Object();

  private final FileSplit split;
  private final IncludesImpl includes;
  private final SearchArgument sarg;
  private final VectorizedRowBatchCtx rbCtx;
  private final boolean isVectorized;
  private final boolean probeDecodeEnabled;
  private VectorizedOrcAcidRowBatchReader acidReader;
  private final Object[] partitionValues;

  private final ArrayBlockingQueue<Object> queue;
  private final AtomicReference<Throwable> pendingError = new AtomicReference<>(null);

  /** Vector that is currently being processed by our user. */
  private ColumnVectorBatch lastCvb = null;
  private boolean isFirst = true;
  private int maxQueueSize = 0;

  private volatile boolean isClosed = false;
  private volatile boolean isInterrupted = false;
  private final ConsumerFeedback<ColumnVectorBatch> feedback;
  private final QueryFragmentCounters counters;
  private long firstReturnTime;

  private final JobConf jobConf;
  private final ReadPipeline rp;
  private final ExecutorService executor;
  private final boolean isAcidScan;
  private final boolean isAcidFormat;

  /**
   * Creates the record reader and checks the input-specific compatibility.
   * @return The reader if the split can be read, null otherwise.
   */
  public static LlapRecordReader create(JobConf job, FileSplit split,
      List<Integer> tableIncludedCols, String hostName, ColumnVectorProducer cvp,
      ExecutorService executor, InputFormat<?, ?> sourceInputFormat, Deserializer sourceSerDe,
      Reporter reporter, Configuration daemonConf) throws IOException, HiveException {
    MapWork mapWork = LlapHiveUtils.findMapWork(job);
    if (mapWork == null) return null; // No compatible MapWork.
    LlapRecordReader rr = new LlapRecordReader(mapWork, job, split, tableIncludedCols, hostName,
        cvp, executor, sourceInputFormat, sourceSerDe, reporter, daemonConf);
    if (!rr.checkOrcSchemaEvolution()) {
      rr.close();
      return null;
    }
    return rr;
  }

  private LlapRecordReader(MapWork mapWork, JobConf job, FileSplit split,
      List<Integer> tableIncludedCols, String hostName, ColumnVectorProducer cvp,
      ExecutorService executor, InputFormat<?, ?> sourceInputFormat, Deserializer sourceSerDe,
      Reporter reporter, Configuration daemonConf) throws IOException, HiveException {
    this.executor = executor;
    this.jobConf = job;
    this.split = split;

    this.sarg = ConvertAstToSearchArg.createFromConf(job);
    final String fragmentId = LlapTezUtils.getFragmentId(job);
    final String dagId = LlapTezUtils.getDagId(job);
    final String queryId = HiveConf.getVar(job, HiveConf.ConfVars.HIVEQUERYID);
    MDC.put("dagId", dagId);
    MDC.put("queryId", queryId);
    TezCounters taskCounters = null;
    if (fragmentId != null) {
      MDC.put("fragmentId", fragmentId);
      taskCounters = FragmentCountersMap.getCountersForFragment(fragmentId);
      LOG.info("Received fragment id: {}", fragmentId);
    } else {
      LOG.warn("Not using tez counters as fragment id string is null");
    }
    this.counters = new QueryFragmentCounters(job, taskCounters);
    this.counters.setDesc(QueryFragmentCounters.Desc.MACHINE, hostName);

    VectorizedRowBatchCtx ctx = mapWork.getVectorizedRowBatchCtx();
    rbCtx = ctx != null ? ctx : LlapInputFormat.createFakeVrbCtx(mapWork);

    isAcidScan = AcidUtils.isFullAcidScan(jobConf);
    TypeDescription schema = OrcInputFormat.getDesiredRowTypeDescr(
        job, isAcidScan, Integer.MAX_VALUE);

    int queueLimitBase = getQueueVar(ConfVars.LLAP_IO_VRB_QUEUE_LIMIT_MAX, job, daemonConf);
    int queueLimitMin = getQueueVar(ConfVars.LLAP_IO_VRB_QUEUE_LIMIT_MIN, job, daemonConf);
    long bestEffortSize = getLongQueueVar(ConfVars.LLAP_IO_CVB_BUFFERED_SIZE, job, daemonConf);

    final boolean
        decimal64Support =
        HiveConf.getVar(job, ConfVars.HIVE_VECTORIZED_INPUT_FORMAT_SUPPORTS_ENABLED).equalsIgnoreCase("decimal_64");
    int
        limit =
        determineQueueLimit(bestEffortSize,
            queueLimitBase,
            queueLimitMin,
            rbCtx.getRowColumnTypeInfos(),
            rbCtx.getDataColumnNums(),
            decimal64Support);
    LOG.info("Queue limit for LlapRecordReader is " + limit);
    this.queue = new ArrayBlockingQueue<>(limit);


    int partitionColumnCount = rbCtx.getPartitionColumnCount();
    if (partitionColumnCount > 0) {
      partitionValues = new Object[partitionColumnCount];
      VectorizedRowBatchCtx.getPartitionValues(rbCtx, mapWork, split, partitionValues);
    } else {
      partitionValues = null;
    }

    this.isVectorized = HiveConf.getBoolVar(jobConf, HiveConf.ConfVars.HIVE_VECTORIZATION_ENABLED);
    if (isAcidScan) {
      OrcSplit orcSplit = (OrcSplit) split;
      this.acidReader = new VectorizedOrcAcidRowBatchReader(
          orcSplit, jobConf, Reporter.NULL, null, rbCtx, true, mapWork);
      isAcidFormat = !orcSplit.isOriginal();
    } else {
      isAcidFormat = false;
    }

    this.includes = new IncludesImpl(tableIncludedCols, isAcidFormat, rbCtx,
        schema, job, isAcidScan && acidReader.includeAcidColumns());

    this.probeDecodeEnabled = HiveConf.getBoolVar(jobConf, ConfVars.HIVE_OPTIMIZE_SCAN_PROBEDECODE);
    if (this.probeDecodeEnabled) {
      includes.setProbeDecodeContext(mapWork.getProbeDecodeContext());
      LOG.info("LlapRecordReader ProbeDecode is enabled");
    }

    // Create the consumer of encoded data; it will coordinate decoding to CVBs.
    feedback = rp = cvp.createReadPipeline(this, split, includes, sarg, counters, includes,
        sourceInputFormat, sourceSerDe, reporter, job, mapWork.getPathToPartitionInfo());
  }

  private static int getQueueVar(ConfVars var, JobConf jobConf, Configuration daemonConf) {
    // Check job config for overrides, otherwise use the default server value.
    int jobVal = jobConf.getInt(var.varname, -1);
    return (jobVal != -1) ? jobVal : HiveConf.getIntVar(daemonConf, var);
  }

  private static long getLongQueueVar(ConfVars var, JobConf jobConf, Configuration daemonConf) {
    // Check job config for overrides, otherwise use the default server value.
    long jobVal = jobConf.getLong(var.varname, -1);
    return (jobVal != -1) ? jobVal : HiveConf.getLongVar(daemonConf, var);
  }

  // For queue size estimation purposes, we assume all columns have weight one, and the following
  // types are counted as multiple columns. This is very primitive; if we wanted to make it better,
  // we'd increase the base limit, and adjust dynamically based on IO and processing perf delays.
  private static final int COL_WEIGHT_COMPLEX = 16, COL_WEIGHT_HIVEDECIMAL = 10,
      COL_WEIGHT_STRING = 8;

  @VisibleForTesting
  static int determineQueueLimit(long maxBufferedSize,
      int queueLimitMax,
      int queueLimitMin,
      TypeInfo[] typeInfos,
      int[] projectedColumnNums,
      final boolean decimal64Support) {
    assert queueLimitMax >= queueLimitMin;
    // If the values are equal, the queue limit is fixed.
    if (queueLimitMax == queueLimitMin) return queueLimitMax;
    // If there are no columns (projection only join?) just assume no weight.
    if (projectedColumnNums == null || projectedColumnNums.length == 0) return queueLimitMax;
    // total weight as bytes
    double totalWeight = 0;
    int numberOfProjectedColumns = projectedColumnNums.length;
    double scale = Math.max(Math.log(numberOfProjectedColumns), 1);

    // Assuming that an empty Column Vector is about 96 bytes the object
    // org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector object internals:
    // OFFSET  SIZE                                                      TYPE DESCRIPTION
    // VALUE
    //      0    16                                                           (object header)
    //     16     1                                                   boolean ColumnVector.noNulls
    //     17     1                                                   boolean ColumnVector.isRepeating
    //     18     1                                                   boolean ColumnVector.preFlattenIsRepeating
    //     19     1                                                   boolean ColumnVector.preFlattenNoNulls
    //     20     4                                                           (alignment/padding gap)
    //     24     8   org.apache.hadoop.hive.ql.exec.vector.ColumnVector.Type ColumnVector.type
    //     32     8                                                 boolean[] ColumnVector.isNull
    //     40     4                                                       int BytesColumnVector.nextFree
    //     44     4                                                       int BytesColumnVector.smallBufferNextFree
    //     48     4                                                       int BytesColumnVector.bufferAllocationCount
    //     52     4                                                           (alignment/padding gap)
    //     56     8                                                  byte[][] BytesColumnVector.vector
    //     64     8                                                     int[] BytesColumnVector.start
    //     72     8                                                     int[] BytesColumnVector.length
    //     80     8                                                    byte[] BytesColumnVector.buffer
    //     88     8                                                    byte[] BytesColumnVector.smallBuffer
    long columnVectorBaseSize = (long) (96 * numberOfProjectedColumns * scale);

    for (int i = 0; i < projectedColumnNums.length; i++) {
      TypeInfo ti = typeInfos[projectedColumnNums[i]];
      int colWeight;
      if (ti.getCategory() != Category.PRIMITIVE) {
        colWeight = COL_WEIGHT_COMPLEX;
      } else {
        PrimitiveTypeInfo pti = (PrimitiveTypeInfo) ti;
        switch (pti.getPrimitiveCategory()) {
        case BINARY:
        case CHAR:
        case VARCHAR:
        case STRING:
          colWeight = COL_WEIGHT_STRING;
          break;
          //Timestamp column vector uses an int and long arrays
        case TIMESTAMP:
        case INTERVAL_DAY_TIME:
          colWeight = 2;
          break;
        case DECIMAL:
          boolean useDecimal64 = false;
          if (ti instanceof DecimalTypeInfo) {
            DecimalTypeInfo dti = (DecimalTypeInfo) ti;
            if (dti.getPrecision() <= TypeDescription.MAX_DECIMAL64_PRECISION && decimal64Support) {
              useDecimal64 = true;
            }
          }
          // decimal_64 column vectors gets the same weight as long column vectors
          if (useDecimal64) {
            colWeight = 1;
          } else {
            colWeight = COL_WEIGHT_HIVEDECIMAL;
          }
          break;
        default:
          colWeight = 1;
        }
      }
      totalWeight += colWeight * 8 * scale;
    }
    //default batch size is 1024
    totalWeight *= 1024;
    totalWeight +=  columnVectorBaseSize;
    int bestEffortSize = Math.min((int) (maxBufferedSize / totalWeight), queueLimitMax);
    return Math.max(bestEffortSize, queueLimitMin);
  }

  /**
   * Starts the data read pipeline
   */
  public void start() {
    // perform the data read asynchronously
    if (executor instanceof StatsRecordingThreadPool) {
      // Every thread created by this thread pool will use the same handler
      ((StatsRecordingThreadPool) executor).setUncaughtExceptionHandler(
          new IOUncaughtExceptionHandler());
    }
    executor.submit(rp.getReadCallable());
  }

  private boolean checkOrcSchemaEvolution() {
    SchemaEvolution evolution = rp.getSchemaEvolution();

    if (evolution.hasConversion() && !evolution.isOnlyImplicitConversion()) {

      // We do not support data type conversion when reading encoded ORC data.
      return false;
    }
    // TODO: should this just use physical IDs?
    for (int i = 0; i < includes.getReaderLogicalColumnIds().size(); ++i) {
      int projectedColId = includes.getReaderLogicalColumnIds().get(i);
      // Adjust file column index for ORC struct.
        int fileColId =  OrcInputFormat.getRootColumn(!isAcidScan) + projectedColId + 1;
      if (!evolution.isPPDSafeConversion(fileColId)) {
        LlapIoImpl.LOG.warn("Unsupported schema evolution! Disabling Llap IO for {}", split);
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean next(NullWritable key, VectorizedRowBatch vrb) throws IOException {
    assert vrb != null;
    if (isClosed) {
      throw new AssertionError("next called after close");
    }
    // Add partition cols if necessary (see VectorizedOrcInputFormat for details).
    boolean wasFirst = isFirst;
    if (isFirst) {
      if (partitionValues != null) {
        rbCtx.addPartitionColsToBatch(vrb, partitionValues);
      }
      isFirst = false;
    }
    ColumnVectorBatch cvb;
    try {
      cvb = nextCvb();
    } catch (InterruptedException e) {
      // Query might have been canceled. Stop the background processing.
      feedback.stop();
      isInterrupted = true; // In case we are stuck in consume.
      throw new IOException(e);
    }
    if (cvb == null) {
      if (wasFirst) {
        firstReturnTime = counters.startTimeCounter();
      }
      counters.incrWallClockCounter(LlapIOCounters.CONSUMER_TIME_NS, firstReturnTime);
      return false;
    }
    if (isAcidFormat) {
      vrb.selectedInUse = true;//why?
      if (isVectorized) {
        // TODO: relying everywhere on the magical constants and columns being together means ACID
        //       columns are going to be super hard to change in a backward compat manner. I can
        //       foresee someone cursing while refactoring all the magic for prefix schema changes.
        /*
          Acid meta cols are always either all included or all excluded the
          the width of 'cvb' changes accordingly so 'acidColCount' and
          'ixInVrb' need to be adjusted. See {@link IncludesImpl} comments.
         */
        // Exclude the row column.
        int acidColCount = acidReader.includeAcidColumns() ?
            OrcInputFormat.getRootColumn(false) - 1 : 0;
        VectorizedRowBatch inputVrb = new VectorizedRowBatch(
            //so +1 is the OrcRecordUpdater.ROW?
            acidColCount + 1 + vrb.getDataColumnCount());
        // By assumption, ACID columns are currently always in the beginning of the arrays.
        System.arraycopy(cvb.cols, 0, inputVrb.cols, 0, acidColCount);
        for (int ixInReadSet = acidColCount; ixInReadSet < cvb.cols.length; ++ixInReadSet) {
          int ixInVrb = includes.getPhysicalColumnIds().get(ixInReadSet) -
              (acidReader.includeAcidColumns() ? 0 : OrcRecordUpdater.ROW);
          // TODO: should we create the batch from vrbctx, and reuse the vectors, like below? Future work.
          inputVrb.cols[ixInVrb] = cvb.cols[ixInReadSet];
        }
        inputVrb.size = cvb.size;
        acidReader.setBaseAndInnerReader(new AcidWrapper(inputVrb));
        acidReader.next(NullWritable.get(), vrb);
      } else {
         // TODO: WTF? The old code seems to just drop the ball here.
        throw new AssertionError("Unsupported mode");
      }
    } else {
      if (includes.getPhysicalColumnIds().size() != cvb.cols.length) {
        throw new RuntimeException("Unexpected number of columns, VRB has "
            + includes.getPhysicalColumnIds().size() + " included, but the reader returned "
            + cvb.cols.length);
      }
      // VRB was created from VrbCtx, so we already have pre-allocated column vectors.
      // Return old CVs (if any) to caller. We assume these things all have the same schema.
      for (int ixInReadSet = 0; ixInReadSet < cvb.cols.length; ++ixInReadSet) {
        int ixInVrb = includes.getPhysicalColumnIds().get(ixInReadSet);
        cvb.swapColumnVector(ixInReadSet, vrb.cols, ixInVrb);
      }
      vrb.selectedInUse = false;//why?
      vrb.size = cvb.size;
    }

    if (wasFirst) {
      firstReturnTime = counters.startTimeCounter();
    }
    return true;
  }

  public VectorizedRowBatchCtx getVectorizedRowBatchCtx() {
    return rbCtx;
  }

  private static final class AcidWrapper
      implements RecordReader<NullWritable, VectorizedRowBatch> {
    private final VectorizedRowBatch acidVrb;

    private AcidWrapper(VectorizedRowBatch acidVrb) {
      this.acidVrb = acidVrb;
    }

    @Override
    public boolean next(NullWritable key, VectorizedRowBatch value) throws IOException {
      return true;
    }

    @Override
    public NullWritable createKey() {
      return NullWritable.get();
    }

    @Override
    public VectorizedRowBatch createValue() {
      return acidVrb;
    }

    @Override
    public long getPos() throws IOException {
      return 0;
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public float getProgress() throws IOException {
      return 0;
    }
  }

  private final class IOUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(final Thread t, final Throwable e) {
      LlapIoImpl.LOG.error("Unhandled error from reader thread. threadName: {} threadId: {}" +
          " Message: {}", t.getName(), t.getId(), e.getMessage());
      try {
        setError(e);
      } catch (InterruptedException e1) {
        LOG.info("IOUncaughtExceptionHandler interrupted; ignoring");
      }
    }
  }

  ColumnVectorBatch nextCvb() throws InterruptedException, IOException {
    boolean isFirst = (lastCvb == null);
    if (!isFirst) {
      feedback.returnData(lastCvb);
    }

    // We are waiting for next block. Either we will get it, or be told we are done.
    int queueSize = queue.size();
    maxQueueSize = Math.max(queueSize, maxQueueSize);
    boolean doLogBlocking = LlapIoImpl.LOG.isTraceEnabled() && queueSize == 0;
    if (doLogBlocking) {
      LlapIoImpl.LOG.trace("next will block");
    }
    // We rely on the fact that poll() checks interrupt even when there's something in the queue.
    // If the structure is replaced with smth that doesn't, we MUST check interrupt here because
    // Hive operators rely on recordreader to handle task interruption, and unlike most RRs we
    // do not do any blocking IO ops on this thread.
    Object next;
    do {
      rethrowErrorIfAny(pendingError.get()); // Best-effort check; see the comment in the method.
      next = queue.poll(100, TimeUnit.MILLISECONDS);
    } while (next == null);
    if (doLogBlocking) {
      LlapIoImpl.LOG.trace("next is unblocked");
    }
    if (next == DONE_OBJECT) {
      return null; // We are done.
    }
    if (next instanceof Throwable) {
      rethrowErrorIfAny((Throwable) next);
      throw new AssertionError("Unreachable");
    }
    lastCvb = (ColumnVectorBatch) next;
    if (LlapIoImpl.LOG.isTraceEnabled()) {
      LlapIoImpl.LOG.trace("Processing will receive vector {}", lastCvb);
    }
    return lastCvb;
  }

  @Override
  public NullWritable createKey() {
    return NullWritable.get();
  }

  @Override
  public VectorizedRowBatch createValue() {
    return rbCtx.createVectorizedRowBatch();
  }

  @Override
  public long getPos() throws IOException {
    return -1; // Position doesn't make sense for async reader, chunk order is arbitrary.
  }

  @Override
  public void close() throws IOException {
    if (LlapIoImpl.LOG.isTraceEnabled()) {
      LlapIoImpl.LOG.trace("close called; closed {}, interrupted {}, err {}, pending {}",
          isClosed, isInterrupted, pendingError.get(), queue.size());
    }
    LlapIoImpl.LOG.info("Maximum queue length observed " + maxQueueSize);
    LlapIoImpl.LOG.info("Llap counters: {}" , counters); // This is where counters are logged!
    feedback.stop();
    isClosed = true;
    rethrowErrorIfAny(pendingError.get());
    MDC.clear();
  }

  private static void rethrowErrorIfAny(Throwable pendingError) throws IOException {
    // This is called either with an error that was queued, or an error that was set into the
    // atomic reference in this class. The latter is best-effort and is used to opportunistically
    // skip processing of a long queue when the error happens.
    if (pendingError == null) return;
    if (pendingError instanceof IOException) {
      throw (IOException)pendingError;
    }
    throw new IOException(pendingError);
  }

  @Override
  public void setDone() throws InterruptedException {
    if (LlapIoImpl.LOG.isDebugEnabled()) {
      LlapIoImpl.LOG.debug("setDone called; closed {}, interrupted {}, err {}, pending {}",
          isClosed, isInterrupted, pendingError.get(), queue.size());
    }
    enqueueInternal(DONE_OBJECT);
  }

  @Override
  public void consumeData(ColumnVectorBatch data) throws InterruptedException {
    if (LlapIoImpl.LOG.isTraceEnabled()) {
      LlapIoImpl.LOG.trace("consume called; closed {}, interrupted {}, err {}, pending {}",
          isClosed, isInterrupted, pendingError.get(), queue.size());
    }
    enqueueInternal(data);
  }

  @Override
  public void setError(Throwable t) throws InterruptedException {
    counters.incrCounter(LlapIOCounters.NUM_ERRORS);
    LlapIoImpl.LOG.debug("setError called; closed {}, interrupted {},  err {}, pending {}",
        isClosed, isInterrupted, pendingError.get(), queue.size());
    LlapIoImpl.LOG.warn("setError called with an error", t);
    assert t != null;
    pendingError.compareAndSet(null, t);
    enqueueInternal(t);
  }

  private void enqueueInternal(Object o) throws InterruptedException {
    // We need to loop here to handle the case where consumer goes away.
    do {} while (!isClosed && !isInterrupted && !queue.offer(o, 100, TimeUnit.MILLISECONDS));
  }

  @Override
  public float getProgress() throws IOException {
    // TODO: plumb progress info thru the reader if we can get metadata from loader first.
    return 0.0f;
  }

  
  /** This class encapsulates include-related logic for LLAP readers. It is not actually specific
   *  to LLAP IO but in LLAP IO in particular, I want to encapsulate all this mess for now until
   *  we have smth better like Schema Evolution v2. This can also hypothetically encapsulate
   *  field pruning inside structs and stuff like that.
   *
   *  There is some split brain issue between {@link SchemaEvolution} used in
   *  non-LLAP path and  this class.  The file schema for acid tables looks
   *  like  this and <op, owid, writerId, rowid, cwid, <f1, ... fn>> and
   *  {@link SchemaEvolution#getFileIncluded()}  respects that.  So if fn=2,
   *  the  type IDs are 0..8 and the fileIncluded[] has 9 bits that indicate
   *  what is read.  So in particular, {@link org.apache.hadoop.hive.ql.io.orc.RecordReader}
   *  produces ColumnVectorS are NULL in every row for each
   *  fileIncluded[9]==false.  The fields corresponding to structs are always
   *  included if any child of the struct has to be included.
   *
   *  LLAP only produces ColumnVectorS if they are needed so the width of
   *  ColumnVectorBatch varies depending on what was projected.
   *
   *  See also {@link VectorizedOrcAcidRowBatchReader#includeAcidColumns()} and
   *  {@link #next(NullWritable, VectorizedRowBatch)}*/
  private static class IncludesImpl implements SchemaEvolutionFactory, Includes {
    private List<Integer> readerLogicalColumnIds;
    private List<Integer> filePhysicalColumnIds;
    private Integer acidStructColumnId = null;
    private final boolean includeAcidColumns;

    // For current schema evolution.
    private TypeDescription readerSchema;
    private JobConf jobConf;

    // ProbeDecode Context for row-level filtering
    private TableScanOperator.ProbeDecodeContext probeDecodeContext = null;

    public IncludesImpl(List<Integer> tableIncludedCols, boolean isAcidScan,
        VectorizedRowBatchCtx rbCtx, TypeDescription readerSchema,
        JobConf jobConf, boolean includeAcidColumns) {
          // Note: columnIds below makes additional changes for ACID. Don't use this var directly.
      this.readerSchema = readerSchema;
      this.jobConf = jobConf;
      this.includeAcidColumns = includeAcidColumns;

      // Assume including everything means the VRB will have everything.
      if (tableIncludedCols == null) {
        // TODO: this is rather brittle, esp. in view of schema evolution (in abstract, not as 
        //       currently implemented in Hive). The compile should supply the columns it expects
        //       to see, which is not "all, of any schema". Is VRB row CVs the right mechanism
        //       for that? Who knows. Perhaps resolve in schema evolution v2.
        tableIncludedCols = new ArrayList<>(rbCtx.getDataColumnCount());
        for (int i = 0; i < rbCtx.getDataColumnCount(); ++i) {
          tableIncludedCols.add(i);
        }
      }

      this.readerLogicalColumnIds = tableIncludedCols;
      LOG.debug("Logical table includes: {}", readerLogicalColumnIds);

      // Note: schema evolution currently does not support column index changes.
      //       So, the indices should line up... to be fixed in SE v2?
      if (isAcidScan) {
        int rootCol = OrcInputFormat.getRootColumn(false);
        this.filePhysicalColumnIds = new ArrayList<>(readerLogicalColumnIds.size() + rootCol);
        this.acidStructColumnId = rootCol - 1; // OrcRecordUpdater.ROW. This is somewhat fragile...
        if (includeAcidColumns) {
          // Up to acidStructColumnId: as we don't want to include the root struct in ACID case;
          // it would cause the whole struct to get read without projection.
          for (int i = 0; i < acidStructColumnId; ++i) {
            // Note: this guarantees that physical column IDs are in order.
            filePhysicalColumnIds.add(i);
          }
        }
        /**
         * Even when NOT including acid columns, we still want to number the
         * physical columns as if acid columns are included because
         * {@link #generateFileIncludes(TypeDescription)} takes the file
         * schema as input
         * (eg <op, owid, writerId, rowid, cwid, <f1, ... fn>>)
         */
        for (int tableColumnId : readerLogicalColumnIds) {
          // Make sure to generate correct ids in type tree in-order traversal
          /* ok, so if filePhysicalColumnIds include acid column ids, we end up decoding the vectors*/
          filePhysicalColumnIds.add(rootCol + tableColumnId);
        }
      } else {
        this.filePhysicalColumnIds = readerLogicalColumnIds;
      }
    }

    @Override
    public String toString() {
      return "logical columns " + readerLogicalColumnIds
          + ", physical columns " + filePhysicalColumnIds;
    }

    @Override
    public SchemaEvolution createSchemaEvolution(TypeDescription fileSchema) {
      if (readerSchema == null) {
        readerSchema = fileSchema;
      }
      // TODO: will this work correctly with ACID?
      boolean[] readerIncludes = OrcInputFormat.genIncludedColumns(
          readerSchema, readerLogicalColumnIds);
      Reader.Options options = new Reader.Options(jobConf)
          .include(readerIncludes).includeAcidColumns(includeAcidColumns);
      return new SchemaEvolution(fileSchema, readerSchema, options);
    }

    @Override
    public boolean[] generateFileIncludes(TypeDescription fileSchema) {
      return OrcInputFormat.genIncludedColumns(
          fileSchema, filePhysicalColumnIds, acidStructColumnId);
    }

    public void setProbeDecodeContext(TableScanOperator.ProbeDecodeContext currProbeDecodeContext) {
      this.probeDecodeContext = currProbeDecodeContext;
    }

    @Override
    public List<Integer> getPhysicalColumnIds() {
      return filePhysicalColumnIds;
    }

    @Override
    public List<Integer> getReaderLogicalColumnIds() {
      return readerLogicalColumnIds;
    }

    @Override
    public TypeDescription[] getBatchReaderTypes(TypeDescription fileSchema) {
      return OrcInputFormat.genIncludedTypes(
          fileSchema, filePhysicalColumnIds, acidStructColumnId);
    }

    @Override
    public String[] getOriginalColumnNames(TypeDescription fileSchema) {
      return OrcInputFormat.genIncludedColNames(
              fileSchema, filePhysicalColumnIds, acidStructColumnId);
    }

    @Override
    public String getQueryId() {
      return HiveConf.getVar(jobConf, HiveConf.ConfVars.HIVEQUERYID);
    }

    @Override
    public boolean isProbeDecodeEnabled() {
      return this.probeDecodeContext != null;
    }

    @Override
    public byte getProbeMjSmallTablePos() {
      return this.probeDecodeContext.getMjSmallTablePos();
    }

    @Override
    public int getProbeColIdx() {
      // TODO: is this the best way to get the ColId?
      Pattern pattern = Pattern.compile("_col([0-9]+)");
      Matcher matcher = pattern.matcher(this.probeDecodeContext.getMjBigTableKeyColName());
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    @Override
    public String getProbeColName() {
      return this.probeDecodeContext.getMjBigTableKeyColName();
    }

    @Override
    public String getProbeCacheKey() {
      return this.probeDecodeContext.getMjSmallTableCacheKey();
    }

  }
} 
