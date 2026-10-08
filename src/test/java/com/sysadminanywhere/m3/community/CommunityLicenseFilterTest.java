package com.sysadminanywhere.m3.community;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class CommunityLicenseFilterTest {
    @Test void activationMethodsNeverReachTheVaadinFallback() throws Exception {
        for (String method : new String[]{"GET","PUT","DELETE"}) {
            var request=new MockHttpServletRequest(method,"/m3/api/v1/license");
            request.setContextPath("/m3");
            var response=new MockHttpServletResponse();
            new CommunityLicenseFilter().doFilter(request,response,(req,res) -> { throw new AssertionError("Activation request reached fallback"); });
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }
    @Test void otherRoutesAreUnaffected() throws Exception {
        var chain=new MockFilterChain();
        new CommunityLicenseFilter().doFilter(new MockHttpServletRequest("GET","/login"),new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
