package com.momentory.retrospect;

import java.io.IOException;

import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.infrastructure.RetrospectDomainConfiguration;

import tools.jackson.databind.json.JsonMapper;

/** 실제 배포 리소스와 같은 경로로 욕구별 행동 목록을 읽는다. */
public final class NeedActionCatalogFixture {

    private NeedActionCatalogFixture() {
    }

    public static NeedActionCatalog load() {
        try {
            return new RetrospectDomainConfiguration().needActionCatalog(JsonMapper.builder().build());
        } catch (IOException e) {
            throw new AssertionError("행동 목록 리소스를 읽을 수 없습니다.", e);
        }
    }
}
