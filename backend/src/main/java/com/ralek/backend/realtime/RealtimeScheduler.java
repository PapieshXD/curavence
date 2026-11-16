package com.ralek.backend.realtime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="ralek.realtime.enabled",havingValue="true",matchIfMissing=true)
public class RealtimeScheduler {
    private final RealtimeService service;
    public RealtimeScheduler(RealtimeService service) {this.service=service;}
    @Scheduled(fixedDelayString="${ralek.realtime.poll-ms:1000}")
    public void tick(){service.dispatch();}
}
