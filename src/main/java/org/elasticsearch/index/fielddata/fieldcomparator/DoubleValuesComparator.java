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

import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.search.MultiValueMode;

import java.io.IOException;

/**
 */
public class DoubleValuesComparator extends DoubleValuesComparatorBase<Double> {

    private final double[] values;

    public DoubleValuesComparator(IndexNumericFieldData indexFieldData, double missingValue, int numHits, MultiValueMode sortMode) {
        super(indexFieldData, missingValue, sortMode);
        this.values = new double[numHits];
    }

    @Override
    public int compare(int slot1, int slot2) {
        final double v1 = values[slot1];
        final double v2 = values[slot2];
        return compare(v1, v2);
    }

    @Override
    public void setBottom(int slot) {
        this.bottom = values[slot];
    }

    @Override
    public void copy(int slot, int doc) throws IOException {
        values[slot] = readerValues.get(doc);
    }

    @Override
    public Double value(int slot) {
        return Double.valueOf(values[slot]);
    }

    @Override
    public void add(int slot, int doc) {
        values[slot] += readerValues.get(doc);
    }

    @Override
    public void divide(int slot, int divisor) {
        values[slot] /= divisor;
    }

    @Override
    public void missing(int slot) {
        values[slot] = missingValue;
    }
}
