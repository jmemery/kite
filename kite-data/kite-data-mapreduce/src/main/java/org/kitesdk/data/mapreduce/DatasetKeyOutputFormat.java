/**
 * Copyright 2014 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.data.mapreduce;

import com.google.common.annotations.Beta;
import java.io.IOException;
import java.net.URI;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.kitesdk.compat.Hadoop;
import org.kitesdk.data.Dataset;
import org.kitesdk.data.DatasetDescriptor;
import org.kitesdk.data.DatasetException;
import org.kitesdk.data.DatasetRepositories;
import org.kitesdk.data.DatasetRepository;
import org.kitesdk.data.DatasetWriter;
import org.kitesdk.data.PartitionKey;
import org.kitesdk.data.View;
import org.kitesdk.data.spi.AbstractDataset;
import org.kitesdk.data.spi.AbstractRefinableView;
import org.kitesdk.data.spi.Constraints;
import org.kitesdk.data.spi.Mergeable;
import org.kitesdk.data.spi.filesystem.FileSystemDataset;

/**
 * A MapReduce {@code OutputFormat} for writing to a {@link Dataset}.
 *
 * Since a {@code Dataset} only contains entities (not key/value pairs), this output
 * format ignores the value.
 *
 * @param <E> The type of entities in the {@code Dataset}.
 */
@Beta
public class DatasetKeyOutputFormat<E> extends OutputFormat<E, Void> {

  public static final String KITE_REPOSITORY_URI = "kite.outputRepositoryUri";
  public static final String KITE_DATASET_NAME = "kite.outputDatasetName";
  public static final String KITE_PARTITION_DIR = "kite.outputPartitionDir";
  public static final String KITE_CONSTRAINTS = "kite.outputConstraints";

  public static void setRepositoryUri(Job job, URI uri) {
    job.getConfiguration().set(KITE_REPOSITORY_URI, uri.toString());
  }

  public static void setDatasetName(Job job, String name) {
    job.getConfiguration().set(KITE_DATASET_NAME, name);
  }

  public static <E> void setView(Job job, View<E> view) {
    setView(job.getConfiguration(), view);
  }

  public static <E> void setView(Configuration conf, View<E> view) {
    if (view instanceof AbstractRefinableView) {
      conf.set(KITE_CONSTRAINTS,
          Constraints.serialize(((AbstractRefinableView) view).getConstraints()));
    } else {
      throw new UnsupportedOperationException("Implementation " +
          "does not provide InputFormat support. View: " + view);
    }
  }

  static class DatasetRecordWriter<E> extends RecordWriter<E, Void> {

    private DatasetWriter<E> datasetWriter;

    public DatasetRecordWriter(View<E> view) {
      this.datasetWriter = view.newWriter();
      this.datasetWriter.open();
    }

    @Override
    public void write(E key, Void v) {
      datasetWriter.write(key);
    }

    @Override
    public void close(TaskAttemptContext taskAttemptContext) {
      datasetWriter.close();
    }
  }

  static class NullOutputCommitter extends OutputCommitter {
    @Override
    public void setupJob(JobContext jobContext) { }

    @Override
    public void setupTask(TaskAttemptContext taskContext) { }

    @Override
    public boolean needsTaskCommit(TaskAttemptContext taskContext) {
      return false;
    }

    @Override
    public void commitTask(TaskAttemptContext taskContext) { }

    @Override
    public void abortTask(TaskAttemptContext taskContext) { }
  }

