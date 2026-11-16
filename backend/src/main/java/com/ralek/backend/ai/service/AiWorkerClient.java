package com.ralek.backend.ai.service;

import com.ralek.backend.ai.dto.WorkerExplanationRequest;
import com.ralek.backend.ai.dto.WorkerExplanationResponse;

public interface AiWorkerClient {

    WorkerExplanationResponse submitExplanation(WorkerExplanationRequest request);
}
