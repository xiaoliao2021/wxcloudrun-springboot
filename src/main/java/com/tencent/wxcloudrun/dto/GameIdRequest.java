package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 只带对局 id 的请求：POST /v1/games/pass
 */
@Data
public class GameIdRequest {

  private String gameId;
}
