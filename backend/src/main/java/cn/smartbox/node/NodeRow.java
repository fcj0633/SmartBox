package cn.smartbox.node;
public record NodeRow(String nodeId,String role,Integer caps,Boolean required,Long currentBootId,Long lastReceivedAt){}
