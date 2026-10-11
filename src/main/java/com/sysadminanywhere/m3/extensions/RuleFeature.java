package com.sysadminanywhere.m3.extensions;

import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.vaadin.flow.component.Component;

/** Optional controls supplied by the installed distribution. */
public interface RuleFeature {
    Component control(Rule rule);
}
