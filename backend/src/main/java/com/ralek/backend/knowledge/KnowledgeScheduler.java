package com.ralek.backend.knowledge;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
@Component
@EnableScheduling
public class KnowledgeScheduler {
    private final KnowledgeService service;
    public KnowledgeScheduler(KnowledgeService service){this.service=service;}
    @Scheduled(fixedDelayString="${ralek.knowledge.poll-ms:30000}") public void tick(){service.tick();}
}
