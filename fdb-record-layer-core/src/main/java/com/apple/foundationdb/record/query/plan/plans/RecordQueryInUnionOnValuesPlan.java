/*
 * RecordQueryUnionOnValuesPlan.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.plans;

import com.apple.foundationdb.record.Bindings;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.GroupExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.TranslationMap;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.apple.foundationdb.record.query.plan.cascades.values.simplification.DefaultValueSimplificationRuleSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;

/**
 * Union plan that compares using a {@link Value}.
 */
@SuppressWarnings("java:S2160")
public class RecordQueryInUnionOnValuesPlan extends RecordQueryInUnionPlan implements RecordQueryPlanWithComparisonKeyValues {

    public RecordQueryInUnionOnValuesPlan(@Nonnull final Quantifier.Physical inner,
                                          @Nonnull final List<? extends InSource> inSources,
                                          @Nonnull final List<? extends Value> comparisonKeyValues,
                                          final boolean reverse,
                                          final int maxNumberOfValuesAllowed,
                                          @Nonnull final Bindings.Internal internal) {
        super(inner,
                inSources,
                new ComparisonKeyFunction.OnValues(Quantifier.CURRENT, comparisonKeyValues),
                reverse,
                maxNumberOfValuesAllowed,
                internal);
    }

    @Nonnull
    @Override
    public ComparisonKeyFunction.OnValues getComparisonKeyFunction() {
        return (ComparisonKeyFunction.OnValues)super.getComparisonKeyFunction();
    }

    @Nonnull
    @Override
    public List<? extends Value> getRequiredValues(@Nonnull final CorrelationIdentifier newBaseAlias, @Nonnull final Type inputType) {
        final var ruleSet = DefaultValueSimplificationRuleSet.ofSimplificationRules();
        return getComparisonKeyValues().stream()
                .map(comparisonKeyValue -> comparisonKeyValue.rebase(AliasMap.of(Quantifier.CURRENT, newBaseAlias)).simplify(ruleSet, AliasMap.emptyMap(), getCorrelatedTo()))
                .collect(ImmutableList.toImmutableList());
    }

    @Nonnull
    @Override
    public Set<KeyExpression> getRequiredFields() {
        throw new RecordCoreException("this plan does not support this getRequiredFields()");
    }

    @Nonnull
    @Override
    public List<? extends Value> getComparisonKeyValues() {
        return getComparisonKeyFunction().getComparisonKeyValues();
    }

    @Nonnull
    @Override
    public Set<Type> getDynamicTypes() {
        return getComparisonKeyValues().stream().flatMap(comparisonKeyValue -> comparisonKeyValue.getDynamicTypes().stream()).collect(ImmutableSet.toImmutableSet());
    }
    
    @Nonnull
    @Override
    public RecordQueryInUnionOnValuesPlan translateCorrelations(@Nonnull final TranslationMap translationMap, @Nonnull final List<? extends Quantifier> translatedQuantifiers) {
        return new RecordQueryInUnionOnValuesPlan(Iterables.getOnlyElement(translatedQuantifiers).narrow(Quantifier.Physical.class),
                getInSources(),
                getComparisonKeyValues(),
                reverse,
                maxNumberOfValuesAllowed,
                internal);
    }

    @Nonnull
    @Override
    public RecordQueryInUnionOnValuesPlan withChild(@Nonnull final RecordQueryPlan child) {
        return new RecordQueryInUnionOnValuesPlan(Quantifier.physical(GroupExpressionRef.of(child)),
                getInSources(),
                getComparisonKeyValues(),
                reverse,
                maxNumberOfValuesAllowed,
                internal);
    }

    @Nonnull
    public static RecordQueryInUnionOnValuesPlan inUnion(@Nonnull final Quantifier.Physical inner,
                                                         @Nonnull final List<? extends InSource> inSources,
                                                         @Nonnull final List<? extends Value> comparisonKeyValues,
                                                         final boolean reverse,
                                                         final int maxNumberOfValuesAllowed,
                                                         @Nonnull final Bindings.Internal internal) {
        return new RecordQueryInUnionOnValuesPlan(inner,
                inSources,
                comparisonKeyValues,
                reverse,
                maxNumberOfValuesAllowed,
                internal);
    }
}
