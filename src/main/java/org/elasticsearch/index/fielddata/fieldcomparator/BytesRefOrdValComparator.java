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

package org.elasticsearch.index.fielddata.fieldcomparator;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.IndexOrdinalsFieldData;
import org.elasticsearch.search.MultiValueMode;

import java.io.IOException;

/**
 * Sorts by field's natural Term sort order, using
 * ordinals.  This is functionally equivalent to {@link
 * org.apache.lucene.search.FieldComparator.TermValComparator}, but it first resolves the string
 * to their relative ordinal positions (using the index
 * returned by {@link org.apache.lucene.search.FieldCache#getTermsIndex}), and
 * does most comparisons using the ordinals.  For medium
 * to large results, this comparator will be much faster
 * than {@link org.apache.lucene.search.FieldComparator.TermValComparator}.  For very small
 * result sets it may be slower.
 *
 * Internally this comparator multiplies ordinals by 4 so that virtual ordinals can be inserted in-between the original field data ordinals.
 * Thanks to this, an ordinal for the missing value and the bottom value can be computed and all ordinals are directly comparable. For example,
 * if the field data ordinals are (a,1), (b,2) and (c,3), they will be internally stored as (a,4), (b,8), (c,12). Then the ordinal for the
 * missing value will be computed by binary searching. For example, if the missing value is 'ab', it will be assigned 6 as an ordinal (between
 * 'a' and 'b'. And if the bottom value is 'ac', it will be assigned 7 as an ordinal (between 'ab' and 'b').
 */
public final class BytesRefOrdValComparator extends NestedWrappableComparator<BytesRef> {

    final IndexOrdinalsFieldData indexFieldData;
    final BytesRef missingValue;

    /* Ords for each slot, times 4.
       @lucene.internal */
    final long[] ords;

    final MultiValueMode sortMode;

    /* Values for each slot.
       @lucene.internal */
    final BytesRef[] values;

    /* Which reader last copied a value into the slot. When
       we compare two slots, we just compare-by-ord if the
       readerGen is the same; else we must compare the
       values (slower).
       @lucene.internal */
    final int[] readerGen;

    /* Gen of current reader we are on.
       @lucene.internal */
    int currentReaderGen = -1;

    /* Current reader's doc ord/values.
       @lucene.internal */
    SortedDocValues termsIndex;
    long missingOrd;

    /* Bottom slot, or -1 if queue isn't full yet
       @lucene.internal */
    int bottomSlot = -1;

    /* Bottom ord (same as ords[bottomSlot] once bottomSlot
       is set).  Cached for faster compares.
       @lucene.internal */
    long bottomOrd;

    BytesRef top;
    long topOrd;

    public BytesRefOrdValComparator(IndexOrdinalsFieldData indexFieldData, int numHits, MultiValueMode sortMode, BytesRef missingValue) {
        this.indexFieldData = indexFieldData;
        this.sortMode = sortMode;
        this.missingValue = missingValue;
        ords = new long[numHits];
        values = new BytesRef[numHits];
        readerGen = new int[numHits];
    }

    @Override
    public int compare(int slot1, int slot2) {
        if (readerGen[slot1] == readerGen[slot2]) {
            final int res = Long.compare(ords[slot1], ords[slot2]);
            assert Integer.signum(res) == Integer.signum(compareValues(values[slot1], values[slot2])) : values[slot1] + " " + values[slot2] + " " + ords[slot1] + " " + ords[slot2];
            return res;
        }

        final BytesRef val1 = values[slot1];
        final BytesRef val2 = values[slot2];
        return compareValues(val1, val2);
    }

