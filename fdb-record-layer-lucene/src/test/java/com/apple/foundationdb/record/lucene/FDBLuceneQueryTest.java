/*
 * FDBLuceneQueryTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.TestRecordsTextProto;
import com.apple.foundationdb.record.lucene.ngram.NgramAnalyzer;
import com.apple.foundationdb.record.lucene.synonym.EnglishSynonymMapConfig;
import com.apple.foundationdb.record.lucene.synonym.SynonymAnalyzer;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.IndexOptions;
import com.apple.foundationdb.record.metadata.IndexTypes;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.common.text.AllSuffixesTextTokenizer;
import com.apple.foundationdb.record.provider.common.text.TextSamples;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContextConfig;
import com.apple.foundationdb.record.provider.foundationdb.indexes.TextIndexTestUtils;
import com.apple.foundationdb.record.provider.foundationdb.properties.RecordLayerPropertyStorage;
import com.apple.foundationdb.record.provider.foundationdb.query.FDBRecordStoreQueryTestBase;
import com.apple.foundationdb.record.query.RecordQuery;
import com.apple.foundationdb.record.query.expressions.Query;
import com.apple.foundationdb.record.query.expressions.QueryComponent;
import com.apple.foundationdb.record.query.plan.PlannableIndexTypes;
import com.apple.foundationdb.record.query.plan.cascades.CascadesPlanner;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.BooleanSource;
import com.apple.test.Tags;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.apple.foundationdb.record.TestHelpers.assertLoadRecord;
import static com.apple.foundationdb.record.lucene.LuceneIndexTest.generateRandomWords;
import static com.apple.foundationdb.record.lucene.LucenePlanMatchers.group;
import static com.apple.foundationdb.record.lucene.LucenePlanMatchers.query;
import static com.apple.foundationdb.record.lucene.LucenePlanMatchers.scanParams;
import static com.apple.foundationdb.record.metadata.Key.Expressions.concat;
import static com.apple.foundationdb.record.metadata.Key.Expressions.concatenateFields;
import static com.apple.foundationdb.record.metadata.Key.Expressions.field;
import static com.apple.foundationdb.record.metadata.Key.Expressions.function;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.bounds;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.coveringIndexScan;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.fetch;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.filter;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.hasTupleString;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.indexName;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.indexScan;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.indexScanType;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.primaryKeyDistinct;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.scan;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.typeFilter;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.unbounded;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.union;
import static com.apple.foundationdb.record.query.plan.match.PlanMatchers.unorderedUnion;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sophisticated queries involving full text predicates.
 */
@Tag(Tags.RequiresFDB)
public class FDBLuceneQueryTest extends FDBRecordStoreQueryTestBase {

    private static final List<TestRecordsTextProto.SimpleDocument> DOCUMENTS = TextIndexTestUtils.toSimpleDocuments(Arrays.asList(
            TextSamples.ANGSTROM,
            TextSamples.AETHELRED,
            TextSamples.PARTIAL_ROMEO_AND_JULIET_PROLOGUE,
            TextSamples.FRENCH,
            TextSamples.ROMEO_AND_JULIET_PROLOGUE,
            TextSamples.ROMEO_AND_JULIET_PROLOGUE_END
    ));

    final List<String> textSamples = Arrays.asList(
            TextSamples.ROMEO_AND_JULIET_PROLOGUE,
            TextSamples.AETHELRED,
            TextSamples.ROMEO_AND_JULIET_PROLOGUE,
            TextSamples.ANGSTROM,
            TextSamples.AETHELRED,
            TextSamples.FRENCH
    );

    private List<TestRecordsTextProto.MapDocument> mapDocuments = IntStream.range(0, textSamples.size() / 2)
            .mapToObj(i -> TestRecordsTextProto.MapDocument.newBuilder()
                    .setDocId(i)
                    .addEntry(TestRecordsTextProto.MapDocument.Entry.newBuilder().setKey("a").setValue(textSamples.get(i * 2)).build())
                    .addEntry(TestRecordsTextProto.MapDocument.Entry.newBuilder().setKey("b").setValue(textSamples.get(i * 2 + 1)).build())
                    .setGroup(i % 2)
                    .build()
            )
            .collect(Collectors.toList());


    private List<TestRecordsTextProto.MapDocument> mapWithFieldDocuments = IntStream.range(0, textSamples.size() / 2)
            .mapToObj(i -> TestRecordsTextProto.MapDocument.newBuilder()
                    .setDocId(i)
                    .addEntry(TestRecordsTextProto.MapDocument.Entry.newBuilder().setKey("a").setValue(textSamples.get(i * 2)).build())
                    .addEntry(TestRecordsTextProto.MapDocument.Entry.newBuilder().setKey("b").setValue(textSamples.get(i * 2 + 1)).build())
                    .setGroup(i % 2)
                    .build()
            )
            .collect(Collectors.toList());

    private List<TestRecordsTextProto.ComplexDocument> complexDocuments = IntStream.range(0, textSamples.size())
            .mapToObj(i -> TestRecordsTextProto.ComplexDocument.newBuilder()
                    .setDocId(i)
                    .setGroup(i % 2)
                    .setText(textSamples.get(i))
                    .build()
            )
            .collect(Collectors.toList());

    private static final String MAP_DOC = "MapDocument";

