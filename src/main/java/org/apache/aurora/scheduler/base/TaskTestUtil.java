/**
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
package org.apache.aurora.scheduler.base;

import java.util.Map;
import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.Constraint;
import org.apache.aurora.gen.Container;
import org.apache.aurora.gen.Container._Fields;
import org.apache.aurora.gen.DockerContainer;
import org.apache.aurora.gen.DockerParameter;
import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.LimitConstraint;
import org.apache.aurora.gen.MesosFetcherURI;
import org.apache.aurora.gen.Metadata;
import org.apache.aurora.gen.PartitionPolicy;
import org.apache.aurora.gen.Resource;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.gen.TaskConstraint;
import org.apache.aurora.gen.TaskEvent;
import org.apache.aurora.gen.ValueConstraint;
import org.apache.aurora.gen.apiConstants;
import org.apache.aurora.scheduler.TierInfo;
import org.apache.aurora.scheduler.TierManager;
import org.apache.aurora.scheduler.TierManager.TierManagerImpl.TierConfig;
import org.apache.aurora.scheduler.configuration.ConfigurationManager;
import org.apache.aurora.scheduler.configuration.ConfigurationManager.ConfigurationManagerSettings;
import org.apache.aurora.scheduler.configuration.executor.ExecutorConfig;
import org.apache.aurora.scheduler.configuration.executor.ExecutorSettings;
import org.apache.aurora.gen.JobKey;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.scheduler.storage.log.ThriftBackfill;
import org.apache.mesos.v1.Protos;
import org.apache.mesos.v1.Protos.ExecutorID;
import org.apache.mesos.v1.Protos.ExecutorInfo;

/**
 * Convenience methods for working with tasks.
 * <p>
 * TODO(wfarner): This lives in under src/main only so benchmarks can access it.  Reconsider the
 * project layout so this is not necessary.
 */
public final class TaskTestUtil {

  public static final JobKey JOB = JobKeys.from("role", "devel", "job");
  public static final TierInfo REVOCABLE_TIER =
      new TierInfo(true /* preemptible */, true /* revocable */);
  public static final TierInfo DEV_TIER =
      new TierInfo(true /* preemptible */, false /* revocable */);
  public static final TierInfo PREFERRED_TIER =
      new TierInfo(false /* preemptible */, false /* revocable */);
  public static final String PROD_TIER_NAME = "tier-prod";
  public static final String DEV_TIER_NAME = "tier-dev";
  public static final TierConfig TIER_CONFIG =
      new TierConfig(DEV_TIER_NAME, ImmutableMap.of(
          PROD_TIER_NAME, PREFERRED_TIER,
          DEV_TIER_NAME, DEV_TIER
      ));
  public static final TierManager TIER_MANAGER = new TierManager.TierManagerImpl(TIER_CONFIG);
  public static final ThriftBackfill THRIFT_BACKFILL = new ThriftBackfill(TIER_MANAGER);
  public static final ConfigurationManagerSettings CONFIGURATION_MANAGER_SETTINGS =
      new ConfigurationManagerSettings(
          ImmutableSet.of(_Fields.MESOS),
          false,
          ImmutableList.of(),
          true,
          true,
          true,
          true,
          ConfigurationManager.DEFAULT_ALLOWED_JOB_ENVIRONMENTS);
  public static final ExecutorID EXECUTOR_ID = ExecutorID.newBuilder()
      .setValue("PLACEHOLDER")
      .build();
  public static final ExecutorInfo EXECUTOR_INFO = ExecutorInfo.newBuilder()
      .setExecutorId(EXECUTOR_ID)
      .setName(apiConstants.AURORA_EXECUTOR_NAME)
      .setCommand(Protos.CommandInfo.newBuilder().build()).build();
  public static final ExecutorSettings EXECUTOR_SETTINGS = new ExecutorSettings(
      ImmutableMap.<String, ExecutorConfig>builder()
          .put(EXECUTOR_INFO.getName(), new ExecutorConfig(EXECUTOR_INFO, ImmutableList.of(), ""))
          .build(),
      false);
  public static final ConfigurationManager CONFIGURATION_MANAGER =
      new ConfigurationManager(CONFIGURATION_MANAGER_SETTINGS,
          TIER_MANAGER,
          THRIFT_BACKFILL,
          EXECUTOR_SETTINGS);

  private TaskTestUtil() {
    // Utility class.
  }

