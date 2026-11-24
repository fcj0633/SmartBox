package cn.smartbox.live;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes whether the fixed remote device link is configured and connected. */
@Component("mqtt")
public class MqttHealthIndicator implements HealthIndicator {
    private final MqttGateway mqtt;

    public MqttHealthIndicator(MqttGateway mqtt) {
        this.mqtt = mqtt;
    }

    @Override
    public Health health() {
        if (!mqtt.enabled()) return Health.up().withDetail("mqtt", "disabled").build();
        if (mqtt.ready()) return Health.up().withDetail("mqtt", "connected").build();
        return Health.down().withDetail("mqtt", "not_connected").build();
    }
}