    private static final Index SIMPLE_TEXT_SUFFIXES = new Index("Complex$text_index", function(LuceneFunctionNames.LUCENE_TEXT, field("text")), LuceneIndexTypes.LUCENE,
            ImmutableMap.of(IndexOptions.TEXT_TOKENIZER_NAME_OPTION, AllSuffixesTextTokenizer.NAME));
    private static final Index TEXT_AND_GROUP = new Index("text_and_group", concat(
            function(LuceneFunctionNames.LUCENE_TEXT, field("text")),
            function(LuceneFunctionNames.LUCENE_STORED, function(LuceneFunctionNames.LUCENE_SORTED, field("group")))),
            LuceneIndexTypes.LUCENE, ImmutableMap.of(IndexOptions.TEXT_TOKENIZER_NAME_OPTION, AllSuffixesTextTokenizer.NAME));
    private static final List<KeyExpression> keys = List.of(field("key"), function(LuceneFunctionNames.LUCENE_TEXT, field("value")));
    private static final KeyExpression mainExpression = field("entry", KeyExpression.FanType.FanOut).nest(concat(keys));

    private static final Index MAP_AND_FIELD_ON_LUCENE_INDEX = new Index("MapField$values", concat(mainExpression, field("doc_id")), LuceneIndexTypes.LUCENE);
    private static final Index MAP_ON_LUCENE_INDEX = new Index("Map$entry-value", new GroupingKeyExpression(mainExpression, 1), LuceneIndexTypes.LUCENE);

    private static final Index COMPLEX_TEXT_BY_GROUP = new Index("Complex$text_by_group", function(LuceneFunctionNames.LUCENE_TEXT, field("text")).groupBy(field("group")), LuceneIndexTypes.LUCENE);

    private ExecutorService executorService = null;

    @Override
    public void setupPlanner(@Nullable PlannableIndexTypes indexTypes) {
        if (useRewritePlanner) {
            planner = new CascadesPlanner(recordStore.getRecordMetaData(), recordStore.getRecordStoreState());
        } else {
            if (indexTypes == null) {
                indexTypes = new PlannableIndexTypes(
                        Sets.newHashSet(IndexTypes.VALUE, IndexTypes.VERSION),
                        Sets.newHashSet(IndexTypes.RANK, IndexTypes.TIME_WINDOW_LEADERBOARD),
                        Sets.newHashSet(IndexTypes.TEXT),
                        Sets.newHashSet(LuceneIndexTypes.LUCENE)
                );
            }
            planner = new LucenePlanner(recordStore.getRecordMetaData(), recordStore.getRecordStoreState(), indexTypes, recordStore.getTimer());
        }
    }

    @Override
    protected FDBRecordContextConfig.Builder contextConfig(@Nonnull final RecordLayerPropertyStorage.Builder propsBuilder) {
        return super.contextConfig(propsBuilder.addProp(LuceneRecordContextProperties.LUCENE_EXECUTOR_SERVICE, (Supplier<ExecutorService>)() -> executorService));
    }

    protected void openRecordStoreWithNgramIndex(FDBRecordContext context, boolean edgesOnly, int minSize, int maxSize) {
        final Index ngramIndex = new Index("Complex$text_index", function(LuceneFunctionNames.LUCENE_TEXT, field("text")), LuceneIndexTypes.LUCENE,
                ImmutableMap.of(LuceneIndexOptions.LUCENE_ANALYZER_NAME_OPTION, NgramAnalyzer.NgramAnalyzerFactory.ANALYZER_FACTORY_NAME,
                        IndexOptions.TEXT_TOKEN_MIN_SIZE, String.valueOf(minSize),
                        IndexOptions.TEXT_TOKEN_MAX_SIZE, String.valueOf(maxSize),
                        LuceneIndexOptions.NGRAM_TOKEN_EDGES_ONLY, String.valueOf(edgesOnly)));
        openRecordStore(context, md -> { }, ngramIndex);
    }

    protected void openRecordStoreWithSynonymIndex(FDBRecordContext context) {
        final Index ngramIndex = new Index("Complex$text_index", function(LuceneFunctionNames.LUCENE_TEXT, field("text")), LuceneIndexTypes.LUCENE,
                ImmutableMap.of(
                        LuceneIndexOptions.LUCENE_ANALYZER_NAME_OPTION, SynonymAnalyzer.QueryOnlySynonymAnalyzerFactory.ANALYZER_FACTORY_NAME,
                        LuceneIndexOptions.TEXT_SYNONYM_SET_NAME_OPTION, EnglishSynonymMapConfig.ExpandedEnglishSynonymMapConfig.CONFIG_NAME));
        openRecordStore(context, md -> { }, ngramIndex);
    }

    protected void openRecordStoreWithGroup(FDBRecordContext context) {
        openRecordStore(context, md -> { }, TEXT_AND_GROUP);
    }

    protected void openRecordStoreWithComplex(FDBRecordContext context) {
        openRecordStore(context, md -> {
            md.addIndex(TextIndexTestUtils.COMPLEX_DOC, COMPLEX_TEXT_BY_GROUP);
        }, null);
    }

    protected void openRecordStore(FDBRecordContext context) {
        openRecordStore(context, md -> { }, SIMPLE_TEXT_SUFFIXES);
    }

