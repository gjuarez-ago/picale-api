package com.metricol.api.models.request;

import com.metricol.api.enums.OrgRole;

import lombok.Getter;
import lombok.Setter;

/** El papel que pasa a tener alguien en la organización. */
@Getter
@Setter
public class PapelRequest {

    private OrgRole orgRole;
}
