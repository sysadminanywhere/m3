package com.sysadminanywhere.m3.messaging.service;
import com.sysadminanywhere.m3.messaging.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.*;
class RegexSafetyTest {
    @Test void hostileBacktrackingPatternCompletesOnAMaximumSizePayload() {
        var rule=new Rule("test",RuleType.INBOUND,new ChannelSettings("source",ChannelType.DIRECTORY,ChannelDirection.INBOUND));
        rule.getConditions().add(new RuleCondition(rule,"payload",ConditionOperator.REGEX,"(a+)+$"));
        var input=org.springframework.messaging.support.MessageBuilder.withPayload("a".repeat(1_000_000)+"!").build();
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertThat(new RuleEngine(null).evaluateConditions(rule,input)).isFalse());
    }
    @Test void rejectsUnsupportedBackreferences() {
        assertThatThrownBy(()->com.google.re2j.Pattern.compile("(a)\\1")).isInstanceOf(com.google.re2j.PatternSyntaxException.class);
        var rule=new Rule("test",RuleType.INBOUND,new ChannelSettings("source",ChannelType.DIRECTORY,ChannelDirection.INBOUND));
        rule.getConditions().add(new RuleCondition(rule,"payload",ConditionOperator.REGEX,"(a)\\1"));
        var input=org.springframework.messaging.support.MessageBuilder.withPayload("aa").build();
        assertThatThrownBy(()->new RuleEngine(null).evaluateConditions(rule,input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }
}
