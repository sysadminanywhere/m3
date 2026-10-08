package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.*;
import java.nio.file.Path;

/** Validate endpoint syntax in the UI process; resource access is checked by the worker. */
public final class ChannelEndpointValidator {
    private ChannelEndpointValidator() { }
    public static void validate(ChannelSettings channel) {
        var spec=InboundSourceSpec.from(channel);
        PayloadCodec.charset(spec.value("charset",""));
        PayloadCodec.charset(spec.value("outputCharset",""));
        switch (channel.getChannelType()) {
            case DIRECTORY -> Path.of(spec.required("directoryPath"));
            case FTP,SFTP -> {
                spec.required("host"); spec.required("username"); spec.required("remoteDirectory");
                port(spec,channel.getChannelType()==ChannelType.FTP ? 21 : 22);
                if (channel.getChannelType()==ChannelType.SFTP && !spec.flag("allowUnknownKeys",false))
                    Path.of(spec.required("knownHostsPath"));
                if (!spec.value("privateKey","").isBlank()) Path.of(spec.value("privateKey",""));
            }
            case KAFKA -> { spec.required("bootstrapServers"); spec.required("topic"); }
            case RABBITMQ -> {
                spec.required("host"); spec.required("username"); port(spec,5672);
                if (channel.getDirection()==ChannelDirection.INBOUND || spec.value("exchange","").isBlank() || spec.flag("declareQueue",false))
                    spec.required("queue");
                if (channel.getDirection()==ChannelDirection.OUTBOUND && !spec.value("exchange","").isBlank()) spec.required("routingKey");
            }
        }
    }
    private static void port(InboundSourceSpec spec,int fallback) {
        if (spec.number("port",fallback)>65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
    }
}
