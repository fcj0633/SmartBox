package cn.smartbox.transport;

/** Rider-facing transport task view; field names intentionally match the additive contract. */
public record TransportView(long task_id,String transport_no,String box_id,String status,
    String item_name,String item_description,Long started_at,Long ended_at,
    long created_at,long updated_at) {}
