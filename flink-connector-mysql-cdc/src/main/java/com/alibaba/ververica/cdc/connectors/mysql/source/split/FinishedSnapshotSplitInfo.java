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

package com.alibaba.ververica.cdc.connectors.mysql.source.split;

import com.alibaba.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import io.debezium.relational.TableId;

import java.util.Arrays;
import java.util.Objects;

/** The information used to describe a finished snapshot split. */
public class FinishedSnapshotSplitInfo {

    private final TableId tableId;
    private final String splitId;
    private final Object[] splitStart;
    private final Object[] splitEnd;
    private final BinlogOffset highWatermark;

    public FinishedSnapshotSplitInfo(
            TableId tableId,
            String splitId,
            Object[] splitStart,
            Object[] splitEnd,
            BinlogOffset highWatermark) {
        this.tableId = tableId;
        this.splitId = splitId;
        this.splitStart = splitStart;
        this.splitEnd = splitEnd;
        this.highWatermark = highWatermark;
    }

    public TableId getTableId() {
        return tableId;
    }

    public String getSplitId() {
        return splitId;
    }

    public Object[] getSplitStart() {
        return splitStart;
    }

    public Object[] getSplitEnd() {
        return splitEnd;
    }

    public BinlogOffset getHighWatermark() {
        return highWatermark;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FinishedSnapshotSplitInfo that = (FinishedSnapshotSplitInfo) o;
        return Objects.equals(tableId, that.tableId)
                && Objects.equals(splitId, that.splitId)
                && Arrays.equals(splitStart, that.splitStart)
                && Arrays.equals(splitEnd, that.splitEnd)
                && Objects.equals(highWatermark, that.highWatermark);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(tableId, splitId, highWatermark);
        result = 31 * result + Arrays.hashCode(splitStart);
        result = 31 * result + Arrays.hashCode(splitEnd);
        return result;
    }

    @Override
    public String toString() {
        return "FinishedSnapshotSplitInfo{"
                + "tableId="
                + tableId
                + ", splitId='"
                + splitId
                + '\''
                + ", splitStart="
                + Arrays.toString(splitStart)
                + ", splitEnd="
                + Arrays.toString(splitEnd)
                + ", highWatermark="
                + highWatermark
                + '}';
    }
}
