package com.agentdrive;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {
    @Test
    void modulesHaveNoIllegalDependencies() {
        ApplicationModules.of(AgentDriveApplication.class).verify();
    }
}
