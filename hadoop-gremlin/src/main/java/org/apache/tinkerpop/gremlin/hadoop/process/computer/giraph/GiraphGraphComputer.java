/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.hadoop.process.computer.giraph;

import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.giraph.conf.GiraphConfiguration;
import org.apache.giraph.conf.GiraphConstants;
import org.apache.giraph.job.GiraphJob;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.tinkerpop.gremlin.hadoop.Constants;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.AbstractHadoopGraphComputer;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.giraph.io.GiraphVertexInputFormat;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.giraph.io.GiraphVertexOutputFormat;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.util.MapReduceHelper;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.util.MemoryMapReduce;
import org.apache.tinkerpop.gremlin.hadoop.structure.HadoopGraph;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.ObjectWritable;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.VertexWritable;
import org.apache.tinkerpop.gremlin.hadoop.structure.util.ConfUtil;
import org.apache.tinkerpop.gremlin.hadoop.structure.util.HadoopHelper;
import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.MapReduce;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.DefaultComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.util.MapMemory;

import java.io.File;
import java.io.NotSerializableException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class GiraphGraphComputer extends AbstractHadoopGraphComputer implements GraphComputer, Tool {

    protected GiraphConfiguration giraphConfiguration = new GiraphConfiguration();
    private MapMemory memory = new MapMemory();

    public GiraphGraphComputer(final HadoopGraph hadoopGraph) {
        super(hadoopGraph);
        final Configuration configuration = hadoopGraph.configuration();
        configuration.getKeys().forEachRemaining(key -> this.giraphConfiguration.set(key, configuration.getProperty(key).toString()));
        this.giraphConfiguration.setMasterComputeClass(GiraphMemory.class);
        this.giraphConfiguration.setVertexClass(GiraphComputeVertex.class);
        this.giraphConfiguration.setWorkerContextClass(GiraphWorkerContext.class);
        this.giraphConfiguration.setOutEdgesClass(EmptyOutEdges.class);
        this.giraphConfiguration.setClass(GiraphConstants.VERTEX_ID_CLASS.getKey(), ObjectWritable.class, ObjectWritable.class);
        this.giraphConfiguration.setClass(GiraphConstants.VERTEX_VALUE_CLASS.getKey(), VertexWritable.class, VertexWritable.class);
        this.giraphConfiguration.setBoolean(GiraphConstants.STATIC_GRAPH.getKey(), true);
        this.giraphConfiguration.setVertexInputFormatClass(GiraphVertexInputFormat.class);
        this.giraphConfiguration.setVertexOutputFormatClass(GiraphVertexOutputFormat.class);
    }

    @Override
    public GraphComputer program(final VertexProgram vertexProgram) {
        super.program(vertexProgram);
        this.memory.addVertexProgramMemoryComputeKeys(this.vertexProgram);
        final BaseConfiguration apacheConfiguration = new BaseConfiguration();
        vertexProgram.storeState(apacheConfiguration);
        ConfUtil.mergeApacheIntoHadoopConfiguration(apacheConfiguration, this.giraphConfiguration);
        this.vertexProgram.getMessageCombiner().ifPresent(combiner -> this.giraphConfiguration.setCombinerClass(GiraphMessageCombiner.class));
        return this;
    }

    @Override
    public Future<ComputerResult> submit() {
        final long startTime = System.currentTimeMillis();
        super.validateStatePriorToExecution();
        return CompletableFuture.<ComputerResult>supplyAsync(() -> {
            try {
                final FileSystem fs = FileSystem.get(this.giraphConfiguration);
                this.loadJars(fs);
                fs.delete(new Path(this.giraphConfiguration.get(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION)), true);
                ToolRunner.run(this, new String[]{});
            } catch (final Exception e) {
                //e.printStackTrace();
                throw new IllegalStateException(e.getMessage(), e);
            }
            this.memory.setRuntime(System.currentTimeMillis() - startTime);
            return new DefaultComputerResult(HadoopHelper.getOutputGraph(this.hadoopGraph, this.resultGraph.get(), this.persist.get()), this.memory.asImmutable());
        });
    }

    @Override
    public int run(final String[] args) {
        this.giraphConfiguration.setBoolean(Constants.GREMLIN_HADOOP_GRAPH_OUTPUT_FORMAT_HAS_EDGES, this.persist.get().equals(Persist.EDGES));
        try {
            // it is possible to run graph computer without a vertex program (and thus, only map reduce jobs if they exist)
            if (null != this.vertexProgram) {
                // calculate main vertex program memory if desired (costs one mapreduce job)
                if (this.giraphConfiguration.getBoolean(Constants.GREMLIN_HADOOP_DERIVE_MEMORY, false)) {
                    final Set<String> memoryKeys = new HashSet<>(this.vertexProgram.getMemoryComputeKeys());
                    memoryKeys.add(Constants.HIDDEN_ITERATION);
                    this.giraphConfiguration.setStrings(Constants.GREMLIN_HADOOP_MEMORY_KEYS, (String[]) memoryKeys.toArray(new String[memoryKeys.size()]));
                    this.mapReducers.add(new MemoryMapReduce(memoryKeys));
                }

                // prepare the giraph vertex-centric computing job
                final GiraphJob job = new GiraphJob(this.giraphConfiguration, Constants.GREMLIN_HADOOP_GIRAPH_JOB_PREFIX + this.vertexProgram);
                // handle input paths (if any)
                if (FileInputFormat.class.isAssignableFrom(this.giraphConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_INPUT_FORMAT, InputFormat.class))) {
                    final Path inputPath = new Path(this.giraphConfiguration.get(Constants.GREMLIN_HADOOP_INPUT_LOCATION));
                    if (!FileSystem.get(this.giraphConfiguration).exists(inputPath))  // TODO: what about when the input is not a file input?
                        throw new IllegalArgumentException("The provided input path does not exist: " + inputPath);
                    FileInputFormat.setInputPaths(job.getInternalJob(), inputPath);
                }
                // handle output paths
                final Path outputPath = new Path(this.giraphConfiguration.get(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION) + "/" + Constants.HIDDEN_G);
                if (this.persist.get().equals(Persist.NOTHING) && this.mapReducers.isEmpty()) // do not write the graph back if it is not needed in MapReduce
                    job.getInternalJob().setOutputFormatClass(NullOutputFormat.class);
                else
                    FileOutputFormat.setOutputPath(job.getInternalJob(), outputPath);
                job.getInternalJob().setJarByClass(GiraphGraphComputer.class);
                this.logger.info(Constants.GREMLIN_HADOOP_GIRAPH_JOB_PREFIX + this.vertexProgram);
                // execute the job and wait until it completes (if it fails, throw an exception)
                if (!job.run(true))
                    throw new NotSerializableException("The GiraphGraphComputer job failed -- aborting all subsequent MapReduce jobs");  // how do I get the exception that occured?
            }
            // do map reduce jobs
            this.giraphConfiguration.setBoolean(Constants.GREMLIN_HADOOP_GRAPH_INPUT_FORMAT_HAS_EDGES, this.giraphConfiguration.getBoolean(Constants.GREMLIN_HADOOP_GRAPH_OUTPUT_FORMAT_HAS_EDGES, true));
            for (final MapReduce mapReduce : this.mapReducers) {
                this.memory.addMapReduceMemoryKey(mapReduce);
                MapReduceHelper.executeMapReduceJob(mapReduce, this.memory, this.giraphConfiguration);
            }
            // if no persistence, delete the map reduce output
            if (this.persist.get().equals(Persist.NOTHING)) {
                final Path outputPath = new Path(this.giraphConfiguration.get(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION) + "/" + Constants.HIDDEN_G);
                if (FileSystem.get(this.giraphConfiguration).exists(outputPath))      // TODO: what about when the output is not a file output?
                    FileSystem.get(this.giraphConfiguration).delete(outputPath, true);
            }
        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public void setConf(final org.apache.hadoop.conf.Configuration configuration) {
        // TODO: is this necessary to implement?
    }

    @Override
    public org.apache.hadoop.conf.Configuration getConf() {
        return this.giraphConfiguration;
    }

    private void loadJars(final FileSystem fs) {
        final String hadoopGremlinLibsRemote = "hadoop-gremlin-libs";
        if (this.giraphConfiguration.getBoolean(Constants.GREMLIN_HADOOP_JARS_IN_DISTRIBUTED_CACHE, true)) {
            final String hadoopGremlinLocalLibs = System.getenv(Constants.HADOOP_GREMLIN_LIBS);
            if (null == hadoopGremlinLocalLibs)
                this.logger.warn(Constants.HADOOP_GREMLIN_LIBS + " is not set -- proceeding regardless");
            else {
                final String[] paths = hadoopGremlinLocalLibs.split(":");
                for (final String path : paths) {
                    final File file = new File(path);
                    if (file.exists()) {
                        Stream.of(file.listFiles()).filter(f -> f.getName().endsWith(Constants.DOT_JAR)).forEach(f -> {
                            try {
                                final Path jarFile = new Path(fs.getHomeDirectory() + "/" + hadoopGremlinLibsRemote + "/" + f.getName());
                                fs.copyFromLocalFile(new Path(f.getPath()), jarFile);
                                try {
                                    DistributedCache.addArchiveToClassPath(jarFile, this.giraphConfiguration, fs);
                                } catch (final Exception e) {
                                    throw new RuntimeException(e.getMessage(), e);
                                }
                            } catch (Exception e) {
                                throw new IllegalStateException(e.getMessage(), e);
                            }
                        });
                    } else {
                        this.logger.warn(path + " does not reference a valid directory -- proceeding regardless");
                    }
                }
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final FileConfiguration configuration = new PropertiesConfiguration(args[0]);
        new GiraphGraphComputer(HadoopGraph.open(configuration)).program(VertexProgram.createVertexProgram(HadoopGraph.open(configuration), configuration)).submit().get();
    }
}