    protected void openRecordStore(FDBRecordContext context, RecordMetaDataHook hook, Index simpleDocIndex) {
        RecordMetaDataBuilder metaDataBuilder = RecordMetaData.newBuilder().setRecords(TestRecordsTextProto.getDescriptor());
        metaDataBuilder.getRecordType(TextIndexTestUtils.COMPLEX_DOC).setPrimaryKey(concatenateFields("group", "doc_id"));
        if (simpleDocIndex != null) {
            metaDataBuilder.removeIndex("SimpleDocument$text");
            metaDataBuilder.addIndex(TextIndexTestUtils.SIMPLE_DOC, simpleDocIndex);
        }
        metaDataBuilder.addIndex(MAP_DOC, MAP_ON_LUCENE_INDEX);
        metaDataBuilder.addIndex(MAP_DOC, MAP_AND_FIELD_ON_LUCENE_INDEX);
        hook.apply(metaDataBuilder);
        recordStore = getStoreBuilder(context, metaDataBuilder.getRecordMetaData())
                .setSerializer(TextIndexTestUtils.COMPRESSING_SERIALIZER)
                .uncheckedOpen();
        setupPlanner(null);
    }

    private void initializeFlat() {
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            DOCUMENTS.forEach(recordStore::saveRecord);
            commit(context);
        }
    }

    private void initializeWithGroup() {
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithGroup(context);
            DOCUMENTS.forEach(recordStore::saveRecord);
            commit(context);
        }
    }

    private void initializeNested() {
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            mapDocuments.forEach(recordStore::saveRecord);
            commit(context);
        }
    }

    private void initializeNestedWithField() {
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            mapWithFieldDocuments.forEach(recordStore::saveRecord);
            commit(context);
        }
    }

    private void initializeWithNgramIndex(String text, boolean edgesOnly, int minSize, int maxSize) {
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithNgramIndex(context, edgesOnly, minSize, maxSize);
            TestRecordsTextProto.SimpleDocument document = TestRecordsTextProto.SimpleDocument.newBuilder()
                    .setDocId(1L)
                    .setGroup(1)
                    .setText(text)
                    .build();
            recordStore.saveRecord(document);
            commit(context);
        }
    }

    private void initializedWithSynonymIndex(String text) {
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithSynonymIndex(context);
            TestRecordsTextProto.SimpleDocument document = TestRecordsTextProto.SimpleDocument.newBuilder()
                    .setDocId(1L)
                    .setGroup(1)
                    .setText(text)
                    .build();
            recordStore.saveRecord(document);
            commit(context);
        }
    }

    private void assertTermIndexedOrNot(String term, boolean indexedExpected, boolean shouldDeferFetch) throws Exception {
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter = new LuceneQueryComponent(term, Lists.newArrayList());
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            RecordCursor<FDBQueriedRecord<Message>> fdbQueriedRecordRecordCursor = recordStore.executeQuery(plan);
            RecordCursor<Tuple> map = fdbQueriedRecordRecordCursor.map(FDBQueriedRecord::getPrimaryKey);
            List<Long> primaryKeys = map.map(t -> t.getLong(0)).asList().get();
            if (indexedExpected) {
                assertEquals(Set.of(1L), Set.copyOf(primaryKeys), "Expected term not indexed");
            } else {
                assertThat("Unexpected term indexed", primaryKeys.isEmpty());
            }
        }
    }

    @ParameterizedTest(name = "testSynonym[shouldDeferFetch={0}]")
    @BooleanSource
    void testSynonym(boolean shouldDeferFetch) throws Exception {
        initializedWithSynonymIndex("Good morning Mr Tian");
        assertTermIndexedOrNot("good", true, shouldDeferFetch);
        assertTermIndexedOrNot("morning", true, shouldDeferFetch);
        // The synonym is not indexed into FDB, it is only used for in fly analysis during query time
        assertTermIndexedOrNot("full", false, shouldDeferFetch);
    }

    @ParameterizedTest(name = "testNgram[shouldDeferFetch={0}]")
    @BooleanSource
    void testNgram(boolean shouldDeferFetch) throws Exception {
        initializeWithNgramIndex("Good morning Mr Tian", false, 3, 5);
        // Token with length == 2, not indexed
        assertTermIndexedOrNot("mo", false, shouldDeferFetch);
        // Token with length == 3, indexed
        assertTermIndexedOrNot("mor", true, shouldDeferFetch);
        // Token with length == 4, indexed
        assertTermIndexedOrNot("morn", true, shouldDeferFetch);
        // Token with length == 5, indexed
        assertTermIndexedOrNot("morni", true, shouldDeferFetch);
        // Token not on front edge, also indexed, because not edgesOnly
        assertTermIndexedOrNot("ornin", true, shouldDeferFetch);
        // Token not on front edge, also indexed, because not edgesOnly
        assertTermIndexedOrNot("rning", true, shouldDeferFetch);
        // Token with length == 6, not indexed
        assertTermIndexedOrNot("mornin", false, shouldDeferFetch);
        // Token indexed with higher/lower case ignored
        assertTermIndexedOrNot("Good", true, shouldDeferFetch);
        assertTermIndexedOrNot("good", true, shouldDeferFetch);
        // Token with length == 2, but also indexed, because it is a separate word
        assertTermIndexedOrNot("Mr", true, shouldDeferFetch);
        // Token with length == 7, but also indexed, because it is a separate word
        assertTermIndexedOrNot("morning", true, shouldDeferFetch);
        // Query parser parses it as "Mr" and "T" with OR operator, and "Mr" is indexed
        assertTermIndexedOrNot("Mr T", true, shouldDeferFetch);
    }

    @ParameterizedTest(name = "testNgramEdgesOnly[shouldDeferFetch={0}]")
    @BooleanSource
    void testNgramEdgesOnly(boolean shouldDeferFetch) throws Exception {
        initializeWithNgramIndex("Good morning Mr Tian", true, 3, 5);
        // Token with length == 2, not indexed
        assertTermIndexedOrNot("mo", false, shouldDeferFetch);
        // Token with length == 3, indexed
        assertTermIndexedOrNot("mor", true, shouldDeferFetch);
        // Token with length == 4, indexed
        assertTermIndexedOrNot("morn", true, shouldDeferFetch);
        // Token with length == 5, indexed
        assertTermIndexedOrNot("morni", true, shouldDeferFetch);
        // Not indexed, because edgesOnly
        assertTermIndexedOrNot("ornin", false, shouldDeferFetch);
        // Not indexed, because edgesOnly
        assertTermIndexedOrNot("rning", false, shouldDeferFetch);
    }

    @ParameterizedTest(name = "simpleLuceneScans[shouldDeferFetch={0}]")
    @BooleanSource
    void simpleLuceneScans(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("civil blood makes civil hands unclean", Lists.newArrayList());
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScan("Complex$text_index"),
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    scanParams(query(hasToString("MULTI civil blood makes civil hands unclean")))));
            RecordQueryPlan plan = planner.plan(query);
            assertThat(plan, matcher);
            RecordCursor<FDBQueriedRecord<Message>> fdbQueriedRecordRecordCursor = recordStore.executeQuery(plan);
            RecordCursor<Tuple> map = fdbQueriedRecordRecordCursor.map(FDBQueriedRecord::getPrimaryKey);
            List<Long> primaryKeys = map.map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(2L, 4L), Set.copyOf(primaryKeys));
        }
    }

    @ParameterizedTest(name = "testThenExpressionBeforeFieldExpression[shouldDeferFetch={0}]")
    @BooleanSource
    void testThenExpressionBeforeFieldExpression(boolean shouldDeferFetch) throws Exception {
        initializeNestedWithField();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("*:*", Lists.newArrayList("doc_id"));
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(filter1)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            RecordCursor<FDBQueriedRecord<Message>> fdbQueriedRecordRecordCursor = recordStore.executeQuery(plan);
            RecordCursor<Tuple> map = fdbQueriedRecordRecordCursor.map(FDBQueriedRecord::getPrimaryKey);
            List<Long> primaryKeys = map.map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(0L, 1L, 2L), Set.copyOf(primaryKeys));
        }

    }

    @ParameterizedTest(name = "simpleLuceneScansDocId[shouldDeferFetch={0}]")
    @BooleanSource
    void simpleLuceneScansDocId(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.field("doc_id").equalsValue(1L))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            Matcher<RecordQueryPlan> matcher = typeFilter(equalTo(Collections.singleton(TextIndexTestUtils.SIMPLE_DOC)), scan(bounds(hasTupleString("[[1],[1]]"))));
            RecordQueryPlan plan = planner.plan(query);
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(1L), Set.copyOf(primaryKeys));
        }
    }

    @ParameterizedTest(name = "delayFetchOnOrOfLuceneScanWithFieldFilter[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnOrOfLuceneScanWithFieldFilter(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("civil blood makes civil hands unclean", Lists.newArrayList("text"));
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.or(filter1, Query.field("doc_id").lessThan(10000L)))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = primaryKeyDistinct(
                    unorderedUnion(
                            indexScan(allOf(indexScan("Complex$text_index"),
                                    indexScanType(LuceneScanTypes.BY_LUCENE),
                                    scanParams(query(hasToString("civil blood makes civil hands unclean"))))),
                            typeFilter(equalTo(Collections.singleton(TextIndexTestUtils.SIMPLE_DOC)),
                                    scan(bounds(hasTupleString("([null],[10000])"))))
                    ));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(2L, 4L, 0L, 1L, 3L, 5L), Set.copyOf(primaryKeys));
            if (shouldDeferFetch) {
                assertLoadRecord(5, context);
            } else {
                assertLoadRecord(6, context);
            }
        }
    }

    @ParameterizedTest(name = "delayFetchOnFilterWithSort[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnLuceneFilterWithSort(boolean shouldDeferFetch) throws Exception {
        initializeWithGroup();
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithGroup(context);
            final QueryComponent filter1 = new LuceneQueryComponent("parents", Lists.newArrayList("text"), true);
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .setSort(field("group"), true)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(List.of(5L, 2L, 4L), primaryKeys);
            if (shouldDeferFetch) {
                assertLoadRecord(5, context);
            } else {
                assertLoadRecord(6, context);
            }
        }
    }

    @ParameterizedTest(name = "delayFetchOnAndOfLuceneAndFieldFilter[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnAndOfLuceneAndFieldFilter(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("civil blood makes civil hands unclean", Lists.newArrayList());
            // Query for full records
            QueryComponent filter2 = Query.field("doc_id").equalsValue(2L);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.and(filter2, filter1))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> scanMatcher = fetch(filter(filter2, coveringIndexScan(indexScan(allOf(indexScanType(LuceneScanTypes.BY_LUCENE), indexScan("Complex$text_index"),
                    scanParams(query(hasToString("MULTI civil blood makes civil hands unclean"))))))));
            assertThat(plan, scanMatcher);
            RecordCursor<FDBQueriedRecord<Message>> primaryKeys;
            primaryKeys = recordStore.executeQuery(plan);
            final List<Long> keys = primaryKeys.map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(2L), Set.copyOf(keys));
            if (shouldDeferFetch) {
                assertLoadRecord(3, context);
            } else {
                assertLoadRecord(4, context);
            }
        }
    }

    @ParameterizedTest(name = "delayFetchOnOrOfLuceneFiltersGivesUnion[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnOrOfLuceneFiltersGivesUnion(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("(\"civil blood makes civil hands unclean\")", Lists.newArrayList("text"), true);
            final QueryComponent filter2 = new LuceneQueryComponent("(\"was king from 966 to 1016\")", Lists.newArrayList());
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.or(filter1, filter2))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = primaryKeyDistinct(unorderedUnion(
                    indexScan(allOf(indexScan("Complex$text_index"),
                            indexScanType(LuceneScanTypes.BY_LUCENE),
                            scanParams(query(hasToString("MULTI (\"civil blood makes civil hands unclean\")"))))),
                    indexScan(allOf(indexScan("Complex$text_index"),
                            indexScanType(LuceneScanTypes.BY_LUCENE),
                            scanParams(query(hasToString("MULTI (\"was king from 966 to 1016\")")))))));
            if (shouldDeferFetch) {
                matcher = fetch(primaryKeyDistinct(unorderedUnion(
                        coveringIndexScan(indexScan(allOf(indexScan("Complex$text_index"),
                                indexScanType(LuceneScanTypes.BY_LUCENE),
                                scanParams(query(hasToString("MULTI (\"civil blood makes civil hands unclean\")")))))),
                        coveringIndexScan(indexScan(allOf(indexScan("Complex$text_index"),
                                indexScanType(LuceneScanTypes.BY_LUCENE),
                                scanParams(query(hasToString("MULTI (\"was king from 966 to 1016\")")))))))));
            }
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(1L, 2L, 4L), Set.copyOf(primaryKeys));
            if (shouldDeferFetch) {
                assertLoadRecord(5, context);
            } else {
                assertLoadRecord(6, context);
            }
        }
    }

    @ParameterizedTest(name = "delayFetchOnAndOfLuceneFilters[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnAndOfLuceneFilters(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("\"the continuance\"", Lists.newArrayList());
            final QueryComponent filter2 = new LuceneQueryComponent("grudge", Lists.newArrayList());
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.and(filter1, filter2))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(SIMPLE_TEXT_SUFFIXES.getName()),
                    scanParams(query(hasToString("MULTI \"the continuance\" AND MULTI grudge")))));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(4L), Set.copyOf(primaryKeys));
            if (shouldDeferFetch) {
                assertLoadRecord(3, context);
            } else {
                assertLoadRecord(4, context);
            }
        }
    }

    @ParameterizedTest(name = "delayFetchOnLuceneComplexStringAnd[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnLuceneComplexStringAnd(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("(the continuance AND grudge)", Lists.newArrayList());
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(SIMPLE_TEXT_SUFFIXES.getName()),
                    scanParams(query(hasToString("MULTI (the continuance AND grudge)")))));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(4L), Set.copyOf(primaryKeys));
            if (shouldDeferFetch) {
                assertLoadRecord(3, context);
            } else {
                assertLoadRecord(4, context);
            }
        }
    }

    @ParameterizedTest(name = "delayFetchOnLuceneComplexStringOr[shouldDeferFetch={0}]")
    @BooleanSource
    void delayFetchOnLuceneComplexStringOr(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("\"the continuance\" OR grudge", Lists.newArrayList());
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(SIMPLE_TEXT_SUFFIXES.getName()),
                    scanParams(query(hasToString("MULTI \"the continuance\" OR grudge")))));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(4L, 5L, 2L), Set.copyOf(primaryKeys));
            if (shouldDeferFetch) {
                assertLoadRecord(3, context);
            } else {
                assertLoadRecord(4, context);
            }
        }
    }

    @ParameterizedTest(name = "misMatchQueryShouldReturnNoResult[shouldDeferFetch={0}]")
    @BooleanSource
    void misMatchQueryShouldReturnNoResult(boolean shouldDeferFetch) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("doesNotExist", Lists.newArrayList("text"), true);
            // Query for full records
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScan("Complex$text_index"), indexScanType(LuceneScanTypes.BY_LUCENE), scanParams(query(hasToString("MULTI doesNotExist")))));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(), Set.copyOf(primaryKeys));
            if (shouldDeferFetch) {
                assertLoadRecord(3, context);
            } else {
                assertLoadRecord(4, context);
            }
        }
    }

    private static Stream<Integer> threadCount() {
        return Stream.of(1, 10);
    }

    @ParameterizedTest(name = "threadedLuceneScanDoesntBreakPlannerAndSearch-PoolThreadCount={0}")
    @MethodSource("threadCount")
    void threadedLuceneScanDoesntBreakPlannerAndSearch(@Nonnull Integer value) throws Exception {
        CountingThreadFactory threadFactory = new CountingThreadFactory();
        executorService = Executors.newFixedThreadPool(value, threadFactory);
        initializeFlat();
        for (int i = 0; i < 200; i++) {
            try (FDBRecordContext context = openContext()) {
                openRecordStore(context);
                String[] randomWords = generateRandomWords(500);
                final TestRecordsTextProto.SimpleDocument dylan = TestRecordsTextProto.SimpleDocument.newBuilder()
                        .setDocId(i)
                        .setText(randomWords[1])
                        .setGroup(2)
                        .build();
                recordStore.saveRecord(dylan);
                commit(context);
            }
        }
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("*:*", Lists.newArrayList("text"), false);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .build();
            setDeferFetchAfterUnionAndIntersection(false);
            RecordQueryPlan plan = planner.plan(query);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertThat(threadFactory.threadCounts, aMapWithSize(greaterThan(0)));
        }
    }

    static class CountingThreadFactory implements ThreadFactory {
        final Map<String, Integer> threadCounts = new ConcurrentHashMap<>();
        final ThreadFactory delegate = Executors.defaultThreadFactory();

        @Override
        public Thread newThread(Runnable r) {
            return delegate.newThread(() -> {
                threadCounts.merge(Thread.currentThread().getName(), 1, Integer::sum);
                r.run();
            });
        }
    }

    @ParameterizedTest(name = "nestedLuceneAndQuery[shouldDeferFetch={0}]")
    @BooleanSource
    void nestedLuceneAndQuery(boolean shouldDeferFetch) throws Exception {
        initializeNested();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(Query.and(
                            new LuceneQueryComponent("entry_value:king", Lists.newArrayList("entry"), false),
                            Query.field("entry").oneOfThem().matches(Query.field("key").equalsValue("a"))))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);

            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexScan("Map$entry-value"),
                    scanParams(allOf(
                            query(hasToString("entry_value:king")),
                            group(hasTupleString("[[a],[a]]"))))
            ));
            assertThat(plan, matcher);
            assertEquals(Set.of(2L), Set.copyOf(primaryKeys));
        }
    }

    @ParameterizedTest(name = "nestedLuceneFieldQuery[shouldDeferFetch={0}]")
    @BooleanSource
    void nestedLuceneFieldQuery(boolean shouldDeferFetch) throws Exception {
        initializeNested();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(new LuceneQueryComponent("entry_value:king", Lists.newArrayList("entry")))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexScan("MapField$values"),
                    scanParams(query(hasToString("entry_value:king")))
            ));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(0L, 2L), Set.copyOf(primaryKeys));
        }
    }

    @ParameterizedTest(name = "nestedOneOfThemQuery[shouldDeferFetch={0}]")
    @BooleanSource
    void nestedOneOfThemQuery(boolean shouldDeferFetch) throws Exception {
        initializeNested();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);

            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(Query.field("entry").oneOfThem().matches(Query.field("key").equalsValue("king")))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexScan("MapField$values"),
                    scanParams(query(hasToString("entry_key:STRING EQUALS king")))));
            if (shouldDeferFetch) {
                matcher = fetch(primaryKeyDistinct(coveringIndexScan(matcher)));
            } else {
                matcher = primaryKeyDistinct(matcher);
            }
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(), Set.copyOf(primaryKeys));
        }
    }

    @ParameterizedTest(name = "nestedOneOfThemWithAndQuery[shouldDeferFetch={0}]")
    @BooleanSource
    void nestedOneOfThemWithAndQuery(boolean shouldDeferFetch) throws Exception {
        initializeNested();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter = Query.field("entry").oneOfThem().matches(Query.and(Query.field("key").equalsValue("b"),
                    Query.field("value").text().containsPhrase("civil blood makes civil hands unclean")));
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(filter)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(
                    indexName(MAP_AND_FIELD_ON_LUCENE_INDEX.getName()),
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    // TODO: This query does not have the proper correlation between the two children. An index that put the key into the name would be needed to tell that.
                    scanParams(query(hasToString("entry_key:STRING EQUALS b AND entry_value:TEXT TEXT_CONTAINS_PHRASE civil blood makes civil hands unclean")))
                    ));
            if (shouldDeferFetch) {
                matcher = fetch(primaryKeyDistinct(coveringIndexScan(matcher)));
            } else {
                matcher = primaryKeyDistinct(matcher);
            }
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            //assertEquals(Set.of(), Set.copyOf(primaryKeys));
        }

    }

    @ParameterizedTest(name = "nestedOneOfThemWithOrQuery[shouldDeferFetch={0}]")
    @BooleanSource
    void nestedOneOfThemWithOrQuery(boolean shouldDeferFetch) throws Exception {
        initializeNested();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter = Query.field("entry").oneOfThem().matches(Query.or(Query.field("key").equalsValue("b"),
                    Query.field("value").text().containsPhrase("civil blood makes civil hands unclean")));
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(filter)
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(
                    indexName(MAP_AND_FIELD_ON_LUCENE_INDEX.getName()),
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    scanParams(query(hasToString("entry_key:STRING EQUALS b OR entry_value:TEXT TEXT_CONTAINS_PHRASE civil blood makes civil hands unclean")))
            ));
            if (shouldDeferFetch) {
                matcher = fetch(primaryKeyDistinct(coveringIndexScan(matcher)));
            } else {
                matcher = primaryKeyDistinct(matcher);
            }
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(0L, 1L, 2L), Set.copyOf(primaryKeys));
        }

    }

    @ParameterizedTest(name = "longFieldQuery[shouldDeferFetch={0}]")
    @BooleanSource
    void longFieldQuery(boolean shouldDeferFetch) throws Exception {
        initializeNested();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(Query.field("doc_id").greaterThan(1L))
                    .build();
            setDeferFetchAfterUnionAndIntersection(shouldDeferFetch);
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(MAP_AND_FIELD_ON_LUCENE_INDEX.getName()),
                    scanParams(query(hasToString("doc_id:LONG GREATER_THAN 1")))
            ));
            assertThat(plan, matcher);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(2L), Set.copyOf(primaryKeys));
        }
    }

    @Test
    void covering() throws Exception {
        initializeWithGroup();
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithGroup(context);
            final QueryComponent filter1 = new LuceneQueryComponent("parents", Lists.newArrayList("text"), true);
            // Query partial full record
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .setRequiredResults(List.of(field("group")))
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = coveringIndexScan(indexScan(allOf(
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(TEXT_AND_GROUP.getName()),
                    scanParams(query(hasToString("MULTI parents")))
            )));
            assertThat(plan, matcher);
            List<Pair<Long, Long>> results = recordStore.executeQuery(plan).map(qr -> {
                long pk = qr.getPrimaryKey().getLong(0);
                TestRecordsTextProto.SimpleDocument.Builder builder = TestRecordsTextProto.SimpleDocument.newBuilder();
                builder.mergeFrom(qr.getRecord());
                long gr = builder.getGroup();
                return Pair.of(pk, gr);
            }).asList().get();
            assertEquals(Set.of(Pair.of(2L, 0L), Pair.of(4L, 0L), Pair.of(5L, 1L)), Set.copyOf(results));
            assertLoadRecord(0, context);
        }
    }

    @Test
    void fullGroupScan() throws Exception {
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, md -> {
                md.removeIndex(MAP_AND_FIELD_ON_LUCENE_INDEX.getName());
            }, SIMPLE_TEXT_SUFFIXES);
            QueryComponent groupFilter = Query.field("entry").oneOfThem().matches(Query.field("key").equalsValue("a"));
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(groupFilter)
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = filter(groupFilter, typeFilter(equalTo(Collections.singleton(TextIndexTestUtils.MAP_DOC)), scan(unbounded())));
            assertThat(plan, matcher);
        }

        try (FDBRecordContext context = openContext()) {
            openRecordStore(context, md -> {
                md.removeIndex(MAP_AND_FIELD_ON_LUCENE_INDEX.getName());
                md.removeIndex(MAP_ON_LUCENE_INDEX.getName());
                md.addIndex(MAP_DOC, new Index("GroupedMap", new GroupingKeyExpression(concat(field("group"), mainExpression), 1), LuceneIndexTypes.LUCENE));
            }, SIMPLE_TEXT_SUFFIXES);
            QueryComponent groupFilter = Query.and(
                    Query.field("group").equalsValue(1L),
                    Query.field("entry").oneOfThem().matches(Query.field("key").equalsValue("a")));
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(MAP_DOC)
                    .setFilter(groupFilter)
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = filter(groupFilter, typeFilter(equalTo(Collections.singleton(TextIndexTestUtils.MAP_DOC)), scan(unbounded())));
            assertThat(plan, matcher);
        }
    }

    @Test
    void andNot() throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("Verona", Lists.newArrayList("text"), true);
            final QueryComponent filter2 = new LuceneQueryComponent("traffic", Lists.newArrayList("text"), true);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.and(filter1, Query.not(filter2)))
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScan("Complex$text_index"),
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    scanParams(query(hasToString("MULTI Verona AND NOT MULTI traffic")))));
            assertThat(plan, matcher);
            assertThat(getLuceneQuery(plan), Matchers.hasToString("+(text:verona) -(text:traffic)"));
            RecordCursor<FDBQueriedRecord<Message>> fdbQueriedRecordRecordCursor = recordStore.executeQuery(plan);
            RecordCursor<Tuple> map = fdbQueriedRecordRecordCursor.map(FDBQueriedRecord::getPrimaryKey);
            List<Long> primaryKeys = map.map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(2L), Set.copyOf(primaryKeys));
        }
    }

    @Test
    void justNot() throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter = new LuceneQueryComponent("Verona", Lists.newArrayList("text"), true);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.not(filter))
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScan("Complex$text_index"),
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    scanParams(query(hasToString("NOT MULTI Verona")))));
            assertThat(plan, matcher);
            assertThat(getLuceneQuery(plan), Matchers.hasToString("+*:* -(text:verona)"));
            RecordCursor<FDBQueriedRecord<Message>> fdbQueriedRecordRecordCursor = recordStore.executeQuery(plan);
            RecordCursor<Tuple> map = fdbQueriedRecordRecordCursor.map(FDBQueriedRecord::getPrimaryKey);
            List<Long> primaryKeys = map.map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(0L, 1L, 3L, 5L), Set.copyOf(primaryKeys));
        }
    }

    @Test
    void notOr() throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("Verona", Lists.newArrayList("text"), true);
            final QueryComponent filter2 = new LuceneQueryComponent("traffic", Lists.newArrayList("text"), true);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.not(Query.or(filter1, filter2)))
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            Matcher<RecordQueryPlan> matcher = indexScan(allOf(indexScan("Complex$text_index"),
                    indexScanType(LuceneScanTypes.BY_LUCENE),
                    scanParams(query(hasToString("NOT MULTI Verona AND NOT MULTI traffic")))));
            assertThat(plan, matcher);
            assertThat(getLuceneQuery(plan), Matchers.hasToString("+*:* -(text:verona) -(text:traffic)"));
            RecordCursor<FDBQueriedRecord<Message>> fdbQueriedRecordRecordCursor = recordStore.executeQuery(plan);
            RecordCursor<Tuple> map = fdbQueriedRecordRecordCursor.map(FDBQueriedRecord::getPrimaryKey);
            List<Long> primaryKeys = map.map(t -> t.getLong(0)).asList().get();
            assertEquals(Set.of(0L, 1L, 3L), Set.copyOf(primaryKeys));
        }
    }

    private org.apache.lucene.search.Query getLuceneQuery(RecordQueryPlan plan) {
        LuceneIndexQueryPlan indexPlan = (LuceneIndexQueryPlan)plan;
        LuceneScanQuery scan = (LuceneScanQuery)indexPlan.getScanParameters().bind(recordStore, recordStore.getRecordMetaData().getIndex(indexPlan.getIndexName()), EvaluationContext.EMPTY);
        return scan.getQuery();
    }

    @Test
    void sortByPrimaryKey() throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("parents", Lists.newArrayList("text"), true);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1)
                    .setSort(field("doc_id"), true)
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            assertEquals(List.of(5L, 4L, 2L), primaryKeys);
        }
    }

    @Test
    void sortByGroupedPrimaryKey() throws Exception {
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithComplex(context);
            complexDocuments.forEach(recordStore::saveRecord);
            commit(context);
        }
        try (FDBRecordContext context = openContext()) {
            openRecordStoreWithComplex(context);
            final QueryComponent filter1 = new LuceneQueryComponent("parents", Lists.newArrayList("text"), true);
            RecordQuery query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.COMPLEX_DOC)
                    .setFilter(Query.and(Query.field("group").equalsValue(0L), filter1))
                    .setSort(field("doc_id"), true)
                    .build();
            RecordQueryPlan plan = planner.plan(query);
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(1)).asList().get();
            assertEquals(List.of(2L, 0L), primaryKeys);
        }
    }

    @ParameterizedTest(name = "unionSearches[sorted={0}]")
    @BooleanSource
    void unionSearches(boolean sorted) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("parents", Lists.newArrayList("text"), true);
            final QueryComponent filter2 = new LuceneQueryComponent("king", Lists.newArrayList("text"), true);
            RecordQuery.Builder query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(Query.or(filter1, filter2));
            if (sorted) {
                query.setSort(field("doc_id"));
            }
            RecordQueryPlan plan = planner.plan(query.build());
            Matcher<RecordQueryPlan> matcher1 = indexScan(allOf(indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(SIMPLE_TEXT_SUFFIXES.getName()),
                    scanParams(query(hasToString("MULTI parents")))));
            Matcher<RecordQueryPlan> matcher2 = indexScan(allOf(indexScanType(LuceneScanTypes.BY_LUCENE),
                    indexName(SIMPLE_TEXT_SUFFIXES.getName()),
                    scanParams(query(hasToString("MULTI king")))));
            if (sorted) {
                assertThat(plan, union(matcher1, matcher2));
            } else {
                assertThat(plan, primaryKeyDistinct(unorderedUnion(matcher1, matcher2)));
            }
            List<Long> primaryKeys = recordStore.executeQuery(plan).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList().get();
            if (sorted) {
                assertEquals(List.of(1L, 2L, 4L, 5L), primaryKeys);
            } else {
                assertEquals(Set.of(1L, 2L, 4L, 5L), new HashSet<>(primaryKeys));
            }
        }
    }

    @ParameterizedTest(name = "continuations[sorted={0}]")
    @BooleanSource
    void continuations(boolean sorted) throws Exception {
        initializeFlat();
        try (FDBRecordContext context = openContext()) {
            openRecordStore(context);
            final QueryComponent filter1 = new LuceneQueryComponent("parents", Lists.newArrayList("text"), true);
            RecordQuery.Builder query = RecordQuery.newBuilder()
                    .setRecordType(TextIndexTestUtils.SIMPLE_DOC)
                    .setFilter(filter1);
            if (sorted) {
                query.setSort(field("doc_id"));
            }
            RecordQueryPlan plan = planner.plan(query.build());
            ExecuteProperties executeProperties = ExecuteProperties.newBuilder().setReturnedRowLimit(2).build();
            List<Long> primaryKeys = new ArrayList<>();
            byte[] continuation = null;
            AtomicReference<RecordCursorResult<Long>> holder = new AtomicReference<>();
            do {
                primaryKeys.addAll(recordStore.executeQuery(plan, continuation, executeProperties).map(FDBQueriedRecord::getPrimaryKey).map(t -> t.getLong(0)).asList(holder).get());
                continuation = holder.get().getContinuation().toBytes();
            } while (continuation != null);
            if (sorted) {
                assertEquals(List.of(2L, 4L, 5L), primaryKeys);
            } else {
                assertEquals(Set.of(2L, 4L, 5L), new HashSet<>(primaryKeys));
            }
        }
    }

}