    @Override
    public int compareBottom(int doc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareTop(int doc) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareBottomMissing() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copy(int slot, int doc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void missing(int slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareTopMissing() {
        throw new UnsupportedOperationException();
    }

    class PerSegmentComparator extends NestedWrappableComparator<BytesRef> {
        final SortedDocValues termsIndex;

        public PerSegmentComparator(SortedDocValues termsIndex) {
            this.termsIndex = termsIndex;
        }

        @Override
        public FieldComparator<BytesRef> setNextReader(AtomicReaderContext context) throws IOException {
            return BytesRefOrdValComparator.this.setNextReader(context);
        }

        @Override
        public int compare(int slot1, int slot2) {
            return BytesRefOrdValComparator.this.compare(slot1, slot2);
        }

        @Override
        public void setBottom(final int bottom) {
            BytesRefOrdValComparator.this.setBottom(bottom);
        }

        @Override
        public void setTopValue(BytesRef value) {
            BytesRefOrdValComparator.this.setTopValue(value);
        }

        @Override
        public BytesRef value(int slot) {
            return BytesRefOrdValComparator.this.value(slot);
        }

        @Override
        public int compareValues(BytesRef val1, BytesRef val2) {
            if (val1 == null) {
                if (val2 == null) {
                    return 0;
                }
                return -1;
            } else if (val2 == null) {
                return 1;
            }
            return val1.compareTo(val2);
        }

        @Override
        public int compareBottom(int doc) {
            assert bottomSlot != -1;
            final long docOrd = termsIndex.getOrd(doc);
            final long comparableOrd = docOrd < 0 ? missingOrd : docOrd << 2;
            return Long.compare(bottomOrd, comparableOrd);
        }

        @Override
        public int compareTop(int doc) throws IOException {
            final long ord = termsIndex.getOrd(doc);
            if (ord < 0) {
                return compareTopMissing();
            } else {
                final long comparableOrd = ord << 2;
                return Long.compare(topOrd, comparableOrd);
            }
        }

        @Override
        public int compareBottomMissing() {
            assert bottomSlot != -1;
            return Long.compare(bottomOrd, missingOrd);
        }

        @Override
        public int compareTopMissing() {
            int cmp = Long.compare(topOrd, missingOrd);
            if (cmp == 0) {
                return compareValues(top, missingValue);
            } else {
                return cmp;
            }
        }

        @Override
        public void copy(int slot, int doc) {
            final int ord = termsIndex.getOrd(doc);
            if (ord < 0) {
                ords[slot] = missingOrd;
                values[slot] = missingValue;
            } else {
                assert ord >= 0;
                ords[slot] = ((long) ord) << 2;
                if (values[slot] == null || values[slot] == missingValue) {
                    values[slot] = new BytesRef();
                }
                values[slot].copyBytes(termsIndex.lookupOrd(ord));
            }
            readerGen[slot] = currentReaderGen;
        }

        @Override
        public void missing(int slot) {
            ords[slot] = missingOrd;
            values[slot] = missingValue;
            readerGen[slot] = currentReaderGen;
        }
    }

    // for assertions
    private boolean consistentInsertedOrd(SortedDocValues termsIndex, long ord, BytesRef value) {
        final int previousOrd = (int) (ord >> 2);
        final int nextOrd = previousOrd + 1;
        final BytesRef previous = previousOrd < 0 ? null : termsIndex.lookupOrd(previousOrd);
        if ((ord & 3) == 0) { // there was an existing ord with the inserted value
            assert compareValues(previous, value) == 0;
        } else {
            assert compareValues(previous, value) < 0;
        }
        if (nextOrd < termsIndex.getValueCount()) {
            final BytesRef next = termsIndex.lookupOrd(nextOrd);
            assert compareValues(value, next) < 0;
        }
        return true;
    }

    // find where to insert an ord in the current terms index
    private long ordInCurrentReader(SortedDocValues termsIndex, BytesRef value) {
        final long ord;
        if (value == null) {
            ord = -1 << 2;
        } else {
            final long docOrd = binarySearch(termsIndex, value);
            if (docOrd >= 0) {
                // value exists in the current segment
                ord = docOrd << 2;
            } else {
                // value doesn't exist, use the ord between the previous and the next term
                ord = ((-2 - docOrd) << 2) + 2;
            }
        }

        assert (ord & 1) == 0;
        return ord;
    }

    @Override
    public FieldComparator<BytesRef> setNextReader(AtomicReaderContext context) throws IOException {
        termsIndex = sortMode.select(indexFieldData.load(context).getOrdinalsValues(), -1);
        missingOrd = ordInCurrentReader(termsIndex, missingValue);
        assert consistentInsertedOrd(termsIndex, missingOrd, missingValue);
        FieldComparator<BytesRef> perSegComp = new PerSegmentComparator(termsIndex);
        currentReaderGen++;
        if (bottomSlot != -1) {
            perSegComp.setBottom(bottomSlot);
        }
        if (top != null) {
            perSegComp.setTopValue(top);
            topOrd = ordInCurrentReader(termsIndex, top);
        } else {
            topOrd = missingOrd;
        }
        return perSegComp;
    }

    @Override
    public void setBottom(final int bottom) {
        bottomSlot = bottom;
        final BytesRef bottomValue = values[bottomSlot];

        if (currentReaderGen == readerGen[bottomSlot]) {
            bottomOrd = ords[bottomSlot];
        } else {
            // insert an ord
            bottomOrd = ordInCurrentReader(termsIndex, bottomValue);
            if (bottomOrd == missingOrd && bottomValue != null) {
                // bottomValue and missingValue and in-between the same field data values -> tie-break
                // this is why we multiply ords by 4
                assert missingValue != null;
                final int cmp = bottomValue.compareTo(missingValue);
                if (cmp < 0) {
                    --bottomOrd;
                } else if (cmp > 0) {
                    ++bottomOrd;
                }
            }
            assert consistentInsertedOrd(termsIndex, bottomOrd, bottomValue);
        }
    }

    @Override
    public void setTopValue(BytesRef value) {
        this.top = value;
    }

    @Override
    public BytesRef value(int slot) {
        return values[slot];
    }

    final protected static long binarySearch(SortedDocValues a, BytesRef key) {
        return binarySearch(a, key, 0, a.getValueCount() - 1);
    }

    final protected static long binarySearch(SortedDocValues a, BytesRef key, int low, int high) {
        assert low >= 0;
        assert high == -1 || (a.lookupOrd(high) == null | a.lookupOrd(high) != null); // make sure we actually can get these values
        assert low == high + 1 || a.lookupOrd(low) == null | a.lookupOrd(low) != null;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            BytesRef midVal = a.lookupOrd(mid);
            int cmp;
            if (midVal != null) {
                cmp = midVal.compareTo(key);
            } else {
                cmp = -1;
            }

            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid;
        }
        return -(low + 1);
    }

}
