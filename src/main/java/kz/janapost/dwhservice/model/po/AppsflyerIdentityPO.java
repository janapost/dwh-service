package kz.janapost.dwhservice.model.po;

import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.util.Date;

/** AppsFlyer ID устройства клиента. Коллекция {@code AppsflyerIdentityPO} — та же схема, что в client-service. */
@Data
public class AppsflyerIdentityPO {
    /** Ключ — jpid. */
    @MongoId
    private String jpid;
    private String appsflyerId;
    /** {@code ANDROID} / {@code IOS}. */
    private String os;
    private Date createTime;
    private Date updateTime;
    private Boolean sentRegisteredEvent;
    private DeepLinkData deepLinkData;
    private Date userCreateTime;
}
