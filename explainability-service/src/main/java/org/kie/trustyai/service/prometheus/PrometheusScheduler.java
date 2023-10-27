package org.kie.trustyai.service.prometheus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.kie.trustyai.explainability.model.Dataframe;
import org.kie.trustyai.service.config.ServiceConfig;
import org.kie.trustyai.service.data.DataSource;
import org.kie.trustyai.service.data.exceptions.DataframeCreateException;
import org.kie.trustyai.service.endpoints.metrics.MetricsCalculator;
import org.kie.trustyai.service.payloads.ReconciledMetricRequest;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class PrometheusScheduler {

    private static final Logger LOG = Logger.getLogger(PrometheusScheduler.class);
    private final ConcurrentHashMap<UUID, ReconciledMetricRequest> spdRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReconciledMetricRequest> dirRequests = new ConcurrentHashMap<>();
    @Inject
    Instance<DataSource> dataSource;
    @Inject
    PrometheusPublisher publisher;
    @Inject
    MetricsCalculator calculator;

    @Inject
    ServiceConfig serviceConfig;

    public Map<UUID, ReconciledMetricRequest> getDirRequests() {
        return dirRequests;
    }

    public Map<UUID, ReconciledMetricRequest> getSpdRequests() {
        return spdRequests;
    }

    public Map<UUID, ReconciledMetricRequest> getAllRequests() {
        // extend this with other metrics when more are added=
        ConcurrentHashMap<UUID, ReconciledMetricRequest> result = new ConcurrentHashMap<>();
        result.putAll(getDirRequests());
        result.putAll(getSpdRequests());
        return result;
    }

    @Scheduled(every = "{service.metrics-schedule}")
    void calculate() {

        if (hasRequests()) {

            try {
                for (final String modelId : getModelIds()) {

                    final Predicate<Map.Entry<UUID, ReconciledMetricRequest>> filterByModelId = request -> request.getValue().getModelId().equals(modelId);

                    final List<Map.Entry<UUID, ReconciledMetricRequest>> modelSpdRequest =
                            spdRequests.entrySet().stream().filter(filterByModelId).collect(Collectors.toList());

                    final List<Map.Entry<UUID, ReconciledMetricRequest>> modelDirRequest =
                            dirRequests.entrySet().stream().filter(filterByModelId).collect(Collectors.toList());

                    // Determine maximum batch requested. All other batches as sub-batches of this one.
                    final int maxBatchSize = Stream.concat(modelSpdRequest.stream(), modelDirRequest.stream())
                            .mapToInt(entry -> entry.getValue().getBatchSize()).max()
                            .orElse(serviceConfig.batchSize().getAsInt());

                    final Dataframe df = dataSource.get().getDataframe(modelId, maxBatchSize);

                    // SPD requests
                    modelSpdRequest.forEach(entry -> {
                        final Dataframe batch = df.tail(Math.min(df.getRowDimension(), entry.getValue().getBatchSize()));
                        final double spd = calculator.calculateSPD(batch, entry.getValue());
                        publisher.gaugeSPD(entry.getValue(), modelId, entry.getKey(), spd);
                    });

                    // DIR requests
                    modelDirRequest.forEach(entry -> {
                        final Dataframe batch = df.tail(Math.min(df.getRowDimension(), entry.getValue().getBatchSize()));
                        final double dir = calculator.calculateDIR(batch, entry.getValue());
                        publisher.gaugeDIR(entry.getValue(), modelId, entry.getKey(), dir);
                    });

                }
            } catch (DataframeCreateException e) {
                LOG.error(e.getMessage());
            }
        }
    }

    public PrometheusPublisher getPublisher() {
        return publisher;
    }

    public void registerSPD(UUID id, ReconciledMetricRequest request) {
        spdRequests.put(id, request);
    }

    public void registerDIR(UUID id, ReconciledMetricRequest request) {
        dirRequests.put(id, request);
    }

    public boolean hasRequests() {
        return !(spdRequests.isEmpty() && dirRequests.isEmpty());
    }

    /**
     * Get unique model ids with registered Prometheus metrics
     * 
     * @return Unique models ids
     */
    public Set<String> getModelIds() {
        return Stream.of(spdRequests.values(), dirRequests.values())
                .flatMap(Collection::stream)
                .map(ReconciledMetricRequest::getModelId)
                .collect(Collectors.toSet());
    }
}
