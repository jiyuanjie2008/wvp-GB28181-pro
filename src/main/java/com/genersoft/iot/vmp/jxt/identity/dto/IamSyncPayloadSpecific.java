package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSyncPayloadSpecific {
    @JsonProperty("deviceName")
    private String deviceName;

    @JsonProperty("sipHa1")
    private String sipHa1;

    private String realm;

    private String charset;

    @JsonProperty("streamMode")
    private String streamMode;

    @JsonProperty("sdpIp")
    private String sdpIp;

    @JsonProperty("mediaServerId")
    private String mediaServerId;

    @JsonProperty("ssrcCheck")
    private Boolean ssrcCheck;

    @JsonProperty("geoCoordSys")
    private String geoCoordSys;

    @JsonProperty("asMessageChannel")
    private Boolean asMessageChannel;

    @JsonProperty("broadcastPushAfterAck")
    private Boolean broadcastPushAfterAck;

    @JsonProperty("heartbeatInterval")
    private Integer heartbeatInterval;

    @JsonProperty("heartbeatCount")
    private Integer heartbeatCount;
}
