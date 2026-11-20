package kz.janapost.dwhservice.model.dto;

import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;
import lombok.Data;

import java.util.Date;

/** AppsFlyer ID устройства клиента ({@code AppsflyerIdentityPO}). */
@Data
public class AppsflyerIdentityDTO {
    private String jpid;
    private String appsflyerId;
    private String os;
    private Date createTime;
    private Date updateTime;
    private Boolean sentRegisteredEvent;
    private DeepLinkData deepLinkData;
    private Date userCreateTime;
}
