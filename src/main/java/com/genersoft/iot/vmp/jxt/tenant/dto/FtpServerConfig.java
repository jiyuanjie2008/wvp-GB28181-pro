package com.genersoft.iot.vmp.jxt.tenant.dto;

import lombok.Data;

@Data
public class FtpServerConfig {

    private String ipv4Address;

    private int ftpPort;

    private String userId;

    private String userPasswd;
}
