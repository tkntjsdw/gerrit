// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.logging;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.server.logging.TraceContext.TraceIdConsumer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

public class TraceContextTest {
  @After
  public void cleanup() {
    LoggingContext.getInstance().clearTags();
    LoggingContext.getInstance().forceLogging(false);
  }

  @Test
  public void openContext() {
    assertTags(ImmutableMap.of());
    try (TraceContext traceContext = TraceContext.open().addTag("foo", "bar")) {
      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));
    }
    assertTags(ImmutableMap.of());
  }

  @Test
  public void openNestedContexts() {
    assertTags(ImmutableMap.of());
    try (TraceContext traceContext = TraceContext.open().addTag("foo", "bar")) {
      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));

      try (TraceContext traceContext2 = TraceContext.open().addTag("abc", "xyz")) {
        assertTags(ImmutableMap.of("abc", ImmutableSet.of("xyz"), "foo", ImmutableSet.of("bar")));
      }

      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));
    }
    assertTags(ImmutableMap.of());
  }

  @Test
  public void openNestedContextsWithSameTagName() {
    assertTags(ImmutableMap.of());
    try (TraceContext traceContext = TraceContext.open().addTag("foo", "bar")) {
      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));

      try (TraceContext traceContext2 = TraceContext.open().addTag("foo", "baz")) {
        assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar", "baz")));
      }

      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));
    }
    assertTags(ImmutableMap.of());
  }

  @Test
  public void openNestedContextsWithSameTagNameAndValue() {
    assertTags(ImmutableMap.of());
    try (TraceContext traceContext = TraceContext.open().addTag("foo", "bar")) {
      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));

      try (TraceContext traceContext2 = TraceContext.open().addTag("foo", "bar")) {
        assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));
      }

      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));
    }
    assertTags(ImmutableMap.of());
  }

  @Test
  public void openContextWithRequestId() {
    assertTags(ImmutableMap.of());
    try (TraceContext traceContext = TraceContext.open().addTag(RequestId.Type.RECEIVE_ID, "foo")) {
      assertTags(ImmutableMap.of("RECEIVE_ID", ImmutableSet.of("foo")));
    }
    assertTags(ImmutableMap.of());
  }

  @Test
  public void addTag() {
    assertTags(ImmutableMap.of());
    try (TraceContext traceContext = TraceContext.open().addTag("foo", "bar")) {
      assertTags(ImmutableMap.of("foo", ImmutableSet.of("bar")));

      traceContext.addTag("foo", "baz");
      traceContext.addTag("bar", "baz");
      assertTags(
          ImmutableMap.of("foo", ImmutableSet.of("bar", "baz"), "bar", ImmutableSet.of("baz")));
    }
    assertTags(ImmutableMap.of());
  }

  @Test
  public void openContextWithForceLogging() {
    assertForceLogging(false);
    try (TraceContext traceContext = TraceContext.open().forceLogging()) {
      assertForceLogging(true);
    }
    assertForceLogging(false);
  }

  @Test
  public void openNestedContextsWithForceLogging() {
    assertForceLogging(false);
    try (TraceContext traceContext = TraceContext.open().forceLogging()) {
      assertForceLogging(true);

      try (TraceContext traceContext2 = TraceContext.open()) {
        // force logging is still enabled since outer trace context forced logging
        assertForceLogging(true);

        try (TraceContext traceContext3 = TraceContext.open().forceLogging()) {
          assertForceLogging(true);
        }

        assertForceLogging(true);
      }

      assertForceLogging(true);
    }
    assertForceLogging(false);
  }

  @Test
  public void forceLogging() {
    assertForceLogging(false);
    try (TraceContext traceContext = TraceContext.open()) {
      assertForceLogging(false);

      traceContext.forceLogging();
      assertForceLogging(true);

      traceContext.forceLogging();
      assertForceLogging(true);
    }
    assertForceLogging(false);
  }

  @Test
  public void newTrace() {
    TestTraceIdConsumer traceIdConsumer = new TestTraceIdConsumer();
    try (TraceContext traceContext = TraceContext.newTrace(true, null, traceIdConsumer)) {
      assertForceLogging(true);
      assertThat(LoggingContext.getInstance().getTagsAsMap().keySet())
          .containsExactly(RequestId.Type.TRACE_ID.name());
    }
    assertThat(traceIdConsumer.tagName).isEqualTo(RequestId.Type.TRACE_ID.name());
    assertThat(traceIdConsumer.traceId).isNotNull();
  }

  @Test
  public void newTraceWithProvidedTraceId() {
    TestTraceIdConsumer traceIdConsumer = new TestTraceIdConsumer();
    String traceId = "foo";
    try (TraceContext traceContext = TraceContext.newTrace(true, traceId, traceIdConsumer)) {
      assertForceLogging(true);
      assertTags(ImmutableMap.of(RequestId.Type.TRACE_ID.name(), ImmutableSet.of(traceId)));
    }
    assertThat(traceIdConsumer.tagName).isEqualTo(RequestId.Type.TRACE_ID.name());
    assertThat(traceIdConsumer.traceId).isEqualTo(traceId);
  }

  @Test
  public void newTraceEnabledWithoutForceLogging() {
    TestTraceIdConsumer traceIdConsumer = new TestTraceIdConsumer();
    try (TraceContext traceContext = TraceContext.newTrace(false, null, traceIdConsumer)) {
      assertForceLogging(false);
      assertThat(LoggingContext.getInstance().getTagsAsMap().keySet())
          .containsExactly(RequestId.Type.TRACE_ID.name());
    }
    assertThat(traceIdConsumer.tagName).isEqualTo(RequestId.Type.TRACE_ID.name());
    assertThat(traceIdConsumer.traceId).isNotNull();
  }

  @Test
  public void newTraceEnabledWithoutForceLoggingWithProvidedTraceId() {
    TestTraceIdConsumer traceIdConsumer = new TestTraceIdConsumer();
    try (TraceContext traceContext = TraceContext.newTrace(false, "foo", traceIdConsumer)) {
      assertForceLogging(false);
      assertThat(LoggingContext.getInstance().getTagsAsMap().keySet())
          .containsExactly(RequestId.Type.TRACE_ID.name());
    }
    assertThat(traceIdConsumer.tagName).isEqualTo("TRACE_ID");
    assertThat(traceIdConsumer.traceId).isEqualTo("foo");
  }

  @Test
  public void newTraceNestingAndForceLogging() {
    // create cartesian product of all possible values for each of the four parameters
    for (boolean forceOuter : List.of(false, true)) {
      for (String outerId : Arrays.asList(null, "outer")) {
        for (boolean forceInner : List.of(false, true)) {
          for (String innerId : Arrays.asList(null, "inner")) {
            newTraceNesting(forceOuter, outerId, forceInner, innerId);
          }
        }
      }
    }
  }

  private void newTraceNesting(
      boolean forceOuter, String outerId, boolean forceInner, String innerId) {
    String message =
        String.format("parameters: (%s, %s, %s, %s)", forceOuter, outerId, forceInner, innerId);
    try (TraceContext outer =
        TraceContext.newTrace(forceOuter, outerId, new TestTraceIdConsumer())) {
      assertForceLogging(forceOuter, message);
      try (TraceContext nested =
          TraceContext.newTrace(forceInner, innerId, new TestTraceIdConsumer())) {
        assertForceLogging(forceOuter || forceInner, message);
      }
    }
  }

  @Test
  public void onlyOneTraceId() throws InterruptedException {
    for (boolean forceOuter : List.of(false, true)) {
      for (boolean forceInner : List.of(false, true)) {
        onlyOneTraceId(forceOuter, forceInner);
      }
    }
  }

  public void onlyOneTraceId(boolean forceOuter, boolean forceInner) throws InterruptedException {
    TestTraceIdConsumer traceIdConsumer1 = new TestTraceIdConsumer();
    try (TraceContext traceContext1 = TraceContext.newTrace(forceOuter, null, traceIdConsumer1)) {
      String expectedTraceId = traceIdConsumer1.traceId;
      assertThat(expectedTraceId).isNotNull();

      TestTraceIdConsumer traceIdConsumer2 = new TestTraceIdConsumer();
      Thread.sleep(2);
      try (TraceContext traceContext2 = TraceContext.newTrace(forceInner, null, traceIdConsumer2)) {
        assertTags(
            ImmutableMap.of(RequestId.Type.TRACE_ID.name(), ImmutableSet.of(expectedTraceId)));
      }
      assertThat(traceIdConsumer2.tagName).isEqualTo(RequestId.Type.TRACE_ID.name());
      assertThat(traceIdConsumer2.traceId).isEqualTo(expectedTraceId);
    }
  }

  @Test
  public void multipleTraceIdsIfTraceIdProvided() {
    for (boolean forceOuter : List.of(false, true)) {
      for (boolean forceInner : List.of(false, true)) {
        multipleTraceIdsIfTraceIdProvided(forceOuter, forceInner);
      }
    }
  }

  public void multipleTraceIdsIfTraceIdProvided(boolean forceOuter, boolean forceInner) {
    String traceId1 = "foo";
    try (TraceContext traceContext1 =
        TraceContext.newTrace(forceOuter, traceId1, (tagName, traceId) -> {})) {
      TestTraceIdConsumer traceIdConsumer = new TestTraceIdConsumer();
      String traceId2 = "bar";
      try (TraceContext traceContext2 =
          TraceContext.newTrace(forceInner, traceId2, traceIdConsumer)) {
        assertTags(
            ImmutableMap.of(RequestId.Type.TRACE_ID.name(), ImmutableSet.of(traceId1, traceId2)));
      }
      assertThat(traceIdConsumer.tagName).isEqualTo(RequestId.Type.TRACE_ID.name());
      assertThat(traceIdConsumer.traceId).isEqualTo(traceId2);
    }
  }

  @Test
  public void operationForTraceTimerCannotBeNull() throws Exception {
    assertThrows(NullPointerException.class, () -> TraceContext.newTimer(null));
    assertThrows(NullPointerException.class, () -> TraceContext.newTimer(null, Metadata.empty()));
    assertThrows(
        NullPointerException.class,
        () ->
            TraceContext.newTimer(
                null, Metadata.builder().accountId(1000000).changeId(123).build()));
  }

  @Test
  public void metadataForTraceTimerCannotBeNull() throws Exception {
    assertThrows(NullPointerException.class, () -> TraceContext.newTimer("test", (Metadata) null));
  }

  private void assertTags(ImmutableMap<String, ImmutableSet<String>> expectedTagMap) {
    Map<String, ? extends Set<Object>> actualTagMap =
        LoggingContext.getInstance().getTags().asMap();
    assertThat(actualTagMap.keySet()).containsExactlyElementsIn(expectedTagMap.keySet());
    for (Map.Entry<String, ImmutableSet<String>> expectedEntry : expectedTagMap.entrySet()) {
      assertThat(actualTagMap.get(expectedEntry.getKey()))
          .containsExactlyElementsIn(expectedEntry.getValue());
    }
  }

  private void assertForceLogging(boolean expected) {
    assertThat(LoggingContext.getInstance().shouldForceLogging(null, null, false))
        .isEqualTo(expected);
  }

  private void assertForceLogging(boolean expected, String message) {
    assertWithMessage(message)
        .that(LoggingContext.getInstance().shouldForceLogging(null, null, false))
        .isEqualTo(expected);
  }

  private static class TestTraceIdConsumer implements TraceIdConsumer {
    String tagName;
    String traceId;

    @Override
    public void accept(String tagName, String traceId) {
      this.tagName = tagName;
      this.traceId = traceId;
    }
  }
}
