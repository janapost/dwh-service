package kz.janapost.dwhservice.model.appsflyer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * Атрибуция установки от AppsFlyer SDK. Перенесено из client-service без изменений: та же схема в
 * {@code AppsflyerIdentityPO.deepLinkData} и тот же snake_case в событии {@code s2s_identity}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DeepLinkData {
    private String mediaSource;
    private String installTime;
    private String clickTime;
    @JsonProperty("is_first_launch")
    private Boolean isFirstLaunch;
    private String afStatus;
    /** В JSON — {@code af_siteid} (без второго подчёркивания). */
    private String afSiteid;
    private String adgroupId;
    private String adset;
    private String adsetId;
    private String agency;
    private String campaign;
    private String campaignId;
    private String httpReferrer;
    private String retargetingConversionType;
    private String afSub1;
    private String afSub2;
    private String afSub3;
    private String afSub4;
    private String afSub5;
    private String afChannel;
    private String origCost;
    @JsonAlias({"costs_cents_USD", "costs_cents_usd"})
    private String costsCentsUSD;
    private String afCpi;
}