  public static TaskConfig makeConfig(JobKey job) {
    return TaskConfig
        .builder()
        .setJob(job)
        .setOwner(Identity.builder().setUser(job.getRole() + "-user").build())
        .setIsService(true)
        .setNumCpus(1.0)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setPriority(1)
        .setMaxTaskFailures(-1)
        .setProduction(true)
        .setTier(PROD_TIER_NAME)
        .setPartitionPolicy(new PartitionPolicy().setDelaySecs(5).setReschedule(true))
        .setConstraints(ImmutableSet.of(
            Constraint.builder()
                .setName("valueConstraint")
                .setConstraint(TaskConstraint.withValue(ValueConstraint.builder()
                    .setNegated(true).setValues(ImmutableSet.of("value1", "value2")).build()))
                .build(),
            Constraint.builder()
                .setName("limitConstraint")
                .setConstraint(TaskConstraint.withLimit(LimitConstraint.builder()
                    .setLimit(5).build()))
                .build()))
        .setRequestedPorts(ImmutableSet.of("http"))
        .setTaskLinks(ImmutableMap.of("http", "link", "admin", "otherLink"))
        .setContactEmail("foo@bar.com")
        .setMetadata(ImmutableSet.of(Metadata.builder().setKey("key").setValue("value").build()))
        .setMesosFetcherUris(ImmutableSet.of(
            MesosFetcherURI.builder().setValue("pathA").setExtract(true).setCache(true).build(),
            MesosFetcherURI.builder().setValue("pathB").setExtract(true).setCache(true).build()))
        .setExecutorConfig(new org.apache.aurora.gen.ExecutorConfig(
            EXECUTOR_INFO.getName(),
            "config"))
        .setContainer(Container.withDocker(
            DockerContainer.builder().setImage("imagename")
                .setParameters(ImmutableList.of(
                    new DockerParameter("a", "b"),
                    new DockerParameter("c", "d")))))
        .setResources(ImmutableSet.of(
            Resource.withNumCpus(1.0),
            Resource.withRamMb(1024),
            Resource.withDiskMb(1024),
            Resource.withNamedPort("http")));
  }

  public static ScheduledTask makeTask(String id, JobKey job) {
    return makeTask(id, makeConfig(job));
  }

  public static ScheduledTask makeTask(String id, JobKey job, int instanceId) {
    return makeTask(id, makeConfig(job), instanceId, Optional.absent());
  }

  public static ScheduledTask makeTask(String id, JobKey job, int instanceId, String agentId) {
    return makeTask(id, makeConfig(job), instanceId, Optional.of(agentId));
  }

  public static ScheduledTask makeTask(String id, TaskConfig config) {
    return makeTask(id, config, 2);
  }

  public static ScheduledTask makeTask(String id, TaskConfig config, int instanceId) {
    return makeTask(id, config, instanceId, Optional.absent());
  }

  public static ScheduledTask makeTask(
      String id,
      TaskConfig config,
      int instanceId,
      Optional<String> agentId) {

    AssignedTask assignedTask = new AssignedTask()
        .setInstanceId(instanceId)
        .setTaskId(id)
        .setAssignedPorts(ImmutableMap.of("http", 1000))
        .setTask(config.newBuilder());
    if (agentId.isPresent()) {
      assignedTask.setSlaveId(agentId.get());
    }

    return ScheduledTask.build(new ScheduledTask()
        .setStatus(ScheduleStatus.ASSIGNED)
        .setTaskEvents(ImmutableList.of(
            new TaskEvent(100L, ScheduleStatus.PENDING)
                .setMessage("message")
                .setScheduler("scheduler"),
            new TaskEvent(101L, ScheduleStatus.ASSIGNED)
                .setMessage("message")
                .setScheduler("scheduler2")))
        .setAncestorId("ancestor")
        .setFailureCount(3)
        .setTimesPartitioned(2)
        .setAssignedTask(assignedTask));
  }

  public static ScheduledTask addStateTransition(
      ScheduledTask task,
      ScheduleStatus status,
      long timestamp) {

    ScheduledTask builder = task.newBuilder();
    builder.setStatus(status);
    builder.addToTaskEvents(new TaskEvent()
        .setTimestamp(timestamp)
        .setStatus(status)
        .setScheduler("scheduler"));
    return ScheduledTask.build(builder);
  }

  public static String tierConfigFile() {
    return "{\"default\": \"preemptible\","
        + "\"tiers\":{"
        + "\"preferred\": {\"revocable\": false, \"preemptible\": false},"
        + "\"preemptible\": {\"revocable\": false, \"preemptible\": true},"
        + "\"revocable\": {\"revocable\": true, \"preemptible\": true}"
        + "}}";
  }

  public static Map<String, TierInfo> tierInfos() {
    return ImmutableMap.of(
        "preferred", PREFERRED_TIER,
        "preemptible", DEV_TIER,
        "revocable", REVOCABLE_TIER);
  }

  public static Set<org.apache.aurora.gen.TierConfig> tierConfigs() {
    return ImmutableSet.of(
        new org.apache.aurora.gen.TierConfig("preferred", PREFERRED_TIER.toMap()),
        new org.apache.aurora.gen.TierConfig("preemptible", DEV_TIER.toMap()),
        new org.apache.aurora.gen.TierConfig("revocable", REVOCABLE_TIER.toMap())
    );
  }
}
