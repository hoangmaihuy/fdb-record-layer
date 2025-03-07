/*
 * GroupByExpression.java
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

package com.apple.foundationdb.record.query.plan.cascades.expressions;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.Column;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.OrderingPart;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.RequestedOrdering;
import com.apple.foundationdb.record.query.plan.cascades.TranslationMap;
import com.apple.foundationdb.record.query.plan.cascades.explain.Attribute;
import com.apple.foundationdb.record.query.plan.cascades.explain.InternalPlannerGraphRewritable;
import com.apple.foundationdb.record.query.plan.cascades.explain.PlannerGraph;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.values.AggregateValue;
import com.apple.foundationdb.record.query.plan.cascades.values.FieldValue;
import com.apple.foundationdb.record.query.plan.cascades.values.ObjectValue;
import com.apple.foundationdb.record.query.plan.cascades.values.RecordConstructorValue;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.google.common.base.Suppliers;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A logical {@code group by} expression that represents grouping incoming tuples and aggregating each group.
 */
@API(API.Status.EXPERIMENTAL)
public class GroupByExpression implements RelationalExpressionWithChildren, InternalPlannerGraphRewritable {

    @Nullable
    private final FieldValue groupingValue;

    @Nonnull
    private final AggregateValue aggregateValue;

    @Nonnull
    private final Supplier<Value> computeResultSupplier;

    @Nonnull
    private final Supplier<Value> computeRuntimeResultSupplier;

    @Nonnull
    private final Supplier<RequestedOrdering> computeRequestedOrderingSupplier;

    @Nonnull
    private final CorrelationIdentifier groupingValueAlias;

    @Nonnull
    private final CorrelationIdentifier aggregateValueAlias;

    @Nonnull
    private final Quantifier inner;

    /**
     * Creates a new instance of {@link GroupByExpression}.
     *
     * @param aggregateValue The aggregation {@code Value} applied to each group.
     * @param groupingValue The grouping {@code Value} used to determine individual groups, can be {@code null} indicating no grouping.
     * @param inner The underlying source of tuples to be grouped.
     */
    public GroupByExpression(@Nonnull final AggregateValue aggregateValue,
                             @Nullable final FieldValue groupingValue,
                             @Nonnull final Quantifier inner) {
        this.groupingValue = groupingValue;
        this.aggregateValue = aggregateValue;
        this.computeResultSupplier = Suppliers.memoize(this::computeResultValue);
        this.computeRuntimeResultSupplier = Suppliers.memoize(this::computeRuntimeValue);
        this.computeRequestedOrderingSupplier = Suppliers.memoize(this::computeRequestOrdering);
        this.inner = inner;
        this.groupingValueAlias = CorrelationIdentifier.uniqueID();
        this.aggregateValueAlias = CorrelationIdentifier.uniqueID();
    }

    @Override
    public int getRelationalChildCount() {
        return 1;
    }

    @Nonnull
    @Override
    public Set<CorrelationIdentifier> getCorrelatedToWithoutChildren() {
        return getResultValue().getCorrelatedTo();
    }

    @Nonnull
    @Override
    public Value getResultValue() {
        return computeResultSupplier.get();
    }

