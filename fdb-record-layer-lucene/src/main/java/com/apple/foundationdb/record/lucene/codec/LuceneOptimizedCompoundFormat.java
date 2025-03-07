/*
 * LuceneOptimizedCompoundFormat.java
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

package com.apple.foundationdb.record.lucene.codec;

import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import org.apache.lucene.codecs.CompoundDirectory;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.lucene50.Lucene50CompoundFormat;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Wrapper for the {@link Lucene50CompoundFormat} to optimize compound files for sitting on FoundationDB.
 * In contrast to {@link Lucene50CompoundFormat}, this wrapper uses {@link LuceneOptimizedWrappedDirectory}
 * as the {@link Directory} for read/write of compound file.
 */
public class LuceneOptimizedCompoundFormat extends CompoundFormat {
    /** Extension of compound file. */
    public static final String DATA_EXTENSION = "cfs";
    /** Extension of compound file entries. */
    public static final String ENTRIES_EXTENSION = "cfe";

    public static final String DATA_CODEC = "Lucene50CompoundData";
    public static final String ENTRY_CODEC = "Lucene50CompoundEntries";
    public static final int VERSION_START = 0;
    static final int VERSION_CURRENT = VERSION_START;

    private Lucene50CompoundFormat compoundFormat;

    public LuceneOptimizedCompoundFormat() {
        this.compoundFormat = new Lucene50CompoundFormat();
    }

    @Override
    public CompoundDirectory getCompoundReader(Directory dir, final SegmentInfo si, final IOContext context) throws IOException {
        dir = (dir instanceof LuceneOptimizedWrappedDirectory) ? dir : new LuceneOptimizedWrappedDirectory(dir);
        return new LuceneOptimizedCompoundReader(dir, si, context);
    }

    @Override
    public void write(Directory dir, final SegmentInfo si, final IOContext context) throws IOException {
        // Make sure all fetches are initiated in advance of the compoundFormat sequentially stepping through them.
        si.files().stream().forEach(file -> {
            try {
                dir.openInput(file, IOContext.READONCE);
            } catch (IOException ioe) {
                throw new RecordCoreArgumentException("Cannot open input for file", ioe)
                        .addLogInfo(LogMessageKeys.SOURCE_FILE, file);
            }
        });
        compoundFormat.write(new LuceneOptimizedWrappedDirectory(dir), si, context);
        final String fileName = IndexFileNames.segmentFileName(si.name, "", DATA_EXTENSION);
        si.setFiles(si.files().stream().filter(file -> !file.equals(fileName)).collect(Collectors.toSet()));
    }

}
