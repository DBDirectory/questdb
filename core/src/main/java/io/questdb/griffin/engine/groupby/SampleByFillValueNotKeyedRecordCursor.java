/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.groupby;

import io.questdb.cairo.sql.DelegatingRecordCursor;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.NoRandomAccessRecordCursor;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.SqlExecutionInterruptor;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.engine.functions.NoArgFunction;
import io.questdb.griffin.engine.functions.TimestampFunction;
import io.questdb.std.IntList;
import io.questdb.std.ObjList;

public class SampleByFillValueNotKeyedRecordCursor implements DelegatingRecordCursor, NoRandomAccessRecordCursor {
    private final ObjList<GroupByFunction> groupByFunctions;
    private final int timestampIndex;
    private final TimestampSampler timestampSampler;
    private final SplitVirtualRecord record;
    private final IntList symbolTableSkewIndex;
    private final SimpleMapValue simpleMapValue;
    private RecordCursor base;
    private Record baseRecord;
    private long lastTimestamp;
    private long nextTimestamp;
    private SqlExecutionInterruptor interruptor;

    public SampleByFillValueNotKeyedRecordCursor(
            ObjList<GroupByFunction> groupByFunctions,
            ObjList<Function> recordFunctions,
            ObjList<Function> placeholderFunctions,
            int timestampIndex, // index of timestamp column in base cursor
            TimestampSampler timestampSampler,
            IntList symbolTableSkewIndex,
            SimpleMapValue simpleMapValue
    ) {
        this.simpleMapValue = simpleMapValue;
        this.groupByFunctions = groupByFunctions;
        this.timestampIndex = timestampIndex;
        this.timestampSampler = timestampSampler;
        this.record = new SplitVirtualRecord(recordFunctions, placeholderFunctions);
        this.record.of(simpleMapValue);
        this.symbolTableSkewIndex = symbolTableSkewIndex;
        assert recordFunctions.size() == placeholderFunctions.size();
        final TimestampFunc timestampFunc = new TimestampFunc(0);
        for (int i = 0, n = recordFunctions.size(); i < n; i++) {
            Function f = recordFunctions.getQuick(i);
            if (f == null) {
                recordFunctions.setQuick(i, timestampFunc);
                placeholderFunctions.setQuick(i, timestampFunc);
            }
        }
    }

    @Override
    public void close() {
        base.close();
        interruptor = null;
    }

    @Override
    public Record getRecord() {
        return record;
    }

    @Override
    public SymbolTable getSymbolTable(int columnIndex) {
        return base.getSymbolTable(symbolTableSkewIndex.get(columnIndex));
    }

    @Override
    public boolean hasNext() {
        if (baseRecord == null) {
            return false;
        }

        // key map has been flushed
        // before we build another one we need to check
        // for timestamp gaps

        // what is the next timestamp we are expecting?
        final long nextTimestamp = timestampSampler.nextTimestamp(lastTimestamp);

        // is data timestamp ahead of next expected timestamp?
        if (this.nextTimestamp > nextTimestamp) {
            this.lastTimestamp = nextTimestamp;
            record.setActiveB();
            return true;
        }

        // this is new timestamp value
        this.lastTimestamp = timestampSampler.round(baseRecord.getTimestamp(timestampIndex));

        // switch to non-placeholder record
        record.setActiveA();

        int n = groupByFunctions.size();
        // initialize values
        for (int i = 0; i < n; i++) {
            interruptor.checkInterrupted();
            groupByFunctions.getQuick(i).computeFirst(simpleMapValue, baseRecord);
        }

        while (base.hasNext()) {
            final long timestamp = timestampSampler.round(baseRecord.getTimestamp(timestampIndex));
            if (lastTimestamp == timestamp) {
                for (int i = 0; i < n; i++) {
                    interruptor.checkInterrupted();
                    groupByFunctions.getQuick(i).computeNext(simpleMapValue, baseRecord);
                }
            } else {
                // timestamp changed, make sure we keep the value of 'lastTimestamp'
                // unchanged. Timestamp columns uses this variable
                // When map is exhausted we would assign 'nextTimestamp' to 'lastTimestamp'
                // and build another map
                this.nextTimestamp = timestamp;
                return true;
            }
        }

        // no more data from base cursor
        // return what we aggregated so far and stop
        baseRecord = null;
        return true;
    }

    @Override
    public long size() {
        return -1;
    }

    @Override
    public void toTop() {
        this.base.toTop();
        if (base.hasNext()) {
            baseRecord = base.getRecord();
            this.nextTimestamp = timestampSampler.round(baseRecord.getTimestamp(timestampIndex));
            this.lastTimestamp = this.nextTimestamp;
        }
    }

    @Override
    public void of(RecordCursor base, SqlExecutionContext executionContext) {
        // factory guarantees that base cursor is not empty
        this.base = base;
        this.baseRecord = base.getRecord();
        this.nextTimestamp = timestampSampler.round(baseRecord.getTimestamp(timestampIndex));
        this.lastTimestamp = this.nextTimestamp;
        interruptor = executionContext.getSqlExecutionInterruptor();
    }

    private class TimestampFunc extends TimestampFunction implements NoArgFunction {

        public TimestampFunc(int position) {
            super(position);
        }

        @Override
        public long getTimestamp(Record rec) {
            return lastTimestamp;
        }
    }
}
