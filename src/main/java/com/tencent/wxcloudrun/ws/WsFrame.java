package com.tencent.wxcloudrun.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 服务端 → 客户端的帧信封：{type, seq, ts, data, reqId}
 * seq / ts 只有服务端帧才有；reqId 只在应答请求帧时带回。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsFrame {

  private String type;
  private long seq;
  private long ts;
  private Map<String, Object> data;
  private String reqId;

  public WsFrame() {
  }

  public WsFrame(String type, long seq, Map<String, Object> data, String reqId) {
    this.type = type;
    this.seq = seq;
    this.ts = System.currentTimeMillis();
    this.data = data;
    this.reqId = reqId;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public long getSeq() {
    return seq;
  }

  public void setSeq(long seq) {
    this.seq = seq;
  }

  public long getTs() {
    return ts;
  }

  public void setTs(long ts) {
    this.ts = ts;
  }

  public Map<String, Object> getData() {
    return data;
  }

  public void setData(Map<String, Object> data) {
    this.data = data;
  }

  public String getReqId() {
    return reqId;
  }

  public void setReqId(String reqId) {
    this.reqId = reqId;
  }
}
