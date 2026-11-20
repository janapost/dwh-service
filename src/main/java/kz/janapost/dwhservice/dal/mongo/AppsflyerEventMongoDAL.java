package kz.janapost.dwhservice.dal.mongo;

import kz.janapost.dwhservice.dal.AppsflyerEventDAL;
import kz.janapost.dwhservice.dal.mongo.repository.AppsflyerEventMongoRepository;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import kz.janapost.dwhservice.model.dto.AppsflyerEventDTO;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO.AppsflyerEventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AppsflyerEventMongoDAL implements AppsflyerEventDAL {
    private final AppsflyerEventMongoRepository repository;

    @Override
    public boolean reserve(AppsflyerEventDTO dto) {
        try {
            AppsflyerEventPO saved = repository.insert(toPo(dto));
            dto.setId(saved.getId());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public AppsflyerEventDTO findOneByDedupKey(String dedupKey) {
        return toDto(repository.findOneByDedupKey(dedupKey));
    }

    @Override
    public List<AppsflyerEventDTO> findFailedForRetry(int maxAttempts, int limit) {
        return repository.findFailedForRetry(maxAttempts, limit).stream().map(AppsflyerEventMongoDAL::toDto).toList();
    }

    @Override
    public boolean claimForRetry(AppsflyerEventDTO dto, int maxAttempts) {
        return repository.claimForRetry(dto.getId(), maxAttempts);
    }

    @Override
    public void updateAfterSend(AppsflyerEventDTO dto, AppsflyerEventStatus status, String response, String error) {
        repository.updateAfterSend(dto.getId(), status, response, error);
    }

    @Override
    public void holdUnsent(AppsflyerEventDTO dto, String reason) {
        repository.holdUnsent(dto.getId(), reason);
    }

    @Override
    public void promoteNoIdentityToRetry(String jpid, String appsflyerId, AppsflyerPlatform platform) {
        repository.promoteNoIdentityToRetry(jpid, appsflyerId, platform);
    }

    @Override
    public boolean existsSentByOrderIdAndEventName(String orderId, AppsflyerEventName eventName) {
        return repository.existsByOrderIdAndEventNameAndStatus(orderId, eventName, AppsflyerEventStatus.SENT);
    }

    @Override
    public List<String> findIdsForCleanup(List<AppsflyerEventStatus> statuses, Date createdBefore, int limit) {
        return repository.findIdsForCleanup(statuses, createdBefore, limit);
    }

    @Override
    public long deleteByIdsForCleanup(List<String> ids, List<AppsflyerEventStatus> statuses, Date createdBefore) {
        return repository.deleteByIdsForCleanup(ids, statuses, createdBefore);
    }

    private static AppsflyerEventPO toPo(AppsflyerEventDTO dto) {
        AppsflyerEventPO po = new AppsflyerEventPO();
        BeanUtils.copyProperties(dto, po);
        return po;
    }

    private static AppsflyerEventDTO toDto(AppsflyerEventPO po) {
        if (po == null) {
            return null;
        }
        AppsflyerEventDTO dto = new AppsflyerEventDTO();
        BeanUtils.copyProperties(po, dto);
        return dto;
    }
}
