package com.ralek.backend.document;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({DocumentProperties.class, DocumentChatProperties.class})
public class DocumentConfig {
}
