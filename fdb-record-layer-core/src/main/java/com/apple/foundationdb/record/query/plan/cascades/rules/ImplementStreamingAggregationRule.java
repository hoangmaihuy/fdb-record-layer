/*
 * ImplementGroupByRule.java
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

package com.apple.foundationdb.record.query.plan.cascades.rules;

import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CascadesRule;
import com.apple.foundationdb.record.query.plan.cascades.CascadesRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.ExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.GroupExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.PlanPartition;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.RequestedOrderingConstraint;
import com.apple.foundationdb.record.query.plan.cascades.expressions.GroupByExpression;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.ReferenceMatchers;
import com.apple.foundationdb.record.query.plan.cascades.properties.OrderingProperty;
import com.apple.foundationdb.record.query.plan.cascades.values.AggregateValue;
import com.apple.foundationdb.record.query.plan.cascades.values.Values;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryStreamingAggregationPlan;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;

import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.AnyMatcher.any;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.QuantifierMatchers.forEachQuantifierOverRef;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.RelationalExpressionMatchers.groupByExpression;

/**
 * Rule for implementing logical {@code GROUP BY} into a physical streaming aggregate operator {@link RecordQueryStreamingAggregationPlan}.
 */
public class ImplementStreamingAggregationRule extends CascadesRule<GroupByExpression> {

    @Nonnull
    private static final BindingMatcher<ExpressionRef<? extends RelationalExpression>> lowerRefMatcher = ReferenceMatchers.anyRef();
    @Nonnull
    private static final BindingMatcher<Quantifier.ForEach> innerQuantifierMatcher = forEachQuantifierOverRef(lowerRefMatcher);
    @Nonnull
    private static final BindingMatcher<GroupByExpression> root =
            groupByExpression(any(innerQuantifierMatcher));

    public ImplementStreamingAggregationRule() {
        super(root, ImmutableSet.of(RequestedOrderingConstraint.REQUESTED_ORDERING));
    }

    @Override
    public void onMatch(@Nonnull final CascadesRuleCall call) {
        final var bindings = call.getBindings();

        final var groupByExpression = bindings.get(root);
        final var correlatedTo = groupByExpression.getCorrelatedTo();
        final var innerQuantifier = Iterables.getOnlyElement(groupByExpression.getQuantifiers());

        final var groupingValue = groupByExpression.getGroupingValue();
        final var currentGroupingValue = groupingValue == null ? null : groupingValue.rebase(AliasMap.of(innerQuantifier.getAlias(), Quantifier.CURRENT));

        final var requiredOrderingKeyValues =
                currentGroupingValue == null || currentGroupingValue.isConstant()
                ? null
                : Values.primitiveAccessorsForType(currentGroupingValue.getResultType(), () -> currentGroupingValue, correlatedTo)
                        .stream()
                        .collect(ImmutableSet.toImmutableSet());

        final var innerReference = innerQuantifier.getRangesOver();
        final var planPartitions = PlanPartition.rollUpTo(innerReference.getPlanPartitions(), OrderingProperty.ORDERING);

        for (final var planPartition : planPartitions) {
            final var providedOrdering = planPartition.getAttributeValue(OrderingProperty.ORDERING);
            if (requiredOrderingKeyValues == null || providedOrdering.satisfiesGroupingValues(requiredOrderingKeyValues)) {
                call.yield(implementGroupBy(planPartition, groupByExpression));
            }
        }
    }

    @Nonnull
    private GroupExpressionRef<RecordQueryStreamingAggregationPlan> implementGroupBy(@Nonnull final PlanPartition planPartition,
                                                                                     @Nonnull final GroupByExpression groupByExpression) {
        final var innerQuantifier = Iterables.getOnlyElement(groupByExpression.getQuantifiers());
        final var newInnerPlanReference = GroupExpressionRef.from(planPartition.getPlans());
        final var newPlanQuantifier = Quantifier.physical(newInnerPlanReference);
        final var aliasMap = AliasMap.of(innerQuantifier.getAlias(), newPlanQuantifier.getAlias());
        final var rebasedAggregatedValue = groupByExpression.getAggregateValue().rebase(aliasMap);
        final var rebaseAggregatedAlias = aliasMap.getTargetOrDefault(groupByExpression.getAggregateValueAlias(), groupByExpression.getAggregateValueAlias());
        final var rebasedGroupingValue = groupByExpression.getGroupingValue() == null ? null : groupByExpression.getGroupingValue().rebase(aliasMap);
        final var rebaseGroupingAlias = aliasMap.getTargetOrDefault(groupByExpression.getGroupingValueAlias(), groupByExpression.getGroupingValueAlias());
        final var rebasedResultValue = groupByExpression.getRuntimeValue().rebase(aliasMap);
        final var result = RecordQueryStreamingAggregationPlan.of(
                newPlanQuantifier,
                rebasedGroupingValue,
                (AggregateValue)rebasedAggregatedValue,
                rebaseGroupingAlias,
                rebaseAggregatedAlias,
                rebasedResultValue);
        return GroupExpressionRef.of(result);
    }
}
