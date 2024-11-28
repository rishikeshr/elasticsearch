/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation.blockhash;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.xpack.ml.aggs.categorization.SerializableTokenListCategory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * BlockHash implementation for {@code Categorize} grouping function.
 * <p>
 *     This implementation expects a single intermediate state in a block, as generated by {@link AbstractCategorizeBlockHash}.
 * </p>
 */
public class CategorizedIntermediateBlockHash extends AbstractCategorizeBlockHash {

    CategorizedIntermediateBlockHash(int channel, BlockFactory blockFactory, boolean outputPartial) {
        super(blockFactory, channel, outputPartial);
    }

    @Override
    public void add(Page page, GroupingAggregatorFunction.AddInput addInput) {
        if (page.getPositionCount() == 0) {
            // No categories
            return;
        }
        BytesRefBlock categorizerState = page.getBlock(channel());
        if (categorizerState.areAllValuesNull()) {
            seenNull = true;
            try (var newIds = blockFactory.newConstantIntVector(NULL_ORD, 1)) {
                addInput.add(0, newIds);
            }
            return;
        }

        Map<Integer, Integer> idMap = readIntermediate(categorizerState.getBytesRef(0, new BytesRef()));
        try (IntBlock.Builder newIdsBuilder = blockFactory.newIntBlockBuilder(idMap.size())) {
            int fromId = idMap.containsKey(0) ? 0 : 1;
            int toId = fromId + idMap.size();
            for (int i = fromId; i < toId; i++) {
                newIdsBuilder.appendInt(idMap.get(i));
            }
            try (IntBlock newIds = newIdsBuilder.build()) {
                addInput.add(0, newIds);
            }
        }
    }

    /**
     * Read intermediate state from a block.
     *
     * @return a map from the old category id to the new one. The old ids go from 0 to {@code size - 1}.
     */
    private Map<Integer, Integer> readIntermediate(BytesRef bytes) {
        Map<Integer, Integer> idMap = new HashMap<>();
        try (StreamInput in = new BytesArray(bytes).streamInput()) {
            if (in.readBoolean()) {
                seenNull = true;
                idMap.put(NULL_ORD, NULL_ORD);
            }
            int count = in.readVInt();
            for (int oldCategoryId = 0; oldCategoryId < count; oldCategoryId++) {
                int newCategoryId = categorizer.mergeWireCategory(new SerializableTokenListCategory(in)).getId();
                // +1 because the 0 ordinal is reserved for null
                idMap.put(oldCategoryId + 1, newCategoryId + 1);
            }
            return idMap;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        categorizer.close();
    }
}
