/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.file.ingest;

import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.batch.BatchRuntimeContext;
import io.cdap.cdap.etl.api.batch.BatchSourceContext;
import io.cdap.plugin.common.ReferenceBatchSource;
import io.cdap.plugin.common.ReferencePluginConfig;
import org.apache.commons.csv.CSVRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;

/**
 * Abstract class for FileCopySource plugin. Extracts metadata of desired files
 * from the source database.
 * @param <K> the FileMetaData class specific to each filesystem.
 */
public abstract class AbstractFileDecompressDecryptSource<K extends CSVRecord>
        extends ReferenceBatchSource<NullWritable, K, StructuredRecord> {

    private final AbstractFileMetadataSourceConfig config;

    protected AbstractFileDecompressDecryptSource(AbstractFileMetadataSourceConfig config) {
        super(config);
        this.config = config;
    }

    /**
     * Loads configurations from UI and check if they are valid.
     */
    public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
        this.config.validate();
    }

    /**
     * Initialize the output StructuredRecord Schema here.
     * @param context
     * @throws Exception
     */
    @Override
    public void initialize(BatchRuntimeContext context) throws Exception {
        super.initialize(context);
    }

    /**
     * Load job configurations here.
     */
    public void prepareRun(BatchSourceContext context) throws Exception {
        config.validate();
    }

    /**
     * Convert file metadata to StructuredRecord and emit.
     */
    public abstract void transform(KeyValue<NullWritable, K> input, Emitter<StructuredRecord> emitter);

    /**
     * Abstract class for the configuration of FileCopySink
     */
    public abstract class AbstractFileMetadataSourceConfig extends ReferencePluginConfig {

        @Macro
        @Description("Collection of sourcePaths separated by \",\" to read files from")
        public String sourcePaths;

        @Macro
        @Description("The number of files each split reads in")
        public Integer maxSplitSize;

        @Description("Whether or not to copy recursively")
        public Boolean recursiveCopy;

        public AbstractFileMetadataSourceConfig(String name, String sourcePaths,
                                                Integer maxSplitSize) {
            super(name);
            this.sourcePaths = sourcePaths;
            this.maxSplitSize = maxSplitSize;
        }

        public void validate() {
            if (!this.containsMacro("maxSplitSize")) {
                if (maxSplitSize <= 0) {
                    throw new IllegalArgumentException("Max split size must be a positive integer.");
                }
            }
        }
    }

    /**
     * This method initializes the configuration instance with fields that are shared by all plugins.
     *
     * @param conf The configuration we wish to initialize.
     */
    protected void setDefaultConf(Configuration conf) {
        FileInputFormat.setSourcePaths(conf, config.sourcePaths);
        FileInputFormat.setMaxSplitSize(conf, config.maxSplitSize);
        FileInputFormat.setRecursiveCopy(conf, config.recursiveCopy.toString());
    }

    /*
     * Put additional configurations here for specific databases.
     */
}