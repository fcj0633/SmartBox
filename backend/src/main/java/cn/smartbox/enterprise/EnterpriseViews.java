package cn.smartbox.enterprise;

import java.util.List;

/** DTOs for the additive enterprise console API. Old rider/enterprise contracts are untouched. */
public final class EnterpriseViews {
    private EnterpriseViews() {}

    public record Point(double x, double y, String place) {}
    public record Route(String code, String name, String from, String to, long duration_ms, List<Point> points) {}
    public record Position(double x, double y, String place, String route_code, String route_name,
                           double progress, long position_at, boolean stale, boolean finished, String source) {}
    public record Environment(String assessment, String level, Double temperature, Double humidity, Double lux,
                              Long observed_at, String time_quality, String data_state, Long received_at) {}
    public record Pose(String state, Double tilt_deg, List<Double> acc_g, boolean valid, Long observed_at,
                       String time_quality, String data_state) {}
    public record SamplePoint(long id, long observed_at, Long received_at, boolean valid, Double temperature,
                              Double humidity, Double lux, String state, Double tilt_deg, List<Double> acc_g,
                              String time_quality) {}
    public record CommandNode(String node_id, String status, String result, String code, Long ack_received_at) {}
    public record CommandRow(String operation_id, String cmd, String status, long created_at, long updated_at,
                             String requested_by, String params, List<CommandNode> nodes) {}
    public record LogRow(long at, String source, String title, String detail, String level) {}
    public record AlarmState(String assessment, Boolean active, String level, long active_count) {}
    public record Node(String node_id, String role, int caps, boolean required, boolean online,
                       Long last_seen_at, Long last_received_at) {}
    public record TaskSummary(long task_id, String transport_no, String status, String item_name,
                              String box_id, String box_display_name, Long started_at, Long ended_at) {}
    public record Box(String box_id, String display_name, String asset_source, String purpose, String notes,
                      String demo_scenario, boolean demo_enabled, String connectivity, String data_state,
                      Environment environment, Pose pose, AlarmState alarm, TaskSummary current_transport,
                      TaskSummary last_completed_transport, Position position, Long last_seen_at, List<Node> nodes) {}
    public record StartCheck(boolean online, boolean risk, List<String> blockers) {}
    public record Task(long task_id, String transport_no, String box_id, String box_display_name, String status,
                       String item_name, String item_description, String operator_username, long created_at,
                       Long started_at, Long ended_at, Route route, Position position, StartCheck start_check) {}
    public record Kpis(long box_total, long online, long partial, long offline, long unknown,
                       long active_transports, long active_alarm_boxes, long unknown_alarm_boxes, long completed_today) {}
    public record Activity(long at, String type, String box_id, String box_display_name, Long task_id,
                           String title, String source) {}
    public record Overview(long enterprise_id, String enterprise_name, long refreshed_at, Kpis kpis,
                           List<Box> boxes, List<Task> tasks, List<Activity> recent_activity) {}
    public record Sample(long id, long observed_at, Double temperature, Double humidity, boolean valid) {}
    public record Event(long id, long observed_at, String type, String level, String description) {}
    public record TaskDetail(Task task, Box box, Route route, Position position, Environment environment,
                             List<Sample> samples, List<Event> events) {}
    public record AlarmRow(String box_id, String box_display_name, String alarm_key, boolean active, String level,
                           long started_at, long updated_at, Long recovered_at, String source_event_type,
                           Long source_event_at, String details) {}
    public record AlarmList(long assessment_unknown_boxes, List<AlarmRow> rows) {}
    public record Context(long enterprise_id, String enterprise_name) {}
}
