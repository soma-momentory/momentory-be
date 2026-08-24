package com.momentory.auth.apple.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppleAuthProperties.class)
public class AppleAuthConfiguration {
}