  static class MergeOutputCommitter<E> extends OutputCommitter {
    @Override
    public void setupJob(JobContext jobContext) {
      createJobDataset(jobContext);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void commitJob(JobContext jobContext) throws IOException {
      Dataset<E> dataset = loadDataset(jobContext);
      Dataset<E> jobDataset = loadJobDataset(jobContext);
      ((Mergeable<Dataset<E>>) dataset).merge(jobDataset);
      deleteJobDataset(jobContext);
    }

    @Override
    public void abortJob(JobContext jobContext, JobStatus.State state) {
      deleteJobDataset(jobContext);
    }

    @Override
    public void setupTask(TaskAttemptContext taskContext) {
      // do nothing: the task attempt dataset is created in getRecordWriter
    }

    @Override
    public boolean needsTaskCommit(TaskAttemptContext taskContext) {
      return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void commitTask(TaskAttemptContext taskContext) throws IOException {
      Dataset<E> taskAttemptDataset = loadTaskAttemptDataset(taskContext);
      if (taskAttemptDataset != null) {
        Dataset<E> jobDataset = loadJobDataset(taskContext);
        ((Mergeable<Dataset<E>>) jobDataset).merge(taskAttemptDataset);
        deleteTaskAttemptDataset(taskContext);
      }
    }

    @Override
    public void abortTask(TaskAttemptContext taskContext) {
      deleteTaskAttemptDataset(taskContext);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public RecordWriter<E, Void> getRecordWriter(TaskAttemptContext taskAttemptContext) {
    Configuration conf = Hadoop.TaskAttemptContext
        .getConfiguration.invoke(taskAttemptContext);
    Dataset<E> dataset = loadDataset(taskAttemptContext);

    if (usePerTaskAttemptDatasets(dataset)) {
      dataset = loadOrCreateTaskAttemptDataset(taskAttemptContext);
    }

    String partitionDir = conf.get(KITE_PARTITION_DIR);
    String constraintsString = conf.get(KITE_CONSTRAINTS);
    if (dataset.getDescriptor().isPartitioned() && partitionDir != null) {
      PartitionKey key = ((FileSystemDataset) dataset).keyFromDirectory(new Path(partitionDir));
      if (key != null) {
        dataset = dataset.getPartition(key, true);
      }
      return new DatasetRecordWriter<E>(dataset);
    } else if (constraintsString != null) {
      Constraints constraints = Constraints.deserialize(constraintsString);
      if (dataset instanceof AbstractDataset) {
        return new DatasetRecordWriter<E>(((AbstractDataset) dataset).filter(constraints));
      }
      throw new DatasetException("Cannot find view from constraints for " + dataset);
    } else {
      return new DatasetRecordWriter<E>(dataset);
    }
  }

  @Override
  public void checkOutputSpecs(JobContext jobContext) {
    // always run
  }

  @Override
  public OutputCommitter getOutputCommitter(TaskAttemptContext taskAttemptContext) {
    Dataset<E> dataset = loadDataset(taskAttemptContext);
    return usePerTaskAttemptDatasets(dataset) ?
        new MergeOutputCommitter<E>() : new NullOutputCommitter();
  }

  private static <E> boolean usePerTaskAttemptDatasets(Dataset<E> dataset) {
    // new API output committers are not called properly in Hadoop 1
    return !isHadoop1() && dataset instanceof Mergeable;
  }

  private static boolean isHadoop1() {
    return !JobContext.class.isInterface();
  }

  private static DatasetRepository getDatasetRepository(JobContext jobContext) {
    Configuration conf = Hadoop.JobContext.getConfiguration.invoke(jobContext);
    return DatasetRepositories.open(conf.get(KITE_REPOSITORY_URI));
  }

  private static String getJobDatasetName(JobContext jobContext) {
    Configuration conf = Hadoop.JobContext.getConfiguration.invoke(jobContext);
    return conf.get(KITE_DATASET_NAME) + "_" + jobContext.getJobID().toString();
  }

  private static String getTaskAttemptDatasetName(TaskAttemptContext taskContext) {
    Configuration conf = Hadoop.TaskAttemptContext
        .getConfiguration.invoke(taskContext);
    return conf.get(KITE_DATASET_NAME) + "_" + taskContext.getTaskAttemptID().toString();
  }

  private static <E> Dataset<E> loadDataset(JobContext jobContext) {
    Configuration conf = Hadoop.JobContext.getConfiguration.invoke(jobContext);
    DatasetRepository repo = getDatasetRepository(jobContext);
    return repo.load(conf.get(KITE_DATASET_NAME));
  }

  private static <E> Dataset<E> createJobDataset(JobContext jobContext) {
    Dataset<Object> dataset = loadDataset(jobContext);
    String jobDatasetName = getJobDatasetName(jobContext);
    DatasetRepository repo = getDatasetRepository(jobContext);
    return repo.create(jobDatasetName, copy(dataset.getDescriptor()));
  }

  private static <E> Dataset<E> loadJobDataset(JobContext jobContext) {
    DatasetRepository repo = getDatasetRepository(jobContext);
    return repo.load(getJobDatasetName(jobContext));
  }

  private static void deleteJobDataset(JobContext jobContext) {
    DatasetRepository repo = getDatasetRepository(jobContext);
    repo.delete(getJobDatasetName(jobContext));
  }

  private static <E> Dataset<E> loadOrCreateTaskAttemptDataset(TaskAttemptContext taskContext) {
    Dataset<Object> dataset = loadDataset(taskContext);
    String taskAttemptDatasetName = getTaskAttemptDatasetName(taskContext);
    DatasetRepository repo = getDatasetRepository(taskContext);
    if (repo.exists(taskAttemptDatasetName)) {
      return repo.load(taskAttemptDatasetName);
    } else {
      return repo.create(taskAttemptDatasetName, copy(dataset.getDescriptor()));
    }
  }

  private static <E> Dataset<E> loadTaskAttemptDataset(TaskAttemptContext taskContext) {
    DatasetRepository repo = getDatasetRepository(taskContext);
    String taskAttemptDatasetName = getTaskAttemptDatasetName(taskContext);
    if (repo.exists(taskAttemptDatasetName)) {
      return repo.load(taskAttemptDatasetName);
    }
    return null;
  }

  private static void deleteTaskAttemptDataset(TaskAttemptContext taskContext) {
    DatasetRepository repo = getDatasetRepository(taskContext);
    String taskAttemptDatasetName = getTaskAttemptDatasetName(taskContext);
    if (repo.exists(taskAttemptDatasetName)) {
      repo.delete(taskAttemptDatasetName);
    }
  }

  private static DatasetDescriptor copy(DatasetDescriptor descriptor) {
    // location must be null when creating a new dataset
    return new DatasetDescriptor.Builder(descriptor).location((URI) null).build();
  }

}