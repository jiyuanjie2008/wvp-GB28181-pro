package com.genersoft.iot.vmp.jxt.tenant.dto;

import lombok.Data;

@Data
public class StorageSiteInfo {

    private String siteId;

    private String ipv4Address;

    private int ftpPort;

    private String status;
}
