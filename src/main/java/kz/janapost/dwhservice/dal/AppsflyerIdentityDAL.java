package kz.janapost.dwhservice.dal;

import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;
import kz.janapost.dwhservice.model.dto.AppsflyerIdentityDTO;

import java.util.Date;

/** AppsFlyer ID устройств клиентов. */
public interface AppsflyerIdentityDAL {

    AppsflyerIdentityDTO findOneByJpid(String jpid);

    /** Атомарный upsert; {@code deepLinkData} / {@code userCreateTime} пишутся, только если ещё не заданы. */
    void upsertIdentity(String jpid, String appsflyerId, String os, DeepLinkData deepLinkData, Date userCreateTime, Date now);
}
