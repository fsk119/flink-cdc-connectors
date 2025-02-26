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

package com.alibaba.ververica.cdc.connectors.mysql.debezium.reader;

import org.apache.flink.util.FlinkRuntimeException;

import org.apache.flink.shaded.guava18.com.google.common.util.concurrent.ThreadFactoryBuilder;

import com.alibaba.ververica.cdc.connectors.mysql.debezium.task.MySqlBinlogSplitReadTask;
import com.alibaba.ververica.cdc.connectors.mysql.debezium.task.MySqlSnapshotSplitReadTask;
import com.alibaba.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext;
import com.alibaba.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySqlSnapshotSplit;
import com.alibaba.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.alibaba.ververica.cdc.connectors.mysql.source.utils.RecordUtils;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.mysql.MySqlOffsetContext;
import io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.source.spi.ChangeEventSource;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.util.SchemaNameAdjuster;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.ververica.cdc.connectors.mysql.source.utils.RecordUtils.normalizedSplitRecords;

/**
 * A snapshot reader that reads data from Table in split level, the split is assigned by primary key
 * range.
 */
public class SnapshotSplitReader implements DebeziumReader<SourceRecord, MySqlSplit> {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotSplitReader.class);
    private final StatefulTaskContext statefulTaskContext;
    private final ExecutorService executor;

    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private volatile boolean currentTaskRunning;
    private volatile Throwable readException;

    // task to read snapshot for current split
    private MySqlSnapshotSplitReadTask splitSnapshotReadTask;
    private MySqlSnapshotSplit currentSnapshotSplit;
    private SchemaNameAdjuster nameAdjuster;
    public AtomicBoolean hasNextElement;
    public AtomicBoolean reachEnd;

    public SnapshotSplitReader(StatefulTaskContext statefulTaskContext, int subtaskId) {
        this.statefulTaskContext = statefulTaskContext;
        ThreadFactory threadFactory =
                new ThreadFactoryBuilder().setNameFormat("debezium-reader-" + subtaskId).build();
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
        this.currentTaskRunning = false;
        this.hasNextElement = new AtomicBoolean(false);
        this.reachEnd = new AtomicBoolean(false);
    }

    public void submitSplit(MySqlSplit mySqlSplit) {
        this.currentSnapshotSplit = mySqlSplit.asSnapshotSplit();
        statefulTaskContext.configure(currentSnapshotSplit);
        this.queue = statefulTaskContext.getQueue();
        this.nameAdjuster = statefulTaskContext.getSchemaNameAdjuster();
        this.hasNextElement.set(true);
        this.reachEnd.set(false);
        this.splitSnapshotReadTask =
                new MySqlSnapshotSplitReadTask(
                        statefulTaskContext.getConnectorConfig(),
                        statefulTaskContext.getOffsetContext(),
                        statefulTaskContext.getSnapshotChangeEventSourceMetrics(),
                        statefulTaskContext.getDatabaseSchema(),
                        statefulTaskContext.getConnection(),
                        statefulTaskContext.getDispatcher(),
                        statefulTaskContext.getTopicSelector(),
                        StatefulTaskContext.getClock(),
                        currentSnapshotSplit);
        executor.submit(
                () -> {
                    try {
                        currentTaskRunning = true;
                        // execute snapshot read task
                        final SnapshotSplitChangeEventSourceContextImpl sourceContext =
                                new SnapshotSplitChangeEventSourceContextImpl();
                        SnapshotResult snapshotResult =
                                splitSnapshotReadTask.execute(sourceContext);

                        final MySqlBinlogSplit appendBinlogSplit = createBinlogSplit(sourceContext);
                        final MySqlOffsetContext mySqlOffsetContext =
                                statefulTaskContext.getOffsetContext();
                        mySqlOffsetContext.setBinlogStartPoint(
                                appendBinlogSplit.getStartingOffset().getFilename(),
                                appendBinlogSplit.getStartingOffset().getPosition());
                        // execute binlog read task
                        if (snapshotResult.isCompletedOrSkipped()) {
                            // task to read binlog for current split
                            MySqlBinlogSplitReadTask splitBinlogReadTask =
                                    new MySqlBinlogSplitReadTask(
                                            statefulTaskContext.getConnectorConfig(),
                                            mySqlOffsetContext,
                                            statefulTaskContext.getConnection(),
                                            statefulTaskContext.getDispatcher(),
                                            statefulTaskContext.getErrorHandler(),
                                            StatefulTaskContext.getClock(),
                                            statefulTaskContext.getTaskContext(),
                                            (MySqlStreamingChangeEventSourceMetrics)
                                                    statefulTaskContext
                                                            .getStreamingChangeEventSourceMetrics(),
                                            statefulTaskContext
                                                    .getTopicSelector()
                                                    .getPrimaryTopic(),
                                            appendBinlogSplit);
                            splitBinlogReadTask.execute(
                                    new SnapshotBinlogSplitChangeEventSourceContextImpl());
                        } else {
                            readException =
                                    new IllegalStateException(
                                            String.format(
                                                    "Read snapshot for mysql split %s fail",
                                                    currentSnapshotSplit));
                        }
                    } catch (Exception e) {
                        currentTaskRunning = false;
                        LOG.error(
                                String.format(
                                        "Execute snapshot read task for mysql split %s fail",
                                        currentSnapshotSplit),
                                e);
                        readException = e;
                    }
                });
    }

    private MySqlBinlogSplit createBinlogSplit(
            SnapshotSplitChangeEventSourceContextImpl sourceContext) {
        return new MySqlBinlogSplit(
                currentSnapshotSplit.splitId(),
                currentSnapshotSplit.getSplitKeyType(),
                sourceContext.getLowWatermark(),
                sourceContext.getHighWatermark(),
                new ArrayList<>(),
                currentSnapshotSplit.getTableSchemas());
    }

    @Override
    public boolean isFinished() {
        return currentSnapshotSplit == null
                || (!currentTaskRunning && !hasNextElement.get() && reachEnd.get());
    }

    @Nullable
    @Override
    public Iterator<SourceRecord> pollSplitRecords() throws InterruptedException {
        checkReadException();

        if (hasNextElement.get()) {
            // data input: [low watermark event][snapshot events][high watermark event][binlog
            // events][binlog-end event]
            // data output: [low watermark event][normalized events][high watermark event]
            boolean reachBinlogEnd = false;
            final List<SourceRecord> sourceRecords = new ArrayList<>();
            while (!reachBinlogEnd) {
                List<DataChangeEvent> batch = queue.poll();
                for (DataChangeEvent event : batch) {
                    sourceRecords.add(event.getRecord());
                    if (RecordUtils.isEndWatermarkEvent(event.getRecord())) {
                        reachBinlogEnd = true;
                        break;
                    }
                }
            }
            // snapshot split return its data once
            hasNextElement.set(false);
            return normalizedSplitRecords(currentSnapshotSplit, sourceRecords, nameAdjuster)
                    .iterator();
        }
        // the data has been polled, no more data
        reachEnd.compareAndSet(false, true);
        return null;
    }

    private void checkReadException() {
        if (readException != null) {
            throw new FlinkRuntimeException(
                    String.format(
                            "Read split %s error due to %s.",
                            currentSnapshotSplit, readException.getMessage()),
                    readException);
        }
    }

    @Override
    public void close() {
        try {
            if (statefulTaskContext.getConnection() != null) {
                statefulTaskContext.getConnection().close();
            }
            if (statefulTaskContext.getBinaryLogClient() != null) {
                statefulTaskContext.getBinaryLogClient().disconnect();
            }
        } catch (Exception e) {
            LOG.error("Close snapshot reader error", e);
        }
    }

    /**
     * {@link ChangeEventSource.ChangeEventSourceContext} implementation that keeps low/high
     * watermark for each {@link MySqlSnapshotSplit}.
     */
    public class SnapshotSplitChangeEventSourceContextImpl
            implements ChangeEventSource.ChangeEventSourceContext {

        private BinlogOffset lowWatermark;
        private BinlogOffset highWatermark;

        public BinlogOffset getLowWatermark() {
            return lowWatermark;
        }

        public void setLowWatermark(BinlogOffset lowWatermark) {
            this.lowWatermark = lowWatermark;
        }

        public BinlogOffset getHighWatermark() {
            return highWatermark;
        }

        public void setHighWatermark(BinlogOffset highWatermark) {
            this.highWatermark = highWatermark;
        }

        @Override
        public boolean isRunning() {
            return lowWatermark != null && highWatermark != null;
        }
    }

    /**
     * The {@link ChangeEventSource.ChangeEventSourceContext} implementation for bounded binlog task
     * of a snapshot split task.
     */
    public class SnapshotBinlogSplitChangeEventSourceContextImpl
            implements ChangeEventSource.ChangeEventSourceContext {

        public void finished() {
            currentTaskRunning = false;
        }

        @Override
        public boolean isRunning() {
            return currentTaskRunning;
        }
    }
}
