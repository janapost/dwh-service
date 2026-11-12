package kz.janapost.dwhservice.model.co;

import lombok.Data;

@Data
public class AdminAccessTokenPayload {
    private String jpid;
    private String type;
    private String system;
    private String subSystem;
    private String subSystemID;
    private Boolean isSuper;
}
