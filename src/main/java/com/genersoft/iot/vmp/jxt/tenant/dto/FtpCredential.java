package com.genersoft.iot.vmp.jxt.tenant.dto;

import lombok.Data;

@Data
public class FtpCredential {

    private String tenantId;

    private String username;

    private String passwordHash;

    private String description;

    private String status;
}
