package com.telerobot.fs.entity.dto.llm;

import lombok.Data;

@Data
public class JiutianAccount extends AccountBaseEntity {

  private String apiKey;
  
  private String botId;

}
