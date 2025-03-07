/*
 * LuceneRecordCursor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2021 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.lucene;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.record.IndexEntry;
import com.apple.foundationdb.record.PipelineOperation;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorContinuation;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.RecordCursorVisitor;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.cursors.BaseCursor;
import com.apple.foundationdb.record.cursors.CursorLimitManager;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.lucene.directory.FDBDirectoryManager;
import com.apple.foundationdb.record.lucene.search.LuceneOptimizedIndexSearcher;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.IndexMaintainerState;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.tuple.TupleHelpers;
import com.google.common.collect.Lists;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * This class is a Record Cursor implementation for Lucene queries.
 *
 */
@API(API.Status.EXPERIMENTAL)
class LuceneRecordCursor implements BaseCursor<IndexEntry> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneRecordCursor.class);
    // pagination within single instance of record cursor for lucene queries.
    private final int pageSize;
    @Nonnull
    private final Executor executor;
    @Nullable
    private final ExecutorService executorService;
    @Nonnull
    private final CursorLimitManager limitManager;
    @Nullable
    private final FDBStoreTimer timer;
    private int limitRemaining;
    /**
     * The records to skip.
     */
    private final int skip;
    /**
     * To track the count of records left to skip.
     */
    private int leftToSkip;
    @Nullable
    private RecordCursorResult<IndexEntry> nextResult;
    final IndexMaintainerState state;
    private IndexReader indexReader;
    private final Query query;
    private final Sort sort;
    private IndexSearcher searcher;
    private RecordCursor<IndexEntry> lookupResults = null;
    private int currentPosition = 0;
    private final List<KeyExpression> fields;
    @Nullable
    private ScoreDoc searchAfter = null;
    private boolean exhausted = false;
    @Nullable
    private final Tuple groupingKey;
    @Nullable
    private final List<String> storedFields;
    @Nullable
    private final List<LuceneIndexExpressions.DocumentFieldType> storedFieldTypes;

    //TODO: once we fix the available fields logic for lucene to take into account which fields are
    // stored there should be no need to pass in a list of fields, or we could only pass in the store field values.
    @SuppressWarnings("squid:S107")
    LuceneRecordCursor(@Nonnull Executor executor,
                       @Nullable ExecutorService executorService,
                       int pageSize,
                       @Nonnull ScanProperties scanProperties,
                       @Nonnull final IndexMaintainerState state,
                       @Nonnull Query query,
                       @Nullable Sort sort,
                       byte[] continuation,
                       @Nullable Tuple groupingKey,
                       @Nullable final List<String> storedFields,
                       @Nullable final List<LuceneIndexExpressions.DocumentFieldType> storedFieldTypes) {
        this.state = state;
        this.executor = executor;
        this.pageSize = pageSize;
        this.executorService = executorService;
        this.storedFields = storedFields;
        this.storedFieldTypes = storedFieldTypes;
        this.limitManager = new CursorLimitManager(state.context, scanProperties);
        this.limitRemaining = scanProperties.getExecuteProperties().getReturnedRowLimitOrMax();
        this.skip = scanProperties.getExecuteProperties().getSkip();
        this.leftToSkip = scanProperties.getExecuteProperties().getSkip();
        this.timer = state.context.getTimer();
        this.query = query;
        this.sort = sort;
        if (continuation != null) {
            try {
                LuceneContinuationProto.LuceneIndexContinuation parsed = LuceneContinuationProto.LuceneIndexContinuation.parseFrom(continuation);
                searchAfter = LuceneCursorContinuation.toScoreDoc(parsed);
            } catch (Exception e) {
                throw new RecordCoreException("Invalid continuation for Lucene index", "ContinuationValues", continuation, e);
            }
        }
        this.fields = state.index.getRootExpression().normalizeKeyForPositions();
        this.groupingKey = groupingKey;
    }

    @Nonnull
    @Override
    public CompletableFuture<RecordCursorResult<IndexEntry>> onNext() {
        if (nextResult != null && !nextResult.hasNext()) {
            // Like the KeyValueCursor, it is necessary to memoize and return the first result where
            // hasNext is false to avoid the NoNextReason changing.
            return CompletableFuture.completedFuture(nextResult);
        }

        // Scan all the pages within skip range firstly
        CompletableFuture<Void> scanPages = AsyncUtil.whileTrue(() -> {
            if (leftToSkip < pageSize) {
                return CompletableFuture.completedFuture(false);
            }
            try {
                searchForTopDocs(pageSize);
            } catch (IndexNotFoundException indexNotFoundException) {
                // Trying to open an empty directory results in an IndexNotFoundException,
                // but this should be interpreted as there not being any data to read
                nextResult = RecordCursorResult.exhausted();
            } catch (IOException ioException) {
                throw new RecordCoreException("Exception to lookup the auto complete suggestions", ioException)
                        .addLogInfo(LogMessageKeys.QUERY, query);
            }
            leftToSkip -= pageSize;
            return CompletableFuture.completedFuture(leftToSkip >= pageSize);
        }, executor);

        return scanPages.thenCompose(vignore -> {
            if (lookupResults == null || !exhausted && (leftToSkip > 0 || (currentPosition + skip) % pageSize == 0) && limitRemaining > 0) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        maybePerformScan();
                    } catch (IndexNotFoundException indexNotFoundException) {
                        // Trying to open an empty directory results in an IndexNotFoundException,
                        // but this should be interpreted as there not being any data to read
                        nextResult = RecordCursorResult.exhausted();
                        return CompletableFuture.completedFuture(nextResult);
                    } catch (IOException ioException) {
                        throw new RecordCoreException("Exception to lookup the auto complete suggestions", ioException)
                                .addLogInfo(LogMessageKeys.QUERY, query);
                    }
                    return lookupResults.onNext();
                }).thenCompose(Function.identity());
            }
            return lookupResults.onNext();
        });
    }

    @Override
    public void close() {
        if (indexReader != null) {
            IOUtils.closeWhileHandlingException(indexReader);
        }
    }

    @Nonnull
    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public boolean accept(@Nonnull RecordCursorVisitor visitor) {
        visitor.visitEnter(this);
        return visitor.visitLeave(this);
    }

    private synchronized IndexReader getIndexReader() throws IOException {
        return FDBDirectoryManager.getManager(state).getIndexReader(groupingKey);
    }

    private void maybePerformScan() throws IOException {
        if (lookupResults != null) {
            lookupResults.close();
        }

        final int limit = limitRemaining == Integer.MAX_VALUE ? pageSize : Math.min(limitRemaining + leftToSkip, pageSize);
        TopDocs newTopDocs = searchForTopDocs(limit);

        lookupResults = RecordCursor.fromIterator(executor, Arrays.stream(newTopDocs.scoreDocs).iterator()).skip(leftToSkip)
                .mapPipelined(this::buildIndexEntryFromScoreDocAsync, state.store.getPipelineSize(PipelineOperation.KEY_TO_RECORD))
                .mapResult(result -> {
                    if (result.hasNext() && limitManager.tryRecordScan()) {
                        RecordCursorContinuation continuationFromDoc = LuceneCursorContinuation.fromScoreDoc(result.get().scoreDoc);
                        currentPosition++;
                        if (limitRemaining != Integer.MAX_VALUE) {
                            limitRemaining--;
                        }
                        nextResult = RecordCursorResult.withNextValue(result.get(), continuationFromDoc);
                    } else if (exhausted) {
                        nextResult = RecordCursorResult.exhausted();
                    } else if (limitRemaining <= 0) {
                        RecordCursorContinuation continuationFromDoc = LuceneCursorContinuation.fromScoreDoc(searchAfter);
                        nextResult = RecordCursorResult.withoutNextValue(continuationFromDoc, NoNextReason.RETURN_LIMIT_REACHED);
                    } else {
                        final Optional<NoNextReason> stoppedReason = limitManager.getStoppedReason();
                        if (!stoppedReason.isPresent()) {
                            throw new RecordCoreException("limit manager stopped LuceneRecordCursor but did not report a reason");
                        } else {
                            nextResult = RecordCursorResult.withoutNextValue(LuceneCursorContinuation.fromScoreDoc(searchAfter), stoppedReason.get());
                        }
                    }
                    return nextResult;
                });
        leftToSkip = 0;
    }

    private TopDocs searchForTopDocs(int limit) throws IOException {
        long startTime = System.nanoTime();
        indexReader = getIndexReader();
        searcher = new LuceneOptimizedIndexSearcher(indexReader, executorService);
        TopDocs newTopDocs;
        if (searchAfter != null && sort != null) {
            newTopDocs = searcher.searchAfter(searchAfter, query, limit, sort);
        } else if (searchAfter != null) {
            newTopDocs = searcher.searchAfter(searchAfter, query, limit);
        } else if (sort != null) {
            newTopDocs = searcher.search(query,  limit, sort);
        } else {
            newTopDocs = searcher.search(query, limit);
        }
        if (newTopDocs.scoreDocs.length < limit) {
            exhausted = true;
        }
        if (newTopDocs.scoreDocs.length != 0) {
            searchAfter = newTopDocs.scoreDocs[newTopDocs.scoreDocs.length - 1];
        }
        if (timer != null) {
            timer.recordSinceNanoTime(LuceneEvents.Events.LUCENE_INDEX_SCAN, startTime);
            timer.increment(LuceneEvents.Counts.LUCENE_SCAN_MATCHED_DOCUMENTS, newTopDocs.scoreDocs.length);
        }
        return newTopDocs;
    }

    private CompletableFuture<ScoreDocIndexEntry> buildIndexEntryFromScoreDocAsync(@Nonnull ScoreDoc scoreDoc) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document document = searcher.doc(scoreDoc.doc);
                IndexableField primaryKey = document.getField(LuceneIndexMaintainer.PRIMARY_KEY_FIELD_NAME);
                BytesRef pk = primaryKey.binaryValue();
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("document={}", document);
                    LOGGER.trace("primary key read={}", Tuple.fromBytes(pk.bytes, pk.offset, pk.length));
                }
                if (timer != null) {
                    timer.increment(FDBStoreTimer.Counts.LOAD_SCAN_ENTRY);
                }
                Tuple setPrimaryKey = Tuple.fromBytes(pk.bytes);
                // Initialized with values that aren't really legal in a Tuple to find offset bugs.
                List<Object> fieldValues = Lists.newArrayList(fields);
                if (groupingKey != null) {
                    // Grouping keys are at the front.
                    for (int i = 0; i < groupingKey.size(); i++) {
                        fieldValues.set(i, groupingKey.get(i));
                    }
                }
                if (storedFields != null) {
                    for (int i = 0; i < storedFields.size(); i++) {
                        if (storedFieldTypes.get(i) == null) {
                            continue;
                        }
                        Object value = null;
                        IndexableField docField = document.getField(storedFields.get(i));
                        switch (storedFieldTypes.get(i)) {
                            case STRING:
                                value = docField.stringValue();
                                break;
                            case BOOLEAN:
                                value = Boolean.valueOf(docField.stringValue());
                                break;
                            case INT:
                            case LONG:
                            case DOUBLE:
                                value = docField.numericValue();
                                break;
                            default:
                                break;
                        }
                        fieldValues.set(i, value);
                    }
                }
                int[] keyPos = state.index.getPrimaryKeyComponentPositions();
                Tuple tuple;
                if (keyPos != null) {
                    List<Object> leftovers = Lists.newArrayList();
                    for (int i = 0; i < keyPos.length; i++) {
                        if (keyPos[i] > -1) {
                            fieldValues.set(keyPos[i], setPrimaryKey.get(i));
                        } else {
                            leftovers.add(setPrimaryKey.get(i));
                        }
                    }
                    tuple = Tuple.fromList(fieldValues).addAll(leftovers);
                } else {
                    tuple = Tuple.fromList(fieldValues).addAll(setPrimaryKey);
                }

                return new ScoreDocIndexEntry(scoreDoc, state.index, tuple);
            } catch (Exception e) {
                throw new RecordCoreException("Failed to get document", "currentPosition", currentPosition, "exception", e);
            }
        }, executor);
    }

    protected static final class ScoreDocIndexEntry extends IndexEntry {
        private final ScoreDoc scoreDoc;

        public ScoreDoc getScoreDoc() {
            return scoreDoc;
        }

        private ScoreDocIndexEntry(@Nonnull ScoreDoc scoreDoc, @Nonnull Index index, @Nonnull Tuple key) {
            super(index, key, TupleHelpers.EMPTY);
            this.scoreDoc = scoreDoc;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            final ScoreDocIndexEntry that = (ScoreDocIndexEntry)o;
            return scoreDoc.score == that.scoreDoc.score && scoreDoc.doc == that.scoreDoc.doc;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), scoreDoc.score, scoreDoc.doc);
        }
    }
}
