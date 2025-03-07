/*
 * FieldValueMatcher.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.cascades.view;

import com.apple.foundationdb.record.query.plan.cascades.values.FieldValue;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import org.hamcrest.Description;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * A Hamcrest matcher that checks whether a {@link Value} is a
 * {@link FieldValue} with particular field names.
 */
public class FieldValueMatcher extends ValueMatcher {
    @Nonnull
    private final List<String> fieldPath;

    public FieldValueMatcher(@Nonnull List<String> fieldPath) {
        this.fieldPath = fieldPath;
    }

    public FieldValueMatcher(@Nonnull String fieldName) {
        this(Collections.singletonList(fieldName));
    }

    @Override
    protected boolean matchesSafely(final Value element) {
        return element instanceof FieldValue &&
               ((FieldValue)element).getFields().equals(fieldPath);
    }

    @Override
    public void describeTo(Description description) {
        description.appendText(String.join(".", fieldPath));
    }
}
