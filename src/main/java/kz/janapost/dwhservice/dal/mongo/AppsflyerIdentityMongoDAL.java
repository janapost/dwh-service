package kz.janapost.dwhservice.dal.mongo;

import kz.janapost.dwhservice.dal.AppsflyerIdentityDAL;
import kz.janapost.dwhservice.dal.mongo.repository.AppsflyerIdentityMongoRepository;
import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;
import kz.janapost.dwhservice.model.dto.AppsflyerIdentityDTO;
import kz.janapost.dwhservice.model.po.AppsflyerIdentityPO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@RequiredArgsConstructor
public class AppsflyerIdentityMongoDAL implements AppsflyerIdentityDAL {
    private final AppsflyerIdentityMongoRepository repository;

    @Override
    public AppsflyerIdentityDTO findOneByJpid(String jpid) {
        AppsflyerIdentityPO po = repository.findOneByJpid(jpid);
        if (po == null) {
            return null;
        }
        AppsflyerIdentityDTO dto = new AppsflyerIdentityDTO();
        BeanUtils.copyProperties(po, dto);
        return dto;
    }

    @Override
    public void upsertIdentity(String jpid, String appsflyerId, String os, DeepLinkData deepLinkData,
                               Date userCreateTime, Date now) {
        repository.upsertIdentity(jpid, appsflyerId, os, deepLinkData, userCreateTime, now);
    }
}