    @Nonnull
    @Override
    public List<? extends Quantifier> getQuantifiers() {
        return ImmutableList.of(inner);
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public boolean equalsWithoutChildren(@Nonnull final RelationalExpression other, @Nonnull final AliasMap equivalences) {
        if (this == other) {
            return true;
        }
        if (getClass() != other.getClass()) {
            return false;
        }
        final var otherGroupByExpr = ((GroupByExpression)other);

        if ( (otherGroupByExpr.getGroupingValue() == null) ^ (getGroupingValue() == null) ) {
            return false;
        }

        if (otherGroupByExpr.getGroupingValue() != null) {
            return Objects.requireNonNull(getGroupingValue()).semanticEquals(otherGroupByExpr.getGroupingValue(), equivalences)
                   && getAggregateValue().semanticEquals(otherGroupByExpr.getAggregateValue(), equivalences);
        } else {
            return getAggregateValue().semanticEquals(otherGroupByExpr.getAggregateValue(), equivalences);
        }
    }

    @Override
    public int hashCodeWithoutChildren() {
        return Objects.hash(computeResultSupplier, getGroupingValue(), getAggregateValue());
    }

    @Override
    public int hashCode() {
        return semanticHashCode();
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(Object other) {
        return semanticEquals(other);
    }

    @Nonnull
    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public RelationalExpression translateCorrelations(@Nonnull final TranslationMap translationMap, @Nonnull final List<? extends Quantifier> translatedQuantifiers) {
        final AggregateValue translatedAggregateValue = getAggregateValue().translateCorrelations(translationMap);
        final Value translatedGroupingValue = getGroupingValue() == null ? null : getGroupingValue().translateCorrelations(translationMap);
        Verify.verify(translatedGroupingValue instanceof FieldValue);
        if (translatedAggregateValue != getAggregateValue() || translatedGroupingValue != getGroupingValue()) {
            return new GroupByExpression(translatedAggregateValue, (FieldValue)translatedGroupingValue, Iterables.getOnlyElement(translatedQuantifiers));
        }
        return this;
    }

    @Override
    public String toString() {
        if (getGroupingValue() != null) {
            return "GroupBy(" + getGroupingValue() + "), aggregationValue: " + getAggregateValue() + ", resultValue: " + computeResultSupplier.get();
        } else {
            return "GroupBy(NULL), aggregationValue: " + getAggregateValue() + ", resultValue: " + computeResultSupplier.get();
        }
    }

    @Nonnull
    @Override
    public PlannerGraph rewriteInternalPlannerGraph(@Nonnull final List<? extends PlannerGraph> childGraphs) {
        if (getGroupingValue() == null) {
            return PlannerGraph.fromNodeAndChildGraphs(
                    new PlannerGraph.LogicalOperatorNode(this,
                            "GROUP BY",
                            List.of("AGG {{agg}}"),
                            ImmutableMap.of("agg", Attribute.gml(getAggregateValue().toString()))),
                    childGraphs);
        } else {
            return PlannerGraph.fromNodeAndChildGraphs(
                    new PlannerGraph.LogicalOperatorNode(this,
                            "GROUP BY",
                            List.of("AGG {{agg}}", "GROUP BY {{grouping}}"),
                            ImmutableMap.of("agg", Attribute.gml(getAggregateValue().toString()),
                                    "grouping", Attribute.gml(getGroupingValue().toString()))),
                    childGraphs);
        }
    }

    @Nullable
    public FieldValue getGroupingValue() {
        return groupingValue;
    }

    @Nonnull
    public CorrelationIdentifier getGroupingValueAlias() {
        return groupingValueAlias;
    }

    @Nonnull
    public AggregateValue getAggregateValue() {
        return aggregateValue;
    }

    @Nonnull
    public CorrelationIdentifier getAggregateValueAlias() {
        return aggregateValueAlias;
    }

    /**
     * Returns the ordering requirements of the underlying scan for the group by to work. This is used by the planner
     * to choose a compatibly-ordered access path.
     *
     * @return The ordering requirements.
     */
    @Nonnull
    public RequestedOrdering getRequestedOrdering() {
        return computeRequestedOrderingSupplier.get();
    }

    /**
     * Constructs a {@code GROUP BY} runtime value that can be assembled on the fly by looking into the bindings of its
     * constituents (grouping expression and aggregation expression). This is done by leveraging the {@link ObjectValue}
     * which gets the message directly from the binding without attempting to follow the identifier chain.
     *
     * @return The runtime type of the {@code GROUP BY} expression.
     */
    @Nonnull
    public Value getRuntimeValue() {
        return computeRuntimeResultSupplier.get();
    }

    @Nonnull
    private Value computeResultValue() {
        final var aggregateColumn = Column.of(Type.Record.Field.of(getAggregateValue().getResultType(), Optional.of(getAggregateValueAlias().getId())), getAggregateValue());
        if (getGroupingValue() == null) {
            return RecordConstructorValue.ofColumns(ImmutableList.of(aggregateColumn));
        } else {
            final var groupingColumn = Column.of(Type.Record.Field.of(getGroupingValue().getResultType(), Optional.of(getGroupingValueAlias().getId())), getGroupingValue());
            return RecordConstructorValue.ofColumns(ImmutableList.of(groupingColumn, aggregateColumn));
        }
    }

    @Nonnull
    private Value computeRuntimeValue() {
        final var aggregateColumn = Column.of(
                Type.Record.Field.of(getAggregateValue().getResultType(), Optional.of(getAggregateValueAlias().getId())),
                ObjectValue.of(getAggregateValueAlias(), getAggregateValue().getResultType()));
        if (getGroupingValue() == null) {
            return RecordConstructorValue.ofColumns(ImmutableList.of(aggregateColumn));
        } else {
            final var groupingColumn = Column.of(
                    Type.Record.Field.of(getGroupingValue().getResultType(), Optional.of(getGroupingValueAlias().getId())),
                    ObjectValue.of(Objects.requireNonNull(getGroupingValueAlias()), getGroupingValue().getResultType()));
            return RecordConstructorValue.ofColumns(ImmutableList.of(groupingColumn, aggregateColumn));
        }
    }

    @Nonnull
    private RequestedOrdering computeRequestOrdering() {
        if (groupingValue == null || groupingValue.isConstant()) {
            return RequestedOrdering.preserve();
        }

        final var groupingValueType = groupingValue.getResultType();
        Verify.verify(groupingValueType instanceof Type.Record);

        return new RequestedOrdering(
                ImmutableList.of(OrderingPart.of(groupingValue)),
                RequestedOrdering.Distinctness.PRESERVE_DISTINCTNESS);
    }
}
