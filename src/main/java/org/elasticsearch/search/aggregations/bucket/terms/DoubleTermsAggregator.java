/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.index.AtomicReaderContext;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.bucket.terms.support.BucketPriorityQueue;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.format.ValueFormat;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 *
 */
public class DoubleTermsAggregator extends TermsAggregator {

    private final ValuesSource.Numeric valuesSource;
    private final ValueFormatter formatter;
    private final LongHash bucketOrds;
    private SortedNumericDoubleValues values;

    public DoubleTermsAggregator(String name, AggregatorFactories factories, ValuesSource.Numeric valuesSource, @Nullable ValueFormat format, long estimatedBucketCount,
                               InternalOrder order, BucketCountThresholds bucketCountThresholds, AggregationContext aggregationContext, Aggregator parent, SubAggCollectionMode collectionMode) {
        super(name, BucketAggregationMode.PER_BUCKET, factories, estimatedBucketCount, aggregationContext, parent, bucketCountThresholds, order, collectionMode);
        this.valuesSource = valuesSource;
        this.formatter = format != null ? format.formatter() : null;
        bucketOrds = new LongHash(estimatedBucketCount, aggregationContext.bigArrays());
    }

    @Override
    public boolean shouldCollect() {
        return true;
    }

    @Override
    public void setNextReader(AtomicReaderContext reader) {
        values = valuesSource.doubleValues();
    }

    @Override
    public void collect(int doc, long owningBucketOrdinal) throws IOException {
        assert owningBucketOrdinal == 0;
        values.setDocument(doc);
        final int valuesCount = values.count();

        double previous = Double.NaN;
        for (int i = 0; i < valuesCount; ++i) {
            final double val = values.valueAt(i);
            if (val != previous) {
                final long bits = Double.doubleToRawLongBits(val);
                long bucketOrdinal = bucketOrds.add(bits);
                if (bucketOrdinal < 0) { // already seen
                    bucketOrdinal = - 1 - bucketOrdinal;
                    collectExistingBucket(doc, bucketOrdinal);
                } else {
                    collectBucket(doc, bucketOrdinal);
                }
                previous = val;
            }
        }
    }

    @Override
    public DoubleTerms buildAggregation(long owningBucketOrdinal) {
        assert owningBucketOrdinal == 0;

        if (bucketCountThresholds.getMinDocCount() == 0 && (order != InternalOrder.COUNT_DESC || bucketOrds.size() < bucketCountThresholds.getRequiredSize())) {
            // we need to fill-in the blanks
            for (AtomicReaderContext ctx : context.searchContext().searcher().getTopReaderContext().leaves()) {
                context.setNextReader(ctx);
                final SortedNumericDoubleValues values = valuesSource.doubleValues();
                for (int docId = 0; docId < ctx.reader().maxDoc(); ++docId) {
                    values.setDocument(docId);
                    final int valueCount = values.count();
                    for (int i = 0; i < valueCount; ++i) {
                        bucketOrds.add(Double.doubleToLongBits(values.valueAt(i)));
                    }
                }
            }
        }

        final int size = (int) Math.min(bucketOrds.size(), bucketCountThresholds.getShardSize());

        BucketPriorityQueue ordered = new BucketPriorityQueue(size, order.comparator(this));
        DoubleTerms.Bucket spare = null;
        for (long i = 0; i < bucketOrds.size(); i++) {
            if (spare == null) {
                spare = new DoubleTerms.Bucket(0, 0, null);
            }
            spare.term = Double.longBitsToDouble(bucketOrds.get(i));
            spare.docCount = bucketDocCount(i);
            spare.bucketOrd = i;
            if (bucketCountThresholds.getShardMinDocCount() <= spare.docCount) {
                spare = (DoubleTerms.Bucket) ordered.insertWithOverflow(spare);
            }
        }

        // Get the top buckets
        final InternalTerms.Bucket[] list = new InternalTerms.Bucket[ordered.size()];
        long survivingBucketOrds[] = new long[ordered.size()];
        for (int i = ordered.size() - 1; i >= 0; --i) {
            final DoubleTerms.Bucket bucket = (DoubleTerms.Bucket) ordered.pop();
            survivingBucketOrds[i] = bucket.bucketOrd;
            list[i] = bucket;
        }
        // replay any deferred collections
        runDeferredCollections(survivingBucketOrds);    
        // Now build the aggs
        for (int i = 0; i < list.length; i++) {
          list[i].aggregations = bucketAggregations(list[i].bucketOrd);
        }        
        
        
        return new DoubleTerms(name, order, formatter, bucketCountThresholds.getRequiredSize(), bucketCountThresholds.getMinDocCount(), Arrays.asList(list));
    }

    @Override
    public DoubleTerms buildEmptyAggregation() {
        return new DoubleTerms(name, order, formatter, bucketCountThresholds.getRequiredSize(), bucketCountThresholds.getMinDocCount(), Collections.<InternalTerms.Bucket>emptyList());
    }

    @Override
    public void doClose() {
        Releasables.close(bucketOrds);
    }

}
