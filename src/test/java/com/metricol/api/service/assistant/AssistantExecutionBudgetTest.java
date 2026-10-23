package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AssistantExecutionBudgetTest {
    @Test void capsCallsToRemainingTimeAndFailsBeforeNextAction() {
        var clock = new AtomicLong();
        try (var scope = new AssistantExecutionBudget(120_000, 100_000, null, clock::get)) {
            assertThat(AssistantExecutionBudget.timeout(Duration.ofSeconds(95))).isEqualTo(Duration.ofSeconds(20));
            clock.set(Duration.ofSeconds(20).toNanos());
            assertThatThrownBy(AssistantExecutionBudget::check).isInstanceOf(AssistantExecutionBudget.Expired.class);
        }
        assertThat(AssistantExecutionBudget.timeout(Duration.ofSeconds(95))).isEqualTo(Duration.ofSeconds(95));
    }
    @Test void confirmationWaitDoesNotConsumeActiveBudgetBetweenSegments() {
        var clock = new AtomicLong();
        try (var first = new AssistantExecutionBudget(120_000, 0, null, clock::get)) {
            clock.addAndGet(Duration.ofSeconds(10).toNanos());
        }
        clock.addAndGet(Duration.ofHours(1).toNanos());
        try (var resumed = new AssistantExecutionBudget(120_000, 10_000, null, clock::get)) {
            assertThat(AssistantExecutionBudget.timeout(Duration.ofDays(1))).isEqualTo(Duration.ofSeconds(110));
        }
    }
    @Test void rateLimitWaitIsVisibleAndCannotExceedRemainingBudget() {
        var messages = new ArrayList<String>();
        try (var scope = new AssistantExecutionBudget(1000, 0, messages::add, () -> 0)) {
            AssistantExecutionBudget.waitingForRateLimit(100);
            assertThat(messages).singleElement().asString().contains("pidió una pausa");
            assertThatThrownBy(() -> AssistantExecutionBudget.waitingForRateLimit(1000))
                    .isInstanceOf(AssistantExecutionBudget.Expired.class);
        }
    }
}
