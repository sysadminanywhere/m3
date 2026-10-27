package com.sysadminanywhere.m3.messaging.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FirstFlowViewTest {
    @Test void identifiesObviousSameDirectoryWithoutAssumingWorkerFilesystemCaseRules() {
        assertThat(FirstFlowView.sameDirectory("/data/in/", "/data/in")).isTrue();
        assertThat(FirstFlowView.sameDirectory("C:\\data\\in\\", "C:/data/in")).isTrue();
        assertThat(FirstFlowView.sameDirectory("/data/in", "/data/IN")).isFalse();
        assertThat(FirstFlowView.sameDirectory("/data/a\\b", "/data/a/b")).isFalse();
        assertThat(FirstFlowView.sameDirectory("/data/in", "/data/out")).isFalse();
        assertThat(FirstFlowView.sameDirectory(null, "/data/in")).isFalse();
    }
}
