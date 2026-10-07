package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;

@Service
public class OutboundChannelSender {
    private final ChannelSettingsRepository channels;
    private final Map<ChannelType, OutboundTransport> transports = new EnumMap<>(ChannelType.class);
    public OutboundChannelSender(ChannelSettingsRepository channels, List<OutboundTransport> adapters) {
        this.channels = channels;
        for (var adapter : adapters) for (var type : adapter.types()) {
            if (transports.putIfAbsent(type, adapter) != null) throw new IllegalStateException("Duplicate outbound transport for " + type);
        }
    }
    public void send(long messageId, PreparedOutboundDelivery delivery) throws Exception {
        var channel = channels.findById(delivery.channelId()).orElseThrow(() -> new IllegalArgumentException("Outbound channel was deleted"));
        if (channel.getDirection() != ChannelDirection.OUTBOUND) throw new IllegalArgumentException("Channel direction changed");
        if (!Boolean.TRUE.equals(channel.getEnabled())) throw new IOException("Outbound channel is disabled");
        var transport = transports.get(channel.getChannelType());
        if (transport == null) throw new IllegalArgumentException("No outbound transport for " + channel.getChannelType());
        transport.send(channel, messageId, delivery);
    }

    public void send(long messageId, PreparedOutboundDelivery delivery,
                     com.sysadminanywhere.m3.messaging.service.JobConfiguration.Channel endpoint, Runnable started) throws Exception {
        var live=channels.findById(delivery.channelId()).orElseThrow(() -> new IllegalArgumentException("Outbound channel was deleted"));
        if(!Boolean.TRUE.equals(live.getEnabled())) throw new IOException("Outbound channel is disabled");
        var channel=endpoint==null ? live : endpoint.restore();
        var transport=transports.get(channel.getChannelType());
        if(transport==null) throw new IllegalArgumentException("No outbound transport for " + channel.getChannelType());
        var metadata=new java.util.TreeMap<>(delivery.metadata());
        metadata.put("m3DeliveryId","m3:"+messageId); metadata.put("m3MessageId",Long.toString(messageId));
        var plan=new PreparedOutboundDelivery(delivery.channelId(),delivery.body(),delivery.payloadType(),metadata);
        DeliveryAttempt.run(started,()->transport.send(channel,messageId,plan));
    }

    static String fileName(long id, PreparedOutboundDelivery delivery) {
        String original = delivery.metadata().getOrDefault("fileName", "payload.dat");
        if (original.isBlank() || original.equals(".") || original.equals("..") || original.contains("/") || original.contains("\\")
                || original.indexOf('\0') >= 0 || original.contains(":")) throw new IllegalArgumentException("fileName must be a simple file name");
        String name = "message-" + id + "-" + original;
        if (name.length() > 255) throw new IllegalArgumentException("fileName is too long");
        return name;
    }
}
