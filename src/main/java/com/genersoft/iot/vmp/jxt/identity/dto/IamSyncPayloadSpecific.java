package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSyncPayloadSpecific {
    private String deviceName;
    private String sipHa1;
    private String realm;
    private String charset;
    private String streamMode;
    private String sdpIp;
    private String mediaServerId;
    private Boolean ssrcCheck;
    private String geoCoordSys;
    private Boolean asMessageChannel;
    private Boolean broadcastPushAfterAck;
    private Integer heartbeatInterval;
    private Integer heartbeatCount;
}
