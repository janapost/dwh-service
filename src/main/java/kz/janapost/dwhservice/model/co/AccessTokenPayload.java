package kz.janapost.dwhservice.model.co;

import lombok.Data;

@Data
public class AccessTokenPayload {
    private String jpid;
    private String phone;
    private String type;
}
