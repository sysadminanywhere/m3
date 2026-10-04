package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import java.util.Set;

public interface OutboundTransport {
    Set<ChannelType> types();
    void send(ChannelSettings channel, long messageId, PreparedOutboundDelivery delivery) throws Exception;
}
