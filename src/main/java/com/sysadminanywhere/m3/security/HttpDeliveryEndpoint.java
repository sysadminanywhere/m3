package com.sysadminanywhere.m3.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Shared validation for published and executed HTTP recipient addresses. */
public final class HttpDeliveryEndpoint {
    private HttpDeliveryEndpoint() {}
    public static URI parse(String value) {
        try {
            URI uri=URI.create(value); String host=uri.getHost();
            boolean local=host!=null && Set.of("localhost","127.0.0.1","::1","[::1]").contains(host.toLowerCase(Locale.ROOT));
            if (host==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535
                    || !("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid HTTP delivery endpoint"); }
    }
}
