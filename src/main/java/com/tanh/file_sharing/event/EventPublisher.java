package com.tanh.file_sharing.event;

@FunctionalInterface
public interface EventPublisher {
    void publish(String eventName, Object data);

    static EventPublisher noop() {
        return (eventName, data) -> { };
    }
}
