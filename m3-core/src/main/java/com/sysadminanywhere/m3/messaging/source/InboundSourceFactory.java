package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import java.util.Set;

/** Register a Spring bean implementing this interface to add a native source adapter. */
public interface InboundSourceFactory {
    Set<ChannelType> types();
    void validate(InboundSourceSpec source);
    AutoCloseable start(InboundSourceSpec source) throws Exception;
}
