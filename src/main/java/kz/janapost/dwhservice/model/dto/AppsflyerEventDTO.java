package kz.janapost.dwhservice.model.dto;

import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO;
import lombok.Data;

import java.util.Date;
import java.util.Map;

/** Строка журнала AppsFlyer ({@link AppsflyerEventPO}). */
@Data
public class AppsflyerEventDTO {
    private String id;
    private String jpid;
    private String orderId;
    private String appsflyerId;
    private AppsflyerPlatform platform;
    private AppsflyerEventName eventName;
    private String dedupKey;
    private String appId;
    private Map<String, Object> eventParams;
    private AppsflyerEventPO.AppsflyerEventStatus status;
    private String error;
    private String response;
    private Date createTime;
    private String source;
    private Integer attempt;
    private Date updateTime;
    private Date claimTime;
}
