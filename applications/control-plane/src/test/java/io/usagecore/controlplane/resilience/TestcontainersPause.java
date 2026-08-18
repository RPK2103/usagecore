package io.usagecore.controlplane.resilience;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.containers.GenericContainer;

final class TestcontainersPause {

    private TestcontainersPause() {
    }

    static void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    static void unpause(GenericContainer<?> container) {
        if (container.getContainerId() == null) {
            return;
        }
        InspectContainerResponse inspect = container.getDockerClient()
                .inspectContainerCmd(container.getContainerId())
                .exec();
        if (Boolean.TRUE.equals(inspect.getState().getPaused())) {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
    }
}
