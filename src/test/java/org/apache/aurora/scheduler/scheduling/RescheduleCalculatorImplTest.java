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
package org.apache.aurora.scheduler.scheduling;

import java.util.Map;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;

import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.testing.easymock.EasyMockTest;
import org.apache.aurora.common.util.BackoffStrategy;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskEvent;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.config.types.TimeAmount;
import org.apache.aurora.scheduler.scheduling.RescheduleCalculator.RescheduleCalculatorImpl;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.gen.ScheduleStatus.FINISHED;
import static org.apache.aurora.gen.ScheduleStatus.INIT;
import static org.apache.aurora.gen.ScheduleStatus.KILLED;
import static org.apache.aurora.gen.ScheduleStatus.KILLING;
import static org.apache.aurora.gen.ScheduleStatus.PENDING;
import static org.apache.aurora.gen.ScheduleStatus.RUNNING;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

public class RescheduleCalculatorImplTest extends EasyMockTest {

  private static final TimeAmount FLAPPING_THRESHOLD = new TimeAmount(1, Time.MINUTES);
  private static final TimeAmount MAX_STARTUP_DELAY = new TimeAmount(10, Time.MINUTES);

  private StorageTestUtil storageUtil;
  private BackoffStrategy backoff;
  private RescheduleCalculator rescheduleCalculator;

  @Before
  public void setUp() {
    storageUtil = new StorageTestUtil(this);
    backoff = createMock(BackoffStrategy.class);
    rescheduleCalculator = new RescheduleCalculatorImpl(
        storageUtil.storage,
        new RescheduleCalculatorImpl.RescheduleCalculatorSettings(
            backoff,
            FLAPPING_THRESHOLD,
            MAX_STARTUP_DELAY));
    storageUtil.expectOperations();
  }

  @Test
  public void testNoPenaltyForNoAncestor() {
    control.replay();

    assertEquals(0L, rescheduleCalculator.getFlappingPenaltyMs(makeTask("a", INIT)));
  }

  @Test
  public void testNoPenaltyDeletedAncestor() {
    String ancestorId = "a";
    storageUtil.expectTaskFetch(ancestorId);

    control.replay();

    assertEquals(
        0L,
        rescheduleCalculator.getFlappingPenaltyMs(setAncestor(makeTask("b", INIT), ancestorId)));
  }

  @Test
  public void testFlappingTask() {
    ScheduledTask ancestor = makeFlappyTask("a");
    storageUtil.expectTaskFetch(Tasks.id(ancestor), ancestor);
    long penaltyMs = 1000L;
    expect(backoff.calculateBackoffMs(0L)).andReturn(penaltyMs);

    control.replay();

    assertEquals(
        penaltyMs,
        rescheduleCalculator.getFlappingPenaltyMs(
            setAncestor(makeTask("b", INIT), Tasks.id(ancestor))));
  }

  @Test
  public void testFlappingTasksBackoffTruncation() {
    // Ensures that the reschedule calculator detects penalty truncation and avoids inspecting
    // ancestors once truncated.
    ScheduledTask taskA = setAncestor(makeFlappyTask("a"), "bugIfQueried");
    ScheduledTask taskB = setAncestor(makeFlappyTask("b"), Tasks.id(taskA));
    ScheduledTask taskC = setAncestor(makeFlappyTask("c"), Tasks.id(taskB));
    ScheduledTask taskD = setAncestor(makeFlappyTask("d"), Tasks.id(taskC));

    Map<ScheduledTask, Long> ancestorsAndPenalties = ImmutableMap.of(
        taskD, 100L,
        taskC, 200L,
        taskB, 300L,
        taskA, 300L);

    long lastPenalty = 0L;
    for (Map.Entry<ScheduledTask, Long> taskAndPenalty : ancestorsAndPenalties.entrySet()) {
      storageUtil.expectTaskFetch(Tasks.id(taskAndPenalty.getKey()), taskAndPenalty.getKey());
      expect(backoff.calculateBackoffMs(lastPenalty)).andReturn(taskAndPenalty.getValue());
      lastPenalty = taskAndPenalty.getValue();
    }

    control.replay();

    ScheduledTask newTask = setAncestor(makeFlappyTask("newTask"), Tasks.id(taskD));
    assertEquals(300L, rescheduleCalculator.getFlappingPenaltyMs(newTask));
  }

  @Test
  public void testNoPenaltyForInterruptedTasks() {
    ScheduledTask ancestor = setEvents(
        makeTask("a", KILLED),
        ImmutableMap.of(INIT, 0L, PENDING, 100L, RUNNING, 200L, KILLING, 300L, KILLED, 400L));
    storageUtil.expectTaskFetch(Tasks.id(ancestor), ancestor);

    control.replay();

    assertEquals(
        0L,
        rescheduleCalculator.getFlappingPenaltyMs(
            setAncestor(makeTask("b", INIT), Tasks.id(ancestor))));
  }

  private ScheduledTask makeFlappyTask(String taskId) {
    return setEvents(
        makeTask(taskId, FINISHED),
        ImmutableMap.of(INIT, 0L, PENDING, 100L, RUNNING, 200L, FINISHED, 300L));
  }

  private ScheduledTask makeTask(String taskId) {
    ScheduledTask builder = TaskTestUtil.makeTask(taskId, TaskTestUtil.JOB).newBuilder();
    builder.unsetAncestorId();
    return ScheduledTask.build(builder);
  }

  private ScheduledTask makeTask(String taskId, ScheduleStatus status) {
    return ScheduledTask.build(makeTask(taskId).newBuilder().setStatus(status));
  }

  private ScheduledTask setAncestor(ScheduledTask task, String ancestorId) {
    return ScheduledTask.build(task.newBuilder().setAncestorId(ancestorId));
  }

  private static final Function<Map.Entry<ScheduleStatus, Long>, TaskEvent> TO_EVENT =
      input -> new TaskEvent().setStatus(input.getKey()).setTimestamp(input.getValue());

  private ScheduledTask setEvents(ScheduledTask task, Map<ScheduleStatus, Long> events) {
    return ScheduledTask.build(task.newBuilder().setTaskEvents(
        FluentIterable.from(events.entrySet()).transform(TO_EVENT).toList()));
  }
}
